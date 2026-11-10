package com.gongmoju.gateway.queue;

import com.gongmoju.gateway.security.JwtTestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

// 대기열 API에서 예상하지 못한 오류가 나도 Spring 기본 오류 JSON이 아니라 공통 형식 500으로 내려가고, 원인 메시지는 응답에 드러나지 않는다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "eureka.client.enabled=false")
class QueueUnexpectedErrorTest {

    private static final String SECRET_DETAIL = "내부 구현 정보";

    @Autowired
    private WebTestClient webTestClient;

    @Value("${jwt.secret}")
    private String secret;

    @MockitoBean
    private QueueService queueService;

    @Test
    void 처리_흐름_안에서_예상하지_못한_오류가_나면_공통_형식_500() throws Exception {
        given(queueService.enter(any(), any())).willReturn(Mono.error(new IllegalStateException(SECRET_DETAIL)));

        assertInternalError(webTestClient.post().uri("/api/v1/queues/{ipoId}/entries", UUID.randomUUID()));
    }

    @Test
    void 호출하자마자_예상하지_못한_오류가_나도_공통_형식_500() throws Exception {
        given(queueService.find(any(), any())).willThrow(new IllegalStateException(SECRET_DETAIL));

        assertInternalError(webTestClient.get().uri("/api/v1/queues/{ipoId}/entries/me", UUID.randomUUID()));
    }

    private void assertInternalError(WebTestClient.RequestHeadersSpec<?> request) throws Exception {
        byte[] body = request
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + JwtTestTokens.valid(secret, UUID.randomUUID().toString(), "USER"))
                .exchange()
                .expectStatus().isEqualTo(500)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.error.code").isEqualTo("INTERNAL_SERVER_ERROR")
                .jsonPath("$.error.message").isEqualTo("서버 내부 오류가 발생했습니다.")
                .returnResult().getResponseBody();
        assertThat(new String(body, StandardCharsets.UTF_8)).doesNotContain(SECRET_DETAIL);
    }
}
