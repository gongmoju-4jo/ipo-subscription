package com.gongmoju.account.infrastructure.messaging;

// 계좌 서비스 Outbox의 aggregate_type 값. common Outbox는 문자열로 저장하고, 허용 값은 서비스별 enum으로 관리한다
//   - common이 도메인 값을 알지 않도록 enum은 서비스에 둔다 (#111 논의됨)
//   - 계좌 서비스가 발행하는 이벤트(margin.reserved, reserve-failed, voided, settled)는 모두 증거금 기준이라 MARGIN 하나이다.
enum OutboxAggregateType {
    MARGIN
}
