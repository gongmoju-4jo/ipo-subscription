package com.gongmoju.account.domain.account;

import com.gongmoju.account.domain.user.User;
import com.gongmoju.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

// 계좌. 잔고 변경과 원장 기록은 계좌 서비스의 메서드 하나로만, 같은 트랜잭션에서
// CHECK(cash_balance >= 0, margin_balance >= 0)는 schema.sql에 둔다
@Getter
@Entity
@Table(name = "account")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "account_id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // 계좌 개설 요청 ID (멱등키)
    @Column(nullable = false, unique = true)
    private UUID requestId;

    // 예수금 (출금 가능액)
    @Column(nullable = false)
    private long cashBalance;

    // 청약증거금
    @Column(nullable = false)
    private long marginBalance;
}
