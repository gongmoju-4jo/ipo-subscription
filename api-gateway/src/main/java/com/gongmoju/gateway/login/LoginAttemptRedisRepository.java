package com.gongmoju.gateway.login;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

/**
 * 로그인 ID별 실패 횟수를 Redis에 읽고 쓴다. 확인과 변경을 Lua 스크립트 하나로 실행해 동시 요청에도 횟수가 어긋나지 않게 한다.
 *
 * <p>키: {@code login-fail:{로그인 ID의 SHA-256 16진수}} String(실패 횟수) + 만료.
 * 로그인 ID 원문을 Redis에 두지 않고, 긴 ID로 큰 키를 만들지 못하게 해시로 둔다. 없는 ID도 같은 방식으로 센다 (가입 여부가 드러나지 않게)
 *
 * <p>흐름: 서비스로 보내기 전 {@link #reserve} → 응답이 401이면 {@link #recordFailure}, 성공이면 {@link #clear}, 그 밖이면 {@link #release}
 */
@Component
public class LoginAttemptRedisRepository {

    static final String KEY_PREFIX = "login-fail:";

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> RESERVE = RedisScript.of(new ClassPathResource("login/reserve.lua"), List.class);
    private static final RedisScript<Long> FAIL = RedisScript.of(new ClassPathResource("login/fail.lua"), Long.class);
    private static final RedisScript<Long> RELEASE = RedisScript.of(new ClassPathResource("login/release.lua"), Long.class);

    private final ReactiveStringRedisTemplate redis;
    private final LoginAttemptProperties properties;

    public LoginAttemptRedisRepository(ReactiveStringRedisTemplate redis, LoginAttemptProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /**
     * 로그인 시도를 예약한다. 차단 중이 아니면 실패 횟수를 미리 1 올리고 통과, 차단 중이면 횟수를 바꾸지 않고 남은 차단 시간을 돌려준다.
     */
    public Mono<Reservation> reserve(String loginId) {
        List<String> args = List.of(Integer.toString(properties.maxFailures()),
                Long.toString(properties.blockDuration().toMillis()));
        return single(redis.execute(RESERVE, List.of(key(loginId)), args))
                .map(result -> number(result.get(0)) == 1
                        ? Reservation.ALLOWED
                        : Reservation.blocked(Duration.ofMillis(number(result.get(1)))));
    }

    /**
     * 로그인 실패(401)를 확정한다. 횟수가 최대에 닿았으면 지금부터 차단 시간만큼 막는다.
     */
    public Mono<Void> recordFailure(String loginId) {
        List<String> args = List.of(Integer.toString(properties.maxFailures()),
                Long.toString(properties.blockDuration().toMillis()));
        return redis.execute(FAIL, List.of(key(loginId)), args).then();
    }

    /**
     * 실패로 세지 않을 응답(400, 5xx 등)이면 예약 때 올린 횟수를 되돌린다.
     */
    public Mono<Void> release(String loginId) {
        return redis.execute(RELEASE, List.of(key(loginId)), List.of()).then();
    }

    /**
     * 로그인 성공. 실패 횟수를 지운다.
     */
    public Mono<Void> clear(String loginId) {
        return redis.delete(key(loginId)).then();
    }

    static String key(String loginId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(loginId.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 자바 구현에 있어야 하는 알고리즘이라 생기지 않는다
            throw new IllegalStateException(e);
        }
    }

    // 스크립트의 배열 반환값을 List 하나로 받는다 (드라이버에 따라 원소별로 나오거나 List 하나로 나오는 차이를 흡수)
    @SuppressWarnings("rawtypes")
    private static Mono<List<?>> single(Flux<List> result) {
        return result.collectList().map(items ->
                items.size() == 1 && items.get(0) instanceof List<?> list ? list : items);
    }

    private static long number(Object value) {
        return ((Number) value).longValue();
    }

    /**
     * 예약 결과.
     *
     * @param allowed    서비스로 보내도 됨
     * @param retryAfter 차단 중이면 남은 차단 시간 (통과면 null)
     */
    public record Reservation(boolean allowed, Duration retryAfter) {
        static final Reservation ALLOWED = new Reservation(true, null);

        static Reservation blocked(Duration retryAfter) {
            return new Reservation(false, retryAfter);
        }
    }
}
