package com.gongmoju.subscription.infrastructure.scheduling;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gongmoju.subscription.application.subscription.SubscriptionVoidRetryService;
import com.gongmoju.subscription.application.subscription.VoidRetryResult;
import com.gongmoju.subscription.application.subscription.VoidRetryResult.Outcome;
import com.gongmoju.subscription.domain.subscription.CancelReason;
import com.gongmoju.subscription.domain.subscription.SubscriptionRepository;
import com.gongmoju.subscription.domain.subscription.SubscriptionStatus;
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
 * 반환 재요청 스케줄러의 대상 조회 기준, 건별 처리, 실패 격리, 최대 횟수 도달 알림 간격을 검증함. 저장소와 건별 처리 서비스는 가짜 객체로 대신함.
 */
@DisplayName("반환 재요청 스케줄러")
class VoidRetrySchedulerTest {

    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration ALERT_INTERVAL = Duration.ofMinutes(5);
    private static final int MAX_ATTEMPTS = 3;
    private static final int BATCH_SIZE = 100;
    private static final Instant NOW = Instant.parse("2026-11-04T01:00:00Z");
    private static final Instant THRESHOLD = NOW.minus(RESPONSE_TIMEOUT);

    private SubscriptionRepository subscriptionRepository;
    private SubscriptionVoidRetryService voidRetryService;
    private VoidRetryScheduler scheduler;
    private Logger schedulerLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(SubscriptionRepository.class);
        voidRetryService = mock(SubscriptionVoidRetryService.class);
        when(subscriptionRepository.findStaleVoidIds(any(), anyInt(), anyInt())).thenReturn(List.of());
        when(subscriptionRepository.findExhaustedVoidIds(any(), anyInt(), anyInt())).thenReturn(List.of());
        scheduler = new VoidRetryScheduler(subscriptionRepository, voidRetryService,
                new PendingTimeoutProperties(RESPONSE_TIMEOUT, MAX_ATTEMPTS, BATCH_SIZE), ALERT_INTERVAL);

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
    @DisplayName("현재 시각에서 응답 시간을 뺀 기준, 최대 횟수, batch 크기로 대상을 조회하고, 대상이 없으면 처리하지 않는다")
    void retryStaleVoid_doesNothing_whenNoTargets() {
        // given, when
        scheduler.retryStaleVoid(NOW);

        // then
        verify(subscriptionRepository).findStaleVoidIds(THRESHOLD, MAX_ATTEMPTS, BATCH_SIZE);
        verifyNoInteractions(voidRetryService);
    }

    @Test
    @DisplayName("조회된 청약마다 기준 시각과 최대 횟수를 넘겨 건별로 처리한다")
    void retryStaleVoid_handlesEachTarget() {
        // given
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(subscriptionRepository.findStaleVoidIds(THRESHOLD, MAX_ATTEMPTS, BATCH_SIZE)).thenReturn(List.of(first, second));
        when(voidRetryService.handle(any(), any(), anyInt())).thenReturn(result(Outcome.RETRIED, 1));

        // when
        scheduler.retryStaleVoid(NOW);

        // then
        verify(voidRetryService).handle(first, THRESHOLD, MAX_ATTEMPTS);
        verify(voidRetryService).handle(second, THRESHOLD, MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("낙관적 락 충돌은 건너뛰고 나머지 청약은 계속 처리한다")
    void retryStaleVoid_skipsOptimisticLockConflictAndContinues() {
        // given
        UUID conflicted = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        when(subscriptionRepository.findStaleVoidIds(THRESHOLD, MAX_ATTEMPTS, BATCH_SIZE)).thenReturn(List.of(conflicted, next));
        when(voidRetryService.handle(eq(conflicted), any(), anyInt())).thenThrow(new OptimisticLockingFailureException("충돌"));
        when(voidRetryService.handle(eq(next), any(), anyInt())).thenReturn(result(Outcome.RETRIED, 1));

        // when
        scheduler.retryStaleVoid(NOW);

        // then
        verify(voidRetryService).handle(next, THRESHOLD, MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("한 건에서 오류가 나도 예외를 밖으로 던지지 않고 나머지 청약을 계속 처리한다")
    void retryStaleVoid_continuesAfterUnexpectedError() {
        // given
        UUID broken = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        when(subscriptionRepository.findStaleVoidIds(THRESHOLD, MAX_ATTEMPTS, BATCH_SIZE)).thenReturn(List.of(broken, next));
        when(voidRetryService.handle(eq(broken), any(), anyInt())).thenThrow(new IllegalStateException("일시 오류"));
        when(voidRetryService.handle(eq(next), any(), anyInt())).thenReturn(result(Outcome.RETRIED, 1));

        // when
        scheduler.retryStaleVoid(NOW);

        // then
        verify(voidRetryService).handle(broken, THRESHOLD, MAX_ATTEMPTS);
        verify(voidRetryService).handle(next, THRESHOLD, MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("최대 횟수에 이른 청약이 있으면 청약 정보(ID, 상태, 해제 사유, 요청 횟수)와 함께 ERROR 로그를 남긴다")
    void retryStaleVoid_logsErrorForExhausted() {
        // given
        UUID exhausted = UUID.randomUUID();
        when(subscriptionRepository.findExhaustedVoidIds(THRESHOLD, MAX_ATTEMPTS, BATCH_SIZE)).thenReturn(List.of(exhausted));
        when(voidRetryService.handle(exhausted, THRESHOLD, MAX_ATTEMPTS)).thenReturn(result(Outcome.MAX_ATTEMPTS_REACHED, MAX_ATTEMPTS));

        // when
        scheduler.retryStaleVoid(NOW);

        // then
        List<ILoggingEvent> errors = errorLogs();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getFormattedMessage())
                .contains(exhausted.toString(), "COMPENSATING", "TIMEOUT", "attempts=" + MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("알림 대상이 처리 직전에 상태가 바뀌어(예: 응답이 와서 CANCELLED) 최대 횟수 도달이 아니면 ERROR 로그를 남기지 않는다")
    void retryStaleVoid_doesNotLogError_whenExhaustedNoLongerReached() {
        // given
        UUID changed = UUID.randomUUID();
        when(subscriptionRepository.findExhaustedVoidIds(THRESHOLD, MAX_ATTEMPTS, BATCH_SIZE)).thenReturn(List.of(changed));
        when(voidRetryService.handle(changed, THRESHOLD, MAX_ATTEMPTS)).thenReturn(result(Outcome.NOT_VOIDING_IGNORED, 0));

        // when
        scheduler.retryStaleVoid(NOW);

        // then
        assertThat(errorLogs()).isEmpty();
    }

    @Test
    @DisplayName("알림 간격 안에서는 최대 횟수에 이른 청약을 다시 조회하지 않고, 간격이 지나면 다시 ERROR 로그를 남긴다")
    void retryStaleVoid_throttlesAlertByInterval() {
        // given
        UUID exhausted = UUID.randomUUID();
        when(subscriptionRepository.findExhaustedVoidIds(any(), anyInt(), anyInt())).thenReturn(List.of(exhausted));
        when(voidRetryService.handle(eq(exhausted), any(), anyInt())).thenReturn(result(Outcome.MAX_ATTEMPTS_REACHED, MAX_ATTEMPTS));

        // when
        scheduler.retryStaleVoid(NOW);
        scheduler.retryStaleVoid(NOW.plus(ALERT_INTERVAL).minusSeconds(1));
        scheduler.retryStaleVoid(NOW.plus(ALERT_INTERVAL));

        // then
        verify(subscriptionRepository, times(2)).findExhaustedVoidIds(any(), anyInt(), anyInt());
        assertThat(errorLogs()).hasSize(2);
    }

    @Test
    @DisplayName("최대 횟수에 이른 청약이 없으면 알림 시각을 기록하지 않아 다음 주기에 바로 다시 확인한다")
    void retryStaleVoid_checksAgainNextCycle_whenNoExhausted() {
        // given, when
        scheduler.retryStaleVoid(NOW);
        scheduler.retryStaleVoid(NOW.plusSeconds(5));

        // then
        verify(subscriptionRepository, times(2)).findExhaustedVoidIds(any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("최대 횟수 도달 알림 조회가 실패해도 예외를 밖으로 던지지 않고, 재요청 처리는 이미 끝나 있다")
    void retryStaleVoid_doesNotThrow_whenAlertLookupFails() {
        // given
        UUID target = UUID.randomUUID();
        when(subscriptionRepository.findStaleVoidIds(THRESHOLD, MAX_ATTEMPTS, BATCH_SIZE)).thenReturn(List.of(target));
        when(voidRetryService.handle(target, THRESHOLD, MAX_ATTEMPTS)).thenReturn(result(Outcome.RETRIED, 1));
        when(subscriptionRepository.findExhaustedVoidIds(any(), anyInt(), anyInt())).thenThrow(new IllegalStateException("DB 오류"));

        // when
        scheduler.retryStaleVoid(NOW);

        // then
        verify(voidRetryService).handle(target, THRESHOLD, MAX_ATTEMPTS);
        verify(voidRetryService, times(1)).handle(any(), any(), anyInt());
    }

    private VoidRetryResult result(Outcome outcome, int attempts) {
        return new VoidRetryResult(outcome, SubscriptionStatus.COMPENSATING, CancelReason.TIMEOUT, attempts, THRESHOLD.minusSeconds(10));
    }

    private List<ILoggingEvent> errorLogs() {
        return logAppender.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
    }
}
