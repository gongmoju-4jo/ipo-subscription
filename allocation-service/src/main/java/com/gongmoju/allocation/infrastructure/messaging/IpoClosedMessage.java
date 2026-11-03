package com.gongmoju.allocation.infrastructure.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gongmoju.allocation.application.allocation.AllocationRoundService;
import com.gongmoju.allocation.domain.allocation.IpoClosed;
import com.gongmoju.allocation.global.exception.InvalidEventException;

import java.time.Instant;
import java.util.UUID;

import static com.gongmoju.allocation.infrastructure.messaging.MessageSupport.parseTime;
import static com.gongmoju.allocation.infrastructure.messaging.MessageSupport.require;

/**
 * {@code ipo.closed} 메시지 본문 (이벤트 명세 1장 공통 필드 + {@code ipo.closed} 절). 공통 필드와 도메인 필드를 한 단계로 펼친다.
 * 숫자는 누락을 구분하려고 {@code Long}으로 받는다. 모르는 필드는 ObjectMapper 설정과 관계없이 무시한다 (이벤트 명세: 필드 추가 허용).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IpoClosedMessage(
        UUID eventId,
        String eventType,
        String occurredAt,
        UUID ipoId,
        String closedAt,
        Long confirmedCount,
        Long offerPrice,
        Long retailQuantity,
        Long equalQuantity,
        Long minQuantity
) {

    public static final String TOPIC = AllocationRoundService.TOPIC;

    /**
     * 메시지를 마감 내용으로 바꾼다. 필수 필드 누락, {@code eventType}과 토픽 이름 불일치, 시각 형식 오류, 명세 범위를 벗어난 숫자는
     * 다시 처리해도 결과가 같으므로 재시도 없이 DLT로 보낸다. UUID 형식 오류는 역직렬화 단계에서 걸러진다.
     *
     * @return 마감 내용
     * @throws InvalidEventException 필수 필드와 형식 검증을 통과하지 못하면
     */
    public IpoClosed toEvent() {
        require(eventId, "eventId");
        if (!TOPIC.equals(eventType)) {
            throw new InvalidEventException("eventType이 " + TOPIC + "가 아닙니다: " + eventType);
        }
        parseTime(occurredAt, "occurredAt");
        require(ipoId, "ipoId");
        Instant closed = parseTime(closedAt, "closedAt");
        long confirmed = requireAtLeast(confirmedCount, 0, "confirmedCount");
        long price = requireAtLeast(offerPrice, 1, "offerPrice");
        long retail = requireAtLeast(retailQuantity, 1, "retailQuantity");
        long equal = requireAtLeast(equalQuantity, 0, "equalQuantity");
        if (equal > retail) {
            throw new InvalidEventException("equalQuantity는 retailQuantity 이하여야 합니다: equalQuantity=" + equal + ", retailQuantity=" + retail);
        }
        long min = requireAtLeast(minQuantity, 1, "minQuantity");
        return new IpoClosed(eventId, ipoId, closed, confirmed, price, retail, equal, min);
    }

    private static long requireAtLeast(Long value, long min, String field) {
        require(value, field);
        if (value < min) {
            throw new InvalidEventException(field + "는 " + min + " 이상이어야 합니다: " + value);
        }
        return value;
    }
}
