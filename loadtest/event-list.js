// 이벤트 목록 조회 부하 테스트 (GET /api/events).
//
// 목적: 목록 응답에 추가된 잔여/총 좌석 수가 N+1을 유발하는지 측정하고,
//       GROUP BY 집계 적용 전(before)/후(after)를 비교한다.
//
// 측정 초점은 절대 RPS(하드웨어 종속)가 아니라:
//   1차) 쿼리 수 — 환경 무관. before=1+2N, after=2 (앱 로그 generate_statistics로 확인)
//   2차) list_duration p95 — 환경 참고치. 데이터 규모를 키우며 증가 곡선 관찰
//
// 실행:
//   k6 run loadtest/event-list.js
//   k6 run -e EVENT_COUNT=200 loadtest/event-list.js   (데이터 2배)

import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';
import { seedData } from './lib/setup.js';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 목록 조회 응답시간을 별도 메트릭으로 분리 집계 (http_req_duration엔 setup 요청도 섞임)
const listDuration = new Trend('list_duration', true);

export const options = {
  scenarios: {
    list_read: {
      executor: 'constant-vus',
      vus: parseInt(__ENV.VUS || '10'),
      duration: __ENV.DURATION || '30s',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],   // 5xx/타임아웃 1% 미만
    list_duration: ['p(95)<2000'],    // 잠정 기준 — 측정 후 조정
  },
};

export function setup() {
  return seedData();
}

export default function () {
  const res = http.get(`${BASE_URL}/api/events`);

  listDuration.add(res.timings.duration);

  check(res, {
    'list 200': (r) => r.status === 200,
    'has events': (r) => Array.isArray(r.json('events')) && r.json('events').length > 0,
    'has seat counts': (r) => r.json('events')[0].totalSeats !== undefined,
  });
}
