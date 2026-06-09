// 공통 setup 헬퍼.
// k6 부하 테스트 시작 전 1회 실행되어 측정용 데이터를 만든다.
//   1. PROVIDER 1명 생성 + 로그인
//   2. 이벤트 EVENT_COUNT개 생성 (각 SEATS_PER_EVENT 좌석)
//   3. BOOKER BOOKER_COUNT명 생성 + 토큰 발급
// 리턴값은 default(data)로 전달된다.
//
// ddl-auto: create 이므로 앱 재시작마다 DB가 비워진다. 따라서 시드 SQL이 아니라
// 이 setup이 자급자족으로 데이터를 만든다 (Testcontainers와 무관 — k6는 실제 앱 서버에 HTTP).

import http from 'k6/http';
import { check, fail } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 측정용 데이터 규모. k6 실행 시 환경변수로 덮어쓸 수 있다.
//   k6 run -e EVENT_COUNT=200 loadtest/event-list.js
const EVENT_COUNT = parseInt(__ENV.EVENT_COUNT || '100');
const SEATS_PER_EVENT = parseInt(__ENV.SEATS_PER_EVENT || '50');
const BOOKER_COUNT = parseInt(__ENV.BOOKER_COUNT || '20');

// confirm 부하용 booker 잔액. 쿠폰은 유저당 1회 제약이라 booker마다 전용 코드 1장을 만들고
// 각자 1회 상환한다(유저당 1회 제약을 건드리지 않음). 좌석가(1000) 대비 충분히 크게.
const BOOKER_CHARGE = parseInt(__ENV.BOOKER_CHARGE || '100000000');

const JSON_HEADERS = { 'Content-Type': 'application/json' };

function signup(email, role) {
  // 이미 존재해도(409) 무시 — 재실행 시 멱등하게 동작
  http.post(`${BASE_URL}/api/auth/signup`, JSON.stringify({
    email, password: '1234', role,
  }), { headers: JSON_HEADERS });
}

function login(email) {
  const res = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({
    email, password: '1234',
  }), { headers: JSON_HEADERS });
  if (!check(res, { 'login 200': (r) => r.status === 200 })) {
    fail(`login failed for ${email}: ${res.status} ${res.body}`);
  }
  return res.json('accessToken');
}

// booker 1명에게 전용 쿠폰 1장을 만들고 즉시 상환시켜 잔액을 채운다.
// 쿠폰 생성은 @Profile("loadtest") 전용 엔드포인트(POST /api/loadtest/coupons)를 사용한다.
function chargeBooker(index, bookerToken) {
  const code = `LOADTEST-BOOKER-${index}`;
  // 1. 전용 쿠폰 생성 (멱등 — 재실행 시 기존 쿠폰 반환). 인증 불필요(프로파일 전용 경로 permitAll).
  const createRes = http.post(`${BASE_URL}/api/loadtest/coupons`, JSON.stringify({
    code, amount: BOOKER_CHARGE, maxUses: 1,
  }), { headers: JSON_HEADERS });
  if (!check(createRes, { 'coupon created': (r) => r.status === 201 })) {
    fail(`coupon create failed for ${code}: ${createRes.status} ${createRes.body}`);
  }
  // 2. 상환 → booker 잔액 적립. 재실행 시 이미 상환했으면 409류 → 무시(이미 잔액 보유).
  http.post(`${BASE_URL}/api/points/redeem`, JSON.stringify({ code }), {
    headers: { ...JSON_HEADERS, 'Authorization': `Bearer ${bookerToken}` },
  });
}

export function seedData() {
  // 1. PROVIDER 생성 + 로그인
  const providerEmail = 'provider@loadtest.com';
  signup(providerEmail, 'PROVIDER');
  const providerToken = login(providerEmail);
  const authHeaders = {
    ...JSON_HEADERS,
    'Authorization': `Bearer ${providerToken}`,
  };

  // 2. 이벤트 EVENT_COUNT개 생성 (각 SEATS_PER_EVENT 좌석)
  const seats = Array.from({ length: SEATS_PER_EVENT }, (_, i) => ({
    label: `S${i + 1}`,
    price: 1000,
  }));

  const eventIds = [];
  for (let i = 0; i < EVENT_COUNT; i++) {
    const res = http.post(`${BASE_URL}/api/events`, JSON.stringify({
      title: `Load Test Event ${i}`,
      venue: 'Test Venue',
      eventAt: '2027-01-01T10:00:00',
      seats,
    }), { headers: authHeaders });
    if (!check(res, { 'event created': (r) => r.status === 201 })) {
      fail(`event create failed: ${res.status} ${res.body}`);
    }
    eventIds.push(res.json('eventId'));
  }

  // 3. BOOKER 생성 + 토큰 발급
  const bookerTokens = [];
  for (let i = 0; i < BOOKER_COUNT; i++) {
    const email = `booker${i}@loadtest.com`;
    signup(email, 'BOOKER');
    bookerTokens.push(login(email));
  }

  console.log(`seed done: ${eventIds.length} events x ${SEATS_PER_EVENT} seats, ${bookerTokens.length} bookers`);

  return { providerToken, bookerTokens, eventIds };
}

// 한 이벤트의 좌석 ID 목록을 조회한다 (GET /api/events/{id}/seats).
function fetchSeatIds(eventId) {
  const res = http.get(`${BASE_URL}/api/events/${eventId}/seats`);
  if (!check(res, { 'seats 200': (r) => r.status === 200 })) {
    fail(`seat fetch failed for event ${eventId}: ${res.status} ${res.body}`);
  }
  return res.json('seats').map((s) => s.seatId);
}

/**
 * hold/confirm 부하용 시드.
 *   - seedData()로 provider/event/booker 생성
 *   - 모든 이벤트의 좌석 ID를 평탄화한 seatIds 풀 수집 (VU가 분산/경합 모드로 선택)
 *   - charge=true면 booker마다 잔액 충전 (confirm 측정용)
 *
 * 리턴: { bookerTokens, seatIds }
 *   seatIds: 전체 좌석 ID 배열 (EVENT_COUNT × SEATS_PER_EVENT개). 좌석 소진을 피하려면
 *            부하 총량보다 충분히 크게 시드하고 VU가 고르게 분산 선택한다.
 */
export function seedForSeatLoad({ charge = false } = {}) {
  const { bookerTokens, eventIds } = seedData();

  const seatIds = [];
  for (const eventId of eventIds) {
    for (const seatId of fetchSeatIds(eventId)) {
      seatIds.push(seatId);
    }
  }

  if (charge) {
    bookerTokens.forEach((token, i) => chargeBooker(i, token));
    console.log(`charged ${bookerTokens.length} bookers (${BOOKER_CHARGE} each)`);
  }

  console.log(`seat load seed: ${seatIds.length} seats in pool`);

  return { bookerTokens, seatIds };
}
