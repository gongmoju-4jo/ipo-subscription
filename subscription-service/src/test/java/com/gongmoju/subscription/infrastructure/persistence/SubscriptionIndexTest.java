package com.gongmoju.subscription.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 확정 청약 목록 조회용 부분 인덱스(#138)를 실제 PostgreSQL(Testcontainers)로 검증함. Docker 필요.
 */
@Testcontainers
@SpringBootTest(properties = "eureka.client.enabled=false")
@DisplayName("확정 청약 목록 조회 인덱스 (PostgreSQL)")
class SubscriptionIndexTest {

    private static final String CONFIRMED_INDEX = "ix_subscription_confirmed_ipo";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "subscription");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("기동하면 확정 청약만 담는 (ipo_id, subscription_id) 부분 인덱스가 생긴다")
    void startup_createsConfirmedPartialIndex() {
        // when
        List<String> columns = indexColumns(CONFIRMED_INDEX);
        String predicate = jdbcTemplate.queryForObject("""
                SELECT pg_get_expr(i.indpred, i.indrelid)
                FROM pg_index i
                JOIN pg_class c ON c.oid = i.indexrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'subscription' AND c.relname = ?""", String.class, CONFIRMED_INDEX);

        // then
        assertThat(columns).containsExactly("ipo_id", "subscription_id");
        assertThat(predicate).isEqualTo("((status)::text = 'CONFIRMED'::text)");
    }

    @Test
    @DisplayName("공모주별 상태 인덱스 (ipo_id, status)는 그대로 남는다")
    void startup_keepsIpoStatusIndex() {
        // when
        List<String> columns = indexColumns("ix_subscription_ipo_status");

        // then
        assertThat(columns).containsExactly("ipo_id", "status");
    }

    // 인덱스 열 이름을 인덱스 안의 순서대로. 인덱스가 없으면 빈 목록
    private List<String> indexColumns(String indexName) {
        return jdbcTemplate.queryForList("""
                SELECT a.attname
                FROM pg_index i
                JOIN pg_class c ON c.oid = i.indexrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord) ON true
                JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
                WHERE n.nspname = 'subscription' AND c.relname = ?
                ORDER BY k.ord""", String.class, indexName);
    }
}
