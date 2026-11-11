package com.gongmoju.gateway.queue;

import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/**
 * 입장 처리 실패 사유. 지표 queue_admission_failing의 reason 라벨이고, 알림(QueueAdmissionDown) 문구가 이 값으로 갈린다.
 *
 * <p>예외와 그 원인(cause)을 모두 보고 아래 순서로 처음 맞는 사유를 고른다. 같은 예외가 여러 사유에 걸치면 앞의 것이 이긴다
 * (예: Lettuce의 시간 초과 예외는 Redis 예외이기도 하지만 시간 초과로 본다).
 * <ol>
 *   <li>{@link #REDIS_OOM}: Redis가 메모리 상한(maxmemory, noeviction)에 닿아 쓰기를 거부함 ("OOM command not allowed")</li>
 *   <li>{@link #REDIS_TIMEOUT}: 응답 시간 초과. Redis가 멈췄거나 연결이 끊긴 경우도 여기로 온다 (클라이언트가 재연결을 기다리다 시간 초과로 끝나서
 *       "느림"과 "죽음"을 구분할 수 없다)</li>
 *   <li>{@link #REDIS_CONNECTION}: 새 연결을 맺지 못함 (연결 거부, 주소를 찾을 수 없음)</li>
 *   <li>{@link #REDIS_ERROR}: 그 밖의 Redis 오류 (자료형 불일치, 스크립트 오류 등)</li>
 *   <li>{@link #OTHER}: Redis와 무관한 오류 (게이트웨이 코드 오류 등)</li>
 * </ol>
 */
enum AdmissionFailureReason {

    REDIS_OOM("redis_oom"),
    REDIS_TIMEOUT("redis_timeout"),
    REDIS_CONNECTION("redis_connection"),
    REDIS_ERROR("redis_error"),
    OTHER("other");

    // 원인 사슬이 비정상적으로 길거나 순환해도 멈추도록 상한을 둔다
    private static final int MAX_CAUSE_DEPTH = 20;
    private static final String OOM_MESSAGE = "OOM command not allowed";

    private final String tag;

    AdmissionFailureReason(String tag) {
        this.tag = tag;
    }

    String tag() {
        return tag;
    }

    static AdmissionFailureReason classify(Throwable error) {
        List<Throwable> chain = causes(error);
        if (any(chain, t -> t.getMessage() != null && t.getMessage().contains(OOM_MESSAGE))) {
            return REDIS_OOM;
        }
        if (any(chain, t -> t instanceof RedisCommandTimeoutException || t instanceof QueryTimeoutException
                || t instanceof TimeoutException)) {
            return REDIS_TIMEOUT;
        }
        if (any(chain, t -> t instanceof RedisConnectionFailureException || t instanceof RedisConnectionException
                || t instanceof ConnectException || t instanceof UnknownHostException)) {
            return REDIS_CONNECTION;
        }
        if (any(chain, t -> t instanceof DataAccessException || t instanceof RedisException)) {
            return REDIS_ERROR;
        }
        return OTHER;
    }

    private static boolean any(List<Throwable> chain, Predicate<Throwable> test) {
        return chain.stream().anyMatch(test);
    }

    private static List<Throwable> causes(Throwable error) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = error; t != null && chain.size() < MAX_CAUSE_DEPTH && seen.add(t); t = t.getCause()) {
            chain.add(t);
        }
        return chain;
    }
}
