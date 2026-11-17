package com.gongmoju.subscription.presentation.internal;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 공모주별 확정 청약 목록 내부 API의 키셋 방식 (API 명세 4.1, #138)을 실제 PostgreSQL(Testcontainers)로 검증함. Docker 필요.
 * 키셋 조회는 PostgreSQL 네이티브 쿼리(부분 인덱스)라 H2로 검증하지 않는다. page 방식은 배정 서비스가 키셋으로 바뀌어 없앴다.
 * 배정 서비스는 사용자 헤더 없이 호출하므로 요청에 X-User-Id를 넣지 않는다.
 */
@Testcontainers
@SpringBootTest(properties = "eureka.client.enabled=false")
@AutoConfigureMockMvc
@DisplayName("확정 청약 목록 키셋 조회 (PostgreSQL)")
class InternalSubscriptionKeysetApiTest {

    private static final UUID IPO_ID = UUID.fromString("8b2d0000-0000-4000-8000-000000000001");
    private static final UUID USER_ID = UUID.fromString("7a1c0000-0000-4000-8000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("9d4e0000-0000-4000-8000-000000000002");

    // PostgreSQL은 UUID를 바이트 순(부호 없는 비교)으로 정렬한다. Java UUID.compareTo(부호 있는 비교)와 달라서
    // 기대 순서는 문자열 순으로 만든다 (첫 바이트가 0x80 이상인 ID가 뒤에 와야 한다)
    private static final List<String> FIVE_IDS = List.of(
            "f0000000-0000-3000-8000-000000000001", "10000000-0000-3000-8000-000000000001",
            "90000000-0000-3000-8000-000000000001", "00000000-0000-3000-8000-000000000002",
            "7fffffff-0000-3000-8000-000000000001");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "subscription");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM outbox");
        jdbcTemplate.update("DELETE FROM subscription");
        jdbcTemplate.update("DELETE FROM ipo");
        insertIpo(IPO_ID, "CLOSED");
    }

    @Test
    @DisplayName("첫 요청은 앞에서부터 size건과 다음 목록 여부, 전체 건수를 돌려준다")
    void firstRequest_returnsItemsWithHasNextAndTotalElements() throws Exception {
        insertAll(FIVE_IDS);

        mockMvc.perform(keyset(IPO_ID).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].subscriptionId").value("00000000-0000-3000-8000-000000000002"))
                .andExpect(jsonPath("$.data.items[1].subscriptionId").value("10000000-0000-3000-8000-000000000001"))
                .andExpect(jsonPath("$.data.items[0].receivedAt").value("2026-11-03T10:00:00+09:00"))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.totalElements").value(5))
                .andExpect(jsonPath("$.data.page").doesNotExist())
                .andExpect(jsonPath("$.data.totalPages").doesNotExist());
    }

    @Test
    @DisplayName("마지막 ID로 이어서 끝까지 읽으면 subscriptionId 오름차순으로 중복, 누락 없이 받고 전체 건수는 첫 요청에만 있다")
    void readToEnd_inOrderWithoutDuplicatesOrGaps() throws Exception {
        insertAll(FIVE_IDS);

        List<String> keyset = readAllByKeyset(2, 3);

        assertThat(keyset).containsExactlyElementsOf(FIVE_IDS.stream().sorted().toList());
    }

    @Test
    @DisplayName("항목은 명세의 6개 필드를 KST 시각으로 담는다")
    void item_hasSixFieldsInKst() throws Exception {
        UUID subscriptionId = insertSubscription(UUID.fromString("50000000-0000-3000-8000-000000000001"), IPO_ID, USER_ID, "CONFIRMED");

        String body = mockMvc.perform(keyset(IPO_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].subscriptionId").value(subscriptionId.toString()))
                .andExpect(jsonPath("$.data.items[0].userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].accountId").value(ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].requestedQuantity").value(100))
                .andExpect(jsonPath("$.data.items[0].marginAmount").value(1500000))
                .andExpect(jsonPath("$.data.items[0].receivedAt").value("2026-11-03T10:00:00+09:00"))
                .andReturn().getResponse().getContentAsString();

        Map<String, Object> item = JsonPath.read(body, "$.data.items[0]");
        assertThat(item).containsOnlyKeys("subscriptionId", "userId", "accountId", "requestedQuantity", "marginAmount", "receivedAt");
    }

    @Test
    @DisplayName("page, sort 파라미터는 무시하고 앞에서부터 subscriptionId 오름차순으로 응답한다")
    void pageAndSort_areIgnored() throws Exception {
        insertAll(FIVE_IDS);

        mockMvc.perform(keyset(IPO_ID).param("page", "1").param("size", "2").param("sort", "subscriptionId,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].subscriptionId").value("00000000-0000-3000-8000-000000000002"))
                .andExpect(jsonPath("$.data.items[1].subscriptionId").value("10000000-0000-3000-8000-000000000001"))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.totalElements").value(5))
                .andExpect(jsonPath("$.data.page").doesNotExist());

        // 다음 요청에 page를 함께 보내도 afterSubscriptionId 다음부터 읽는다 (page와 함께 보내도 400이 아님)
        mockMvc.perform(keyset(IPO_ID).param("page", "0").param("size", "2")
                        .param("afterSubscriptionId", "10000000-0000-3000-8000-000000000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].subscriptionId").value("7fffffff-0000-3000-8000-000000000001"));
    }

    @Test
    @DisplayName("빈 afterSubscriptionId는 보내지 않은 것과 같아 첫 요청으로 응답한다 (page를 함께 보내도 같음)")
    void emptyAfter_isFirstRequest() throws Exception {
        insertAll(FIVE_IDS);

        for (MockHttpServletRequestBuilder request : List.of(
                keyset(IPO_ID).param("afterSubscriptionId", "").param("size", "2"),
                keyset(IPO_ID).param("afterSubscriptionId", "").param("page", "1").param("size", "2"))) {
            mockMvc.perform(request)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].subscriptionId").value("00000000-0000-3000-8000-000000000002"))
                    .andExpect(jsonPath("$.data.totalElements").value(5))
                    .andExpect(jsonPath("$.data.hasNext").value(true));
        }
    }

    @Test
    @DisplayName("size는 최대 1000까지 허용한다")
    void size1000_isAllowed() throws Exception {
        mockMvc.perform(keyset(IPO_ID).param("size", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(1000));
    }

    @Test
    @DisplayName("건수가 size로 나누어떨어져도 마지막 요청의 hasNext는 false다")
    void exactMultipleOfSize_lastRequestHasNoNext() throws Exception {
        insertAll(FIVE_IDS.subList(0, 4));

        List<String> ids = readAllByKeyset(2, 2);

        assertThat(ids).hasSize(4);
    }

    @Test
    @DisplayName("확정 청약이 없으면 빈 목록, hasNext false, 전체 건수 0, 기본 size 500이다")
    void noConfirmed_returnsEmpty() throws Exception {
        mockMvc.perform(keyset(IPO_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.size").value(500))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    @DisplayName("해당 공모주의 CONFIRMED 청약만 돌려준다")
    void onlyConfirmedOfIpo() throws Exception {
        UUID otherIpo = insertIpo(UUID.fromString("8b2d0000-0000-4000-8000-000000000002"), "CLOSED");
        UUID confirmed = insertSubscription(UUID.fromString("50000000-0000-3000-8000-000000000001"), IPO_ID, "CONFIRMED");
        List<String> others = List.of("PENDING", "REJECTED", "COMPENSATING", "CANCEL_REQUESTED", "CANCELLED", "ALLOCATED", "REFUNDED");
        for (int i = 0; i < others.size(); i++) {
            // 확정 청약 앞뒤에 다른 상태 청약을 둔다
            insertSubscription(UUID.fromString((i % 2 == 0 ? "1" : "9") + "000000" + i + "-0000-3000-8000-000000000001"), IPO_ID, others.get(i));
        }
        insertSubscription(UUID.fromString("60000000-0000-3000-8000-000000000001"), otherIpo, "CONFIRMED");

        mockMvc.perform(keyset(IPO_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].subscriptionId").value(confirmed.toString()))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("afterSubscriptionId가 UUID가 아니거나 size가 범위를 벗어나면 400이다")
    void invalidParams_areBadRequest() throws Exception {
        List<String[]> invalid = List.of(
                new String[]{"afterSubscriptionId", "not-a-uuid"},
                new String[]{"size", "0"}, new String[]{"size", "1001"}, new String[]{"size", "abc"});

        for (String[] param : invalid) {
            mockMvc.perform(keyset(IPO_ID).param(param[0], param[1]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.details[0].field").value(param[0]));
        }
        mockMvc.perform(keyset(IPO_ID).param("size", "1001"))
                .andExpect(jsonPath("$.error.message").value("페이지 요청 값이 올바르지 않습니다."))
                .andExpect(jsonPath("$.error.details[0].reason").value("페이지 크기는 1 이상 1000 이하여야 합니다."));
    }

    @Test
    @DisplayName("status가 없거나 CONFIRMED가 아니면 400이다")
    void statusMissingOrNotConfirmed_isBadRequest() throws Exception {
        mockMvc.perform(get("/internal/ipos/" + IPO_ID + "/subscriptions"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("status"));

        for (String status : List.of("PENDING", "ALLOCATED", "confirmed", "UNKNOWN")) {
            mockMvc.perform(get("/internal/ipos/" + IPO_ID + "/subscriptions").param("status", status))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.details[0].field").value("status"));
        }
    }

    @Test
    @DisplayName("ipoId가 UUID가 아니면 400이다")
    void ipoIdNotUuid_isBadRequest() throws Exception {
        mockMvc.perform(get("/internal/ipos/not-a-uuid/subscriptions").param("status", "CONFIRMED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[0].field").value("ipoId"));
    }

    @Test
    @DisplayName("공모주가 없으면 404, CLOSED가 아니면(OPEN, ALLOCATED) 409다")
    void ipoMissingOrNotClosed() throws Exception {
        mockMvc.perform(keyset(UUID.fromString("8b2d0000-0000-4000-8000-000000000099")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.details[0].field").value("ipoId"));

        UUID open = insertIpo(UUID.fromString("8b2d0000-0000-4000-8000-000000000003"), "OPEN");
        UUID allocated = insertIpo(UUID.fromString("8b2d0000-0000-4000-8000-000000000004"), "ALLOCATED");
        for (UUID ipoId : List.of(open, allocated)) {
            mockMvc.perform(keyset(ipoId))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"))
                    .andExpect(jsonPath("$.error.details[0].field").value("ipoId"))
                    .andExpect(jsonPath("$.error.details[0].reason").value("마감된 공모주가 아닙니다."));
        }
        mockMvc.perform(keyset(open).param("afterSubscriptionId", FIVE_IDS.get(0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"));
    }

    // 첫 요청부터 hasNext가 false일 때까지 이어서 읽는다. 요청 횟수와 totalElements가 첫 요청에만 있는지도 확인한다
    private List<String> readAllByKeyset(int size, int expectedRequests) throws Exception {
        List<String> ids = new ArrayList<>();
        String after = null;
        for (int request = 1; ; request++) {
            MockHttpServletRequestBuilder builder = keyset(IPO_ID).param("size", String.valueOf(size));
            if (after != null) {
                builder.param("afterSubscriptionId", after);
            }
            String body = mockMvc.perform(builder)
                    .andExpect(status().isOk())
                    .andExpect(request == 1 ? jsonPath("$.data.totalElements").exists() : jsonPath("$.data.totalElements").doesNotExist())
                    .andReturn().getResponse().getContentAsString();
            List<String> items = JsonPath.read(body, "$.data.items[*].subscriptionId");
            ids.addAll(items);
            boolean hasNext = JsonPath.read(body, "$.data.hasNext");
            if (!hasNext) {
                assertThat(request).isEqualTo(expectedRequests);
                return ids;
            }
            assertThat(items).hasSize(size);
            after = items.get(items.size() - 1);
        }
    }

    private MockHttpServletRequestBuilder keyset(UUID ipoId) {
        return get("/internal/ipos/" + ipoId + "/subscriptions").param("status", "CONFIRMED");
    }

    private void insertAll(List<String> ids) {
        for (String id : ids) {
            insertSubscription(UUID.fromString(id), IPO_ID, "CONFIRMED");
        }
    }

    // 값은 API 명세 4.1 예시와 같다 (100주, 증거금 1,500,000원, 접수 2026-11-03T10:00:00+09:00)
    private UUID insertSubscription(UUID subscriptionId, UUID ipoId, String status) {
        return insertSubscription(subscriptionId, ipoId, UUID.randomUUID(), status);
    }

    private UUID insertSubscription(UUID subscriptionId, UUID ipoId, UUID userId, String status) {
        jdbcTemplate.update(
                "INSERT INTO subscription (subscription_id, request_id, ipo_id, user_id, account_id,"
                        + " requested_quantity, margin_amount, status, attempts, received_at, version, created_at, created_by)"
                        + " VALUES (?, ?, ?, ?, ?, 100, 1500000, ?, 0, ?, 0, ?, 'SYSTEM:test')",
                subscriptionId, UUID.randomUUID(), ipoId, userId, ACCOUNT_ID, status,
                OffsetDateTime.parse("2026-11-03T10:00:00.123+09:00"), OffsetDateTime.parse("2026-11-03T10:00:01+09:00"));
        return subscriptionId;
    }

    private UUID insertIpo(UUID ipoId, String status) {
        jdbcTemplate.update(
                "INSERT INTO ipo (ipo_id, name, offer_price, retail_quantity, equal_quantity, min_quantity, max_quantity,"
                        + " margin_rate, subscription_start_at, subscription_end_at, refund_date, status, version, created_at, created_by)"
                        + " VALUES (?, '테스트 공모주 A', 15000, 100000, 50000, 10, 10000, 1.00, ?, ?, DATE '2026-11-06', ?, 0, ?, 'SYSTEM:test')",
                ipoId, OffsetDateTime.parse("2026-11-03T10:00:00+09:00"), OffsetDateTime.parse("2026-11-04T16:00:00+09:00"),
                status, OffsetDateTime.parse("2026-10-28T10:00:00+09:00"));
        return ipoId;
    }
}
