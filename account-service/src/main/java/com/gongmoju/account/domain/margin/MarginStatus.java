package com.gongmoju.account.domain.margin;

// 증거금 상태. PENDING → POSTED 또는 VOIDED, 한 번만 (2단계 이체)
public enum MarginStatus {
    PENDING,
    POSTED,
    VOIDED
}
