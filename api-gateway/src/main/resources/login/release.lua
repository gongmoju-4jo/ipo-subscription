-- 로그인 실패로 세지 않을 응답(400, 5xx 등)이면 예약 때 올린 횟수를 되돌린다. 만료는 그대로 둔다 (DECR은 만료를 유지한다)
-- 되돌려 0이 되면 키를 지운다. 0인 키를 남기면 비밀번호 없는 요청(서비스가 바로 400)마다 키가 15분씩 남아,
-- 같은 Redis를 쓰는 대기열까지 메모리 상한(noeviction)에 닿을 수 있다. 0인 키와 없는 키는 세 스크립트에서 똑같이 다뤄진다
-- KEYS[1] 로그인 ID별 실패 횟수 키
-- 반환: 되돌린 뒤 횟수
local count = tonumber(redis.call('GET', KEYS[1]) or '0')
if count > 1 then
  return redis.call('DECR', KEYS[1])
end
redis.call('DEL', KEYS[1])
return 0
