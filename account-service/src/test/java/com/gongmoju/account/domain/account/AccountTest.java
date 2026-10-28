package com.gongmoju.account.domain.account;

import com.gongmoju.account.global.exception.AccountErrorCode;
import com.gongmoju.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 잔고 변경 메서드의 돈 이동과 거절 조건 (스프링 없이 실행)
class AccountTest {

    // 예수금 cash, 청약증거금 margin인 계좌 (개설 메서드는 F-12에서 추가)
    private Account account(long cash, long margin) {
        Account account = new Account();
        if (cash + margin > 0) {
            account.deposit(cash + margin);
        }
        if (margin > 0) {
            account.reserveMargin(margin);
        }
        return account;
    }

    @Test
    void 입금하면_예수금이_늘어난다() {
        Account account = account(0, 0);

        account.deposit(1_000);

        assertThat(account.getCashBalance()).isEqualTo(1_000);
        assertThat(account.getMarginBalance()).isZero();
    }

    @Test
    void 증거금_확보는_예수금에서_청약증거금으로_옮긴다() {
        Account account = account(1_000, 0);

        account.reserveMargin(700);

        assertThat(account.getCashBalance()).isEqualTo(300);
        assertThat(account.getMarginBalance()).isEqualTo(700);
    }

    @Test
    void 예수금과_같은_금액은_확보할_수_있다() {
        Account account = account(1_000, 0);

        account.reserveMargin(1_000);

        assertThat(account.getCashBalance()).isZero();
        assertThat(account.getMarginBalance()).isEqualTo(1_000);
    }

    @Test
    void 예수금이_부족하면_확보하지_않고_잔고도_그대로다() {
        Account account = account(1_000, 0);

        assertThatThrownBy(() -> account.reserveMargin(1_001))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(AccountErrorCode.INSUFFICIENT_BALANCE);
        assertThat(account.getCashBalance()).isEqualTo(1_000);
        assertThat(account.getMarginBalance()).isZero();
    }

    @Test
    void 해제하면_청약증거금이_예수금으로_돌아간다() {
        Account account = account(300, 700);

        account.voidMargin(700);

        assertThat(account.getCashBalance()).isEqualTo(1_000);
        assertThat(account.getMarginBalance()).isZero();
    }

    @Test
    void 정산은_배정_대금을_계좌_밖으로_빼고_환불은_예수금으로_돌린다() {
        Account account = account(300, 700);

        account.postMargin(450);
        account.refundMargin(250);

        // 1,000원 중 배정 대금 450원만 빠져나감
        assertThat(account.getCashBalance()).isEqualTo(550);
        assertThat(account.getMarginBalance()).isZero();
    }

    @Test
    void 청약증거금보다_많이_차감하면_예외이고_잔고는_그대로다() {
        Account account = account(300, 700);

        assertThatThrownBy(() -> account.voidMargin(701)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> account.postMargin(701)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> account.refundMargin(701)).isInstanceOf(IllegalStateException.class);
        assertThat(account.getCashBalance()).isEqualTo(300);
        assertThat(account.getMarginBalance()).isEqualTo(700);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void 금액이_1보다_작으면_모든_메서드가_거절한다(long amount) {
        Account account = account(300, 700);

        assertThatThrownBy(() -> account.deposit(amount)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.reserveMargin(amount)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.voidMargin(amount)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.postMargin(amount)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.refundMargin(amount)).isInstanceOf(IllegalArgumentException.class);
        assertThat(account.getCashBalance()).isEqualTo(300);
        assertThat(account.getMarginBalance()).isEqualTo(700);
    }

    @Test
    void 잔고가_long_범위를_넘으면_예외이고_잔고는_그대로다() {
        Account account = account(Long.MAX_VALUE, 0);

        assertThatThrownBy(() -> account.deposit(1)).isInstanceOf(ArithmeticException.class);
        assertThat(account.getCashBalance()).isEqualTo(Long.MAX_VALUE);
    }
}
