-- 로그인 실패(서비스 401) 기록. 횟수는 예약(reserve.lua) 때 이미 올렸고, 최대 횟수에 닿았으면 그때부터 차단 시간만큼 만료를 다시 맞춘다
-- (다시 맞추지 않으면 첫 실패 14분 뒤 5번째로 실패한 ID는 1분만 막힌다)
-- KEYS[1] 로그인 ID별 실패 횟수 키
-- ARGV[1] 최대 실패 횟수, ARGV[2] 차단 시간(ms)
-- 반환: 지금 횟수
local count = tonumber(redis.call('GET', KEYS[1]) or '0')
if count >= tonumber(ARGV[1]) then
  redis.call('PEXPIRE', KEYS[1], ARGV[2])
end
return count
