package com.gongmoju.subscription.infrastructure.redis;

import com.gongmoju.subscription.domain.ipo.CompetitionRateCounter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 경쟁률 사본을 Redis에 둔다. 게이트웨이 접속 대기열과 같은 Redis를 쓴다 (디스크에 저장하지 않아 재시작하면 비고, 그때는 DB로 다시 채움).
 * <ul>
 *   <li>{@code competition:requested:{ipoId}}: 사본. 배정 대상 신청 수량 합 (정수 문자열). 유효 시간 {@code ttl}</li>
 *   <li>{@code competition:loading:{ipoId}}: 채우는 중 표시 (해시: {@code t} 채우기 토큰, {@code p} 그사이 들어온 확정의 합). 유효 시간 {@code loadingTtl} (채우던 쪽이 죽어도 풀림)</li>
 * </ul>
 * 채우기마다 토큰을 두어, 표시가 만료돼 다른 쪽이 다시 채우기 시작했거나 장애 복구로 표시가 지워진 뒤에는 늦게 끝난 채우기가 옛 값을 넣거나 남의 표시를 지우지 못하게 한다.
 * Redis 호출이 실패하면 {@code retryAfter} 동안 Redis를 부르지 않는다. 그동안 읽기는 빈 결과(호출하는 쪽이 DB로 계산), 쓰기는 건너뛴다.
 * 건너뛴(또는 실패한) 더하기가 있는 공모주는 기억했다가 Redis가 돌아오면 사본을 지워, 낮은 값이 남지 않고 다음 조회가 DB로 다시 채우게 한다.
 */
@Slf4j
public class RedisCompetitionRateCounter implements CompetitionRateCounter {

    static final String KEY_PREFIX = "competition:requested:";
    static final String LOADING_PREFIX = "competition:loading:";

    // 사본이 있으면 사본에, 채우는 중이면 채우는 중 합계에 더한다 (INCRBY는 유효 시간을 바꾸지 않음)
    private static final RedisScript<Long> ADD = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then return redis.call('INCRBY', KEYS[1], ARGV[1]) end
            if redis.call('EXISTS', KEYS[2]) == 1 then return redis.call('HINCRBY', KEYS[2], 'p', ARGV[1]) end
            return nil""", Long.class);

    // 사본도 표시도 없을 때만 이 토큰으로 채우는 중 표시를 만든다 (다른 쪽이 채우는 중이면 맡지 않음). 맡으면 1
    private static final RedisScript<Long> START = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 or redis.call('EXISTS', KEYS[2]) == 1 then return 0 end
            redis.call('HSET', KEYS[2], 't', ARGV[1], 'p', '0')
            redis.call('PEXPIRE', KEYS[2], ARGV[2])
            return 1""", Long.class);

    // 표시가 이 토큰일 때만: 사본이 없으면 DB 합산 + 채우는 중 합계로 넣고 표시를 지운다. 토큰이 다르면(만료, 다른 쪽, 복구로 지워짐) 아무것도 안 함.
    // Lua 숫자로 계산하지 않는다 (큰 값은 1e+14처럼 바뀌어 정수로 읽지 못함). DB 합산은 받은 문자열 그대로 넣고 그사이 합계는 INCRBY로 더한다 (유효 시간 유지)
    private static final RedisScript<Long> FINISH = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[2], 't') ~= ARGV[3] then return 0 end
            if redis.call('EXISTS', KEYS[1]) == 0 then
              redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
              local pending = redis.call('HGET', KEYS[2], 'p')
              if pending and pending ~= '0' then redis.call('INCRBY', KEYS[1], pending) end
            end
            redis.call('DEL', KEYS[2])
            return 1""", Long.class);

    private final StringRedisTemplate redis;
    private final CompetitionRateProperties properties;
    private final Clock clock;
    // 이 시각 전까지는 Redis를 부르지 않는다 (실패 뒤 retryAfter)
    private final AtomicReference<Instant> skipUntil = new AtomicReference<>(Instant.MIN);
    // 더하기를 건너뛰거나 실패한 공모주. Redis가 돌아오면 사본을 지운다 (남은 사본은 그만큼 낮은 값이라)
    private final Set<UUID> stale = ConcurrentHashMap.newKeySet();
    // 이 인스턴스가 맡은 채우기의 토큰 (공모주마다 이 인스턴스 안에서는 한 요청만 채움, CompetitionRateReader)
    private final Map<UUID, String> loadingTokens = new ConcurrentHashMap<>();

    public RedisCompetitionRateCounter(StringRedisTemplate redis, CompetitionRateProperties properties, Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void addConfirmed(UUID ipoId, long requestedQuantity) {
        if (!ready()) {
            stale.add(ipoId);
            return;
        }
        try {
            redis.execute(ADD, List.of(key(ipoId), loadingKey(ipoId)), Long.toString(requestedQuantity));
        } catch (RuntimeException e) {
            stale.add(ipoId);
            fail("더하기", e);
        }
    }

    @Override
    public Map<UUID, Long> find(Collection<UUID> ipoIds) {
        if (ipoIds.isEmpty() || !ready()) {
            return Map.of();
        }
        List<UUID> ids = new ArrayList<>(ipoIds);
        List<String> values;
        try {
            values = redis.opsForValue().multiGet(ids.stream().map(RedisCompetitionRateCounter::key).toList());
        } catch (RuntimeException e) {
            fail("읽기", e);
            return Map.of();
        }
        Map<UUID, Long> result = new HashMap<>();
        for (int i = 0; values != null && i < ids.size(); i++) {
            String value = values.get(i);
            if (value == null) {
                continue;
            }
            try {
                result.put(ids.get(i), Long.parseLong(value));
            } catch (NumberFormatException e) {
                // 정상적으로는 생기지 않음. 없는 것으로 보고 DB로 계산하게 둔다 (덮어쓰지는 않음, 유효 시간이 지나면 바뀜)
                log.warn("경쟁률 사본 값이 정수가 아님. DB로 계산함. ipoId={}, value={}", ids.get(i), value);
            }
        }
        return result;
    }

    @Override
    public Set<UUID> startLoading(Collection<UUID> ipoIds) {
        if (ipoIds.isEmpty() || !ready()) {
            return Set.of();
        }
        Set<UUID> owned = new HashSet<>();
        try {
            for (UUID id : ipoIds) {
                String token = UUID.randomUUID().toString();
                Long started = redis.execute(START, List.of(key(id), loadingKey(id)), token, Long.toString(properties.loadingTtl().toMillis()));
                if (started != null && started == 1L) {
                    loadingTokens.put(id, token);
                    owned.add(id);
                }
            }
        } catch (RuntimeException e) {
            fail("채우기 시작", e);
            return Set.of();
        }
        return owned;
    }

    @Override
    public void finishLoading(Map<UUID, Long> requestedQuantities) {
        // 토큰은 결과와 관계없이 꺼낸다 (넣지 못한 채우기의 표시는 유효 시간 뒤 풀림)
        Map<UUID, String> tokens = new HashMap<>();
        requestedQuantities.keySet().forEach(id -> {
            String token = loadingTokens.remove(id);
            if (token != null) {
                tokens.put(id, token);
            }
        });
        if (tokens.isEmpty() || !ready()) {
            return;
        }
        try {
            for (Map.Entry<UUID, String> e : tokens.entrySet()) {
                Long stored = redis.execute(FINISH, List.of(key(e.getKey()), loadingKey(e.getKey())),
                        Long.toString(requestedQuantities.get(e.getKey())), Long.toString(properties.ttl().toMillis()), e.getValue());
                if (stored == null || stored != 1L) {
                    log.info("채우는 중 표시가 바뀌어(만료, 다른 쪽이 채움, 장애 복구) 이 합산은 넣지 않음. ipoId={}", e.getKey());
                }
            }
        } catch (RuntimeException e) {
            fail("채우기", e);
        }
    }

    static String key(UUID ipoId) {
        return KEY_PREFIX + ipoId;
    }

    static String loadingKey(UUID ipoId) {
        return LOADING_PREFIX + ipoId;
    }

    // 건너뛰는 중이 아니면 true. 돌아온 직후에는 더하기를 놓친 공모주의 사본과 채우는 중 표시를 지운다 (실패하면 다시 건너뜀).
    // 표시도 지워야, 놓친 더하기 전에 DB를 합산한 채우기가 끝나며 옛 값을 넣지 못한다 (토큰이 없어져 FINISH가 건너뜀)
    private boolean ready() {
        if (clock.instant().isBefore(skipUntil.get())) {
            return false;
        }
        if (stale.isEmpty()) {
            return true;
        }
        List<UUID> ids = new ArrayList<>(stale);
        try {
            List<String> keys = new ArrayList<>();
            ids.forEach(id -> {
                keys.add(key(id));
                keys.add(loadingKey(id));
            });
            redis.delete(keys);
            ids.forEach(stale::remove);
            log.info("더하기를 놓친 경쟁률 사본을 지움 (다음 조회가 DB로 다시 채움). ipoIds={}", ids);
            return true;
        } catch (RuntimeException e) {
            fail("놓친 사본 지우기", e);
            return false;
        }
    }

    private void fail(String action, RuntimeException e) {
        Instant until = clock.instant().plus(properties.retryAfter());
        Instant previous = skipUntil.getAndSet(until);
        // 건너뛰는 동안에는 Redis를 부르지 않으니 retryAfter마다 최대 한 번 남는다
        log.warn("경쟁률 사본(Redis) {} 실패. {}까지 Redis를 부르지 않고 DB로 계산함. 이전 건너뛰기 끝={}, 원인={}",
                action, until, previous, e.toString());
    }
}
