package com.gongmoju.gateway.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

// 공개 경로, 로그인이 필요한 경로, 관리자 경로 규칙을 확인한다.
// 실제 서비스가 없어서 보안을 통과한 요청은 라우트 단계에서 503 등이 날 수 있으므로, 통과 여부는 401, 403이 아닌지로 본다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "eureka.client.enabled=false")
@Import(UserHeaderInjectionTest.EchoConfig.class)
class AuthorizationRulesTest {

    @Autowired
    private WebTestClient webTestClient;

    @Value("${jwt.secret}")
    private String secret;

    private final String userId = UUID.randomUUID().toString();

    // ---- 공개 경로 ----

    @Test
    void 회원가입과_로그인은_토큰_없이_호출할_수_있다() {
        passes(call(HttpMethod.POST, "/api/v1/users", null));
        passes(call(HttpMethod.POST, "/api/v1/auth/login", null));
    }

    @Test
    void 공개_경로는_만료된_토큰이_붙어_있어도_토큰을_읽지_않고_통과한다() throws Exception {
        String expired = JwtTestTokens.expired(secret, userId, "USER");

        passes(call(HttpMethod.POST, "/api/v1/auth/login", expired));
        passes(call(HttpMethod.POST, "/api/v1/users", expired));
    }

    @Test
    void Swagger_UI와_API_문서는_토큰_없이_열린다() {
        call(HttpMethod.GET, "/swagger-ui/index.html", null).expectStatus().isOk();
        call(HttpMethod.GET, "/v3/api-docs/swagger-config", null).expectStatus().isOk();
        passes(call(HttpMethod.GET, "/account-service/v3/api-docs", null));
        passes(call(HttpMethod.GET, "/subscription-service/v3/api-docs", null));
        passes(call(HttpMethod.GET, "/allocation-service/v3/api-docs", null));
    }

    @Test
    void Swagger_UI_화면을_그리는_JS_CSS_파일도_토큰_없이_열린다() {
        String html = new String(call(HttpMethod.GET, "/swagger-ui/index.html", null)
                .expectStatus().isOk().expectBody().returnResult().getResponseBody());

        // index.html이 불러오는 상대 경로 파일 (./swagger-ui.css, ./swagger-ui-bundle.js 등)
        Matcher assets = Pattern.compile("(?:src|href)=\"\\./([^\"]+)\"").matcher(html);
        int count = 0;
        while (assets.find()) {
            call(HttpMethod.GET, "/swagger-ui/" + assets.group(1), null).expectStatus().isOk();
            count++;
        }
        assertThat(count).isPositive();
    }

    // ---- 로그인이 필요한 경로 ----

    @Test
    void 토큰이_없으면_보호되는_경로는_401이다() {
        for (String path : new String[]{"/api/v1/accounts", "/api/v1/accounts/" + userId + "/deposits", "/api/v1/ipos",
                "/api/v1/subscriptions", "/api/v1/allocations/subscriptions/" + userId, "/api/v1/unknown", "/anything"}) {
            call(HttpMethod.GET, path, null).expectStatus().isUnauthorized();
        }
    }

    @Test
    void 공개는_경로와_메서드가_모두_맞을_때만이라_같은_경로의_다른_메서드나_하위_경로는_401이다() {
        call(HttpMethod.GET, "/api/v1/users", null).expectStatus().isUnauthorized();
        call(HttpMethod.PUT, "/api/v1/users", null).expectStatus().isUnauthorized();
        call(HttpMethod.POST, "/api/v1/users/" + userId, null).expectStatus().isUnauthorized();
        call(HttpMethod.GET, "/api/v1/auth/login", null).expectStatus().isUnauthorized();
        call(HttpMethod.POST, "/api/v1/auth/login/extra", null).expectStatus().isUnauthorized();
        call(HttpMethod.POST, "/v3/api-docs/anything", null).expectStatus().isUnauthorized();
    }

    @Test
    void 유효한_토큰이면_보호되는_경로를_통과한다() throws Exception {
        call(HttpMethod.GET, "/test-echo/headers", JwtTestTokens.valid(secret, userId, "USER")).expectStatus().isOk();
        passes(call(HttpMethod.GET, "/api/v1/accounts", JwtTestTokens.valid(secret, userId, "USER")));
    }

    @Test
    void 만료된_토큰으로_보호되는_경로를_호출하면_401이다() throws Exception {
        call(HttpMethod.GET, "/api/v1/accounts", JwtTestTokens.expired(secret, userId, "USER")).expectStatus().isUnauthorized();
    }

    // ---- 관리자 경로 ----

    @Test
    void 일반_회원이_관리자_경로를_호출하면_403이다() throws Exception {
        String user = JwtTestTokens.valid(secret, userId, "USER");

        call(HttpMethod.POST, "/api/v1/admin/ipos", user).expectStatus().isForbidden();
        call(HttpMethod.GET, "/api/v1/admin/ipos", user).expectStatus().isForbidden();
        call(HttpMethod.GET, "/api/v1/admin", user).expectStatus().isForbidden();
    }

    @Test
    void 관리자는_관리자_경로를_통과한다() throws Exception {
        passes(call(HttpMethod.POST, "/api/v1/admin/ipos", JwtTestTokens.valid(secret, userId, "ADMIN")));
    }

    @Test
    void 토큰이_없으면_관리자_경로는_403이_아니라_401이다() {
        call(HttpMethod.POST, "/api/v1/admin/ipos", null).expectStatus().isUnauthorized();
    }

    private WebTestClient.ResponseSpec call(HttpMethod method, String path, String token) {
        WebTestClient.RequestHeadersSpec<?> request = webTestClient.method(method).uri(path);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return request.exchange();
    }

    private static void passes(WebTestClient.ResponseSpec response) {
        response.expectStatus().value(status -> assertThat(status).isNotIn(401, 403));
    }
}
