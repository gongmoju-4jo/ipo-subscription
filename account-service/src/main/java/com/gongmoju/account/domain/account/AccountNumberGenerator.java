package com.gongmoju.account.domain.account;

import java.security.SecureRandom;
import java.util.Random;

/**
 * 계좌번호를 만든다. 숫자 12자리 난수이고 첫 자리는 0이 아니다. 하이픈 없이 숫자만 쓰고 화면 표기는 프론트가 정한다.
 * <p>
 * 순번이 아니라 난수로 만들어서 번호로 계좌 수나 다음 번호를 짐작할 수 없다. 겹치는 번호는 UNIQUE 제약이 막으므로 호출하는 쪽에서 다시 만든다.
 */
public class AccountNumberGenerator {

    private static final int LENGTH = 12;

    private final Random random;

    public AccountNumberGenerator() {
        this(new SecureRandom());
    }

    public AccountNumberGenerator(Random random) {
        this.random = random;
    }

    /**
     * @return 숫자 12자리 계좌번호
     */
    public String generate() {
        StringBuilder number = new StringBuilder(LENGTH);
        number.append(1 + random.nextInt(9));
        for (int i = 1; i < LENGTH; i++) {
            number.append(random.nextInt(10));
        }
        return number.toString();
    }
}
