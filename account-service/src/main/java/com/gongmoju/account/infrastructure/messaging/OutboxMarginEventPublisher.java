package com.gongmoju.account.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.account.application.margin.MarginReserveService;
import com.gongmoju.account.application.margin.ReserveMarginCommand;
import com.gongmoju.account.domain.margin.MarginEventPublisher;
import com.gongmoju.account.domain.margin.MarginReserveFailed;
import com.gongmoju.account.domain.margin.MarginReserved;
import com.gongmoju.account.domain.margin.MarginSettled;
import com.gongmoju.account.domain.margin.MarginVoided;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 증거금 인출 결과 이벤트를 Outbox에 저장하는 구현체.
 * <p>
 * 실제 Kafka 전송은 별도 OutboxPublisher가 담당한다.
 * MarginReserved를 Kafka 이벤트 본문 형식으로 변환한다.
 * 메시지 키는 accountId이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class OutboxMarginEventPublisher implements MarginEventPublisher {

    // TODO: aggregateType은 공통 Outbox 규격이 정해지면 enum으로 바꿀지 결정 (서비스별 enum 제안 중)
    static final String AGGREGATE_TYPE = "MARGIN";
    static final String MARGIN_RESERVED = "margin.reserved";
    static final String MARGIN_RESERVE_FAILED = "margin.reserve-failed";
    static final String MARGIN_VOIDED = "margin.voided";
    static final String MARGIN_SETTLED = "margin.settled";

    // 이벤트 명세: ISO-8601, +09:00, 밀리초 (OutboxMessageFactory의 occurredAt과 같은 형식)
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

    /**
     * 증거금 인출 실패 이벤트를 Outbox에 저장한다.
     * Margin이 생성되지 않았으므로 subscriptionId를 aggregateId로 사용한다.
     *
     * @param event 인출 실패 결과
     */
    @Override
    public void publishReserveFailed(MarginReserveFailed event) {
        MarginReserveFailedPayload payload =
                new MarginReserveFailedPayload(
                        event.subscriptionId(), event.reason().name()
                );

        save(MARGIN_RESERVE_FAILED, event.subscriptionId(), event.accountId(), payload);
    }

    /**
     * 증거금 반환 이벤트를 Outbox에 저장한다.
     * <p>
     * - aggregateId:
     * Margin이 있으면 marginId, 없으면(amount 0) subscriptionId를 사용한다.
     *
     * @param event 증거금 반환 결과
     */
    @Override
    public void publishVoided(MarginVoided event) {
        UUID aggregateId =
                event.marginId() != null ?
                        event.marginId() : event.subscriptionId();

        save(MARGIN_VOIDED, aggregateId, event.accountId(),
                new MarginVoidedPayload(event.subscriptionId(), event.amount()));
    }

    /**
     * 증거금 정산 이벤트를 Outbox에 저장한다.
     * <p>
     * - aggregateId: marginId (정산은 margin이 있어야 하기 때문)
     *
     * @param event 증거금 정산 결과
     */
    @Override
    public void publishSettled(MarginSettled event) {
        save(MARGIN_SETTLED, event.marginId(), event.accountId(),
                new MarginSettledPayload(event.subscriptionId(), event.postedAmount(), event.refundAmount()));
    }

    /**
     * 증거금 이벤트를 JSON 변환하여 Outbox에 저장한다.
     *
     * @param topic       이벤트 토픽명
     * @param aggregateId 성공 시 marginId, 실패 시 subscriptionId
     * @param accountId   Kafka 메시지 키로 사용할 accountId
     * @param payload     이벤트 본문 데이터
     */
    private void save(String topic, UUID aggregateId, UUID accountId, Object payload) {
        outboxRepository.save(Outbox.of(
                AGGREGATE_TYPE, aggregateId, topic, accountId.toString(), toJson(payload)
        ));
        log.debug("[Outbox] 저장. topic={}, aggregateId={}, key={}, payload={}", topic, aggregateId, accountId, payload);
    }

    /**
     * 이벤트 본문의 JSON 문자열로 직렬화한다.
     * 실패 시 IllegalStateException을 발생시킨다.
     *
     * @param payload 직렬화할 이벤트 본문
     * @return JSON 문자열
     */
    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox payload를 만들 수 없습니다. type=" + payload.getClass().getSimpleName(), e);
        }
    }
}
