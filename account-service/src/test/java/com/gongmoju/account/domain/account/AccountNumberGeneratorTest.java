package com.gongmoju.account.domain.account;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AccountNumberGeneratorTest {

    private final AccountNumberGenerator generator = new AccountNumberGenerator();

    @Test
    void 숫자_12자리이고_첫_자리는_0이_아니다() {
        for (int i = 0; i < 10_000; i++) {
            assertThat(generator.generate()).matches("[1-9][0-9]{11}");
        }
    }

    @Test
    void 매번_다른_번호가_나온다() {
        Set<String> numbers = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            numbers.add(generator.generate());
        }

        // 12자리 난수라 만 개를 뽑아도 겹치지 않는 것이 정상이다 (순번이 아니므로 규칙으로 짐작할 수 없다)
        assertThat(numbers).hasSize(10_000);
    }

    @Test
    void 같은_시드면_같은_번호를_만든다() {
        AccountNumberGenerator first = new AccountNumberGenerator(new Random(42));
        AccountNumberGenerator second = new AccountNumberGenerator(new Random(42));

        assertThat(first.generate()).isEqualTo(second.generate());
    }
}
