package com.gongmoju.account.infrastructure.scheduling;

import com.gongmoju.account.application.reconciliation.LedgerReconciliationResult;
import com.gongmoju.account.application.reconciliation.LedgerReconciliationService;
import com.gongmoju.account.domain.reconciliation.LedgerMismatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.gongmoju.account.domain.reconciliation.LedgerReconciliationCheck.LEDGER_SUM;
import static com.gongmoju.account.domain.reconciliation.LedgerReconciliationCheck.MARGIN_PENDING_LEDGER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 원장 대사 스케줄러의 로그 기록을 검증한다. 대사 서비스는 가짜 객체(mock)로 대체한다
// ERROR 로그 건수는 Slack 알림(ErrorLogDetected)의 건수가 되므로, 불일치 건수와 관계없이 1줄인지 확인한다
@ExtendWith(OutputCaptureExtension.class)
class LedgerReconciliationSchedulerTest {

    private LedgerReconciliationService reconciliationService;
    private LedgerReconciliationScheduler scheduler;

    @BeforeEach
    void setUp() {
        reconciliationService = mock(LedgerReconciliationService.class);
        scheduler = new LedgerReconciliationScheduler(reconciliationService);
    }

    @Test
    void 불일치가_없으면_INFO로_검사별_건수와_실행_시간을_기록한다(CapturedOutput output) {
        when(reconciliationService.reconcile()).thenReturn(new LedgerReconciliationResult(List.of()));

        scheduler.reconcile();

        assertThat(output.getOut())
                .contains("[원장 대사] 불일치 없음. 실행 시간 ")
                .contains("MARGIN_BALANCE=0, LEDGER_SUM=0, MARGIN_PENDING_LEDGER=0, MARGIN_STATUS_LEDGER=0");
        assertThat(errorLines(output)).isEmpty();
    }

    @Test
    void 불일치가_있으면_검사마다_ERROR_1줄에_건수와_불일치_발생_대상_ID를_기록한다(CapturedOutput output) {
        // 잔고 합계 검사 2건(계좌 2개) + 인출 원장 검사 1건 → ERROR는 검사 수인 2줄
        UUID firstAccountId = UUID.randomUUID();
        UUID secondAccountId = UUID.randomUUID();
        UUID marginId = UUID.randomUUID();
        when(reconciliationService.reconcile()).thenReturn(new LedgerReconciliationResult(List.of(
                new LedgerMismatch(LEDGER_SUM, firstAccountId, null, "예수금=65000, 원장 예수금 합계=75000"),
                new LedgerMismatch(LEDGER_SUM, secondAccountId, null, "예수금=1000, 원장 예수금 합계=0"),
                new LedgerMismatch(MARGIN_PENDING_LEDGER, firstAccountId, marginId, "증거금=15000, 인출 원장 2줄"))));

        scheduler.reconcile();

        assertThat(errorLines(output)).hasSize(2);
        assertThat(output.getOut())
                .contains("[원장 대사] 불일치 검사=LEDGER_SUM(잔고 = 원장 금액 합계) 2건. 불일치 발생 대상 ID: "
                        + firstAccountId + ", " + secondAccountId)
                .contains("[원장 대사] 불일치 검사=MARGIN_PENDING_LEDGER(증거금마다 인출 원장 1줄, 금액 = 증거금) 1건. 불일치 발생 대상 ID: "
                        + marginId)
                .contains("[원장 대사] 불일치 총 3건. 자동으로 수정하지 않으니 운영자 확인 필요")
                .contains("MARGIN_BALANCE=0, LEDGER_SUM=2, MARGIN_PENDING_LEDGER=1, MARGIN_STATUS_LEDGER=0")
                .contains("[원장 대사] 불일치 상세 1/3: 검사=LEDGER_SUM(잔고 = 원장 금액 합계), accountId=" + firstAccountId
                        + ", marginId=null, 예수금=65000, 원장 예수금 합계=75000")
                .contains("[원장 대사] 불일치 상세 3/3: 검사=MARGIN_PENDING_LEDGER(증거금마다 인출 원장 1줄, 금액 = 증거금), accountId="
                        + firstAccountId + ", marginId=" + marginId + ", 증거금=15000, 인출 원장 2줄");
    }

    @Test
    void 상세_로그와_불일치_발생_대상_ID는_상한까지만_기록하고_생략_건수를_기록한다(CapturedOutput output) {
        int total = LedgerReconciliationScheduler.DETAIL_LOG_LIMIT + 5;
        List<LedgerMismatch> mismatches = IntStream.range(0, total)
                .mapToObj(i -> new LedgerMismatch(LEDGER_SUM, UUID.randomUUID(), null, "번호=" + i))
                .toList();
        when(reconciliationService.reconcile()).thenReturn(new LedgerReconciliationResult(mismatches));

        scheduler.reconcile();

        assertThat(output.getOut())
                .contains("불일치 상세 100/105:")
                .doesNotContain("불일치 상세 101/105:")
                .contains("[원장 대사] 불일치 상세 5건 생략. 상세 로그 상한 100건");
        // 불일치 발생 대상 ID는 100개 + "외 5건"
        assertThat(errorLines(output)).singleElement().asString()
                .contains("105건. 불일치 발생 대상 ID: " + mismatches.get(0).accountId())
                .contains(mismatches.get(99).accountId() + " 외 5건")
                .doesNotContain(mismatches.get(100).accountId().toString());
    }

    @Test
    void 대사_실행에_실패하면_ERROR를_기록하고_예외를_전파하지_않는다(CapturedOutput output) {
        when(reconciliationService.reconcile()).thenThrow(new DataAccessResourceFailureException("DB 연결 실패"));

        assertThatCode(scheduler::reconcile).doesNotThrowAnyException();

        assertThat(errorLines(output)).singleElement().asString()
                .contains("[원장 대사] 실행 실패. 다음 실행 시각에 다시 실행함");
        assertThat(output.getOut()).contains("DB 연결 실패");
    }

    private List<String> errorLines(CapturedOutput output) {
        return output.getOut().lines()
                .filter(line -> line.contains("ERROR") && line.contains("[원장 대사]"))
                .toList();
    }
}
