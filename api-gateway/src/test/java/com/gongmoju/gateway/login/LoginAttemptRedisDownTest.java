package com.gongmoju.gateway.login;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static com.gongmoju.gateway.login.FakeAccountServiceConfig.BACKEND_HITS;
import static org.assertj.core.api.Assertions.assertThat;

// Redis에 접속할 수 없으면 횟수 확인 없이 로그인을 통과시킨다 (청약 신청의 입장 허가 확인은 503, LoginAttemptFilter 설명)
// 아무것도 듣지 않는 포트 1로 접속하게 해서 연결 실패를 만든다
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"eureka.client.enabled=false", "queue.scheduler.enabled=false",
                "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1"})
@Import(FakeAccountServiceConfig.class)
class LoginAttemptRedisDownTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void Redis에_접속할_수_없으면_여섯_번_틀려도_모두_서비스로_가고_맞으면_로그인된다() {
        int hitsBefore = BACKEND_HITS.get();

        for (int i = 0; i < 6; i++) {
            login("wrong").expectStatus().isUnauthorized();
        }
        login("right").expectStatus().isOk();

        assertThat(BACKEND_HITS.get()).isEqualTo(hitsBefore + 7);
    }

    private WebTestClient.ResponseSpec login(String password) {
        return webTestClient.post().uri("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"loginId\":\"investor01\",\"password\":\"" + password + "\"}")
                .exchange();
    }
}
