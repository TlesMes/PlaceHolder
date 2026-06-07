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
