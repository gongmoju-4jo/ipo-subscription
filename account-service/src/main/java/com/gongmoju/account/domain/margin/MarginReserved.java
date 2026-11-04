package com.gongmoju.account.domain.margin;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 도메인 계층에서 인출 성공 결과를 표현하는 객체.
 * <p>
 * margin.reserved 로 발행한다.
 * margin 엔티티에서 생성된다.
 *
 * @param subscriptionId 청약 ID
 * @param marginId       생성된 증거금 ID
 * @param accountId      인출된 예수금 게좌 ID, 메시지 키로 사용
 * @param amount         인출된 증거금 금액
 * @param reservedAt     최초 증거금 인출 시각 (Instant)
 */
public record MarginReserved(
        UUID subscriptionId,
        UUID marginId,
        UUID accountId,
        long amount,
        Instant reservedAt
) {

    public MarginReserved {
        // margin을 저장(persist)한 뒤에 만들어야 marginId, reservedAt(created_at)이 채워져 있다
        Objects.requireNonNull(marginId, "marginId는 margin 저장 후에 정해집니다.");
        Objects.requireNonNull(reservedAt, "reservedAt은 margin 저장 후에 정해집니다.");
    }

    // reservedAt은 MVP에서 margin.created_at을 쓴다. 인출과 같은 트랜잭션에서 margin을 만들 때 찍히는 시각이라 최초 인출 시각과 같다
    // TODO: Margin.createdAt과 실제 인출 성공 시각의 차이를 검증하고, 불일치 시 reservedAt 별도 관리
    // TODO: 재발행(기존 margin PENDING) 때도 같은 margin.created_at을 읽어 최초 값을 유지하는지 3단계 테스트로 확인
    public static MarginReserved from(Margin margin) {
        return new MarginReserved(margin.getSubscriptionId(), margin.getId(), margin.getAccount().getId(),
                margin.getAmount(), margin.getCreatedAt());
    }
}
