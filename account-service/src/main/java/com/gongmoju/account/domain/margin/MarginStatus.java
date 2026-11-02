package com.gongmoju.account.domain.margin;

// 증거금 상태. PENDING → POSTED 또는 VOIDED, 한 번만 (2단계 이체)

/**
 * 청약증거금의 인출 및 정산 상태를 나타낸다.
 *
 * <ul>
 *   <li>PENDING: 증거금 인출 완료, 정산 대기</li>
 *   <li>POSTED: 정산 및 잔여 증거금 환불 완료</li>
 *   <li>VOIDED: 청약 취소 또는 실패로 인출한 증거금 전액 반환 완료</li>
 * </ul>
 */
public enum MarginStatus {
    PENDING, // 증거금 인출 완료, 정산 대기
    POSTED, // 정산 및 잔여 증거금 환불 완료
    VOIDED // 청약 취소 또는 실패로 인출한 증거금 전액 반환 완료
}
