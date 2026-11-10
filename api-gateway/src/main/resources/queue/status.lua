-- 내 순번 조회. 대기 중이면 이탈 기한을 늘린다 (조회가 끊기면 입장 처리 때 이탈로 빠진다)
-- KEYS[1] waiting, KEYS[2] heartbeat, KEYS[3] 이 사람의 입장 허가 키
-- ARGV[1] userId, ARGV[2] 이탈 기한(epoch ms)
-- 반환: {상태(0 대기 중 아님, 1 대기, 2 허가), 순번, 대기 인원, 진입 시각, 허가 남은 시간(ms, 대기면 -1), 0}
local ttl = redis.call('PTTL', KEYS[3])
if ttl > 0 then
  return {2, 0, redis.call('ZCARD', KEYS[1]), redis.call('GET', KEYS[3]), ttl, 0}
end

local rank = redis.call('ZRANK', KEYS[1], ARGV[1])
if not rank then
  return {0, 0, 0, '0', -1, 0}
end

redis.call('ZADD', KEYS[2], 'XX', ARGV[2], ARGV[1])
return {1, rank + 1, redis.call('ZCARD', KEYS[1]), redis.call('ZSCORE', KEYS[1], ARGV[1]), -1, 0}
