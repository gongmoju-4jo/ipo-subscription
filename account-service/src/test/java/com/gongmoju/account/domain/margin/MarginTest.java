package com.gongmoju.account.domain.margin;

import com.gongmoju.account.domain.account.Account;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

// 증거금 생성 규칙 (스프링 없이 실행)
class MarginTest {

    @Test
    void 인출하면_PENDING_상태의_증거금이_만들어진다() {
        Account account = mock(Account.class);
        UUID subscriptionId = UUID.randomUUID();
        UUID ipoId = UUID.randomUUID();

        Margin margin = Margin.withdraw(account, subscriptionId, ipoId, 7_500_000);

        assertThat(margin.getStatus()).isEqualTo(MarginStatus.PENDING);
        assertThat(margin.getAccount()).isSameAs(account);
        assertThat(margin.getSubscriptionId()).isEqualTo(subscriptionId);
        assertThat(margin.getIpoId()).isEqualTo(ipoId);
        assertThat(margin.getAmount()).isEqualTo(7_500_000);
        assertThat(margin.getPostedAmount()).isNull();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void 금액이_1보다_작으면_거절한다(long amount) {
        assertThatThrownBy(() -> Margin.withdraw(mock(Account.class), UUID.randomUUID(), UUID.randomUUID(), amount))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 3_000_000, 7_500_000})
    void 정산하면_POSTED가_되고_배정_대금이_남는다(long postedAmount) {
        Margin margin = Margin.withdraw(mock(Account.class), UUID.randomUUID(), UUID.randomUUID(), 7_500_000);

        margin.postPending(postedAmount);

        assertThat(margin.getStatus()).isEqualTo(MarginStatus.POSTED);
        assertThat(margin.getPostedAmount()).isEqualTo(postedAmount);
        assertThat(margin.getSettledAt()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 7_500_001})
    void 배정_대금이_1보다_작거나_증거금보다_크면_거절한다(long postedAmount) {
        Margin margin = Margin.withdraw(mock(Account.class), UUID.randomUUID(), UUID.randomUUID(), 7_500_000);

        assertThatThrownBy(() -> margin.postPending(postedAmount))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(margin.getStatus()).isEqualTo(MarginStatus.PENDING);
        assertThat(margin.getPostedAmount()).isNull();
    }

    @Test
    void PENDING이_아니면_정산을_거절한다() {
        Margin margin = Margin.withdraw(mock(Account.class), UUID.randomUUID(), UUID.randomUUID(), 7_500_000);
        margin.voidPending();

        assertThatThrownBy(() -> margin.postPending(3_000_000))
                .isInstanceOf(IllegalStateException.class);
        assertThat(margin.getStatus()).isEqualTo(MarginStatus.VOIDED);
    }
}
