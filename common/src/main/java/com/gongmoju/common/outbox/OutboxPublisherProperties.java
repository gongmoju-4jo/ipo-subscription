package com.gongmoju.common.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Outbox 발행기 설정 (application.yml의 outbox.publisher.*). 서비스 3개가 같은 키를 쓴다.
 * 스케줄 주기는 {@link OutboxPublisher}의 {@code @Scheduled}가 같은 키(interval)를 직접 읽는다.
 * 락 최대 시간도 {@code @SchedulerLock}이 같은 키(lock-at-most-for)를 직접 읽고, 여기서는 실행 시간 상한과 맞는지 검사만 한다.
 *
 * @param batchSize      한 번에 조회해서 발행하는 최대 행 수
 * @param alertThreshold 이 횟수 이상 연속 실패하면 알림 로그를 남긴다 (발행은 계속 시도한다. Consumer 재시도와 별개)
 * @param sendTimeout    한 번의 반복에서 보낸 행들의 Kafka 발행 성공을 기다리는 최대 시간 (행마다가 아니라 전체)
 * @param maxRunDuration 한 번의 실행(publishPending)이 새 전송을 시작하는 상한. 넘으면 남은 행은 다음 실행에서 보낸다 (#177)
 * @param lockAtMostFor  스케줄러 락이 저절로 풀리는 시간. 락을 잡은 인스턴스가 죽어도 이 시간 뒤에는 다른 인스턴스가 이어서 발행한다
 */
@ConfigurationProperties(prefix = "outbox.publisher")
public record OutboxPublisherProperties(
        @DefaultValue("100") int batchSize,
        @DefaultValue("5") int alertThreshold,
        @DefaultValue("5s") Duration sendTimeout,
        @DefaultValue("30s") Duration maxRunDuration,
        @DefaultValue("1m") Duration lockAtMostFor
) {

    /**
     * 상한을 넘은 뒤에도 실행이 이어질 수 있는 전송 호출 막힘 시간. 서비스 3개의 Producer max.block.ms(5초)와 같게 둔다.
     * 상한 직전에 시작한 전송 호출 하나가 이만큼 막힐 수 있다
     */
    static final Duration SEND_BLOCK_MARGIN = Duration.ofSeconds(5);

    // 생성자가 둘이라 설정값을 묶을 생성자를 지정한다
    @ConstructorBinding
    public OutboxPublisherProperties {
        if (maxRunDuration.isNegative() || maxRunDuration.isZero()) {
            throw new IllegalArgumentException("outbox.publisher.max-run-duration은 0보다 커야 합니다: " + maxRunDuration);
        }
        // 한 번의 실행이 가장 길게 걸리는 시간: 상한 + 마지막 전송 호출 막힘 + 보낸 행들의 응답 대기 (+ 완료 표시 DB 처리)
        // 이 시간이 락 최대 시간 이상이면, 실행 중에 락이 풀려 다른 인스턴스가 같은 행을 동시에 발행할 수 있다
        Duration longestRun = maxRunDuration.plus(SEND_BLOCK_MARGIN).plus(sendTimeout);
        if (longestRun.compareTo(lockAtMostFor) >= 0) {
            throw new IllegalArgumentException(
                    "outbox.publisher.max-run-duration(" + maxRunDuration + ") + 전송 호출 막힘(" + SEND_BLOCK_MARGIN
                            + ") + send-timeout(" + sendTimeout + ")은 lock-at-most-for(" + lockAtMostFor + ")보다 짧아야 합니다");
        }
    }

    /**
     * 실행 시간 상한, 락 최대 시간은 기본값으로 둔다 (테스트, 직접 만드는 경우)
     */
    public OutboxPublisherProperties(int batchSize, int alertThreshold, Duration sendTimeout) {
        this(batchSize, alertThreshold, sendTimeout, Duration.ofSeconds(30), Duration.ofMinutes(1));
    }
}
