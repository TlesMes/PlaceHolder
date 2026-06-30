# ADR-014. 대기열 enter/status 이벤트 존재 검증 캐싱 (Caffeine)

## 상태

확정 — 구현 완료 (`feature/cache-event-exists`)

> 컨텍스트·고려안·트레이드오프·한계는 Claude 정리, **결정은 작성자 확정.**

## 컨텍스트

### 방파제가 보호 대상을 때리는 자기모순

대기열(ADR-013)의 존재 이유는 **오픈 정각 스파이크로부터 DB를 보호하는 트래픽 셰이핑**이다.
그런데 대기열 진입/상태 조회 경로에 다음 검증이 있었다:

```java
// QueueService.requireEventExists — enter()와 status() 양쪽에서 호출
if (!eventRepository.existsById(eventId)) {   // = SELECT 1 FROM events WHERE id=?
    throw new EventNotFoundException(...);
}
```

- `POST /api/queue/{eventId}/enter` — 오픈 정각에 진입마다 1회
- `GET  /api/queue/{eventId}/status` — 대기 중 **2~10초 간격 폴링마다** 1회

즉 대기열이 막아주려던 그 DB를, 대기열 자신이 매 요청 두드린다. 인기 이벤트 1개에 1,000명이
2초 간격으로 폴링하면 분당 ~3만 회의 **답이 변하지 않는** 동일 질의가 보호 대상 DB로 직행한다.

### 캐싱 대상 데이터의 성질

캐시하려는 값은 `"eventId N의 행이 존재하는가" → boolean` 하나다.

- 이벤트 행은 **생성 후 변경·삭제되지 않는다** (논리 삭제 컬럼 없음, 삭제 API 없음).
- pk는 auto-increment라 바뀌지 않는다.
- 따라서 한 번 `true`면 사실상 영구 `true` — 인메모리 캐시에 이상적인 근-불변 데이터.

## 고려한 방식

| 방식 | 내용 | 평가 |
|------|------|------|
| 부팅 스냅샷 | 기동 시 전체 event id를 Set으로 적재 | ❌ 런타임 생성 이벤트(`POST /api/events`)를 영원히 인식 못 함 |
| 이벤트 생성 훅 | `EventService.create`에서 캐시에 id 추가 | △ 큐 캐시가 이벤트 생성 경로에 결합. 누락·일관성 관리 부담 |
| **read-through 캐시** ✅ | 첫 조회는 DB, 이후 TTL 동안 메모리 | 생성 경로와 무결합. 신규 이벤트는 첫 요청에서 자연 적재 |

## 구현 결정 (초안)

### 1. read-through + 양성(true)만 캐싱

```java
@Cacheable(value = "eventExists", unless = "#result == false")
public boolean exists(Long eventId) {
    return eventRepository.existsById(eventId);
}
```

`unless = "#result == false"`로 **존재하는 이벤트만 캐시**한다. `false`를 캐시하면 직후 생성된
이벤트가 TTL 동안 404로 고착되므로(기능 버그), 미존재는 매번 DB를 거치게 둔다.

### 2. 별도 빈 분리 — self-invocation 회피

`@Cacheable`은 Spring AOP 프록시가 가로채는데, **같은 클래스 내 호출(self-invocation)은 프록시를
우회**해 캐시가 조용히 무력화된다. 원래 검증은 `QueueService` 내부 호출이었으므로, 존재 확인만
독립 빈 `EventExistenceChecker`로 떼어 `QueueService`가 **주입받아 외부 호출**하도록 했다.

### 3. Caffeine, TTL 60초 / maxSize 1000

```java
Caffeine.newBuilder().maximumSize(1_000).expireAfterWrite(Duration.ofSeconds(60))
```

- `expireAfterWrite`(접근 기준 아님): 인기 이벤트가 계속 조회돼도 최대 60초면 무효화 →
  만약 삭제 경로가 생겨도 staleness 상한이 60초로 고정. `expireAfterAccess`였다면 핫이벤트가
  계속 접근돼 **영원히 만료 안 되는** 위험.
- `maximumSize`: 메모리 상한(이벤트 수 대비 충분).

### 4. `sync`는 의도적으로 포기 — `unless`와 양립 불가

`@Cacheable(sync = true)`는 캐시 스탬피드(동시 미스 시 여러 스레드가 동시에 DB 적재)를 막지만,
**`sync=true`에서는 `unless`를 쓸 수 없다.** 이유:

- 일반 모드: `조회 → 메서드 실행 → unless 평가 → (조건 맞으면) 저장 생략` — 단계가 분리돼
  결과를 보고 거를 수 있다.
- sync 모드: `cache.get(key, loader)` 한 번의 **원자 연산**으로 조회·실행·저장이 묶인다(스탬피드
  방지의 핵심). 로더 반환값이 곧 저장값이라, **중간에 결과를 보고 저장을 취소할 훅이 없다.**

우리에겐 양성 캐싱(`unless`)이 기능 정합성에 필수이고, `sync` 부재로 생기는 스탬피드는
오픈 첫 ms + 60초 만료 순간에 **초경량 PK 조회**가 몇 번 겹치는 정도라 미미하다.
→ **기능 버그(unless 상실)를 피하려 미세 성능 손실(sync 상실)을 받아들이는** 트레이드오프.

## ⚠️ 한계 (측정 안 함 — 정직성)

이 변경은 **실측으로 효과를 입증하지 않았다.** ADR-013/D-2와 동일한 정직성 기준을 적용한다.

- "분당 3만 쿼리 → ~0"은 **산술 추정**이지 측정값이 아니다.
- `existsById`는 PK 단건 조회로 극도로 가볍다. 이것이 *실제로* 의미 있는 병목이었다는 증거는 없다.
  "DB 직격"은 구조적으로 맞지만, 그 위험의 정량적 크기는 **미지**다.
- 따라서 이 ADR은 "성능 개선을 측정으로 증명한 것"이 아니라 **"구조적 결함을 인지하고 저비용으로
  보강한 설계 의사결정"** 으로 읽혀야 한다.
- 스탬피드는 잔존한다(§4). 현 부하 가정에선 무시 가능하나, 측정 전엔 단정하지 않는다.

### 효과를 진짜 확인하려면 (후속)
- 부하생성기에서 `enter`/`status` 폴링 부하를 주고, MySQL `existsById` 쿼리 수 / 커넥션 점유를
  캐시 on/off로 비교. D-2의 생성기 병목(coordinated omission)을 피하려면 생성기 분리 필요.

## 결정

read-through **Caffeine 캐시**(`eventExists`, TTL 60초 / maxSize 1000)를 채택한다.

- **양성(`true`)만 캐싱**한다(`unless = "#result == false"`). 미존재 id를 캐싱하면 직후 생성된
  이벤트가 TTL 동안 404로 고착되는 기능 버그가 생기므로, 미존재는 매번 DB를 거치게 둔다.
- 양성 캐싱과 `sync=true`는 양립 불가하므로 **스탬피드 방지(`sync`)를 포기**한다. 잔존 스탬피드
  (오픈 첫 ms·60초 만료 순간의 동시 미스)는 초경량 PK 조회가 몇 회 겹치는 수준이라 수용한다.
- `@Cacheable`이 self-invocation으로 무력화되지 않도록 존재 검증을 별도 빈
  `EventExistenceChecker`로 분리하고 `QueueService`가 주입받아 외부 호출한다.
- **효과 측정은 후속 과제로 남긴다**(§한계). 측정 없이도 "방파제가 매 요청 보호 대상 DB를
  직격"하는 구조적 결함 제거 + 저비용이라는 근거로 현 시점 도입을 확정한다.

## 참조

- ADR-013: 대기열 설계 — 이 캐시가 보호하려는 트래픽 셰이핑 레이어
- ADR-008: 비관적 락 — 정합성 담당(이 캐시와 무관, 트래픽 경로 최적화일 뿐)
- ADR-011: N+1 → GROUP BY 집계 — "조회 경로 DB 부하 절감"이라는 동일 계열의 선행 사례
- `feature/cache-event-exists`: 구현 브랜치 (`QueueService`, `EventExistenceChecker`, `CacheConfig`)
