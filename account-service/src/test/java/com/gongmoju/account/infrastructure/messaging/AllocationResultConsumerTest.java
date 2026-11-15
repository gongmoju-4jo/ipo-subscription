package com.gongmoju.account.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gongmoju.account.application.margin.MarginSettleService;
import com.gongmoju.account.application.margin.SettleMarginCommand;
import com.gongmoju.account.global.exception.InvalidEventException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.kafka.listener.BatchListenerFailedException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

// 정산 Consumer의 배치 처리 분기를 검증한다 (#114). 정산 서비스는 가짜 객체(mock)로 대체한다
// 실제 Kafka에서는 수신 시점에 따라 배치 구성이 달라져서 "배치 실패 → 1건씩 처리" 분기를 항상 재현할 수 없어 단위 테스트로 확인한다
class AllocationResultConsumerTest {

    private static final String BROKEN_JSON = "{not json";

    private final UUID ipoId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    private MarginSettleService settleService;
    private AllocationResultConsumer consumer;

    @BeforeEach
    void setUp() {
        settleService = mock(MarginSettleService.class);
        consumer = new AllocationResultConsumer(new ObjectMapper(), settleService);
    }

    @Test
    void 정상_배치는_트랜잭션_1개로_정산한다() {
        Message first = message();
        Message second = message();

        consumer.consume(List.of(first.payload(), second.payload()));

        verify(settleService).settleBatch(List.of(first.command(), second.command()));
        verify(settleService, never()).settle(any());
    }

    @Test
    void 배치_정산이_실패하면_1건씩_순서대로_다시_정산한다() {
        Message first = message();
        Message second = message();
        doThrow(new IllegalStateException("배치 실패")).when(settleService).settleBatch(any());

        consumer.consume(List.of(first.payload(), second.payload()));

        InOrder order = inOrder(settleService);
        order.verify(settleService).settle(first.command());
        order.verify(settleService).settle(second.command());
    }

    @Test
    void 한_건씩_처리_중_실패하면_실패_위치를_전달하고_이후_건은_처리하지_않는다() {
        Message first = message();
        Message failing = message();
        Message third = message();
        doThrow(new IllegalStateException("배치 실패")).when(settleService).settleBatch(any());
        doThrow(new InvalidEventException("증거금이 없습니다.")).when(settleService).settle(failing.command());

        assertThatThrownBy(() -> consumer.consume(List.of(first.payload(), failing.payload(), third.payload())))
                .isInstanceOfSatisfying(BatchListenerFailedException.class, e -> {
                    assertThat(e.getIndex()).isEqualTo(1);
                    assertThat(e.getCause()).isInstanceOf(InvalidEventException.class);
                });

        verify(settleService).settle(first.command());
        verify(settleService, never()).settle(third.command());
    }

    @Test
    void 잘못된_메시지가_있으면_배치_정산_없이_1건씩_처리하고_해당_위치를_전달한다() {
        Message first = message();
        Message third = message();

        assertThatThrownBy(() -> consumer.consume(List.of(first.payload(), BROKEN_JSON, third.payload())))
                .isInstanceOfSatisfying(BatchListenerFailedException.class, e -> {
                    assertThat(e.getIndex()).isEqualTo(1);
                    assertThat(e.getCause()).isInstanceOf(InvalidEventException.class);
                });

        verify(settleService, never()).settleBatch(any());
        verify(settleService).settle(first.command());
        verify(settleService, never()).settle(third.command());
    }

    // 배정 서비스가 발행하는 형식의 메시지와, 정상 변환됐을 때 기대하는 정산 명령
    private Message message() {
        UUID eventId = UUID.randomUUID();
        UUID subscriptionId = UUID.randomUUID();
        String payload = """
                {"eventId":"%s","eventType":"allocation.result","occurredAt":"2026-10-27T16:20:00.000+09:00","ipoId":"%s",\
                "subscriptionId":"%s","accountId":"%s","equalAllocatedQuantity":1,"proportionalAllocatedQuantity":2,\
                "allocatedQuantity":3,"allocatedAmount":6000}""".formatted(eventId, ipoId, subscriptionId, accountId);
        return new Message(payload, new SettleMarginCommand(eventId, subscriptionId, accountId, ipoId, 6_000));
    }

    private record Message(String payload, SettleMarginCommand command) {
    }
}
