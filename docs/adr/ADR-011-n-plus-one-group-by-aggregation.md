# ADR-011. 이벤트 목록 좌석 통계 N+1 해결 — GROUP BY 집계 vs fetch join

## 상태

채택

## 컨텍스트

이벤트 목록 화면에 좌석 현황 요약("이벤트별 잔여/총 좌석 수", 예: `500석 중 320석 잔여`)이 필요해 목록 응답에 통계 두 필드를 추가했다. 좌석 예약 서비스 목록에 당연히 있어야 할 정보다.

최초 구현(`EventService.getEvents()`)은 카운트가 필요하니 이벤트 루프 안에서 좌석 수를 이벤트마다 개별 질의하는 가장 직관적인 형태였다:
- `seatRepository.countByEventId(eventId)` — 이벤트당 1쿼리
- `seatRepository.countByEventIdAndStatus(eventId, AVAILABLE)` — 이벤트당 1쿼리

→ 이벤트 N개 = `events 1쿼리 + count 2쿼리 × N` = **1 + 2N 쿼리**. 전형적인 N+1이다.

Phase D 부하 측정의 첫 작업으로 이 목록 조회를 측정 대상으로 골랐다. **절대 처리량(RPS)은 측정 환경(하드웨어)에 종속적이라 해석이 어렵지만, "쿼리 수"와 "상대 개선율"은 환경이 바뀌어도 유지되는 지표**라 before/after 비교에 적합했기 때문이다. 측정으로 N+1을 정량 확인하고, 집계 쿼리로 해결한 뒤 다시 측정해 효과를 검증한다.

## 근거 (비교 분석)

카운트 N+1을 해소하는 두 후보를 비교했다.

| 전략 | 동작 | 장점 | 단점/리스크 |
|---|---|---|---|
| **GROUP BY 집계** (채택) | `WHERE event.id IN (:ids) GROUP BY event.id`로 이벤트별 개수만 한 번에 집계 | 쿼리 1개, **개수만 반환**(이벤트당 한 행), 단방향 설계 유지, 의도(개수)와 쿼리 일치 | 별도 projection 매핑 필요 |
| **fetch join** | `JOIN FETCH e.seats`로 좌석 전체를 끌어와 메모리에서 카운트 | 쿼리 1개, JPA 표준 패턴 | **좌석 전체 행을 메모리로 로딩**(이벤트 100×좌석 500 = 5만 행을 개수 세려고 적재), 단방향 설계상 `Event.seats` 컬렉션이 없어 **양방향 매핑 추가 필요**(CLAUDE.md "연관관계 전부 단방향" 위배) |

### 핵심: "개수가 필요하면 개수를 질의한다"

fetch join은 쿼리 수를 1로 줄이긴 하지만, **카운트 목적에 좌석 전체 데이터를 앱으로 가져오는 낭비**가 있다. 목록 화면에 필요한 건 "320/500"이라는 숫자 두 개지 좌석 5만 행이 아니다. GROUP BY 집계는 DB가 카운트만 계산해 이벤트당 한 행(숫자)만 반환하므로 의도와 쿼리가 일치한다.

또한 이 프로젝트는 단방향·LAZY를 설계 원칙으로 삼는데(ADR 및 CLAUDE.md), fetch join을 쓰려면 `Event`에 `@OneToMany seats` 양방향 매핑을 추가해야 해 원칙과 충돌한다. GROUP BY는 `Seat` 쪽 단방향(`s.event.id`)만으로 충분하다.

> fetch join이 부적합하다는 게 아니다. **좌석 목록을 실제로 화면에 다 보여주는 경우(상세 페이지)** 에는 fetch join이 맞다. "개수만 필요한 목록"과 "전체를 보여주는 상세"는 다른 문제이며, 도구를 목적에 맞게 골라야 한다.

## 결정

**이벤트 목록의 좌석 통계는 GROUP BY 집계 쿼리 1개로 조회한다.**

- `SeatRepository.countSeatsByEventIds(List<Long> eventIds)` — `event.id IN :eventIds GROUP BY event.id`로 (eventId, total, available) 집계, `SeatCountProjection`으로 매핑.
- `EventService.getEvents()` — 집계 결과를 `Map<eventId, counts>`로 만들어 루프에서 O(1) 조회.

→ `events 1쿼리 + 집계 1쿼리` = **총 2쿼리** (이벤트 수 무관 상수).

## 측정 결과 (환경: 로컬, MySQL 8.0 Docker)

쿼리 수는 Hibernate `generate_statistics`(Session Metrics)로, 응답시간은 k6(`loadtest/event-list.js`)로 측정했다.

| 지표 | before (최초 구현) | after (집계) | 개선 |
|---|---|---|---|
| 쿼리 수 (이벤트 5개, 로그 검증) | 11 (=1+2×5) | **2** | — |
| 쿼리 수 (이벤트 100개) | 201 (=1+2×100) | **2** | ~100배 ↓ |
| list 조회 p95 (이벤트 100개, VU10) | 357ms | **35.65ms** | ~10배 ↓ |
| 처리량 (iterations/s, 동일 조건) | 21.9/s | **250.8/s** | ~11배 ↑ |

- before는 데이터 5→100개(20배)에 p95 24ms→357ms(~15배)로 **선형 폭발**.
- after는 쿼리 수가 이벤트 수와 무관한 상수 2라 p95가 평탄.
- **쿼리 수(환경 무관)가 1차 증거**, 응답시간(환경 참고치)이 2차. 절대 RPS가 아니라 상대 개선·곡선 형태에 주목한다.

## 트레이드오프 / 후속

- **집계와 목록이 별도 쿼리(2쿼리):** 이론상 1쿼리(JOIN + 집계)로 더 줄일 수 있으나, 페이징·정렬과의 결합을 단순하게 유지하기 위해 events 조회와 집계를 분리했다. 이벤트 수 무관 상수라 N+1 문제는 이미 해소됐다.
- **N+1 탐지 자동화는 범위 밖:** 회귀로 N+1이 재발하는 것을 막으려면 쿼리 수를 단언하는 테스트(예: datasource-proxy/Hibernate statistics assert)를 둘 수 있다. 이번 PR에서는 측정 서사에 집중하고, 자동 탐지는 후속 과제로 남긴다.
- **상세 페이지 fetch join은 별개:** 좌석을 실제로 나열하는 상세 조회(`getEventDetail`)는 현재 2쿼리(event + seats)로 충분하며, 필요 시 fetch join이 적합한 별도 결정이다.
