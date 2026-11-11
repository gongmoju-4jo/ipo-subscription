package com.gongmoju.gateway.queue;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 대기열 상태를 Redis에 읽고 쓴다. 여러 명령을 묶어야 하는 동작은 Lua 스크립트 하나로 실행해 중간 상태가 생기지 않게 한다.
 *
 * <p>키 (ipoId, userId는 UUID 소문자 문자열. 대문자로 받은 값도 UUID로 바꿔 넘겨야 같은 키가 된다)
 * <ul>
 *   <li>{@code queue:{ipoId}:waiting} Sorted Set: member = userId, score = 진입 시각(ms). 대기 순번</li>
 *   <li>{@code queue:{ipoId}:heartbeat} Sorted Set: member = userId, score = 이탈 기한(ms). 지나면 입장 처리 때 이탈 처리된다</li>
 *   <li>{@code queue:{ipoId}:admitted:{userId}} String + TTL: 입장 허가. 값은 진입 시각(ms)</li>
 *   <li>{@code queue:active} Set: 대기자가 있는 ipoId. 입장 스케줄러가 돌 대상</li>
 * </ul>
 */
@Component
public class QueueRedisRepository {

    static final String ACTIVE_KEY = "queue:active";

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> ENTER = script("queue/enter.lua");
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> STATUS = script("queue/status.lua");
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> ADMIT = script("queue/admit.lua");
    private static final RedisScript<Long> LEAVE = RedisScript.of(new ClassPathResource("queue/leave.lua"), Long.class);

    private static final long STATE_NOT_IN_QUEUE = 0;
    private static final long STATE_ADMITTED = 2;

    private final ReactiveStringRedisTemplate redis;

    public QueueRedisRepository(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 대기열에 진입한다. 이미 대기 중이면 처음 순번을 유지하고, 이미 허가를 받았으면 허가 상태를 돌려준다.
     *
     * @param deadline 이탈 기한. 이 시각까지 순번 조회가 없으면 입장 처리 때 이탈 처리된다
     */
    public Mono<QueueState> enter(UUID ipoId, UUID userId, Instant now, Instant deadline) {
        List<String> keys = List.of(waitingKey(ipoId), heartbeatKey(ipoId), ACTIVE_KEY, admittedKey(ipoId, userId));
        List<String> args = List.of(ipoId.toString(), userId.toString(),
                Long.toString(now.toEpochMilli()), Long.toString(deadline.toEpochMilli()));
        return single(redis.execute(ENTER, keys, args)).map(QueueRedisRepository::toState);
    }

    /**
     * 내 상태를 조회한다. 대기 중이면 이탈 기한을 늘린다. 대기 중도 아니고 허가도 없으면 비어 있다.
     */
    public Mono<QueueState> find(UUID ipoId, UUID userId, Instant deadline) {
        List<String> keys = List.of(waitingKey(ipoId), heartbeatKey(ipoId), admittedKey(ipoId, userId));
        List<String> args = List.of(userId.toString(), Long.toString(deadline.toEpochMilli()));
        return single(redis.execute(STATUS, keys, args))
                .filter(result -> number(result.get(0)) != STATE_NOT_IN_QUEUE)
                .map(QueueRedisRepository::toState);
    }

    /**
     * 대기 순번과 입장 허가를 모두 지운다. 없어도 성공한다.
     */
    public Mono<Void> leave(UUID ipoId, UUID userId) {
        List<String> keys = List.of(waitingKey(ipoId), heartbeatKey(ipoId), admittedKey(ipoId, userId));
        return redis.execute(LEAVE, keys, List.of(userId.toString())).then();
    }

    /**
     * 공모주 하나의 입장 처리: 이탈 기한이 지난 사람을 최대 {@code dropoutLimit}명 빼고, 앞에서부터 {@code count}명에게 허가를 준다.
     * 대기자가 0명이 되면 입장 처리 대상에서 뺀다.
     */
    public Mono<AdmitResult> admit(UUID ipoId, Instant now, long count, Duration ttl, int dropoutLimit) {
        List<String> keys = List.of(waitingKey(ipoId), heartbeatKey(ipoId), ACTIVE_KEY);
        List<String> args = List.of(ipoId.toString(), Long.toString(now.toEpochMilli()), Long.toString(count),
                Long.toString(ttl.toMillis()), Integer.toString(dropoutLimit), admittedKeyPrefix(ipoId));
        return single(redis.execute(ADMIT, keys, args))
                .map(result -> new AdmitResult(number(result.get(0)), number(result.get(1)), number(result.get(2))));
    }

    /**
     * 이 사람이 이 공모주의 입장 허가를 갖고 있는지. 청약 신청 전 입장 허가 확인에 쓴다.
     */
    public Mono<Boolean> isAdmitted(UUID ipoId, UUID userId) {
        return redis.hasKey(admittedKey(ipoId, userId));
    }

    /**
     * 이 공모주의 대기 인원. 순번 조회 간격을 정할 때 쓴다.
     */
    public Mono<Long> waitingCount(UUID ipoId) {
        return redis.opsForZSet().size(waitingKey(ipoId));
    }

    /**
     * 대기자가 있는 공모주 목록.
     */
    public Flux<UUID> activeIpoIds() {
        return redis.opsForSet().members(ACTIVE_KEY).map(UUID::fromString);
    }

    static String waitingKey(UUID ipoId) {
        return "queue:" + ipoId + ":waiting";
    }

    static String heartbeatKey(UUID ipoId) {
        return "queue:" + ipoId + ":heartbeat";
    }

    static String admittedKey(UUID ipoId, UUID userId) {
        return admittedKeyPrefix(ipoId) + userId;
    }

    private static String admittedKeyPrefix(UUID ipoId) {
        return "queue:" + ipoId + ":admitted:";
    }

    @SuppressWarnings("rawtypes")
    private static RedisScript<List> script(String path) {
        return RedisScript.of(new ClassPathResource(path), List.class);
    }

    // 스크립트의 배열 반환값을 List 하나로 받는다 (드라이버에 따라 원소별로 나오거나 List 하나로 나오는 차이를 흡수)
    @SuppressWarnings("rawtypes")
    private static Mono<List<?>> single(Flux<List> result) {
        return result.collectList().map(items ->
                items.size() == 1 && items.get(0) instanceof List<?> list ? list : items);
    }

    private static QueueState toState(List<?> result) {
        boolean admitted = number(result.get(0)) == STATE_ADMITTED;
        long ttlMillis = number(result.get(4));
        return new QueueState(
                admitted ? QueueStatus.ADMITTED : QueueStatus.WAITING,
                number(result.get(1)),
                number(result.get(2)),
                Instant.ofEpochMilli(number(result.get(3))),
                admitted ? Duration.ofMillis(ttlMillis) : null,
                number(result.get(5)) == 1);
    }

    // 정수는 Long으로, 점수(진입 시각)는 문자열로 온다. 점수 문자열이 지수 표기여도 읽히게 BigDecimal로 바꾼다
    private static long number(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return new BigDecimal(value.toString()).longValueExact();
    }
}
