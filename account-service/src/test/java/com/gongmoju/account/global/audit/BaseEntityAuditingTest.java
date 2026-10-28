package com.gongmoju.account.global.audit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

// BaseEntity를 상속한 엔티티를 저장하면 Audit 필드가 자동으로 채워지고, 시각 컬럼이 TIMESTAMPTZ로 생성되는지 확인 (테스트 전용 엔티티)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:audit;MODE=PostgreSQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class BaseEntityAuditingTest {

    @Autowired
    private AuditSampleRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 저장하면_시각과_작성자가_자동으로_채워진다() {
        Instant before = Instant.now();

        AuditSample saved = repository.saveAndFlush(new AuditSample());

        assertThat(saved.getCreatedAt()).isNotNull().isAfterOrEqualTo(before.minusSeconds(1));
        assertThat(saved.getUpdatedAt()).isNotNull();
        // HTTP 요청이 아니므로 시스템 작업으로 기록
        assertThat(saved.getCreatedBy()).isEqualTo("SYSTEM:account-service");
        assertThat(saved.getUpdatedBy()).isEqualTo("SYSTEM:account-service");
    }

    @Test
    void 시각_컬럼은_시간대를_포함한_타입으로_생성된다() {
        String createdAtType = jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_name = 'AUDIT_SAMPLE' AND column_name = 'CREATED_AT'",
                String.class);

        assertThat(createdAtType).isEqualTo("TIMESTAMP WITH TIME ZONE");
    }
}
