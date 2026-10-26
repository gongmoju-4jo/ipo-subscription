package com.gongmoju.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LikeUtilsTest {

    @Test
    void 일반_검색어는_앞뒤에_퍼센트만_붙는다() {
        assertThat(LikeUtils.contains("고등어")).isEqualTo("%고등어%");
    }

    @Test
    void 앞뒤_공백은_지운다() {
        assertThat(LikeUtils.contains("  고등어 ")).isEqualTo("%고등어%");
    }

    @Test
    void 퍼센트는_글자로_이스케이프한다() {
        assertThat(LikeUtils.contains("10%")).isEqualTo("%10\\%%");
    }

    @Test
    void 밑줄은_글자로_이스케이프한다() {
        assertThat(LikeUtils.contains("a_b")).isEqualTo("%a\\_b%");
    }

    @Test
    void 역슬래시는_먼저_이스케이프해서_두_번_바뀌지_않는다() {
        // \% 입력: \ → \\ 를 먼저 하고 % → \% 를 해야 \\\% (글자 \ + 글자 %)가 된다
        assertThat(LikeUtils.contains("\\%")).isEqualTo("%\\\\\\%%");
    }

    @Test
    void 특수문자만_있는_검색어도_글자로_비교한다() {
        assertThat(LikeUtils.contains("%")).isEqualTo("%\\%%");
        assertThat(LikeUtils.contains("_")).isEqualTo("%\\_%");
    }
}
