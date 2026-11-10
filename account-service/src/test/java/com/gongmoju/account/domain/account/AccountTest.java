package com.gongmoju.account.domain.account;

import com.gongmoju.account.domain.ledger.LedgerEntry;
import com.gongmoju.account.domain.ledger.LedgerEntryType;
import com.gongmoju.account.domain.margin.Margin;
import com.gongmoju.account.global.exception.AccountErrorCode;
import com.gongmoju.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 잔고 변경 메서드의 돈 이동과 거절 조건 (스프링 없이 실행)
class AccountTest {

    // 예수금 cash, 청약증거금 margin인 계좌 (개설 메서드는 F-12에서 추가)
    private Account account(long cash, long margin) {
        Account account = new Account();
        if (cash + margin > 0) {
            account.deposit(cash + margin, UUID.randomUUID());
        }
        if (margin > 0) {
            account.reserveMargin(margin, margin(account));
        }
        return account;
    }

    // 원장에 연결할 증거금 (금액 검사는 계좌 메서드가 하므로 여기 금액은 의미 없음)
    private static Margin margin(Account account) {
        return Margin.withdraw(account, UUID.randomUUID(), UUID.randomUUID(), 1);
    }

    @Test
    void 잔고를_바꾸면_그_거래의_원장을_거래_후_잔고와_함께_돌려준다() {
        Account account = account(0, 0);
        UUID requestId = UUID.randomUUID();

        Margin margin = margin(account);

        LedgerEntry deposit = account.deposit(1_000, requestId);
        LedgerEntry reserve = account.reserveMargin(700, margin);
        LedgerEntry post = account.postMargin(450, margin);
        LedgerEntry refund = account.refundMargin(250, margin);

        assertThat(deposit.getType()).isEqualTo(LedgerEntryType.DEPOSIT);
        assertThat(deposit.getRequestId()).isEqualTo(requestId);
        assertThat(deposit.getCashBalanceAfter()).isEqualTo(1_000);
        assertThat(reserve.getType()).isEqualTo(LedgerEntryType.MARGIN_PENDING);
        assertThat(reserve.getAmount()).isEqualTo(700);
        assertThat(reserve.getCashBalanceAfter()).isEqualTo(300);
        assertThat(reserve.getMarginBalanceAfter()).isEqualTo(700);
        assertThat(post.getType()).isEqualTo(LedgerEntryType.MARGIN_POST);
        assertThat(post.getMarginBalanceAfter()).isEqualTo(250);
        assertThat(refund.getType()).isEqualTo(LedgerEntryType.REFUND);
        assertThat(refund.getCashBalanceAfter()).isEqualTo(550);
        assertThat(refund.getMarginBalanceAfter()).isZero();
        // 증거금 거래 원장은 전달한 margin과 연결된다
        assertThat(deposit.getMargin()).isNull();
        assertThat(reserve.getMargin()).isSameAs(margin);
        assertThat(post.getMargin()).isSameAs(margin);
        assertThat(refund.getMargin()).isSameAs(margin);
    }

    @Test
    void 반환은_MARGIN_VOID_원장을_돌려준다() {
        Account account = account(300, 700);

        Margin margin = margin(account);

        LedgerEntry voided = account.voidMargin(700, margin);

        assertThat(voided.getType()).isEqualTo(LedgerEntryType.MARGIN_VOID);
        assertThat(voided.getAmount()).isEqualTo(700);
        assertThat(voided.getCashBalanceAfter()).isEqualTo(1_000);
        assertThat(voided.getMarginBalanceAfter()).isZero();
        assertThat(voided.getMargin()).isSameAs(margin);
    }

    @Test
    void 입금하면_예수금이_늘어난다() {
        Account account = account(0, 0);

        account.deposit(1_000, UUID.randomUUID());

        assertThat(account.getCashBalance()).isEqualTo(1_000);
        assertThat(account.getMarginBalance()).isZero();
    }

    @Test
    void 증거금_확보는_예수금에서_청약증거금으로_옮긴다() {
        Account account = account(1_000, 0);

        account.reserveMargin(700, margin(account));

        assertThat(account.getCashBalance()).isEqualTo(300);
        assertThat(account.getMarginBalance()).isEqualTo(700);
    }

    @Test
    void 예수금과_같은_금액은_확보할_수_있다() {
        Account account = account(1_000, 0);

        account.reserveMargin(1_000, margin(account));

        assertThat(account.getCashBalance()).isZero();
        assertThat(account.getMarginBalance()).isEqualTo(1_000);
    }

    @Test
    void 예수금이_부족하면_확보하지_않고_잔고도_그대로다() {
        Account account = account(1_000, 0);

        assertThatThrownBy(() -> account.reserveMargin(1_001, margin(account)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AccountErrorCode.INSUFFICIENT_BALANCE);
        assertThat(account.getCashBalance()).isEqualTo(1_000);
        assertThat(account.getMarginBalance()).isZero();
    }

    @Test
    void 해제하면_청약증거금이_예수금으로_돌아간다() {
        Account account = account(300, 700);

        account.voidMargin(700, margin(account));

        assertThat(account.getCashBalance()).isEqualTo(1_000);
        assertThat(account.getMarginBalance()).isZero();
    }

    @Test
    void 정산은_배정_대금을_계좌_밖으로_빼고_환불은_예수금으로_돌린다() {
        Account account = account(300, 700);

        account.postMargin(450, margin(account));
        account.refundMargin(250, margin(account));

        // 1,000원 중 배정 대금 450원만 빠져나감
        assertThat(account.getCashBalance()).isEqualTo(550);
        assertThat(account.getMarginBalance()).isZero();
    }

    @Test
    void 청약증거금보다_많이_차감하면_예외이고_잔고는_그대로다() {
        Account account = account(300, 700);

        assertThatThrownBy(() -> account.voidMargin(701, margin(account))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> account.postMargin(701, margin(account))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> account.refundMargin(701, margin(account))).isInstanceOf(IllegalStateException.class);
        assertThat(account.getCashBalance()).isEqualTo(300);
        assertThat(account.getMarginBalance()).isEqualTo(700);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void 금액이_1보다_작으면_모든_메서드가_거절한다(long amount) {
        Account account = account(300, 700);

        assertThatThrownBy(() -> account.deposit(amount, UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.reserveMargin(amount, margin(account))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.voidMargin(amount, margin(account))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.postMargin(amount, margin(account))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.refundMargin(amount, margin(account))).isInstanceOf(IllegalArgumentException.class);
        assertThat(account.getCashBalance()).isEqualTo(300);
        assertThat(account.getMarginBalance()).isEqualTo(700);
    }

    @Test
    void 잔고가_long_범위를_넘으면_예외이고_잔고는_그대로다() {
        Account account = account(Long.MAX_VALUE, 0);

        assertThatThrownBy(() -> account.deposit(1, UUID.randomUUID())).isInstanceOf(ArithmeticException.class);
        assertThat(account.getCashBalance()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void 인출로_청약증거금이_long_범위를_넘으면_예외이고_두_잔고_모두_그대로다() {
        // 청약증거금 MAX - 1, 예수금 MAX (입금은 예수금만 검사해서 두 잔고 합은 long 범위를 넘을 수 있다)
        Account account = new Account();
        account.deposit(Long.MAX_VALUE, UUID.randomUUID());
        account.reserveMargin(Long.MAX_VALUE - 1, margin(account));
        account.deposit(Long.MAX_VALUE - 1, UUID.randomUUID());

        assertThatThrownBy(() -> account.reserveMargin(10, margin(account))).isInstanceOf(ArithmeticException.class);
        assertThat(account.getCashBalance()).isEqualTo(Long.MAX_VALUE);
        assertThat(account.getMarginBalance()).isEqualTo(Long.MAX_VALUE - 1);
    }
}
