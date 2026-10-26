package com.gongmoju.common.feign;

import feign.RetryableException;
import feign.Retryer;

import java.net.SocketTimeoutException;
import java.util.Locale;

/**
 * 내부 API 호출 재시도. Feign은 RetryableException일 때만 이 Retryer를 부른다.
 * <ul>
 *   <li>5xx: FeignErrorDecoder가 FeignServerException을 RetryableException으로 감싸 넘긴다 → 재시도 후에도 실패하면 FeignServerException으로 꺼내 던진다</li>
 *   <li>타임아웃, 연결 실패: Feign이 RetryableException으로 넘긴다 → 그대로 던진다</li>
 *   <li>4xx: FeignClientException이라 여기로 오지 않는다 (재시도 없음)</li>
 * </ul>
 * 횟수, 간격 계산은 {@code Retryer.Default}에 맡긴다 (대기는 interval부터 1.5배씩, maxInterval까지).
 * 5xx 래핑을 푸는 건 이 Retryer뿐이라 내부 클라이언트의 Retryer는 반드시 FeignRetryer여야 한다 (서비스가 Retryer 빈을 직접 만들 때도).
 * 재시도를 끄려면 yml {@code common.feign.retry.max-retries: 0} 또는 {@link #noRetry()}를 쓴다.
 * {@code Retryer.NEVER_RETRY}는 5xx가 RetryableException으로 나가므로, 자기 디코더를 쓰는 외부 클라이언트(Slack 등)에만 지정한다.
 */
public class FeignRetryer implements Retryer {

    private final long interval;
    private final long maxInterval;
    private final int maxAttempts;
    private final boolean retryOnReadTimeout;
    private final Retryer delegate;

    /**
     * 재시도 정책을 만든다.
     *
     * @param interval           첫 재시도 전 대기(ms)
     * @param maxInterval        대기 상한(ms)
     * @param maxAttempts        처음 호출을 포함한 총 횟수
     * @param retryOnReadTimeout 응답 지연(read timeout)도 재시도할지
     */
    public FeignRetryer(long interval, long maxInterval, int maxAttempts, boolean retryOnReadTimeout) {
        this.interval = interval;
        this.maxInterval = maxInterval;
        this.maxAttempts = maxAttempts;
        this.retryOnReadTimeout = retryOnReadTimeout;
        this.delegate = new Retryer.Default(interval, maxInterval, maxAttempts);
    }

    /**
     * 재시도 없이 1번만 호출. 5xx는 FeignServerException으로 꺼내 던진다.
     * <p>
     * 내부 클라이언트에서 재시도를 끌 때 {@code Retryer.NEVER_RETRY} 대신 사용 (NEVER_RETRY는 5xx가 RetryableException으로 나감)
     *
     * @return 재시도하지 않는 FeignRetryer
     */
    public static FeignRetryer noRetry() {
        return new FeignRetryer(0, 0, 1, true);
    }

    /**
     * 재시도할지 정한다. 더 재시도하지 않으면 예외를 던진다.
     *
     * @param e Feign이 넘긴 재시도 가능 예외
     * @throws FeignServerException 5xx로 재시도 횟수를 다 쓴 경우
     * @throws RetryableException   타임아웃, 연결 실패로 재시도 횟수를 다 쓴 경우 (응답 지연 재시도를 껐으면 바로)
     */
    @Override
    public void continueOrPropagate(RetryableException e) {
        if (!retryOnReadTimeout && isReadTimeout(e)) {
            throw e;
        }
        try {
            delegate.continueOrPropagate(e);
        } catch (RetryableException last) {
            if (last.getCause() instanceof FeignServerException serverException) {
                throw serverException;
            }
            throw last;
        }
    }

    /**
     * 호출마다 시도 횟수를 새로 세도록 Feign이 clone()으로 새 Retryer를 만든다
     *
     * @return 같은 설정의 새 FeignRetryer
     */
    @Override
    public Retryer clone() {
        return new FeignRetryer(interval, maxInterval, maxAttempts, retryOnReadTimeout);
    }

    // 기본 클라이언트(HttpURLConnection)는 연결, 응답 타임아웃 모두 SocketTimeoutException이라 메시지로 구분한다
    // ("Connect timed out" / "Read timed out")
    private boolean isReadTimeout(RetryableException e) {
        if (!(e.getCause() instanceof SocketTimeoutException timeout)) {
            return false;
        }
        String message = timeout.getMessage();
        return message == null || !message.toLowerCase(Locale.ROOT).contains("connect");
    }
}
