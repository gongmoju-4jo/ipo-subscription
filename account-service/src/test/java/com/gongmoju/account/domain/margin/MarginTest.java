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
}
