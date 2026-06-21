// 좌석 홀드 부하 테스트 (POST /api/seats/{seatId}/hold).
//
// 목적: 비관적 락(SELECT ... FOR UPDATE) 단일 행 쓰기 경로의 포화점(knee)을 찾는다.
//       도착률을 계단식으로 올리며, 달성 처리량이 천장에 닿고 p99가 꺾이는 지점을 관찰한다.
//
// 측정 초점은 절대 RPS(하드웨어 종속)가 아니라 곡선의 형태다.
//
// ★ 포화/abort 기준 = 성공 지연 p99 (A안). 1차 측정에서 이 시스템은 5xx로 죽지 않고
//   4xx 거절 + dropped_iterations로 버텨, 5xx 기준 abort가 hold 경로엔 발동하지 않았다.
//   대신 성공 지연 p99가 임계(P99_ABORT_MS)를 넘는 순간을 knee로 보고 자동 중단한다.
//   (5xx 안전망은 보조로 유지 — 진짜 장애 시 즉시 중단.)
//
// ★ ramp는 1차 측정 천장(~356/s) 근방을 촘촘히 본다(50~600/s). 1차의 50→5000은
//   knee를 한 칸(50→500)에 건너뛰고 상단은 dropped_iterations 노이즈였다.
//
// ★ 모드: 분산(spread). 좌석 풀 전체에 부하를 흩뿌려 같은 행 락 충돌을 최소화한다.
//   - "락이 동시 출발 시 한 명만 성공하는가" 같은 정합성은 JUnit 동시성 테스트(C-1/C-4)가
//     이미 증명했다. 부하 테스트는 그걸 재탕하지 않고, 처리량/지연 곡선만 본다.
//   - hold는 좌석을 소비(AVAILABLE→HELD)하므로 풀이 부하 총량보다 충분히 커야 한다.
//     풀이 작으면 측정이 "락 천장"이 아니라 "재고 소진 거절"이 되어 곡선이 오염된다.
//     EVENT_COUNT×SEATS_PER_EVENT를 부하 총량보다 크게 잡을 것.
//
// 성공/거절/에러를 분리 집계한다:
//   - 성공(200): 달성 처리량·성공 지연(knee 판정의 핵심)
//   - 거절(4xx, 이미 점유): 우연한 충돌·소진 신호. 빠른 4xx가 성공 지연에 섞이면 착시가 생기므로 분리.
//   - 에러(5xx/타임아웃): 진짜 포화(lock wait timeout, 커넥션 고갈)
//
// 실행:
//   k6 run loadtest/hold.js
//   k6 run -e P99_ABORT_MS=1000 loadtest/hold.js                (knee 판정 p99 임계 조정)
//   k6 run -e EVENT_COUNT=400 loadtest/hold.js                  (좌석 풀 키우기 — 소진 방지)

import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import { seedForSeatLoad } from './lib/setup.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// hold 응답시간(서버 처리시간). 성공만 따로 보려면 아래 분기에서 성공 시에만 add.
const holdDuration = new Trend('hold_duration', true);
const holdSuccess = new Counter('hold_success');
const holdRejected = new Counter('hold_rejected');
const holdError = new Counter('hold_error');

// 성공 지연 p99 abort 임계(ms). 이 값을 성공 p99가 넘으면 knee로 보고 중단. env로 조정.
const P99_ABORT_MS = parseInt(__ENV.P99_ABORT_MS || '2000');
const STAGE_DURATION = __ENV.STAGE_DURATION || '30s';

export const options = {
  scenarios: {
    hold_ramp: {
      executor: 'ramping-arrival-rate',
      startRate: 50,                // 50/s에서 출발 (저부하 구간 스킵)
      timeUnit: '1s',
      // 천장(~356/s) 근방을 촘촘히 올린다(open-loop, 내리지 않음). 성공 p99가 임계를 넘으면
      // abortOnFail이 멈추므로 상단(600)까지 가기 전에 보통 중단된다 — 곡선 형태 보존.
      stages: [
        { target: 50,  duration: STAGE_DURATION },
        { target: 100, duration: STAGE_DURATION },
        { target: 150, duration: STAGE_DURATION },
        { target: 200, duration: STAGE_DURATION },
        { target: 250, duration: STAGE_DURATION },
        { target: 300, duration: STAGE_DURATION },
        { target: 400, duration: STAGE_DURATION },
        { target: 500, duration: STAGE_DURATION },
        { target: 600, duration: STAGE_DURATION },
      ],
      preAllocatedVUs: 200,
      maxVUs: 3000,
    },
  },
  thresholds: {
    // ★ 주 기준(A): 성공 지연 p99가 임계 초과 = knee 도달 → 자동 중단.
    //   hold_duration은 성공(200)에만 add하므로 "성공 지연 p99"가 정확히 집계된다.
    //   delayAbortEval로 초기 cold-start 스파이크의 오발동을 방지한다.
    'hold_duration': [{ threshold: `p(99)<${P99_ABORT_MS}`, abortOnFail: true, delayAbortEval: '10s' }],
    // 보조: 5xx/타임아웃은 여전히 진짜 실패 → 즉시 중단 (1차엔 0이었지만 안전망 유지).
    //   4xx(이미 점유)는 정상 거절이라 기준에서 제외 — hold_error엔 5xx/timeout만 집계된다.
    'hold_error': [{ threshold: 'count<1', abortOnFail: true }],
  },
};

export function setup() {
  // hold는 포인트 차감이 없으므로 잔액 충전 불필요.
  const { bookerTokens, seatIds } = seedForSeatLoad({ charge: false });
  console.log(`hold load (spread): ${seatIds.length} seats in pool, ${bookerTokens.length} bookers`);
  return { bookerTokens, seatIds };
}

export default function (data) {
  const { bookerTokens, seatIds } = data;

  // 좌석/토큰을 풀에서 무작위 선택 → 부하를 흩뿌린다(분산).
  const seatId = seatIds[Math.floor(Math.random() * seatIds.length)];
  const token = bookerTokens[Math.floor(Math.random() * bookerTokens.length)];

  const res = http.post(`${BASE_URL}/api/seats/${seatId}/hold`, null, {
    headers: { 'Authorization': `Bearer ${token}` },
  });

  if (res.status === 200) {
    holdSuccess.add(1);
    holdDuration.add(res.timings.duration);   // 성공 지연만 곡선에 — 4xx 착시 방지
  } else if (res.status >= 400 && res.status < 500) {
    holdRejected.add(1);   // 이미 점유 등 정상 거절 (우연한 충돌·소진 신호)
  } else {
    holdError.add(1);      // 5xx/타임아웃 — 진짜 실패(락 wait timeout 포함)
  }

  check(res, { 'hold not 5xx': (r) => r.status < 500 });
}
