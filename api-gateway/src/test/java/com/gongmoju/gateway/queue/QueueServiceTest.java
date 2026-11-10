package com.gongmoju.gateway.queue;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class QueueServiceTest {

    private final QueueService service = new QueueService(null,
            new QueueProperties(Duration.ofSeconds(1), 433, Duration.ofMinutes(5), 3, 500, 1000), null);

    @Test
    void 순서_조회_간격은_대기_인원을_조회_예산으로_나눈_값이고_최소_1초다() {
        assertThat(service.pollInterval(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(service.pollInterval(500)).isEqualTo(Duration.ofSeconds(1));
        assertThat(service.pollInterval(501)).isEqualTo(Duration.ofMillis(1002));
        assertThat(service.pollInterval(10_000)).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void 대기자_전원이_알려준_간격으로_조회해도_초당_조회_수는_예산을_넘지_않는다() {
        for (long waiting : new long[]{1, 499, 500, 501, 7_777, 100_000}) {
            double pollsPerSecond = waiting / (service.pollInterval(waiting).toMillis() / 1000.0);
            assertThat(pollsPerSecond).isLessThanOrEqualTo(500);
        }
    }
}
