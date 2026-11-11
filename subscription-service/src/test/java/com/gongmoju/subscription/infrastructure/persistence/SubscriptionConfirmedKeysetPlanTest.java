package com.gongmoju.subscription.infrastructure.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 확정 청약 키셋 쿼리가 실행 계획을 재사용할 때도 부분 인덱스 ix_subscription_confirmed_ipo를 쓰는지 실제 PostgreSQL(Testcontainers)로 검증함 (#138). Docker 필요.
 * <p>
 * 같은 쿼리를 여러 번 받으면 PostgreSQL은 값과 관계없이 통하는 공용 계획(generic plan)을 재사용할 수 있다.
 * 상태를 바인딩 값으로 넘기면 공용 계획은 그 값이 CONFIRMED인지 몰라 부분 인덱스를 고르지 못하므로, 쿼리에 'CONFIRMED'를 직접 적어야 한다.
 * 저장소 {@code @Query}의 SQL을 그대로 가져와 공용 계획을 강제하고 실행 계획을 확인한다.
 */
@Testcontainers
@SpringBootTest(properties = "eureka.client.enabled=false")
@DisplayName("확정 청약 키셋 쿼리 실행 계획 (PostgreSQL)")
class SubscriptionConfirmedKeysetPlanTest {

    private static final String CONFIRMED_INDEX = "ix_subscription_confirmed_ipo";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "subscription");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("첫 요청 쿼리는 공용 계획에서도 부분 인덱스로 찾는다")
    void firstRequestQuery_usesConfirmedIndexWithGenericPlan() throws Exception {
        // given
        String sql = querySql("findConfirmedByIpoId", UUID.class, int.class);

        // when
        String plan = genericPlan(sql, "uuid, int", List.of("ipoId", "limit"),
                "'" + UUID.randomUUID() + "', 501");

        // then
        assertThat(plan).contains("Index Scan using " + CONFIRMED_INDEX).doesNotContain("Sort");
    }

    @Test
    @DisplayName("다음 요청 쿼리는 공용 계획에서도 부분 인덱스로 청약 ID 위치부터 찾는다")
    void nextRequestQuery_usesConfirmedIndexWithGenericPlan() throws Exception {
        // given
        String sql = querySql("findConfirmedByIpoIdAfter", UUID.class, UUID.class, int.class);

        // when
        String plan = genericPlan(sql, "uuid, uuid, int", List.of("ipoId", "afterSubscriptionId", "limit"),
                "'" + UUID.randomUUID() + "', '" + UUID.randomUUID() + "', 501");

        // then
        assertThat(plan).contains("Index Scan using " + CONFIRMED_INDEX)
                .contains("subscription_id > $2")
                .doesNotContain("Sort");
    }

    // 저장소 메서드의 네이티브 쿼리 SQL
    private static String querySql(String method, Class<?>... parameterTypes) throws NoSuchMethodException {
        return SubscriptionJpaRepository.class.getDeclaredMethod(method, parameterTypes).getAnnotation(Query.class).value();
    }

    /**
     * SQL의 이름 붙은 파라미터를 순서대로 $1, $2...로 바꿔 PREPARE하고, 공용 계획을 강제한 실행 계획을 돌려준다.
     * 테이블이 작아 순차 탐색이 더 싸게 나오지 않게 순차 탐색을 끈다. 한 연결 안에서 실행한다.
     */
    private String genericPlan(String sql, String types, List<String> names, String args) {
        String prepared = sql;
        for (int i = 0; i < names.size(); i++) {
            prepared = prepared.replace(":" + names.get(i), "$" + (i + 1));
        }
        String statement = "PREPARE keyset_plan_test(" + types + ") AS " + prepared;
        return jdbcTemplate.execute((Statement st) -> {
            st.execute("SET plan_cache_mode = force_generic_plan");
            st.execute("SET enable_seqscan = off");
            st.execute(statement);
            try {
                List<String> lines = new ArrayList<>();
                try (ResultSet rs = st.executeQuery("EXPLAIN (COSTS OFF) EXECUTE keyset_plan_test(" + args + ")")) {
                    while (rs.next()) {
                        lines.add(rs.getString(1));
                    }
                }
                return String.join("\n", lines);
            } finally {
                st.execute("DEALLOCATE keyset_plan_test");
                st.execute("RESET plan_cache_mode");
                st.execute("RESET enable_seqscan");
            }
        });
    }
}
