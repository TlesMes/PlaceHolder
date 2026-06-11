// 예약 확정 부하 테스트 (POST /api/seats/{seatId}/confirm).
//
// 목적: confirm 트랜잭션의 포화점(knee)을 찾는다. confirm은 hold보다 무겁다 —
//       좌석 + BookerAccount 두 행을 잠그는 다중 락 + 포인트 차감 트랜잭션.
//       도착률을 ramp하며 달성 처리량 천장과 p99 knee를 관찰한다.
//
// ★ confirm 격리 측정. setup이 좌석을 미리 HELD로 만들어 두고, 부하 구간에선 confirm만 호출한다.
//   hold 비용이 안 섞여 confirm 트랜잭션 자체의 비용을 깨끗이 본다.
//   (락 정합성은 JUnit 동시성 테스트가 이미 증명 — 부하 테스트는 곡선만 본다.)
//
// ⚠️ confirm은 좌석을 1회 소비(HELD→CONFIRMED)한다. 그래서:
//   - 좌석마다 정확히 1번만 confirm. VU 반복은 전역 카운터로 좌석을 겹치지 않게 소진한다.
//   - 미리 hold한 좌석 수(holds.length)가 총 confirm 시도보다 많아야 한다(부족하면 좌석 고갈).
//   - hold TTL 5분 안에 측정이 끝나야 한다(만료 hold는 confirm 불가). 측정 길이를 짧게 유지.
//
// 성공/거절/에러 분리 집계:
//   - 성공(200): 달성 처리량·성공 지연(knee 판정 핵심)
//   - 거절(4xx): 좌석 고갈/만료/holder 불일치 등 — 측정 전제가 깨졌다는 신호(곡선에서 걸러 해석)
//   - 에러(5xx/타임아웃): 진짜 포화(lock wait timeout, 커넥션 고갈)
//
// 실행 (좌석 풀을 충분히 — confirm 총량보다 크게):
//   k6 run -e EVENT_COUNT=200 -e START_RATE=50 -e MAX_RATE=400 loadtest/confirm.js

import http from 'k6/http';
import { check } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import exec from 'k6/execution';
import { seedForConfirmLoad } from './lib/setup.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

const confirmDuration = new Trend('confirm_duration', true);
const confirmSuccess = new Counter('confirm_success');
const confirmRejected = new Counter('confirm_rejected');
const confirmError = new Counter('confirm_error');
const seatExhausted = new Counter('seat_exhausted');   // 미리 hold한 좌석을 다 써버린 횟수

const START_RATE = parseInt(__ENV.START_RATE || '50');
// 상한은 높게 — 고정 상한을 낮게 박으면 안 꺾인 채 통과해 knee를 놓친다. 실제 종료는 abortOnFail.
// 단 confirm은 좌석을 1회 소비하므로 상한이 높을수록 사전 hold할 좌석 풀(EVENT_COUNT)도 커야 한다.
const MAX_RATE = parseInt(__ENV.MAX_RATE || '3000');
const STAGE_DURATION = __ENV.STAGE_DURATION || '30s';

export const options = {
  scenarios: {
    confirm_ramp: {
      executor: 'ramping-arrival-rate',
      startRate: START_RATE,
      timeUnit: '1s',
      // 올리기만 한다(open-loop). 에러가 나면 abortOnFail이 중단하므로 보통 상한 전에 멈춘다.
      stages: [
        { target: START_RATE, duration: STAGE_DURATION },
        { target: Math.round(MAX_RATE * 0.1), duration: STAGE_DURATION },
        { target: Math.round(MAX_RATE * 0.25), duration: STAGE_DURATION },
        { target: Math.round(MAX_RATE * 0.5), duration: STAGE_DURATION },
        { target: Math.round(MAX_RATE * 0.75), duration: STAGE_DURATION },
        { target: MAX_RATE, duration: STAGE_DURATION },
      ],
      preAllocatedVUs: 200,
      maxVUs: 3000,
    },
  },
  thresholds: {
    // ★ 에러(5xx/타임아웃) 발생 = 한계 도달 → 자동 중단. "안 터지고 통과" 방지.
    'confirm_error': [{ threshold: 'count<1', abortOnFail: true }],
    // seat_exhausted는 abort하지 않는다. 좌석 고갈은 시스템 한계가 아니라 시드 부족 —
    // 중단이 아니라 EVENT_COUNT를 키워 재측정해야 할 신호다(0이어야 측정 유효).
    'seat_exhausted': ['count<1'],
  },
};

export function setup() {
  return seedForConfirmLoad();   // { holds: [{ seatId, token }] }
}

export default function (data) {
  const { holds } = data;

  // 전역 반복 인덱스로 좌석을 겹치지 않게 소진(좌석=1회용). 풀을 넘어서면 고갈 신호.
  const idx = exec.scenario.iterationInTest;
  if (idx >= holds.length) {
    seatExhausted.add(1);
    return;
  }
  const { seatId, token } = holds[idx];

  const res = http.post(`${BASE_URL}/api/seats/${seatId}/confirm`, null, {
    headers: { 'Authorization': `Bearer ${token}` },
  });

  if (res.status === 200) {
    confirmSuccess.add(1);
    confirmDuration.add(res.timings.duration);   // 성공 지연만 — 4xx 착시 방지
  } else if (res.status >= 400 && res.status < 500) {
    confirmRejected.add(1);
  } else {
    confirmError.add(1);
  }

  check(res, { 'confirm not 5xx': (r) => r.status < 500 });
}
