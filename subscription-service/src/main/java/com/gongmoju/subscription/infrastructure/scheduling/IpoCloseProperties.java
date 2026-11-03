package com.gongmoju.subscription.infrastructure.scheduling;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * 공모주 마감 스케줄러 설정 (application.yml의 ipo.close.*).
 * 확인 간격(interval)은 {@link IpoCloseScheduler}의 {@code @Scheduled}가 같은 키를 직접 읽고, 켜고 끄기(enabled)는 조건부 빈 등록이 읽는다.
 *
 * @param gracePeriod 마감 시각 뒤 대기 시간. 마감 전에 받은 요청의 발행이 끝나기를 기다린다 (max.block.ms + delivery.timeout.ms + 여유, D-16)
 * @param alertAfter  대기 시간이 지난 뒤 이 시간 동안 마감하지 못하면 알림 로그를 남긴다. 이후에도 이 간격으로 다시 남긴다
 */
@ConfigurationProperties(prefix = "ipo.close")
public record IpoCloseProperties(
        @DefaultValue("PT20S") Duration gracePeriod,
        @DefaultValue("PT5M") Duration alertAfter
) {
}
