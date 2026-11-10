package com.gongmoju.account.domain.account;

import com.gongmoju.account.domain.ledger.LedgerEntry;
import com.gongmoju.account.domain.ledger.LedgerEntryType;
import com.gongmoju.account.domain.margin.Margin;
import com.gongmoju.account.domain.user.User;
import com.gongmoju.account.global.exception.AccountErrorCode;
import com.gongmoju.common.entity.BaseEntity;
import com.gongmoju.common.exception.BusinessException;
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

/**
 * 투자자의 예수금과 청약증거금 잔고를 관리하는 계좌 엔티티.
 * <p>
 * 잔고 변경과 원장(LedgerEntry) 기록은 호출하는 서비스의 동일한 DB 트랜잭션에서 처리한다.
 * DB에서도 음수 잔고를 방지하도록 schema.sql에 CHECK 제약조건을 정의한다.
 */
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

    // 계좌 개설 요청을 식별하는 고유 ID. 동일한 요청으로 계좌가 중복 생성되지 않도록 관리
    @Column(nullable = false, unique = true)
    private UUID requestId;

    // 계좌번호 (화면 표시용). 숫자 12자리, 하이픈 없이 저장. 한 번 정하면 바뀌지 않는다. 다른 서비스는 account_id로만 참조한다
    @Column(nullable = false, unique = true, length = 20)
    private String accountNumber;

    // 투자자가 입금하거나 청약에 사용할 수 있는 예수금 잔고 (원)
    @Column(nullable = false)
    private long cashBalance;

    // 청약을 위해 인출했으며, 아직 정산 또는 환불되지 않은 청약증거금 잔고 (원)
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

    // 잔고 변경 메서드는 원장 type 하나에 하나씩. 잔고를 변경한 후, 해당 거래의 LedgerEntry를 만들어 반환한다 (#106)
    //   - 잔고만 변경하고 원장이 누락되는 코드를 작성할 수 없게 하기 위함 (팀 규칙: 잔고 변경과 원장 기록은 정해진 메서드 하나로만)
    //   - 원장은 거래 후 잔고를 이 계좌에서 읽으므로 잔고를 바꾼 다음에 만든다
    //   - 저장은 호출한 서비스가 같은 트랜잭션에서 한다 (도메인은 Repository를 모름)
    // 금액 검증(1 이상)은 API, Consumer가 먼저 하고 이곳에서 한 번 더 거부한다. 덧셈 오버플로는 Math.addExact로 예외를 발생시킨다

    // DEPOSIT: 입금; 예수금 증가
    //   Math.addExact로 long 범위를 초과하는 금액이 계산되지 않도록 검사
    public LedgerEntry deposit(long amount, UUID requestId) {
        requirePositive(amount);
        cashBalance = Math.addExact(cashBalance, amount);
        return LedgerEntry.deposit(this, amount, requestId);
    }

    // 요청한 회원의 계좌인지 확인한다
    public boolean isOwnedBy(UUID userId) {
        return user.getId().equals(userId);
    }

    // 인출 전에 예수금이 충분한지 확인한다. 부족하면 서비스가 예외 대신 거절 응답(INSUFFICIENT_BALANCE)을 저장한다
    public boolean hasCashBalance(long amount) {
        return cashBalance >= amount;
    }

    /**
     * 증거금을 인출한다.
     * LedgerEntryType: MARGIN_PENDING
     * <p>
     * 예수금 감소 -> 청약증거금 증가
     * 예수금이 부족하면 INSUFFICIENT_BALANCE 예외를 발생시켜 인출 차단
     * 인출 전후의 계좌 전체 잔고(예수금 + 청약증거금)는 동일
     *
     * @param amount 인출할 증거금 금액 (원)
     * @param margin 이번 인출로 만든 증거금
     * @return 인출 원장 (MARGIN_PENDING)
     */
    public LedgerEntry reserveMargin(long amount, Margin margin) {
        requirePositive(amount);
        if (cashBalance < amount) {
            throw new BusinessException(AccountErrorCode.INSUFFICIENT_BALANCE);
        }
        long newMarginBalance = Math.addExact(marginBalance, amount); // 계산, 필드 반영 분리
        cashBalance -= amount;
        marginBalance = newMarginBalance;
        return LedgerEntry.ofMargin(this, LedgerEntryType.MARGIN_PENDING, amount, margin);
    }

    // MARGIN_VOID: 증거금 인출 무효화; 청약증거금 전액 → 예수금 (확정 실패, 취소, 0주 배정)
    //   정산 완료된 증거금의 반환은 호출 전에 Margin 상태로 검증
    public LedgerEntry voidMargin(long amount, Margin margin) {
        withdrawMargin(amount);
        cashBalance = Math.addExact(cashBalance, amount);
        return LedgerEntry.ofMargin(this, LedgerEntryType.MARGIN_VOID, amount, margin);
    }

    // MARGIN_POST: 증거금 최종 정산; 청약증거금에서 배정 대금만큼 차감 (계좌 밖으로 나감)
    //   배정 서비스의 결과를 바탕으로 정산하며, 차감 금액은 예수금으로 반환하지 않음
    //   정산 금액이 0원이면 호출하지 않고 원장 기록도 생성하지 않음
    public LedgerEntry postMargin(long amount, Margin margin) {
        withdrawMargin(amount);
        return LedgerEntry.ofMargin(this, LedgerEntryType.MARGIN_POST, amount, margin);
    }

    // REFUND: 증거금 환불; 정산 후 남은 청약증거금 → 예수금 으로 반환
    //   환불액이 0원이면 호출하지 않고, 원장 기록도 생성하지 않음.
    public LedgerEntry refundMargin(long amount, Margin margin) {
        withdrawMargin(amount);
        cashBalance = Math.addExact(cashBalance, amount);
        return LedgerEntry.ofMargin(this, LedgerEntryType.REFUND, amount, margin);
    }

    // 증거금 인출
    //   청약증거금 잔고보다 큰 금액을 차감하려는 경우, 데이터 정합성 오류로 판단
    //   ex) 증거금 100만원인데, postMargin(1500000) 호출 ->
    private void withdrawMargin(long amount) {
        requirePositive(amount);
        if (marginBalance < amount) {
            throw new IllegalStateException("청약증거금보다 큰 금액을 차감할 수 없습니다. accountId=" + id);
        }
        marginBalance -= amount;
    }

    // 금액이 양수인지 검사한다.
    private static void requirePositive(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("금액은 1 이상이어야 합니다. amount=" + amount);
        }
    }
}
