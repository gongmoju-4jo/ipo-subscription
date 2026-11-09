package com.gongmoju.account.infrastructure.messaging;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.gongmoju.account.global.exception.InvalidEventException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Consumer 3개가 함께 쓰는 메시지 읽기. 재시도해도 소용없는 실패는 InvalidEventException(바로 DLT)이어야 한다
class KafkaMessageParserTest {

    record Sample(String name, long amount) {
    }

    private final ObjectReader reader = new ObjectMapper().readerFor(Sample.class);

    @Test
    void 정상_JSON은_메시지로_읽는다() {
        Sample sample = KafkaMessageParser.parse("{\"name\":\"a\",\"amount\":15000}", reader);

        assertThat(sample).isEqualTo(new Sample("a", 15000));
    }

    @Test
    void 빈_본문은_InvalidEventException() {
        assertThatThrownBy(() -> KafkaMessageParser.parse("  ", reader))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("비어 있습니다");
        assertThatThrownBy(() -> KafkaMessageParser.parse(null, reader))
                .isInstanceOf(InvalidEventException.class);
    }

    @Test
    void 본문이_null_리터럴이면_InvalidEventException() {
        assertThatThrownBy(() -> KafkaMessageParser.parse("null", reader))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("JSON 객체가 아닙니다");
    }

    @Test
    void 읽을_수_없는_JSON은_InvalidEventException() {
        assertThatThrownBy(() -> KafkaMessageParser.parse("{not json", reader))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("읽을 수 없습니다");
    }

    @Test
    void 넘겨받은_reader_설정을_그대로_쓴다_소수_금액_거절() {
        ObjectReader strict = new ObjectMapper().readerFor(Sample.class).without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);

        assertThatThrownBy(() -> KafkaMessageParser.parse("{\"name\":\"a\",\"amount\":45000.5}", strict))
                .isInstanceOf(InvalidEventException.class);
    }
}
