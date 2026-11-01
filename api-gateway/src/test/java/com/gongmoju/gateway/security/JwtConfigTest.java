package com.gongmoju.gateway.security;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// JWT 서명 키 검증 규칙은 account-service와 같아야 한다 (다르면 모든 요청이 401)
class JwtConfigTest {

    private static String base64OfLength(int bytes) {
        return Base64.getEncoder().encodeToString(new byte[bytes]);
    }

    @Test
    void 키가_32바이트_이상이면_디코딩한다() {
        assertThat(JwtConfig.decodeSecret(base64OfLength(32))).hasSize(32);
        assertThat(JwtConfig.decodeSecret(base64OfLength(37))).hasSize(37);
    }

    @Test
    void 키_앞뒤_공백은_무시한다() {
        assertThat(JwtConfig.decodeSecret("  " + base64OfLength(32) + "\n")).hasSize(32);
    }

    @Test
    void 키가_비어_있으면_실패한다() {
        assertThatThrownBy(() -> JwtConfig.decodeSecret("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtConfig.decodeSecret("   ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> JwtConfig.decodeSecret(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 키가_32바이트보다_짧으면_실패하고_메시지에_키를_담지_않는다() {
        String shortSecret = base64OfLength(31);

        assertThatThrownBy(() -> JwtConfig.decodeSecret(shortSecret))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(shortSecret);
    }

    @Test
    void 키가_Base64가_아니면_실패한다() {
        assertThatThrownBy(() -> JwtConfig.decodeSecret("이것은-base64가-아님!!"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 설정_값은_toString에_찍히지_않는다() {
        String secret = base64OfLength(32);

        assertThat(new JwtProperties(secret, "account-service").toString()).doesNotContain(secret);
    }
}
