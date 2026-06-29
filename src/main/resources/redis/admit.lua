-- 대기열 원자 입장 (ADR-013).
-- check-then-act(활성 수 확인 → ZPOPMIN → 토큰 발급) 전체를 EVAL 1회로 원자화한다.
-- Redis가 스크립트를 통째로 직렬 실행하므로, 다중 인스턴스/스레드가 동시 호출해도
-- ceiling·rate 초과나 한 사용자 중복 입장이 발생하지 않는다(락 없이 상호배제).
--
-- KEYS[1] = queue:{eventId}   대기열 ZSET (score=진입시각, member=userId)
-- KEYS[2] = active:all        전역 활성 세션 ZSET (score=만료시각ms, member="{eventId}:{userId}")
-- ARGV[1] = eventId
-- ARGV[2] = now(ms)
-- ARGV[3] = ceiling           동시 활성 세션 전역 상한 (C)
-- ARGV[4] = ratePerSec        초당 입장 허용 수 전역 상한 (R)
-- ARGV[5] = ttlMs             입장 토큰 TTL(ms)
-- ARGV[6] = max               이번 호출에서 발급할 최대 인원
-- 반환: 입장시킨 userId 목록(진입 순서)

local queueKey = KEYS[1]
local activeKey = KEYS[2]
local eventId = ARGV[1]
local now = tonumber(ARGV[2])
local ceiling = tonumber(ARGV[3])
local ratePerSec = tonumber(ARGV[4])
local ttlMs = tonumber(ARGV[5])
local max = tonumber(ARGV[6])

-- 만료된 활성 세션 청소 → 이후 ZCARD가 유효 활성 수가 된다.
redis.call('ZREMRANGEBYSCORE', activeKey, 0, now)

local rateKey = 'rate:' .. math.floor(now / 1000)
local admitted = {}

while #admitted < max do
  -- C: 전역 ceiling 확인 (부수효과 없음)
  if redis.call('ZCARD', activeKey) >= ceiling then break end
  -- R: 전역 초당 rate 확인 (부수효과 없음 — 실제 입장 시에만 소비)
  local rate = tonumber(redis.call('GET', rateKey) or '0')
  if rate >= ratePerSec then break end
  -- 대기열 맨 앞 1명
  local popped = redis.call('ZPOPMIN', queueKey, 1)
  if not popped or #popped == 0 then break end
  local userId = popped[1]
  -- 실제 입장 — rate 소비 + 활성 등록 + 게이트용 토큰 발급
  redis.call('INCR', rateKey)
  redis.call('EXPIRE', rateKey, 2)
  redis.call('ZADD', activeKey, now + ttlMs, eventId .. ':' .. userId)
  redis.call('SET', 'entry:' .. eventId .. ':' .. userId, '1', 'PX', ttlMs)
  admitted[#admitted + 1] = userId
end

return admitted
