-- 로그인 시도 예약. 서비스로 보내기 전에 실패 횟수를 먼저 올려, 동시에 보낸 요청이 모두 확인을 통과하지 못하게 한다
-- KEYS[1] 로그인 ID별 실패 횟수 키
-- ARGV[1] 최대 실패 횟수, ARGV[2] 실패를 세는 기간(ms, 첫 실패부터)
-- 반환: {1, 올린 뒤 횟수} 서비스로 보냄 / {0, 남은 차단 시간(ms)} 차단 중 (횟수, 만료를 바꾸지 않는다)
local max = tonumber(ARGV[1])
local count = tonumber(redis.call('GET', KEYS[1]) or '0')
if count >= max then
  local ttl = redis.call('PTTL', KEYS[1])
  if ttl < 0 then
    -- 만료 없는 키는 생기지 않지만, 생기면 영구 차단이 되지 않게 기간을 다시 건다
    redis.call('PEXPIRE', KEYS[1], ARGV[2])
    ttl = tonumber(ARGV[2])
  end
  return {0, ttl}
end
count = redis.call('INCR', KEYS[1])
if count == 1 or redis.call('PTTL', KEYS[1]) < 0 then
  redis.call('PEXPIRE', KEYS[1], ARGV[2])
end
return {1, count}
