-- 공모주 하나의 입장 처리. 이탈 기한이 지난 사람을 먼저 빼고(이탈자에게 허가를 주지 않게), 앞에서부터 정해진 수만큼 허가를 준다
-- KEYS[1] waiting, KEYS[2] heartbeat, KEYS[3] active
-- ARGV[1] ipoId, ARGV[2] 지금(epoch ms), ARGV[3] 이번에 허가할 인원, ARGV[4] 허가 유효 시간(ms),
-- ARGV[5] 이번에 정리할 이탈자 상한, ARGV[6] 입장 허가 키 접두사(queue:{ipoId}:admitted:)
-- 반환: {허가한 인원, 정리한 이탈자, 남은 대기 인원}
-- 입장 허가 키는 KEYS로 넘기지 않고 여기서 만든다. Redis 한 대 전제 (클러스터에서는 키가 다른 노드에 있을 수 있어 쓸 수 없다)

-- 이탈자 정리. 한 번에 지우는 수에 상한을 둔다 (한꺼번에 수만 명이 창을 닫아도 Redis를 오래 붙잡지 않게, 남은 사람은 다음 주기에)
local stale = redis.call('ZRANGEBYSCORE', KEYS[2], '-inf', '(' .. ARGV[2], 'LIMIT', 0, ARGV[5])
for _, user in ipairs(stale) do
  redis.call('ZREM', KEYS[1], user)
  redis.call('ZREM', KEYS[2], user)
end

-- 허가 키 값은 진입 시각 (허가 상태에서도 진입 시각을 돌려줄 수 있게)
local admitted = 0
local count = tonumber(ARGV[3])
if count > 0 then
  local popped = redis.call('ZPOPMIN', KEYS[1], count)
  for i = 1, #popped, 2 do
    local user = popped[i]
    redis.call('SET', ARGV[6] .. user, popped[i + 1], 'PX', ARGV[4])
    redis.call('ZREM', KEYS[2], user)
    admitted = admitted + 1
  end
end

local remaining = redis.call('ZCARD', KEYS[1])
if remaining == 0 then
  redis.call('SREM', KEYS[3], ARGV[1])
end
return {admitted, #stale, remaining}
