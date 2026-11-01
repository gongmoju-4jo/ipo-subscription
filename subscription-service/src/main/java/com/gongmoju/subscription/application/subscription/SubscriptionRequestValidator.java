package com.gongmoju.subscription.application.subscription;

import com.gongmoju.subscription.domain.ipo.Ipo;
import com.gongmoju.subscription.domain.ipo.IpoRepository;
import com.gongmoju.subscription.domain.subscription.SubscriptionIdGenerator;
import com.gongmoju.subscription.domain.subscription.SubscriptionRequested;
import com.gongmoju.subscription.global.exception.InvalidEventException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 접수 Consumer의 저장 전 검증 (이벤트 명세 2-1).
 * 검증 1(필수 값, 형식)은 메시지를 {@link SubscriptionRequested}로 변환하는 단계에서 처리하므로 본 클래스는 검증 2, 3을 순서대로 수행함.
 * 검증 4, 5(기존 청약 조회, 내용 비교)는 저장 흐름에서 처리함.
 */
@Component
@RequiredArgsConstructor
public class SubscriptionRequestValidator {

    private final IpoRepository ipoRepository;

    /**
     * 검증 2, 3을 순서대로 수행함. 실패 시 재처리해도 결과가 같으므로 재시도 없이 DLT로 전송할 예외를 던짐.
     *
     * @param event 접수 내용 (검증 1 통과 값)
     * @return 검증을 통과한 종목. 증거금 계산에 그대로 사용하며 재조회하지 않음
     * @throws InvalidEventException 검증 2: {@code subscriptionId}가 {@code userId}와 {@code requestId}로 계산한 값과 불일치,
     *                               검증 3: 종목 없음, {@code receivedAt}이 마감 시각과 같거나 이후, 증거금률이 100%가 아님
     */
    public Ipo validate(SubscriptionRequested event) {
        validateSubscriptionId(event);
        return validateIpo(event);
    }

    // 검증 2
    private void validateSubscriptionId(SubscriptionRequested event) {
        UUID expected = SubscriptionIdGenerator.generate(event.userId(), event.requestId());
        if (!expected.equals(event.subscriptionId())) {
            throw new InvalidEventException("subscriptionId가 userId와 requestId로 계산한 값과 다릅니다: " + event.subscriptionId());
        }
    }

    // 검증 3
    private Ipo validateIpo(SubscriptionRequested event) {
        Ipo ipo = ipoRepository.findById(event.ipoId())
                .orElseThrow(() -> new InvalidEventException("존재하지 않는 종목입니다: " + event.ipoId()));
        if (!ipo.isBeforeSubscriptionEnd(event.receivedAt())) {
            throw new InvalidEventException("receivedAt이 청약 마감 시각과 같거나 이후입니다: " + event.receivedAt());
        }
        if (!ipo.hasFullMarginRate()) {
            throw new InvalidEventException("증거금률이 100%가 아닌 종목은 접수할 수 없습니다: " + event.ipoId());
        }
        return ipo;
    }
}
