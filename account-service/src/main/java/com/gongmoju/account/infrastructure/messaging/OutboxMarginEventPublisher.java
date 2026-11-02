package com.gongmoju.account.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.account.application.margin.MarginReserveService;
import com.gongmoju.account.application.margin.ReserveMarginCommand;
import com.gongmoju.account.domain.margin.MarginEventPublisher;
import com.gongmoju.account.domain.margin.MarginReserveFailed;
import com.gongmoju.account.domain.margin.MarginReserved;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

// 메시지 키는 accountId (이벤트 명세 3장)

/**
 * 증거금 인출 성공 결과를 Outbox에 저장하는 구현체.
 * <p>
 * MarginReserved를 Kafka 이벤트 본문 형식으로 변환한다.
 * 호출한 서비스의 DB 트랜잭션에 참여한다.
 * {@link MarginReserveService#reserve(ReserveMarginCommand)}에서 호출된다.
 * 실제 Kafka 전송은 별도 OutboxPublisher가 담당한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class OutboxMarginEventPublisher implements MarginEventPublisher {

    // TODO: aggregateType은 공통 Outbox 규격이 정해지면 enum으로 바꿀지 결정 (서비스별 enum 제안 중)
    static final String AGGREGATE_TYPE = "MARGIN";
    static final String MARGIN_RESERVED = "margin.reserved";
    static final String MARGIN_RESERVE_FAILED = "margin.reserve-failed";

    // 이벤트 명세 시각 규칙: ISO-8601, +09:00, 밀리초 (OutboxMessageFactory의 occurredAt과 같은 형식)
    private static final DateTimeFormatter EVENT_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx");
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);

    private final OutboxJpaRepository outboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * 인출 성공 결과를 이벤트 명세에 맞게 변환하고 Outbox에 저장한다.
     *
     * @param event 도메인에서 만들어진 인출 성공 결과 객체
     */
    @Override
    public void publishReserved(MarginReserved event) {
        MarginReservedPayload payload = new MarginReservedPayload(
                event.subscriptionId(), event.marginId(), event.amount(),
                EVENT_TIME_FORMAT.format(OffsetDateTime.ofInstant(event.reservedAt(), KST)));

        save(MARGIN_RESERVED, event.marginId(), event.accountId(), payload);
    }

    // 거절은 margin이 없어서 aggregateId에 청약 ID를 쓴다
    @Override
    public void publishReserveFailed(MarginReserveFailed event) {
        MarginReserveFailedPayload payload = new MarginReserveFailedPayload(event.subscriptionId(), event.reason().name());

        save(MARGIN_RESERVE_FAILED, event.subscriptionId(), event.accountId(), payload);
    }

    // 증거금 이벤트 공통 저장. 메시지 키는 accountId
    private void save(String topic, UUID aggregateId, UUID accountId, Object payload) {
        outboxRepository.save(Outbox.of(AGGREGATE_TYPE, aggregateId, topic, accountId.toString(), toJson(payload)));
        log.debug("Outbox 저장. topic={}, aggregateId={}, key={}, payload={}", topic, aggregateId, accountId, payload);
    }

    // 직렬화 실패 시 예외를 전파해 인출과 Outbox 저장을 같이 롤백한다. (응답 없이 돈만 이동하지 않도록)
    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox payload를 만들 수 없습니다. type=" + payload.getClass().getSimpleName(), e);
        }
    }
}
