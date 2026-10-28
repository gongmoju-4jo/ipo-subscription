package com.gongmoju.account.domain.ledger;

import com.gongmoju.account.domain.account.Account;
import com.gongmoju.account.domain.margin.Margin;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

// 원장 생성 규칙 (스프링 없이 실행). Account, Margin은 생성 메서드가 아직 없어서 mock으로 잔고만 정한다
class LedgerEntryTest {

    private Account accountWith(long cash, long margin) {
        Account account = mock(Account.class);
        given(account.getCashBalance()).willReturn(cash);
        given(account.getMarginBalance()).willReturn(margin);
        return account;
    }

    @Test
    void 입금_원장은_거래_후_잔고와_요청_ID를_남긴다() {
        Account account = accountWith(1_000, 0);
        UUID requestId = UUID.randomUUID();

        LedgerEntry entry = LedgerEntry.deposit(account, 1_000, requestId);

        assertThat(entry.getType()).isEqualTo(LedgerEntryType.DEPOSIT);
        assertThat(entry.getAmount()).isEqualTo(1_000);
        assertThat(entry.getCashBalanceAfter()).isEqualTo(1_000);
        assertThat(entry.getMarginBalanceAfter()).isZero();
        assertThat(entry.getRequestId()).isEqualTo(requestId);
        assertThat(entry.getMargin()).isNull();
        assertThat(entry.getSubscriptionId()).isNull();
    }

    @Test
    void 입금은_요청_ID가_없으면_거절한다() {
        Account account = accountWith(1_000, 0);

        assertThatThrownBy(() -> LedgerEntry.deposit(account, 1_000, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 증거금_원장은_어느_청약의_돈인지_남긴다() {
        Account account = accountWith(300, 700);
        Margin margin = mock(Margin.class);
        UUID subscriptionId = UUID.randomUUID();
        given(margin.getSubscriptionId()).willReturn(subscriptionId);

        LedgerEntry entry = LedgerEntry.ofMargin(account, LedgerEntryType.MARGIN_PENDING, 700, margin);

        assertThat(entry.getType()).isEqualTo(LedgerEntryType.MARGIN_PENDING);
        assertThat(entry.getCashBalanceAfter()).isEqualTo(300);
        assertThat(entry.getMarginBalanceAfter()).isEqualTo(700);
        assertThat(entry.getMargin()).isSameAs(margin);
        assertThat(entry.getSubscriptionId()).isEqualTo(subscriptionId);
        assertThat(entry.getRequestId()).isNull();
    }

    @Test
    void 증거금_원장은_margin이_없거나_입금_type이면_거절한다() {
        Account account = accountWith(300, 700);
        Margin margin = mock(Margin.class);

        assertThatThrownBy(() -> LedgerEntry.ofMargin(account, LedgerEntryType.MARGIN_VOID, 700, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LedgerEntry.ofMargin(account, LedgerEntryType.DEPOSIT, 700, margin))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 원장_금액이_1보다_작으면_거절한다() {
        Account account = accountWith(300, 700);
        Margin margin = mock(Margin.class);

        assertThatThrownBy(() -> LedgerEntry.deposit(account, 0, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LedgerEntry.ofMargin(account, LedgerEntryType.REFUND, 0, margin))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
