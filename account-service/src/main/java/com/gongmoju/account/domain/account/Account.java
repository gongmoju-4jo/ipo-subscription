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

    // 계좌번호 (화면 표시용). 숫자 12자리, 하이픈 없이 저장. 한 번 정하면 바뀌지 않는다. 다른 서비스는 account_id로만 참조한다
    @Column(nullable = false, unique = true, length = 20)
    private String accountNumber;

    // 예수금 (출금 가능액)
    @Column(nullable = false)
    private long cashBalance;

    // 청약증거금
    @Column(nullable = false)
    private long marginBalance;

    /**
     * 계좌를 개설한다. 잔고는 0으로 시작하고 가상 입금으로 채운다.
     *
     * @param user          소유자
     * @param requestId     개설 요청 ID (멱등키)
     * @param accountNumber 계좌번호
     * @return 새 계좌
     */
    public static Account open(User user, UUID requestId, String accountNumber) {
        Account account = new Account();
        account.user = user;
        account.requestId = requestId;
        account.accountNumber = accountNumber;
        return account;
    }
}
