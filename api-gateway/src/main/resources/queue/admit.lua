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
-- 허가를 먼저 쓰고 그다음 대기열에서 뺀다. Redis가 메모리 상한(noeviction)에 닿으면 SET만 거부되고 스크립트는 되돌려지지 않으므로,
-- 대기열에서 먼저 빼면(ZPOPMIN) 허가 없이 대기열에서 사라지는 사람이 생긴다. 이 순서면 SET이 거부된 사람부터는 대기열에 그대로 남는다
local admitted = 0
local count = tonumber(ARGV[3])
if count > 0 then
  local front = redis.call('ZRANGE', KEYS[1], 0, count - 1, 'WITHSCORES')
  for i = 1, #front, 2 do
    local user = front[i]
    redis.call('SET', ARGV[6] .. user, front[i + 1], 'PX', ARGV[4])
    redis.call('ZREM', KEYS[1], user)
    redis.call('ZREM', KEYS[2], user)
    admitted = admitted + 1
  end
end

local remaining = redis.call('ZCARD', KEYS[1])
if remaining == 0 then
  redis.call('SREM', KEYS[3], ARGV[1])
end
return {admitted, #stale, remaining}
