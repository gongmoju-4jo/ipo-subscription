package com.gongmoju.subscription.infrastructure.scheduling;

import com.gongmoju.subscription.application.subscription.ReserveTimeoutResult;
import com.gongmoju.subscription.application.subscription.SubscriptionReserveTimeoutService;
import com.gongmoju.subscription.domain.subscription.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 응답 시간 초과 스케줄러의 대상 조회 기준, 건별 처리, 실패 격리를 검증함. 저장소와 건별 처리 서비스는 가짜 객체로 대신함.
 */
@DisplayName("PENDING 응답 시간 초과 스케줄러")
class PendingTimeoutSchedulerTest {

    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_ATTEMPTS = 3;
    private static final int BATCH_SIZE = 100;
    private static final Instant NOW = Instant.parse("2026-11-04T01:00:00Z");
    private static final Instant THRESHOLD = NOW.minus(RESPONSE_TIMEOUT);

    private SubscriptionRepository subscriptionRepository;
    private SubscriptionReserveTimeoutService timeoutService;
    private PendingTimeoutScheduler scheduler;

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(SubscriptionRepository.class);
        timeoutService = mock(SubscriptionReserveTimeoutService.class);
        scheduler = new PendingTimeoutScheduler(subscriptionRepository, timeoutService,
                new PendingTimeoutProperties(RESPONSE_TIMEOUT, MAX_ATTEMPTS, BATCH_SIZE));
    }

    @Test
    @DisplayName("현재 시각에서 응답 시간을 뺀 기준과 batch 크기로 대상을 조회하고, 대상이 없으면 처리하지 않는다")
    void handleTimedOutPending_doesNothing_whenNoTargets() {
        // given
        when(subscriptionRepository.findStalePendingIds(THRESHOLD, BATCH_SIZE)).thenReturn(List.of());

        // when
        scheduler.handleTimedOutPending(NOW);

        // then
        verify(subscriptionRepository).findStalePendingIds(THRESHOLD, BATCH_SIZE);
        verifyNoInteractions(timeoutService);
    }

    @Test
    @DisplayName("조회된 청약마다 기준 시각과 최대 횟수를 넘겨 건별로 처리한다")
    void handleTimedOutPending_handlesEachTarget() {
        // given
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(subscriptionRepository.findStalePendingIds(THRESHOLD, BATCH_SIZE)).thenReturn(List.of(first, second));
        when(timeoutService.handle(any(), any(), anyInt())).thenReturn(ReserveTimeoutResult.RETRIED);

        // when
        scheduler.handleTimedOutPending(NOW);

        // then
        verify(timeoutService).handle(first, THRESHOLD, MAX_ATTEMPTS);
        verify(timeoutService).handle(second, THRESHOLD, MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("낙관적 락 충돌은 건너뛰고 나머지 청약은 계속 처리한다")
    void handleTimedOutPending_skipsOptimisticLockConflictAndContinues() {
        // given
        UUID conflicted = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        when(subscriptionRepository.findStalePendingIds(THRESHOLD, BATCH_SIZE)).thenReturn(List.of(conflicted, next));
        when(timeoutService.handle(eq(conflicted), any(), anyInt())).thenThrow(new OptimisticLockingFailureException("충돌"));
        when(timeoutService.handle(eq(next), any(), anyInt())).thenReturn(ReserveTimeoutResult.TIMEOUT_COMPENSATION_STARTED);

        // when
        scheduler.handleTimedOutPending(NOW);

        // then
        verify(timeoutService).handle(next, THRESHOLD, MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("한 건에서 오류가 나도 예외를 밖으로 던지지 않고 나머지 청약을 계속 처리한다")
    void handleTimedOutPending_continuesAfterUnexpectedError() {
        // given
        UUID broken = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        when(subscriptionRepository.findStalePendingIds(THRESHOLD, BATCH_SIZE)).thenReturn(List.of(broken, next));
        when(timeoutService.handle(eq(broken), any(), anyInt())).thenThrow(new IllegalStateException("일시 오류"));
        when(timeoutService.handle(eq(next), any(), anyInt())).thenReturn(ReserveTimeoutResult.RETRIED);

        // when
        scheduler.handleTimedOutPending(NOW);

        // then
        verify(timeoutService).handle(broken, THRESHOLD, MAX_ATTEMPTS);
        verify(timeoutService).handle(next, THRESHOLD, MAX_ATTEMPTS);
    }

    @Test
    @DisplayName("설정 값이 잘못되면(응답 시간 0, 최대 횟수 0, batch 0) 설정을 만들 때 예외가 난다")
    void properties_throwsWhenInvalid() {
        assertThatThrownBy(() -> new PendingTimeoutProperties(Duration.ZERO, 3, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PendingTimeoutProperties(RESPONSE_TIMEOUT, 0, 100)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PendingTimeoutProperties(RESPONSE_TIMEOUT, 3, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new PendingTimeoutProperties(RESPONSE_TIMEOUT, 3, 100).maxAttempts()).isEqualTo(3);
    }
}
