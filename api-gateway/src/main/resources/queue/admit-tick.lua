-- 입장 처리 칸 차지. 시간을 입장 주기 길이의 칸으로 나누고, 칸마다 먼저 차지한 게이트웨이 한 대만 입장 처리한다 (여러 대여도 입장 속도 유지)
-- ARGV[1] 칸 키 접두사(queue:admit-tick:), ARGV[2] 칸 길이(ms)
-- 반환: 1 이 칸을 차지함(입장 처리), 0 다른 게이트웨이가 이미 차지함(건너뜀)
-- 칸은 Redis 시계로 나눈다 (게이트웨이마다 시계가 조금 달라도 같은 칸으로 판단). 칸 키는 여기서 만들어 KEYS로 넘기지 않는다. admit.lua와 같은 Redis 한 대 전제
local t = redis.call('TIME')
local nowMs = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local length = tonumber(ARGV[2])
local window = math.floor(nowMs / length)
-- 키는 칸 길이의 2배 뒤 사라진다 (그 칸이 지난 뒤에는 쓰지 않으므로 지울 필요 없음)
if redis.call('SET', ARGV[1] .. string.format('%d', window), '1', 'NX', 'PX', length * 2) then
  return 1
end
return 0
