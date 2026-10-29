package com.gongmoju.account.infrastructure.security;

import com.gongmoju.account.domain.auth.AccessToken;
import com.gongmoju.common.auth.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 발급한 토큰을 Gateway가 검증하는 것과 같은 방식(같은 키, HS256)으로 풀어서 claim을 확인한다
class JwtAccessTokenIssuerTest {

    // 테스트 전용 키 (실제 키 아님). 디코딩 후 37바이트
    private static final String SECRET = "dGVzdC1vbmx5LWp3dC1zZWNyZXQta2V5LTAxMjM0NTY3ODlhYg==";
    private static final String OTHER_SECRET = Base64.getEncoder().encodeToString("another-test-only-key-0123456789abcdef".getBytes());

    private final UUID userId = UUID.fromString("7a1c0000-0000-4000-8000-000000000001");
    // 디코더가 현재 시각으로 만료를 검사하므로 지금 시각 기준으로 고정한다
    private final Clock clock = Clock.fixed(Instant.now().truncatedTo(ChronoUnit.SECONDS), ZoneOffset.UTC);

    @Test
    void 발급한_토큰은_sub_role_iss_iat_exp_claim을_가진다() {
        AccessToken token = issuer(SECRET, 3600).issue(userId, UserRole.USER);

        Jwt jwt = decoder(SECRET).decode(token.value());

        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.<String>getClaim("role")).isEqualTo("USER");
        // getIssuer()는 iss를 URL로 바꾸려 해서 "account-service" 같은 이름에는 쓸 수 없다
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("account-service");
        assertThat(jwt.getIssuedAt()).isEqualTo(clock.instant());
        assertThat(jwt.getExpiresAt()).isEqualTo(clock.instant().plusSeconds(3600));
        assertThat(jwt.getHeaders()).containsEntry("alg", "HS256");
    }

    @Test
    void 관리자_역할도_role_claim으로_담긴다() {
        AccessToken token = issuer(SECRET, 3600).issue(userId, UserRole.ADMIN);

        assertThat(decoder(SECRET).decode(token.value()).<String>getClaim("role")).isEqualTo("ADMIN");
    }

    @Test
    void expiresIn은_설정한_유효_시간과_같고_exp와_iat의_차이와_일치한다() {
        AccessToken token = issuer(SECRET, 1800).issue(userId, UserRole.USER);

        Jwt jwt = decoder(SECRET).decode(token.value());

        assertThat(token.expiresIn()).isEqualTo(1800);
        assertThat(jwt.getExpiresAt().getEpochSecond() - jwt.getIssuedAt().getEpochSecond()).isEqualTo(token.expiresIn());
    }

    @Test
    void 다른_키로는_검증할_수_없다() {
        AccessToken token = issuer(SECRET, 3600).issue(userId, UserRole.USER);

        assertThatThrownBy(() -> decoder(OTHER_SECRET).decode(token.value())).isInstanceOf(BadJwtException.class);
    }

    @Test
    void 키가_없으면_만들_때_실패한다() {
        assertThatThrownBy(() -> issuer("", 3600)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> issuer(null, 3600)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 키가_32바이트보다_짧으면_만들_때_실패한다() {
        String shortSecret = Base64.getEncoder().encodeToString("short-key-31-bytes-0123456789abc".substring(0, 31).getBytes());

        assertThatThrownBy(() -> issuer(shortSecret, 3600))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(shortSecret);
    }

    @Test
    void 키가_Base64가_아니면_만들_때_실패한다() {
        assertThatThrownBy(() -> issuer("이것은-base64가-아님!!", 3600)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 유효_시간이_0_이하이면_만들_때_실패한다() {
        assertThatThrownBy(() -> issuer(SECRET, 0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 토큰과_키는_toString에_찍히지_않는다() {
        AccessToken token = issuer(SECRET, 3600).issue(userId, UserRole.USER);

        assertThat(token.toString()).doesNotContain(token.value());
        assertThat(new JwtProperties(SECRET, "account-service", 3600).toString()).doesNotContain(SECRET);
    }

    private JwtAccessTokenIssuer issuer(String secret, long expiresInSeconds) {
        return new JwtAccessTokenIssuer(new JwtProperties(secret, "account-service", expiresInSeconds), clock);
    }

    private NimbusJwtDecoder decoder(String secret) {
        byte[] key = Base64.getDecoder().decode(secret);
        return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA256")).macAlgorithm(MacAlgorithm.HS256).build();
    }
}
