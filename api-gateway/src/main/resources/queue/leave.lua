-- 대기열 이탈. 대기 순번과 입장 허가를 모두 지운다 (없어도 성공)
-- KEYS[1] waiting, KEYS[2] heartbeat, KEYS[3] 이 사람의 입장 허가 키
-- ARGV[1] userId
redis.call('ZREM', KEYS[1], ARGV[1])
redis.call('ZREM', KEYS[2], ARGV[1])
redis.call('DEL', KEYS[3])
return 1
