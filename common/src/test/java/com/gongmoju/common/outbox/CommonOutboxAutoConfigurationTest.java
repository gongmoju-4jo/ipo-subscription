package com.gongmoju.common.outbox;

import com.gongmoju.common.testapp.CommonTestApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서비스 없이 common 자동 설정만으로 Outbox 엔티티, Repository, 발행기가 등록되고 실제 PostgreSQL에 저장, 조회되는지 확인함. Docker 필요.
 */
@Testcontainers
@SpringBootTest(classes = CommonTestApplication.class)
@DisplayName("Outbox 자동 설정 (PostgreSQL)")
class CommonOutboxAutoConfigurationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    private OutboxJpaRepository outboxRepository;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("자동 설정이 발행기와 Repository를 등록하고, 저장한 Outbox를 미발행 목록에서 조회할 수 있다")
    void autoConfiguration_registersPublisherAndRepository() {
        // given
        String messageKey = "key-" + UUID.randomUUID();
        Outbox outbox = Outbox.of("MARGIN", UUID.randomUUID(), "margin.reserved", messageKey, "{\"k\":\"v\"}");

        // when
        transactionTemplate.executeWithoutResult(status -> outboxRepository.save(outbox));

        // then
        assertThat(outboxPublisher).isNotNull();
        assertThat(outbox.getId()).isNotNull();
        assertThat(outboxRepository.findByPublishedAtIsNullOrderByCreatedAtAsc(org.springframework.data.domain.PageRequest.of(0, 100)))
                .extracting(Outbox::getMessageKey)
                .contains(messageKey);
    }
}
