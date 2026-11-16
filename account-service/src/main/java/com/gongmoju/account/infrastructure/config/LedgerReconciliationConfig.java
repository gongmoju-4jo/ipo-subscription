package com.gongmoju.account.infrastructure.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// 원장 대사 스케줄러 설정. 스케줄링은 ledger.reconciliation.enabled=false로 비활성화 가능하다 (테스트는 대사 메서드를 직접 호출)
// Outbox 발행기와 별도로 활성화한다. 한쪽을 비활성화해도 다른 쪽 스케줄은 실행돼야 하기 때문
@Configuration
class LedgerReconciliationConfig {

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(prefix = "ledger.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfig {
    }
}
