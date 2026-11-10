package com.gongmoju.gateway.queue;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 접속 대기열 설정 (application.yml의 queue.*).
 *
 * @param admitInterval    입장 처리 주기
 * @param admitRate        초당 입장 인원. 전체 공모주 합이고, 대기자가 있는 공모주에 나눠 준다.
 *                         서비스가 계속 받아도 밀리지 않는 처리량(S1 정식 측정 433건/초)에 맞춘다. 대기열 효과 측정 때만 QUEUE_ADMIT_RATE로 바꾼다
 * @param admissionTtl     입장 허가 유효 시간. 허가 후 입력 화면에서 청약 신청까지 주는 시간
 * @param dropoutFactor    받은 조회 간격의 몇 배 동안 조회가 없으면 이탈로 볼지 (조회를 한두 번 놓친 사람은 빼지 않게)
 * @param pollBudget       게이트웨이가 감당할 초당 순번 조회 수. 조회 간격 = max(1초, 대기 인원 ÷ 이 값). 대기열 효과 측정으로 확정한다
 * @param dropoutBatchSize 입장 처리 한 번에 정리할 이탈자 상한 (Redis를 오래 붙잡지 않게)
 */
@ConfigurationProperties(prefix = "queue")
public record QueueProperties(
        @DefaultValue("PT1S") Duration admitInterval,
        @DefaultValue("433") int admitRate,
        @DefaultValue("PT5M") Duration admissionTtl,
        @DefaultValue("3") int dropoutFactor,
        @DefaultValue("500") int pollBudget,
        @DefaultValue("1000") int dropoutBatchSize
) {

    public QueueProperties {
        requirePositive(admitInterval, "queue.admit-interval");
        requirePositive(admissionTtl, "queue.admission-ttl");
        requirePositive(admitRate, "queue.admit-rate");
        requirePositive(dropoutFactor, "queue.dropout-factor");
        requirePositive(pollBudget, "queue.poll-budget");
        requirePositive(dropoutBatchSize, "queue.dropout-batch-size");
    }

    /**
     * 입장 처리 한 번에 허가할 전체 인원 (초당 입장 인원 × 주기). 1초 주기면 admitRate와 같다.
     */
    public long admitsPerTick() {
        return Math.max(1, Math.round(admitRate * (admitInterval.toMillis() / 1000.0)));
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + "는 0보다 커야 합니다: " + value);
        }
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + "는 0보다 커야 합니다: " + value);
        }
    }
}
