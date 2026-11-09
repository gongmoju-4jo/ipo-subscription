-- 대기열 진입. 이미 줄에 있으면 처음 순번과 진입 시각을 유지하고, 이미 입장 허가를 받았으면 허가 상태를 돌려준다
-- KEYS[1] waiting(진입 시각순 줄), KEYS[2] heartbeat(이탈 기한), KEYS[3] active(대기자가 있는 공모주), KEYS[4] 이 사람의 입장 허가 키
-- ARGV[1] ipoId, ARGV[2] userId, ARGV[3] 지금(epoch ms), ARGV[4] 이탈 기한(epoch ms)
-- 반환: {상태(1 대기, 2 허가), 순번, 대기 인원, 진입 시각, 허가 남은 시간(ms, 대기면 -1), 새로 진입(1/0)}
local ttl = redis.call('PTTL', KEYS[4])
if ttl > 0 then
  return {2, 0, redis.call('ZCARD', KEYS[1]), redis.call('GET', KEYS[4]), ttl, 0}
end

local created = redis.call('ZADD', KEYS[1], 'NX', ARGV[3], ARGV[2])
redis.call('ZADD', KEYS[2], ARGV[4], ARGV[2])
redis.call('SADD', KEYS[3], ARGV[1])
local rank = redis.call('ZRANK', KEYS[1], ARGV[2])
return {1, rank + 1, redis.call('ZCARD', KEYS[1]), redis.call('ZSCORE', KEYS[1], ARGV[2]), -1, created}
