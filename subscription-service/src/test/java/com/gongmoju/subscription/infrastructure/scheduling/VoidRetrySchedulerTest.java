package com.gongmoju.subscription.infrastructure.scheduling;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gongmoju.subscription.application.subscription.SubscriptionVoidRetryService;
import com.gongmoju.subscription.application.subscription.VoidRetryResult;
import com.gongmoju.subscription.application.subscription.VoidRetryResult.Outcome;
import com.gongmoju.subscription.domain.subscription.CancelReason;
import com.gongmoju.subscription.domain.subscription.Subscription;
import com.gongmoju.subscription.domain.subscription.SubscriptionRepository;
import com.gongmoju.subscription.domain.subscription.SubscriptionStatus;
import com.gongmoju.subscription.domain.subscription.VoidRetryBackoff;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 반환 재요청 스케줄러의 대상 조회 기준, 건별 처리, 실패 격리, 반환 지연 알림(읽기 전용 조회, 알림 간격)을 검증함. 저장소와 건별 처리 서비스는 가짜 객체로 대신함.
 */
@DisplayName("반환 재요청 스케줄러")
class VoidRetrySchedulerTest {

    private static final Duration INITIAL_INTERVAL = Duration.ofSeconds(30);
    private static final Duration MAX_INTERVAL = Duration.ofMinutes(5);
    private static final Duration ALERT_INTERVAL = Duration.ofMinutes(5);
    private static final int ALERT_ATTEMPTS = 5;
    private static final int BATCH_SIZE = 100;
    private static final VoidRetryBackoff BACKOFF = new VoidRetryBackoff(INITIAL_INTERVAL, MAX_INTERVAL);
    private static final Instant NOW = Instant.parse("2026-11-04T01:00:00Z");

    private SubscriptionRepository subscriptionRepository;
    private SubscriptionVoidRetryService voidRetryService;
    private VoidRetryScheduler scheduler;
    private Logger schedulerLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(SubscriptionRepository.class);
        voidRetryService = mock(SubscriptionVoidRetryService.class);
        when(subscriptionRepository.findStaleVoidIds(any(), any(), anyInt())).thenReturn(List.of());
        when(subscriptionRepository.findLongVoiding(any(), anyInt(), any(), anyInt())).thenReturn(List.of());
        scheduler = new VoidRetryScheduler(subscriptionRepository, voidRetryService,
                new VoidRetryProperties(INITIAL_INTERVAL, MAX_INTERVAL, ALERT_ATTEMPTS, BATCH_SIZE), ALERT_INTERVAL);

        schedulerLogger = (Logger) LoggerFactory.getLogger(VoidRetryScheduler.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        schedulerLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        schedulerLogger.detachAppender(logAppender);
    }

    @Test
    @DisplayName("현재 시각, 대기 시간 계산(30초 시작, 5분 상한), batch 크기로 재요청 대상을 조회하고, 대상이 없으면 처리하지 않는다")
    void retryStaleVoid_doesNothing_whenNoTargets() {
        // given, when
        scheduler.retryStaleVoid(NOW);

        // then
        verify(subscriptionRepository).findStaleVoidIds(NOW, BACKOFF, BATCH_SIZE);
        verifyNoInteractions(voidRetryService);
    }

    @Test
    @DisplayName("조회된 청약마다 기준 시각과 대기 시간 계산을 넘겨 건별로 처리한다")
    void retryStaleVoid_handlesEachTarget() {
        // given
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(subscriptionRepository.findStaleVoidIds(NOW, BACKOFF, BATCH_SIZE)).thenReturn(List.of(first, second));
        when(voidRetryService.handle(any(), any(), any())).thenReturn(result(Outcome.RETRIED, 1));

        // when
        scheduler.retryStaleVoid(NOW);

        // then
        verify(voidRetryService).handle(first, NOW, BACKOFF);
        verify(voidRetryService).handle(second, NOW, BACKOFF);
    }

    @Test
    @DisplayName("낙관적 락 충돌은 건너뛰고 나머지 청약은 계속 처리한다")
    void retryStaleVoid_skipsOptimisticLockConflictAndContinues() {
        // given
        UUID conflicted = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        when(subscriptionRepository.findStaleVoidIds(NOW, BACKOFF, BATCH_SIZE)).thenReturn(List.of(conflicted, next));
        when(voidRetryService.handle(eq(conflicted), any(), any())).thenThrow(new OptimisticLockingFailureException("충돌"));
        when(voidRetryService.handle(eq(next), any(), any())).thenReturn(result(Outcome.RETRIED, 1));

        // when
        scheduler.retryStaleVoid(NOW);

        // then
        verify(voidRetryService).handle(next, NOW, BACKOFF);
    }

    @Test
    @DisplayName("한 건에서 오류가 나도 예외를 밖으로 던지지 않고 나머지 청약을 계속 처리한다")
    void retryStaleVoid_continuesAfterUnexpectedError() {
        // given
        UUID broken = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        when(subscriptionRepository.findStaleVoidIds(NOW, BACKOFF, BATCH_SIZE)).thenReturn(List.of(broken, next));
        when(voidRetryService.handle(eq(broken), any(), any())).thenThrow(new IllegalStateException("일시 오류"));
        when(voidRetryService.handle(eq(next), any(), any())).thenReturn(result(Outcome.RETRIED, 1));

        // when
        scheduler.retryStaleVoid(NOW);

        // then
        verify(voidRetryService).handle(broken, NOW, BACKOFF);
        verify(voidRetryService).handle(next, NOW, BACKOFF);
    }

    @Test
    @DisplayName("요청 횟수가 알림 기준(5) 이상인 반환 지연 청약이 있으면 청약 정보(ID, 상태, 해제 사유, 요청 횟수)와 함께 ERROR 로그를 남기고, 재요청 서비스는 호출하지 않는다")
    void retryStaleVoid_logsErrorForLongVoiding_withoutCallingRetryService() {
        // given
        UUID id = UUID.randomUUID();
        Subscription longVoiding = subscription(id, ALERT_ATTEMPTS);
        when(subscriptionRepository.findLongVoiding(NOW, ALERT_ATTEMPTS, INITIAL_INTERVAL, BATCH_SIZE)).thenReturn(List.of(longVoiding));
        when(subscriptionRepository.countLongVoiding(NOW, ALERT_ATTEMPTS, INITIAL_INTERVAL)).thenReturn(1L);

        // when
        scheduler.retryStaleVoid(NOW);

        // then: 청약별 상세 로그 1줄과 요약 로그 1줄
        List<ILoggingEvent> errors = errorLogs();
        assertThat(errors).hasSize(2);
        assertThat(errors.get(0).getFormattedMessage())
                .contains(id.toString(), "COMPENSATING", "TIMEOUT", "attempts=" + ALERT_ATTEMPTS, "5번째 요청", "재요청은 계속");
        assertThat(errors.get(1).getFormattedMessage()).contains("총 1건", "상세 로그 1건", "빠진 0건");
        // 알림 확인은 읽기 전용 조회만 쓰고 청약을 바꾸는 재요청 서비스(handle)를 거치지 않음
        verifyNoInteractions(voidRetryService);
    }

    @Test
    @DisplayName("반환 지연 청약이 있어도 같은 주기의 재요청 대상 처리는 그대로 진행한다 (알림은 재요청을 막지 않는다)")
    void retryStaleVoid_stillRetries_whenLongVoidingExists() {
        // given
        UUID target = UUID.randomUUID();
        when(subscriptionRepository.findStaleVoidIds(NOW, BACKOFF, BATCH_SIZE)).thenReturn(List.of(target));
        when(voidRetryService.handle(target, NOW, BACKOFF)).thenReturn(result(Outcome.RETRIED, ALERT_ATTEMPTS));
        Subscription longVoiding = subscription(target, ALERT_ATTEMPTS);
        when(subscriptionRepository.findLongVoiding(NOW, ALERT_ATTEMPTS, INITIAL_INTERVAL, BATCH_SIZE))
                .thenReturn(List.of(longVoiding));
        when(subscriptionRepository.countLongVoiding(NOW, ALERT_ATTEMPTS, INITIAL_INTERVAL)).thenReturn(1L);

        // when
        scheduler.retryStaleVoid(NOW);

        // then
        verify(voidRetryService).handle(target, NOW, BACKOFF);
        assertThat(errorLogs()).hasSize(2);
    }

    @Test
    @DisplayName("반환 지연 청약이 상세 로그 건수보다 많으면 요약 로그에 총 건수와 상세에서 빠진 건수를 남겨 누락이 보이게 한다")
    void retryStaleVoid_summaryShowsOmittedCount_whenLongVoidingExceedsDetailLimit() {
        // given: 알림 대상이 모두 250건인데 상세로 조회되는 것은 앞쪽 2건
        Subscription first = subscription(UUID.randomUUID(), ALERT_ATTEMPTS);
        Subscription second = subscription(UUID.randomUUID(), ALERT_ATTEMPTS + 1);
        when(subscriptionRepository.findLongVoiding(NOW, ALERT_ATTEMPTS, INITIAL_INTERVAL, BATCH_SIZE))
                .thenReturn(List.of(first, second));
        when(subscriptionRepository.countLongVoiding(NOW, ALERT_ATTEMPTS, INITIAL_INTERVAL)).thenReturn(250L);

        // when
        scheduler.retryStaleVoid(NOW);

        // then
        List<ILoggingEvent> errors = errorLogs();
        assertThat(errors).hasSize(3);
        assertThat(errors.get(2).getFormattedMessage()).contains("총 250건", "상세 로그 2건", "빠진 248건");
    }

    @Test
    @DisplayName("총 건수 조회가 실패하면 예외를 던지지 않고 상세 로그를 남기며, 요약 로그에는 총 건수와 빠진 건수를 확인 불가로 표시하고 상세 건수만 기록한다")
    void retryStaleVoid_marksTotalUnknown_whenCountFails() {
        // given
        UUID id = UUID.randomUUID();
        Subscription longVoiding = subscription(id, ALERT_ATTEMPTS);
        when(subscriptionRepository.findLongVoiding(NOW, ALERT_ATTEMPTS, INITIAL_INTERVAL, BATCH_SIZE)).thenReturn(List.of(longVoiding));
        when(subscriptionRepository.countLongVoiding(any(), anyInt(), any())).thenThrow(new IllegalStateException("DB 오류"));

        // when
        scheduler.retryStaleVoid(NOW);

        // then
        List<ILoggingEvent> errors = errorLogs();
        assertThat(errors).hasSize(2);
        assertThat(errors.get(0).getFormattedMessage()).contains(id.toString());
        String summary = errors.get(1).getFormattedMessage();
        assertThat(summary).contains("확인 불가", "상세 로그 1건");
        // 조회에 실패했는데 총 건수나 빠진 건수를 확정해서 기록하면 안 됨 (실제 대상이 더 있어도 "빠진 0건"으로 보이기 때문)
        assertThat(summary).doesNotContain("총 1건", "빠진 0건");
    }

    @Test
    @DisplayName("알림 간격 안에서는 반환 지연 청약을 다시 조회하지 않고, 간격이 지나면 다시 ERROR 로그를 남긴다")
    void retryStaleVoid_throttlesAlertByInterval() {
        // given
        Subscription longVoiding = subscription(UUID.randomUUID(), ALERT_ATTEMPTS);
        when(subscriptionRepository.findLongVoiding(any(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(longVoiding));

        // when
        scheduler.retryStaleVoid(NOW);
        scheduler.retryStaleVoid(NOW.plus(ALERT_INTERVAL).minusSeconds(1));
        scheduler.retryStaleVoid(NOW.plus(ALERT_INTERVAL));

        // then
        verify(subscriptionRepository, times(2)).findLongVoiding(any(), anyInt(), any(), anyInt());
        assertThat(errorLogs()).hasSize(4);
    }

    @Test
    @DisplayName("반환 지연 청약이 없으면 알림 시각을 기록하지 않아 다음 주기에 바로 다시 확인한다")
    void retryStaleVoid_checksAgainNextCycle_whenNoLongVoiding() {
        // given, when
        scheduler.retryStaleVoid(NOW);
        scheduler.retryStaleVoid(NOW.plusSeconds(5));

        // then
        verify(subscriptionRepository, times(2)).findLongVoiding(any(), anyInt(), any(), anyInt());
        assertThat(errorLogs()).isEmpty();
    }

    @Test
    @DisplayName("반환 지연 알림 조회가 실패해도 예외를 밖으로 던지지 않고, 재요청 처리는 이미 끝나 있다")
    void retryStaleVoid_doesNotThrow_whenAlertLookupFails() {
        // given
        UUID target = UUID.randomUUID();
        when(subscriptionRepository.findStaleVoidIds(NOW, BACKOFF, BATCH_SIZE)).thenReturn(List.of(target));
        when(voidRetryService.handle(target, NOW, BACKOFF)).thenReturn(result(Outcome.RETRIED, 1));
        when(subscriptionRepository.findLongVoiding(any(), anyInt(), any(), anyInt())).thenThrow(new IllegalStateException("DB 오류"));

        // when
        scheduler.retryStaleVoid(NOW);

        // then
        verify(voidRetryService).handle(target, NOW, BACKOFF);
        verify(voidRetryService, times(1)).handle(any(), any(), any());
    }

    private Subscription subscription(UUID id, int attempts) {
        Subscription subscription = mock(Subscription.class);
        when(subscription.getId()).thenReturn(id);
        when(subscription.getStatus()).thenReturn(SubscriptionStatus.COMPENSATING);
        when(subscription.getCancelReason()).thenReturn(CancelReason.TIMEOUT);
        when(subscription.getAttempts()).thenReturn(attempts);
        when(subscription.getLastRequestedAt()).thenReturn(NOW.minusSeconds(60));
        return subscription;
    }

    private VoidRetryResult result(Outcome outcome, int attempts) {
        return new VoidRetryResult(outcome, SubscriptionStatus.COMPENSATING, CancelReason.TIMEOUT, attempts, NOW.minusSeconds(60));
    }

    private List<ILoggingEvent> errorLogs() {
        return logAppender.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
    }
}
