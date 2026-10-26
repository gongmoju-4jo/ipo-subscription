package com.gongmoju.common.feign;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.gongmoju.common.response.ApiErrorDetail;
import feign.Client;
import feign.Feign;
import feign.Request;
import feign.RequestLine;
import feign.RetryableException;
import feign.Retryer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 실제 HTTP 서버에 Feign으로 호출해 재시도 횟수와 최종 예외 종류를 확인한다.
// 호출 횟수는 Client를 감싸서 센다 (연결 실패처럼 서버에 닿지 않는 호출도 세기 위해)
class FeignRetryTest {

    private static final int READ_TIMEOUT_MS = 200;

    interface TestApi {
        @RequestLine("GET /test")
        String get();
    }

    // 서버가 차례로 돌려줄 응답. 다 쓰면 200
    private record Reply(int status, String body, long delayMs) {
    }

    private final Queue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final AtomicInteger attempts = new AtomicInteger();
    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/test", exchange -> {
            Reply reply = replies.poll();
            if (reply == null) {
                reply = new Reply(200, "ok", 0);
            }
            if (reply.delayMs() > 0) {
                try {
                    Thread.sleep(reply.delayMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(reply.status(), body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } catch (IOException ignored) {
                // 클라이언트가 read timeout으로 먼저 끊은 경우
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void 서버_오류가_두_번_나고_세_번째에_성공하면_결과를_돌려준다() {
        reply(500, errorBody("INTERNAL_SERVER_ERROR", "서버 오류"));
        reply(500, errorBody("INTERNAL_SERVER_ERROR", "서버 오류"));

        assertThat(api(retryer(2, true)).get()).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void 서버_오류가_계속되면_재시도_후_FeignServerException의_status와_code를_그대로_던진다() {
        for (int i = 0; i < 3; i++) {
            reply(503, errorBody("SERVICE_UNAVAILABLE", "점검 중"));
        }

        assertThatThrownBy(() -> api(retryer(2, true)).get())
                .isInstanceOfSatisfying(FeignServerException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(503);
                    assertThat(e.getCode()).isEqualTo("SERVICE_UNAVAILABLE");
                    assertThat(e.getMessage()).contains("TestApi#get()").contains("점검 중");
                });
        assertThat(attempts).hasValue(3);
    }

    @Test
    void ApiResponse_형식이_아닌_503도_재시도하고_UNKNOWN_ERROR로_던진다() {
        for (int i = 0; i < 3; i++) {
            reply(503, "Load balancer does not contain an instance for the service order-service");
        }

        assertThatThrownBy(() -> api(retryer(2, true)).get())
                .isInstanceOfSatisfying(FeignServerException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(503);
                    assertThat(e.getCode()).isEqualTo("UNKNOWN_ERROR");
                });
        assertThat(attempts).hasValue(3);
    }

    @Test
    void 클라이언트_오류는_재시도하지_않고_FeignClientException을_그대로_던진다() {
        reply(409, """
                {"success":false,"error":{"code":"INSUFFICIENT_STOCK","message":"재고가 부족합니다.",
                "details":[{"field":"quantity","reason":"재고 3개"}]}}
                """);

        assertThatThrownBy(() -> api(retryer(2, true)).get())
                .isInstanceOfSatisfying(FeignClientException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(409);
                    assertThat(e.getCode()).isEqualTo("INSUFFICIENT_STOCK");
                    assertThat(e.getMessage()).isEqualTo("재고가 부족합니다.");
                    assertThat(e.getDetails()).containsExactly(new ApiErrorDetail("quantity", "재고 3개"));
                });
        assertThat(attempts).hasValue(1);
    }

    @Test
    void 응답_지연은_재시도_후_RetryableException을_던진다() {
        for (int i = 0; i < 3; i++) {
            slowReply();
        }

        assertThatThrownBy(() -> api(retryer(2, true)).get())
                .isInstanceOf(RetryableException.class)
                .hasCauseInstanceOf(SocketTimeoutException.class);
        assertThat(attempts).hasValue(3);
    }

    @Test
    void 응답_지연_재시도를_끄면_한_번만_호출하고_RetryableException을_던진다() {
        slowReply();

        assertThatThrownBy(() -> api(retryer(2, false)).get())
                .isInstanceOf(RetryableException.class)
                .hasCauseInstanceOf(SocketTimeoutException.class);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void 응답_지연_재시도를_꺼도_서버_오류는_재시도한다() {
        reply(500, errorBody("INTERNAL_SERVER_ERROR", "서버 오류"));
        reply(500, errorBody("INTERNAL_SERVER_ERROR", "서버 오류"));

        assertThat(api(retryer(2, false)).get()).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
    }

    @Test
    void 연결_실패는_재시도_후_RetryableException을_던진다() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        assertThatThrownBy(() -> api("http://localhost:" + closedPort, retryer(2, false)).get())
                .isInstanceOf(RetryableException.class)
                .hasCauseInstanceOf(ConnectException.class);
        assertThat(attempts).hasValue(3);
    }

    @Test
    void 재시도_횟수가_0이면_서버_오류도_한_번만_호출한다() {
        reply(500, errorBody("INTERNAL_SERVER_ERROR", "서버 오류"));

        assertThatThrownBy(() -> api(retryer(0, true)).get())
                .isInstanceOfSatisfying(FeignServerException.class, e -> assertThat(e.getStatus()).isEqualTo(500));
        assertThat(attempts).hasValue(1);
    }

    @Test
    void noRetry를_쓰면_서버_오류를_한_번만_호출하고_FeignServerException을_던진다() {
        reply(500, errorBody("INTERNAL_SERVER_ERROR", "서버 오류"));

        assertThatThrownBy(() -> api(FeignRetryer.noRetry()).get())
                .isInstanceOfSatisfying(FeignServerException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(500);
                    assertThat(e.getCode()).isEqualTo("INTERNAL_SERVER_ERROR");
                });
        assertThat(attempts).hasValue(1);
    }

    // 내부 클라이언트에서 재시도를 끌 때 Retryer.NEVER_RETRY 대신 FeignRetryer.noRetry()를 써야 하는 이유:
    // 5xx를 감싼 RetryableException을 풀어 줄 Retryer가 없어 catch (FeignServerException e)에 걸리지 않는다
    @Test
    void Retryer_NEVER_RETRY를_쓰면_서버_오류가_RetryableException으로_나간다() {
        reply(500, errorBody("INTERNAL_SERVER_ERROR", "서버 오류"));

        assertThatThrownBy(() -> api(Retryer.NEVER_RETRY).get())
                .isInstanceOf(RetryableException.class)
                .hasCauseInstanceOf(FeignServerException.class);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void 호출마다_재시도_횟수를_새로_센다() {
        TestApi api = api(retryer(2, true));
        for (int i = 0; i < 6; i++) {
            reply(500, errorBody("INTERNAL_SERVER_ERROR", "서버 오류"));
        }

        assertThatThrownBy(api::get).isInstanceOf(FeignServerException.class);
        assertThatThrownBy(api::get).isInstanceOf(FeignServerException.class);
        assertThat(attempts).hasValue(6);
    }

    // 연결 타임아웃은 환경마다 재현이 불안정해서 Retryer에 직접 넣어 확인한다
    @Test
    void 응답_지연_재시도를_꺼도_연결_타임아웃은_재시도한다() {
        FeignRetryer retryer = retryer(2, false);

        retryer.continueOrPropagate(timeout("Connect timed out"));
        retryer.continueOrPropagate(timeout("connect timed out"));
        RetryableException last = timeout("Connect timed out");
        assertThatThrownBy(() -> retryer.continueOrPropagate(last)).isSameAs(last);
    }

    @Test
    void 응답_지연_재시도를_끄면_read_timeout은_바로_던진다() {
        RetryableException readTimeout = timeout("Read timed out");

        assertThatThrownBy(() -> retryer(2, false).continueOrPropagate(readTimeout)).isSameAs(readTimeout);
    }

    private FeignRetryer retryer(int maxRetries, boolean retryOnReadTimeout) {
        return new FeignRetryer(1, 1, maxRetries + 1, retryOnReadTimeout);
    }

    private TestApi api(Retryer retryer) {
        return api("http://localhost:" + server.getAddress().getPort(), retryer);
    }

    private TestApi api(String url, Retryer retryer) {
        Client delegate = new Client.Default(null, null);
        Client counting = (request, options) -> {
            attempts.incrementAndGet();
            return delegate.execute(request, options);
        };
        return Feign.builder()
                .client(counting)
                .errorDecoder(new FeignErrorDecoder(new ObjectMapper()))
                .retryer(retryer)
                .options(new Request.Options(1000, TimeUnit.MILLISECONDS, READ_TIMEOUT_MS, TimeUnit.MILLISECONDS, true))
                .target(TestApi.class, url);
    }

    private void reply(int status, String body) {
        replies.add(new Reply(status, body, 0));
    }

    private void slowReply() {
        replies.add(new Reply(200, "late", READ_TIMEOUT_MS * 3L));
    }

    private String errorBody(String code, String message) {
        return """
                {"success":false,"error":{"code":"%s","message":"%s","details":[]}}
                """.formatted(code, message);
    }

    private RetryableException timeout(String message) {
        Request request = Request.create(Request.HttpMethod.GET, "http://localhost/test", Map.of(), null,
                StandardCharsets.UTF_8, null);
        return new RetryableException(-1, message, Request.HttpMethod.GET, new SocketTimeoutException(message),
                (Long) null, request);
    }
}
