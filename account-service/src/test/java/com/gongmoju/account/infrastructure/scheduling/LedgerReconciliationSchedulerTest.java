package com.gongmoju.account.infrastructure.scheduling;

import com.gongmoju.account.application.reconciliation.LedgerReconciliationResult;
import com.gongmoju.account.application.reconciliation.LedgerReconciliationService;
import com.gongmoju.account.domain.reconciliation.LedgerCheckResult;
import com.gongmoju.account.domain.reconciliation.LedgerMismatch;
import com.gongmoju.account.domain.reconciliation.LedgerReconciliationCheck;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.gongmoju.account.application.reconciliation.LedgerReconciliationService.SAMPLE_LIMIT;
import static com.gongmoju.account.domain.reconciliation.LedgerReconciliationCheck.LEDGER_SUM;
import static com.gongmoju.account.domain.reconciliation.LedgerReconciliationCheck.MARGIN_BALANCE;
import static com.gongmoju.account.domain.reconciliation.LedgerReconciliationCheck.MARGIN_PENDING_LEDGER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// 원장 대사 스케줄러의 로그 기록을 검증한다. 대사 서비스는 가짜 객체(mock)로 대체한다
// ERROR 로그 건수는 Slack 알림(ErrorLogDetected)의 건수가 되므로, 불일치 건수와 관계없이 검사당 1줄인지 확인한다
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
        when(reconciliationService.reconcile()).thenReturn(result());

        scheduler.reconcile();

        assertThat(output.getOut())
                .contains("[원장 대사] 불일치 없음. 실행 시간 ")
                .contains("MARGIN_BALANCE=0, LEDGER_SUM=0, MARGIN_PENDING_LEDGER=0, MARGIN_STATUS_LEDGER=0, LEDGER_MARGIN_ACCOUNT=0");
        assertThat(errorLines(output)).isEmpty();
    }

    @Test
    void 불일치가_있으면_검사마다_ERROR_1줄에_건수와_불일치_발생_대상_ID를_기록한다(CapturedOutput output) {
        // 잔고 합계 검사 2건(계좌 2개) + 인출 원장 검사 1건 → ERROR는 검사 수인 2줄
        UUID firstAccountId = UUID.randomUUID();
        UUID secondAccountId = UUID.randomUUID();
        UUID marginId = UUID.randomUUID();
        when(reconciliationService.reconcile()).thenReturn(result(
                checkResult(LEDGER_SUM, 2,
                        new LedgerMismatch(LEDGER_SUM, firstAccountId, null, "예수금=65000, 원장 예수금 합계=75000"),
                        new LedgerMismatch(LEDGER_SUM, secondAccountId, null, "예수금=1000, 원장 예수금 합계=0")),
                checkResult(MARGIN_PENDING_LEDGER, 1,
                        new LedgerMismatch(MARGIN_PENDING_LEDGER, firstAccountId, marginId, "증거금=15000, 인출 원장 2줄"))));

        scheduler.reconcile();

        assertThat(errorLines(output)).hasSize(2);
        assertThat(output.getOut())
                .contains("[원장 대사] 불일치 검사=LEDGER_SUM(잔고 = 원장 금액 합계) 2건. 불일치 발생 대상 ID: "
                        + firstAccountId + ", " + secondAccountId)
                .contains("[원장 대사] 불일치 검사=MARGIN_PENDING_LEDGER(증거금마다 인출 원장 1줄, 금액 = 증거금) 1건. 불일치 발생 대상 ID: "
                        + marginId)
                .contains("[원장 대사] 불일치 총 3건. 자동으로 수정하지 않으니 운영자 확인 필요")
                .contains("MARGIN_BALANCE=0, LEDGER_SUM=2, MARGIN_PENDING_LEDGER=1, MARGIN_STATUS_LEDGER=0, LEDGER_MARGIN_ACCOUNT=0")
                .contains("[원장 대사] 불일치 상세 1/2: 검사=LEDGER_SUM(잔고 = 원장 금액 합계), accountId=" + firstAccountId
                        + ", marginId=null, 예수금=65000, 원장 예수금 합계=75000")
                .contains("[원장 대사] 불일치 상세 1/1: 검사=MARGIN_PENDING_LEDGER(증거금마다 인출 원장 1줄, 금액 = 증거금), accountId="
                        + firstAccountId + ", marginId=" + marginId + ", 증거금=15000, 인출 원장 2줄");
    }

    @Test
    void 조회_상한을_초과하면_전체_건수와_조회한_대상만_기록하고_생략_건수를_기록한다(CapturedOutput output) {
        // 전체 105건 중 조회 상한 100건만 조회된 결과
        List<LedgerMismatch> samples = samples(LEDGER_SUM, SAMPLE_LIMIT);
        when(reconciliationService.reconcile()).thenReturn(result(new LedgerCheckResult(LEDGER_SUM, 105, samples)));

        scheduler.reconcile();

        assertThat(output.getOut())
                .contains("[원장 대사] 불일치 총 105건.")
                .contains("불일치 상세 100/105:")
                .contains("[원장 대사] 불일치 상세 5건 생략 (검사=LEDGER_SUM). 검사별 조회 상한 100건");
        assertThat(errorLines(output)).singleElement().asString()
                .contains("105건. 불일치 발생 대상 ID: " + samples.get(0).accountId())
                .contains(samples.get(99).accountId() + " 외 5건");
    }

    @Test
    void 이전_검사의_불일치가_상한을_초과해도_이후_검사의_상세를_기록한다(CapturedOutput output) {
        // 청약증거금 검사 전체 101건(조회 100건) + 잔고 합계 검사 1건
        UUID lastAccountId = UUID.randomUUID();
        when(reconciliationService.reconcile()).thenReturn(result(
                new LedgerCheckResult(MARGIN_BALANCE, SAMPLE_LIMIT + 1, samples(MARGIN_BALANCE, SAMPLE_LIMIT)),
                checkResult(LEDGER_SUM, 1, new LedgerMismatch(LEDGER_SUM, lastAccountId, null, "예수금=1000, 원장 예수금 합계=0"))));

        scheduler.reconcile();

        assertThat(output.getOut())
                .contains("[원장 대사] 불일치 상세 1건 생략 (검사=MARGIN_BALANCE). 검사별 조회 상한 100건")
                .contains("[원장 대사] 불일치 상세 1/1: 검사=LEDGER_SUM(잔고 = 원장 금액 합계), accountId=" + lastAccountId
                        + ", marginId=null, 예수금=1000, 원장 예수금 합계=0");
    }

    @Test
    void 대사_실행에_실패하면_ERROR를_기록하고_예외를_전파하지_않는다(CapturedOutput output) {
        when(reconciliationService.reconcile()).thenThrow(new DataAccessResourceFailureException("DB 연결 실패"));

        assertThatCode(scheduler::reconcile).doesNotThrowAnyException();

        assertThat(errorLines(output)).singleElement().asString()
                .contains("[원장 대사] 실행 실패. 다음 실행 시각에 다시 실행함");
        assertThat(output.getOut()).contains("DB 연결 실패");
    }

    private static LedgerReconciliationResult result(LedgerCheckResult... checkResults) {
        return new LedgerReconciliationResult(List.of(checkResults));
    }

    private static LedgerCheckResult checkResult(LedgerReconciliationCheck check, long totalCount, LedgerMismatch... samples) {
        return new LedgerCheckResult(check, totalCount, List.of(samples));
    }

    private static List<LedgerMismatch> samples(LedgerReconciliationCheck check, int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> new LedgerMismatch(check, UUID.randomUUID(), null, "번호=" + i))
                .toList();
    }

    private List<String> errorLines(CapturedOutput output) {
        return output.getOut().lines()
                .filter(line -> line.contains("ERROR") && line.contains("[원장 대사]"))
                .toList();
    }
}
