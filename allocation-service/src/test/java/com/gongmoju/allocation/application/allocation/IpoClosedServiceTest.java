package com.gongmoju.allocation.application.allocation;

import com.gongmoju.allocation.domain.allocation.AllocationEventPublisher;
import com.gongmoju.allocation.domain.allocation.ConfirmedSubscription;
import com.gongmoju.allocation.domain.allocation.ConfirmedSubscriptionReader;
import com.gongmoju.allocation.domain.allocation.IpoClosed;
import com.gongmoju.common.event.ProcessedEventRepository;
import com.gongmoju.common.feign.FeignServerException;
import feign.Request;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// ipo.closed 처리: 회차 시작, 확정 청약 목록 조회, 배정 실행, 실패 기록
// 처리 기록의 ON CONFLICT (event_id, consumer_name)를 H2가 지원하지 않아 실제 PostgreSQL(Testcontainers)로 확인한다. Docker 필요
// 확정 청약 목록은 청약 서비스를 띄우지 않고 가짜 Reader로 넘긴다
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = "eureka.client.enabled=false")
class IpoClosedServiceTest {

    private static final UUID IPO_ID = UUID.fromString("8b2d0000-0000-4000-8000-000000000002");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withUrlParam("currentSchema", "allocation");

    @Autowired
    private IpoClosedService ipoClosedService;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private AllocationRoundService allocationRoundService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ConfirmedSubscriptionReader confirmedSubscriptionReader;

    @MockitoSpyBean
    private AllocationEventPublisher allocationEventPublisher;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM allocation.processed_event");
        jdbcTemplate.update("DELETE FROM allocation.outbox");
        jdbcTemplate.update("DELETE FROM allocation.allocation");
        jdbcTemplate.update("DELETE FROM allocation.allocation_round");
    }

    // 회차를 만들고 배정을 실행해 DONE으로 바꾼다. 받은 조건과 새 시드를 회차에 저장하고 처리 기록을 남긴다
    @Test
    void allocatesAndFinishesRound() {
        when(confirmedSubscriptionReader.readAll(IPO_ID)).thenReturn(subscriptions(3));
        IpoClosed event = event(3);

        IpoClosedResult result = ipoClosedService.handle(event);

        assertThat(result).isEqualTo(IpoClosedResult.ALLOCATED);
        Map<String, Object> round = round();
        assertThat(round.get("STATUS")).isEqualTo("DONE");
        assertThat(longValue(round, "OFFER_PRICE")).isEqualTo(10000);
        assertThat(longValue(round, "RETAIL_QUANTITY")).isEqualTo(100);
        assertThat(longValue(round, "EQUAL_QUANTITY")).isEqualTo(50);
        assertThat(longValue(round, "MIN_QUANTITY")).isEqualTo(10);
        assertThat(round.get("LOTTERY_SEED")).isNotNull();
        assertThat(round.get("STARTED_AT")).isNotNull();
        assertThat(round.get("FINISHED_AT")).isNotNull();
        assertThat(count("allocation.allocation")).isEqualTo(3);
        assertThat(outboxCount("allocation.result")).isEqualTo(3);
        assertThat(outboxCount("allocation.finished")).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("SELECT * FROM allocation.processed_event")).singleElement().satisfies(row -> {
            assertThat(row.get("EVENT_ID")).isEqualTo(event.eventId());
            assertThat(row.get("CONSUMER_NAME")).isEqualTo("allocation-ipo-closed");
            assertThat(row.get("TOPIC")).isEqualTo("ipo.closed");
        });
    }

    // 배정이 끝나면 확정 청약 건수와 단계별 소요 시간(조회, 계산, 저장 기록, 커밋 포함 배정, 전체)을 로그로 남긴다
    // 이미 끝난 공모주를 다시 받으면 배정하지 않으므로 이 로그를 다시 남기지 않는다
    @Test
    void logsCountAndElapsedTimesWhenAllocated(CapturedOutput output) {
        when(confirmedSubscriptionReader.readAll(IPO_ID)).thenReturn(subscriptions(3));
        IpoClosed event = event(3);

        ipoClosedService.handle(event);

        Object roundId = round().get("ALLOCATION_ROUND_ID");
        assertThat(output.getOut()).containsPattern("배정 계산, 저장 기록 완료 \\(커밋 전\\)\\. allocationRoundId=" + roundId
                + ", ipoId=" + IPO_ID + ", subscriptionCount=3, calculateMillis=\\d+, saveMillis=\\d+");
        assertThat(output.getOut()).containsPattern("배정 완료\\. ipoId=" + IPO_ID + ", allocationRoundId=" + roundId
                + ", confirmedCount=3, readMillis=\\d+, allocateMillis=\\d+, totalMillis=\\d+");

        ipoClosedService.handle(event);

        assertThat(output.getOut().split("배정 완료\\. ", -1)).hasSize(2);
    }

    // 확정 청약이 0건이어도 회차를 DONE으로 바꾸고 allocation.finished만 기록한다
    @Test
    void finishesRoundWithoutConfirmedSubscriptions() {
        when(confirmedSubscriptionReader.readAll(IPO_ID)).thenReturn(List.of());

        assertThat(ipoClosedService.handle(event(0))).isEqualTo(IpoClosedResult.ALLOCATED);

        assertThat(round().get("STATUS")).isEqualTo("DONE");
        assertThat(outboxCount("allocation.result")).isZero();
        assertThat(outboxCount("allocation.finished")).isEqualTo(1);
    }

    // 같은 메시지를 다시 받거나 eventId가 다른 ipo.closed가 다시 와도 배정은 한 번만 한다 (목록도 다시 조회하지 않음)
    @Test
    void ignoresIpoClosedAfterRoundIsDone() {
        when(confirmedSubscriptionReader.readAll(IPO_ID)).thenReturn(subscriptions(3));
        IpoClosed event = event(3);
        ipoClosedService.handle(event);

        assertThat(ipoClosedService.handle(event)).isEqualTo(IpoClosedResult.ALREADY_DONE_IGNORED);
        assertThat(ipoClosedService.handle(event(3))).isEqualTo(IpoClosedResult.ALREADY_DONE_IGNORED);

        verify(confirmedSubscriptionReader).readAll(IPO_ID);
        assertThat(count("allocation.allocation_round")).isEqualTo(1);
        assertThat(count("allocation.allocation")).isEqualTo(3);
        assertThat(outboxCount("allocation.finished")).isEqualTo(1);
        assertThat(count("allocation.processed_event")).isEqualTo(1);
    }

    // 같은 eventId를 이미 처리했으면 배정하지 않는다 (처리 기록은 DONE과 같은 트랜잭션이라 보통은 회차 확인에서 먼저 걸러진다)
    @Test
    void ignoresDuplicateEventId() {
        IpoClosed event = event(1);
        UUID roundId = allocationRoundService.start(event).orElseThrow();
        jdbcTemplate.update("INSERT INTO allocation.processed_event (processed_event_id, event_id, consumer_name, topic, processed_at)"
                + " VALUES (?, ?, 'allocation-ipo-closed', 'ipo.closed', CURRENT_TIMESTAMP)", UUID.randomUUID(), event.eventId());

        IpoClosedResult result = allocationRoundService.complete(roundId, subscriptions(1), event);

        assertThat(result).isEqualTo(IpoClosedResult.DUPLICATE_EVENT_IGNORED);
        assertThat(count("allocation.allocation")).isZero();
        assertThat(count("allocation.outbox")).isZero();
        assertThat(count("allocation.processed_event")).isEqualTo(1);
    }

    // 같은 (eventId, consumerName)은 예외 없이 false를 반환하고 같은 트랜잭션을 계속 쓸 수 있다. Consumer 이름이 다르면 따로 저장한다
    @Test
    void insertsProcessedEventOnlyOnce() {
        UUID eventId = UUID.randomUUID();

        transactionTemplate.executeWithoutResult(status -> {
            assertThat(processedEventRepository.insertIfAbsent(eventId, "allocation-ipo-closed", "ipo.closed")).isTrue();
            assertThat(processedEventRepository.insertIfAbsent(eventId, "allocation-ipo-closed", "ipo.closed")).isFalse();
            assertThat(processedEventRepository.insertIfAbsent(eventId, "other-consumer", "ipo.closed")).isTrue();
        });

        assertThat(count("allocation.processed_event")).isEqualTo(2);
    }

    // 목록 조회가 실패하면(5xx, Circuit Breaker 열림, 타임아웃) 회차를 FAILED로 기록하고 예외를 그대로 던진다. 배정 결과, Outbox, 처리 기록은 남지 않는다
    @ParameterizedTest(name = "{0}")
    @MethodSource("readFailures")
    void marksRoundFailedWhenReadingFails(String situation, RuntimeException failure) {
        when(confirmedSubscriptionReader.readAll(IPO_ID)).thenThrow(failure);

        assertThatThrownBy(() -> ipoClosedService.handle(event(3))).isSameAs(failure);

        Map<String, Object> round = round();
        assertThat(round.get("STATUS")).isEqualTo("FAILED");
        assertThat(round.get("FINISHED_AT")).isNotNull();
        assertNothingRecorded();
    }

    // 청약 서비스 호출이 실제로 실패할 때 나는 예외 (common Feign 설정, Resilience4j)
    static Stream<Arguments> readFailures() {
        Request request = Request.create(Request.HttpMethod.GET, "http://subscription-service/internal/ipos", Map.of(), null,
                StandardCharsets.UTF_8, null);
        return Stream.of(
                Arguments.of("청약 서비스 5xx",
                        new FeignServerException(503, "SERVICE_UNAVAILABLE", "청약 서비스 오류", "getConfirmedSubscriptions")),
                Arguments.of("Circuit Breaker 열림",
                        CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("subscription-service"))),
                Arguments.of("타임아웃",
                        new RetryableException(-1, "Read timed out", Request.HttpMethod.GET,
                                new SocketTimeoutException("Read timed out"), (Long) null, request)));
    }

    // 조회한 건수가 confirmedCount와 다르면 배정하지 않고 회차를 FAILED로 기록한다
    @Test
    void marksRoundFailedWhenCountDiffers() {
        when(confirmedSubscriptionReader.readAll(IPO_ID)).thenReturn(subscriptions(2));

        assertThatThrownBy(() -> ipoClosedService.handle(event(3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("confirmedCount=3")
                .hasMessageContaining("readCount=2");

        assertThat(round().get("STATUS")).isEqualTo("FAILED");
        assertNothingRecorded();
        verify(allocationEventPublisher, never()).publishFinished(any(), anyLong(), anyLong());
    }

    // 배정 실행 중 실패하면 배정 결과, Outbox와 함께 처리 기록도 롤백되고 회차는 FAILED가 된다
    @Test
    void rollsBackProcessedEventWhenAllocationFails() {
        when(confirmedSubscriptionReader.readAll(IPO_ID)).thenReturn(subscriptions(3));
        doThrow(new IllegalStateException("기록 실패"))
                .when(allocationEventPublisher).publishFinished(any(), anyLong(), anyLong());

        assertThatThrownBy(() -> ipoClosedService.handle(event(3))).hasMessage("기록 실패");

        assertThat(round().get("STATUS")).isEqualTo("FAILED");
        assertNothingRecorded();
    }

    // FAILED 회차는 같은 이벤트를 다시 처리할 때(Consumer 재시도) 처음 정한 시드로 다시 배정해 DONE이 된다
    @Test
    void retriesFailedRoundWithSameSeed() {
        IpoClosed event = event(3);
        when(confirmedSubscriptionReader.readAll(IPO_ID))
                .thenThrow(new IllegalStateException("청약 서비스 응답 없음"))
                .thenReturn(subscriptions(3));
        assertThatThrownBy(() -> ipoClosedService.handle(event)).hasMessage("청약 서비스 응답 없음");
        Object seed = round().get("LOTTERY_SEED");
        Object startedAt = round().get("STARTED_AT");

        assertThat(ipoClosedService.handle(event)).isEqualTo(IpoClosedResult.ALLOCATED);

        Map<String, Object> round = round();
        assertThat(round.get("STATUS")).isEqualTo("DONE");
        assertThat(round.get("LOTTERY_SEED")).isEqualTo(seed);
        assertThat(round.get("STARTED_AT")).isEqualTo(startedAt);
        assertThat(count("allocation.allocation_round")).isEqualTo(1);
        assertThat(count("allocation.allocation")).isEqualTo(3);
        assertThat(outboxCount("allocation.finished")).isEqualTo(1);
    }

    // 다시 받은 ipo.closed의 조건이 달라도 처음 저장한 조건으로 배정한다 (결과 재현)
    @Test
    void keepsStoredConditionsOnRetry() {
        when(confirmedSubscriptionReader.readAll(IPO_ID))
                .thenThrow(new IllegalStateException("청약 서비스 응답 없음"))
                .thenReturn(subscriptions(3));
        assertThatThrownBy(() -> ipoClosedService.handle(event(3))).isInstanceOf(IllegalStateException.class);

        IpoClosed changed = new IpoClosed(UUID.randomUUID(), IPO_ID, Instant.parse("2026-11-04T07:00:00Z"),
                3, 20000, 200, 100, 20);
        assertThat(ipoClosedService.handle(changed)).isEqualTo(IpoClosedResult.ALLOCATED);

        Map<String, Object> round = round();
        assertThat(longValue(round, "OFFER_PRICE")).isEqualTo(10000);
        assertThat(longValue(round, "RETAIL_QUANTITY")).isEqualTo(100);
        assertThat(jdbcTemplate.queryForObject("SELECT SUM(allocated_quantity) FROM allocation.allocation", Long.class))
                .isEqualTo(100);
    }

    private void assertNothingRecorded() {
        assertThat(count("allocation.allocation")).isZero();
        assertThat(count("allocation.outbox")).isZero();
        assertThat(count("allocation.processed_event")).isZero();
    }

    private IpoClosed event(long confirmedCount) {
        return new IpoClosed(UUID.randomUUID(), IPO_ID, Instant.parse("2026-11-04T07:00:00Z"),
                confirmedCount, 10000, 100, 50, 10);
    }

    private List<ConfirmedSubscription> subscriptions(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new ConfirmedSubscription(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 100L * (i + 1)))
                .toList();
    }

    private Map<String, Object> round() {
        return jdbcTemplate.queryForMap("SELECT * FROM allocation.allocation_round WHERE ipo_id = ?", IPO_ID);
    }

    private long count(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private long outboxCount(String topic) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM allocation.outbox WHERE topic = ?", Long.class, topic);
    }

    private long longValue(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }
}
