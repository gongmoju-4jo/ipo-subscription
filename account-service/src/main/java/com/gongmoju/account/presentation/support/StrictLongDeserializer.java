package com.gongmoju.account.presentation.support;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

// 금액처럼 소수 내림(1.5 -> 1), 문자열 변환("100" -> 100)이 있으면 안 되는 필드용. 정수 숫자만 받고 나머지는 JSON 형식 오류(400)가 된다
public class StrictLongDeserializer extends JsonDeserializer<Long> {

    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_NUMBER_INT) {
            return parser.getLongValue();
        }
        return (Long) context.handleUnexpectedToken(Long.class, parser);
    }
}
