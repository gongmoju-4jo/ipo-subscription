package com.gongmoju.subscription.infrastructure.scheduling;

import com.gongmoju.subscription.application.ipo.IpoCloseResult;
import com.gongmoju.subscription.application.ipo.IpoCloseService;
import com.gongmoju.subscription.domain.ipo.Ipo;
import com.gongmoju.subscription.domain.ipo.IpoRepository;
import com.gongmoju.subscription.domain.ipo.IpoStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 마감 스케줄러의 대상 조회, 마감 처리 호출, 알림 로그 간격을 검증함. 저장소와 마감 처리는 가짜 객체로 대신함.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("공모주 마감 스케줄러")
class IpoCloseSchedulerTest {

    private static final Duration GRACE_PERIOD = Duration.ofSeconds(20);
    private static final Duration ALERT_AFTER = Duration.ofMinutes(5);
    private static final Instant END_AT = Instant.parse("2026-10-27T07:00:00Z");
    // 마감 조건을 확인하기 시작하는 시각 (마감 시각 + 대기 시간)
    private static final Instant CLOSABLE_AT = END_AT.plus(GRACE_PERIOD);
    private static final String ALERT_MESSAGE = "공모주 마감이 대기 시간 뒤";

    private IpoRepository ipoRepository;
    private IpoCloseService closeService;
    private IpoCloseScheduler scheduler;
    private Ipo ipo;
    private UUID ipoId;

    @BeforeEach
    void setUp() {
        ipoRepository = mock(IpoRepository.class);
        closeService = mock(IpoCloseService.class);
        scheduler = new IpoCloseScheduler(ipoRepository, closeService, new IpoCloseProperties(GRACE_PERIOD, ALERT_AFTER));

        ipoId = UUID.randomUUID();
        ipo = mock(Ipo.class);
        when(ipo.getId()).thenReturn(ipoId);
        when(ipo.getSubscriptionEndAt()).thenReturn(END_AT);
    }

    @Test
    @DisplayName("마감 시각 + 대기 시간 이전을 기준으로 OPEN 공모주를 찾고, 대상이 없으면 마감 처리를 호출하지 않는다")
    void closeDueIpos_doesNothing_whenNoDueIpo() {
        // given
        Instant now = END_AT.plusSeconds(10);
        when(ipoRepository.findByStatusAndSubscriptionEndAtLessThanEqual(IpoStatus.OPEN, now.minus(GRACE_PERIOD)))
                .thenReturn(List.of());

        // when
        scheduler.closeDueIpos(now);

        // then
        verify(ipoRepository).findByStatusAndSubscriptionEndAtLessThanEqual(IpoStatus.OPEN, END_AT.minusSeconds(10));
        verifyNoInteractions(closeService);
    }

    @Test
    @DisplayName("대상 공모주가 있으면 접수 토픽 랙을 보지 않고 마감 처리를 호출한다")
    void closeDueIpos_closes_whenIpoIsDue() {
        // given
        due(CLOSABLE_AT);
        when(closeService.close(ipoId)).thenReturn(closed());

        // when
        scheduler.closeDueIpos(CLOSABLE_AT);

        // then
        verify(closeService).close(ipoId);
    }

    @Test
    @DisplayName("마감 처리에서 예외가 나도 밖으로 던지지 않는다 (다음 확인에서 다시 시도)")
    void closeDueIpos_swallowsCloseFailure() {
        // given
        due(CLOSABLE_AT);
        when(closeService.close(ipoId)).thenThrow(new IllegalStateException("DB 오류"));

        // when
        scheduler.closeDueIpos(CLOSABLE_AT);

        // then
        verify(closeService).close(ipoId);
    }

    @Test
    @DisplayName("대기 시간 뒤 alertAfter가 지나기 전에는 막혀 있어도 알림 로그를 남기지 않는다")
    void closeDueIpos_doesNotAlert_beforeAlertAfter(CapturedOutput output) {
        // given
        Instant now = CLOSABLE_AT.plus(ALERT_AFTER).minusSeconds(1);
        blockedByInProgress(now);

        // when
        scheduler.closeDueIpos(now);

        // then
        assertThat(output.getOut()).doesNotContain(ALERT_MESSAGE);
    }

    @Test
    @DisplayName("대기 시간 뒤 alertAfter가 지나면 알림 로그를 남기고, 이후 alertAfter 간격으로만 다시 남긴다")
    void closeDueIpos_alertsOnceThenEveryAlertAfter(CapturedOutput output) {
        // given
        Instant first = CLOSABLE_AT.plus(ALERT_AFTER);
        Instant soon = first.plusSeconds(5);
        Instant next = first.plus(ALERT_AFTER);
        blockedByInProgress(first);
        blockedByInProgress(soon);
        blockedByInProgress(next);

        // when, then
        scheduler.closeDueIpos(first);
        assertThat(alertCount(output)).isEqualTo(1);
        assertThat(output.getOut()).contains("PENDING 1");

        scheduler.closeDueIpos(soon);
        assertThat(alertCount(output)).isEqualTo(1);

        scheduler.closeDueIpos(next);
        assertThat(alertCount(output)).isEqualTo(2);
    }

    private void due(Instant now) {
        when(ipoRepository.findByStatusAndSubscriptionEndAtLessThanEqual(IpoStatus.OPEN, now.minus(GRACE_PERIOD)))
                .thenReturn(List.of(ipo));
    }

    private void blockedByInProgress(Instant now) {
        due(now);
        when(closeService.close(ipoId)).thenReturn(new IpoCloseResult(IpoCloseResult.Outcome.IN_PROGRESS_REMAINING, 0, 1, 0, 0));
    }

    private IpoCloseResult closed() {
        return new IpoCloseResult(IpoCloseResult.Outcome.CLOSED, 0, 0, 0, 0);
    }

    private int alertCount(CapturedOutput output) {
        return output.getOut().split(ALERT_MESSAGE, -1).length - 1;
    }
}
