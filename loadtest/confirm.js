// 예약 확정 부하 테스트 (POST /api/seats/{seatId}/confirm).
//
// 목적: confirm 트랜잭션의 포화점(knee)을 찾는다. confirm은 hold보다 무겁다 —
//       좌석 + BookerAccount 두 행을 잠그는 다중 락 + 포인트 차감 트랜잭션.
//       도착률을 ramp하며 달성 처리량 천장과 p99 knee를 관찰한다.
//
// ★ 포화/abort 기준 = 성공 지연 p99 (A안, hold.js와 동일). confirm은 hold보다 무거워
//   천장이 더 낮으므로 ramp를 더 보수적으로(30~300/s) 잡는다.
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
//   k6 run -e EVENT_COUNT=200 loadtest/confirm.js
//   k6 run -e EVENT_COUNT=200 -e P99_ABORT_MS=1000 loadtest/confirm.js   (p99 임계 조정)

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

// 성공 지연 p99 abort 임계(ms). 성공 p99가 넘으면 knee로 보고 중단. env로 조정.
// hold와 통일(2000은 너무 높아 막판에야 걸림).
const P99_ABORT_MS = parseInt(__ENV.P99_ABORT_MS || '1000');
const STAGE_DURATION = __ENV.STAGE_DURATION || '30s';

export const options = {
  // confirm setup은 좌석을 전부 사전 hold해 더 무겁다. k6 기본 60s로는 부족 → 상향.
  setupTimeout: '600s',
  scenarios: {
    confirm_ramp: {
      executor: 'ramping-arrival-rate',
      startRate: 30,                // confirm은 무거워 hold보다 낮게 출발
      timeUnit: '1s',
      // 30~300/s를 촘촘히 올린다(open-loop). 성공 p99가 임계를 넘으면 abortOnFail이 멈춘다.
      // confirm은 좌석을 1회 소비하므로 상단이 높을수록 사전 hold 좌석 풀(EVENT_COUNT)도 커야 한다.
      stages: [
        { target: 30,  duration: STAGE_DURATION },
        { target: 60,  duration: STAGE_DURATION },
        { target: 100, duration: STAGE_DURATION },
        { target: 150, duration: STAGE_DURATION },
        { target: 200, duration: STAGE_DURATION },
        { target: 250, duration: STAGE_DURATION },
        { target: 300, duration: STAGE_DURATION },
      ],
      preAllocatedVUs: 200,
      maxVUs: 3000,
    },
  },
  thresholds: {
    // ★ 주 기준(A): 성공 지연 p99가 임계 초과 = knee 도달 → 자동 중단.
    //   confirm_duration은 성공(200)에만 add하므로 "성공 지연 p99"가 정확히 집계된다.
    'confirm_duration': [{ threshold: `p(99)<${P99_ABORT_MS}`, abortOnFail: true, delayAbortEval: '10s' }],
    // 보조: 5xx/타임아웃은 진짜 실패 → 즉시 중단.
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

  // tags.name으로 URL 그룹화 — seatId가 URL에 들어가 요청마다 별도 time series가 생기는 걸 막는다.
  const res = http.post(`${BASE_URL}/api/seats/${seatId}/confirm`, null, {
    headers: { 'Authorization': `Bearer ${token}` },
    tags: { name: 'POST /api/seats/{id}/confirm' },
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
