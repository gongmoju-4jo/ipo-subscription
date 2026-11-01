package com.gongmoju.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CI 실패 화면(로그, 테스트 리포트 아티팩트, PR 본문 표시)을 확인하기 위한 임시 테스트.
 * 확인이 끝나면 이 브랜치와 PR을 닫고 삭제한다. develop에 머지하지 않는다.
 */
class TmpCiFailCheckTest {

    @Test
    void CI_실패_확인용_일부러_실패하는_테스트() {
        assertThat(1 + 1).isEqualTo(3);
    }
}
