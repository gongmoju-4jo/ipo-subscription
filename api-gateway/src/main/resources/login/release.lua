-- 로그인 실패로 세지 않을 응답(400, 5xx 등)이면 예약 때 올린 횟수를 되돌린다. 만료는 그대로 둔다 (DECR은 만료를 유지한다)
-- KEYS[1] 로그인 ID별 실패 횟수 키
-- 반환: 되돌린 뒤 횟수
local count = tonumber(redis.call('GET', KEYS[1]) or '0')
if count > 0 then
  return redis.call('DECR', KEYS[1])
end
return 0
