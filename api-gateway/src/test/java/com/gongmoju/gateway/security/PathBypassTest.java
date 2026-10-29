package com.gongmoju.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.reactive.server.StatusAssertions;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.net.URI;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 경로를 살짝 바꿔서 보호 규칙을 피하려는 요청이 서비스까지 가지 않는지 확인한다.
// 경로를 그대로 보내려고 URI를 직접 만든다 (URI 템플릿은 %를 한 번 더 인코딩하고 //를 호스트로 해석한다).
// 실제 서비스가 없어서 라우트가 맞아 서비스로 넘어가면 503이 나므로, 막혔는지는 400, 401, 403, 404인지로 본다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "eureka.client.enabled=false")
class PathBypassTest {

    @Autowired
    private WebTestClient webTestClient;

    @LocalServerPort
    private int port;

    @Value("${jwt.secret}")
    private String secret;

    private final String userId = UUID.randomUUID().toString();

    @Test
    void 관리자_경로의_끝_슬래시와_퍼센트_인코딩은_일반_회원에게_403이다() throws Exception {
        String user = JwtTestTokens.valid(secret, userId, "USER");

        status(HttpMethod.GET, "/api/v1/admin/ipos/", user).isForbidden();
        status(HttpMethod.GET, "/api/v1/%61dmin/ipos", user).isForbidden();
    }

    @Test
    void 일반_회원은_관리자_경로를_변형해도_서비스에_도달하지_못한다() throws Exception {
        String user = JwtTestTokens.valid(secret, userId, "USER");

        // 대소문자(라우트 없음), 앞 슬래시 중복, 점 경로, 세미콜론, 인코딩된 슬래시와 점
        for (String path : new String[]{"/API/V1/ADMIN/ipos", "//api/v1/admin/ipos", "/api/v1/./admin/ipos",
                "/api/v1/users/../admin/ipos", "/api/v1/admin;x=1/ipos", "/api/v1/admin%2Fipos",
                "/api/v1/%2e%2e/admin/ipos"}) {
            assertBlocked(HttpMethod.GET, path, user);
        }
    }

    @Test
    void 공개_경로를_앞세워_보호되는_경로에_토큰_없이_접근할_수_없다() {
        for (String path : new String[]{"/swagger-ui/../api/v1/accounts", "/v3/api-docs/../api/v1/accounts",
                "/account-service/v3/api-docs/../../api/v1/accounts", "/swagger-ui/%2e%2e/api/v1/accounts"}) {
            assertBlocked(HttpMethod.GET, path, null);
        }
    }

    @Test
    void 공개_경로에_점_경로를_붙여도_보호되는_경로는_열리지_않는다() {
        for (String path : new String[]{"/api/v1/auth/login/../../accounts", "/api/v1/users/../accounts",
                "/api/v1/auth/login%2F..%2Faccounts"}) {
            assertBlocked(HttpMethod.POST, path, null);
        }
    }

    @Test
    void 공개는_정확히_이_경로만이라_끝_슬래시가_붙으면_공개가_아니다() {
        status(HttpMethod.POST, "/api/v1/users/", null).isUnauthorized();
        status(HttpMethod.POST, "/api/v1/auth/login/", null).isUnauthorized();
    }

    private void assertBlocked(HttpMethod method, String path, String token) {
        status(method, path, token).value(status -> assertThat(status)
                .as("%s 요청이 서비스로 넘어가면 안 된다", path).isIn(400, 401, 403, 404));
    }

    private StatusAssertions status(HttpMethod method, String path, String token) {
        WebTestClient.RequestHeadersSpec<?> request = webTestClient.method(method).uri(URI.create("http://localhost:" + port + path));
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return request.exchange().expectStatus();
    }
}
