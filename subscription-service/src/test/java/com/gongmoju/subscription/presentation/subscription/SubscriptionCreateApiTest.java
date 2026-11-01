package com.gongmoju.subscription.presentation.subscription;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.subscription.domain.subscription.SubscriptionIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 청약 신청 API. 공모주는 DB에 직접 넣고(H2), Kafka 전송은 KafkaTemplate을 대신해 결과만 정한다
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:subscription-create-api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class SubscriptionCreateApiTest {

    private static final String SUBSCRIPTIONS = "/api/v1/subscriptions";
    private static final String TOPIC = "subscription.requested";

    private static final UUID USER_ID = UUID.fromString("7a1c0000-0000-4000-8000-000000000001");
    private static final UUID REQUEST_ID = UUID.fromString("3b8f0000-0000-4000-8000-000000000031");
    private static final UUID IPO_ID = UUID.fromString("8b2d0000-0000-4000-8000-000000000001");
    private static final UUID ACCOUNT_ID = UUID.fromString("9d4e0000-0000-4000-8000-000000000002");

    // 공모주 청약 기간: 2026-11-03T10:00+09:00 (01:00Z) ~ 2026-11-04T16:00+09:00 (07:00Z)
    private static final String START_UTC = "2026-11-03T01:00:00.000Z";
    private static final String END_UTC = "2026-11-04T07:00:00.000Z";
    private static final String DURING_UTC = "2026-11-03T05:30:15.123Z";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM subscription.ipo");
        insertIpo(IPO_ID, "OPEN");
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void 접수하면_202와_결정적_청약_ID를_명세_형식으로_돌려준다() throws Exception {
        UUID expectedId = SubscriptionIdGenerator.generate(USER_ID, REQUEST_ID);

        subscribe(body(REQUEST_ID, IPO_ID, ACCOUNT_ID, 100), USER_ID.toString(), DURING_UTC)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("청약 신청을 접수했습니다."))
                .andExpect(jsonPath("$.data.subscriptionId").value(expectedId.toString()))
                .andExpect(jsonPath("$.data.requestId").value(REQUEST_ID.toString()))
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.statusUrl").value("/api/v1/subscriptions/" + expectedId));
    }

    @Test
    void 발행_메시지는_userId_키와_이벤트_명세_필드를_가진다() throws Exception {
        subscribe(body(REQUEST_ID, IPO_ID, ACCOUNT_ID, 100), USER_ID.toString(), DURING_UTC)
                .andExpect(status().isAccepted());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(TOPIC), key.capture(), payload.capture());
        assertThat(key.getValue()).isEqualTo(USER_ID.toString());

        JsonNode message = objectMapper.readTree(payload.getValue());
        assertThat(message.properties()).extracting(Map.Entry::getKey).containsExactly(
                "eventId", "eventType", "occurredAt", "requestId", "subscriptionId", "userId", "accountId", "ipoId",
                "requestedQuantity", "receivedAt");
        assertThat(UUID.fromString(message.get("eventId").asText())).isNotNull();
        assertThat(message.get("eventType").asText()).isEqualTo(TOPIC);
        assertThat(message.get("occurredAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\+09:00");
        assertThat(message.get("requestId").asText()).isEqualTo(REQUEST_ID.toString());
        assertThat(message.get("subscriptionId").asText())
                .isEqualTo(SubscriptionIdGenerator.generate(USER_ID, REQUEST_ID).toString());
        assertThat(message.get("userId").asText()).isEqualTo(USER_ID.toString());
        assertThat(message.get("accountId").asText()).isEqualTo(ACCOUNT_ID.toString());
        assertThat(message.get("ipoId").asText()).isEqualTo(IPO_ID.toString());
        assertThat(message.get("requestedQuantity").asLong()).isEqualTo(100);
        // 헤더(UTC)를 KST +09:00, 밀리초 3자리로 바꿔 넣는다
        assertThat(message.get("receivedAt").asText()).isEqualTo("2026-11-03T14:30:15.123+09:00");
    }

    @Test
    void 도착_시각에_밀리초가_없어도_000으로_채운다() throws Exception {
        subscribe(body(REQUEST_ID, IPO_ID, ACCOUNT_ID, 100), USER_ID.toString(), "2026-11-03T05:30:15Z")
                .andExpect(status().isAccepted());

        assertThat(sentMessage().get("receivedAt").asText()).isEqualTo("2026-11-03T14:30:15.000+09:00");
    }

    @Test
    void 같은_requestId로_다시_요청하면_같은_청약_ID로_다시_발행한다() throws Exception {
        String expectedId = SubscriptionIdGenerator.generate(USER_ID, REQUEST_ID).toString();

        subscribe(body(REQUEST_ID, IPO_ID, ACCOUNT_ID, 100), USER_ID.toString(), DURING_UTC)
                .andExpect(jsonPath("$.data.subscriptionId").value(expectedId));
        subscribe(body(REQUEST_ID, IPO_ID, ACCOUNT_ID, 100), USER_ID.toString(), DURING_UTC)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.subscriptionId").value(expectedId));

        // 중복은 접수 Consumer가 request_id로 걸러낸다
        verify(kafkaTemplate, times(2)).send(anyString(), anyString(), anyString());
    }

    @Test
    void 다른_회원이_같은_requestId를_쓰면_다른_청약_ID다() throws Exception {
        UUID otherUser = UUID.randomUUID();

        subscribe(body(REQUEST_ID, IPO_ID, ACCOUNT_ID, 100), otherUser.toString(), DURING_UTC)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.subscriptionId")
                        .value(SubscriptionIdGenerator.generate(otherUser, REQUEST_ID).toString()));
    }

    @Test
    void 대문자_UUID로_보내도_소문자와_같은_청약_ID다() throws Exception {
        subscribe(body(REQUEST_ID.toString().toUpperCase(), IPO_ID.toString(), ACCOUNT_ID.toString(), 100),
                USER_ID.toString().toUpperCase(), DURING_UTC)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.subscriptionId")
                        .value(SubscriptionIdGenerator.generate(USER_ID, REQUEST_ID).toString()));
    }

    @Test
    void 청약_시작_시각은_포함하고_마감_시각은_포함하지_않는다() throws Exception {
        subscribe(body(UUID.randomUUID(), IPO_ID, ACCOUNT_ID, 100), USER_ID.toString(), START_UTC)
                .andExpect(status().isAccepted());
        subscribe(body(UUID.randomUUID(), IPO_ID, ACCOUNT_ID, 100), USER_ID.toString(), "2026-11-04T06:59:59.999Z")
                .andExpect(status().isAccepted());

        subscribe(body(UUID.randomUUID(), IPO_ID, ACCOUNT_ID, 100), USER_ID.toString(), "2026-11-03T00:59:59.999Z")
                .andExpect(status().isUnprocessableEntity());
        subscribe(body(UUID.randomUUID(), IPO_ID, ACCOUNT_ID, 100), USER_ID.toString(), END_UTC)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("SUBSCRIPTION_CLOSED"))
                .andExpect(jsonPath("$.error.message").value("청약 가능 시간이 아닙니다."))
                .andExpect(jsonPath("$.error.details[0].field").value("ipoId"))
                .andExpect(jsonPath("$.error.details[0].reason").value("청약 시작 전이거나 마감 시각이 지났습니다."));

        verify(kafkaTemplate, times(2)).send(anyString(), anyString(), anyString());
    }

    @Test
    void 청약_수량은_최소_청약_수량부터_청약_한도까지_받는다() throws Exception {
        subscribe(body(UUID.randomUUID(), IPO_ID, ACCOUNT_ID, 10), USER_ID.toString(), DURING_UTC)
                .andExpect(status().isAccepted());
        subscribe(body(UUID.randomUUID(), IPO_ID, ACCOUNT_ID, 10000), USER_ID.toString(), DURING_UTC)
                .andExpect(status().isAccepted());

        for (long quantity : new long[]{9, 10001, 0, -1}) {
            subscribe(body(UUID.randomUUID(), IPO_ID, ACCOUNT_ID, quantity), USER_ID.toString(), DURING_UTC)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.error.details[0].field").value("requestedQuantity"))
                    .andExpect(jsonPath("$.error.details[0].reason").value("청약 수량은 10 이상 10000 이하여야 합니다."));
        }
        verify(kafkaTemplate, times(2)).send(anyString(), anyString(), anyString());
    }

    @Test
    void 청약_수량이_소수면_잘라서_받지_않고_400이다() throws Exception {
        String body = """
                {"requestId":"%s","ipoId":"%s","accountId":"%s","requestedQuantity":100.5}"""
                .formatted(REQUEST_ID, IPO_ID, ACCOUNT_ID);

        subscribe(body, USER_ID.toString(), DURING_UTC)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void 필수값이_없으면_400으로_없는_필드를_모두_알려준다() throws Exception {
        subscribe("{}", USER_ID.toString(), DURING_UTC)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.details[*].field")
                        .value(containsInAnyOrder("requestId", "ipoId", "accountId", "requestedQuantity")));

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void UUID_형식이_아니면_400으로_해당_필드를_알려준다() throws Exception {
        subscribe(body("abc", IPO_ID.toString(), ACCOUNT_ID.toString(), 100), USER_ID.toString(), DURING_UTC)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.details[0].field").value("requestId"))
                .andExpect(jsonPath("$.error.details[0].reason").value("UUID 형식이 아닙니다."));
    }

    @Test
    void 요청_본문_오류가_헤더_오류보다_먼저다() throws Exception {
        subscribe("{}", null, null)
                .andExpect(status().isBadRequest());
    }

    @Test
    void 회원_ID_헤더가_없거나_UUID가_아니면_401이다() throws Exception {
        for (String header : new String[]{null, "", "abc"}) {
            subscribe(body(REQUEST_ID, IPO_ID, ACCOUNT_ID, 100), header, DURING_UTC)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        }
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void 도착_시각_헤더가_없거나_형식이_틀리면_401이다() throws Exception {
        for (String header : new String[]{null, "", "2026-11-03 05:30:15", "2026-11-03T14:30:15"}) {
            subscribe(body(REQUEST_ID, IPO_ID, ACCOUNT_ID, 100), USER_ID.toString(), header)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        }
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void 없는_공모주는_404다() throws Exception {
        subscribe(body(REQUEST_ID, UUID.randomUUID(), ACCOUNT_ID, 100), USER_ID.toString(), DURING_UTC)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("공모주를 찾을 수 없습니다."))
                .andExpect(jsonPath("$.error.details[0].field").value("ipoId"));
    }

    @Test
    void OPEN이_아닌_공모주는_청약_기간_안이어도_409다() throws Exception {
        UUID closedIpo = UUID.randomUUID();
        insertIpo(closedIpo, "CLOSED");

        subscribe(body(REQUEST_ID, closedIpo, ACCOUNT_ID, 100), USER_ID.toString(), DURING_UTC)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.error.details[0].field").value("ipoId"));

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void 브로커_저장을_확인하지_못하면_500이다() throws Exception {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("broker down")));

        subscribe(body(REQUEST_ID, IPO_ID, ACCOUNT_ID, 100), USER_ID.toString(), DURING_UTC)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"));
    }

    @Test
    void 전송_요청_자체가_실패해도_500이다() throws Exception {
        // 메타데이터를 못 받아 max.block.ms가 지나면 send가 바로 예외를 던진다
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenThrow(new KafkaException("metadata timeout"));

        subscribe(body(REQUEST_ID, IPO_ID, ACCOUNT_ID, 100), USER_ID.toString(), DURING_UTC)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"));
    }

    private ResultActions subscribe(String body, String userId, String receivedAt) throws Exception {
        MockHttpServletRequestBuilder request = post(SUBSCRIPTIONS).contentType(MediaType.APPLICATION_JSON).content(body);
        if (userId != null) {
            request.header("X-User-Id", userId);
        }
        if (receivedAt != null) {
            request.header("X-Request-Received-At", receivedAt);
        }
        return mockMvc.perform(request);
    }

    private JsonNode sentMessage() throws Exception {
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(anyString(), anyString(), payload.capture());
        return objectMapper.readTree(payload.getValue());
    }

    private static String body(UUID requestId, UUID ipoId, UUID accountId, long quantity) {
        return body(requestId.toString(), ipoId.toString(), accountId.toString(), quantity);
    }

    private static String body(String requestId, String ipoId, String accountId, long quantity) {
        return """
                {"requestId":"%s","ipoId":"%s","accountId":"%s","requestedQuantity":%d}"""
                .formatted(requestId, ipoId, accountId, quantity);
    }

    // 값은 API 명세 예시(테스트 공모주 A)와 같다. 최소 청약 수량 10, 청약 한도 10000
    private void insertIpo(UUID ipoId, String status) {
        jdbcTemplate.update(
                "INSERT INTO subscription.ipo (ipo_id, name, offer_price, retail_quantity, equal_quantity, min_quantity, max_quantity,"
                        + " margin_rate, subscription_start_at, subscription_end_at, refund_date, status, version, created_at, created_by)"
                        + " VALUES (?, '테스트 공모주 A', 15000, 100000, 50000, 10, 10000, 1.00, ?, ?, DATE '2026-11-06', ?, 0, ?, 'SYSTEM:test')",
                ipoId, OffsetDateTime.parse("2026-11-03T10:00:00+09:00"), OffsetDateTime.parse("2026-11-04T16:00:00+09:00"),
                status, OffsetDateTime.parse("2026-10-28T10:00:00+09:00"));
    }
}
