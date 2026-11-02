package com.gongmoju.account.application.margin;

import com.gongmoju.account.domain.account.Account;
import com.gongmoju.account.domain.account.AccountRepository;
import com.gongmoju.account.domain.event.ProcessedEventRecorder;
import com.gongmoju.account.domain.ledger.LedgerEntry;
import com.gongmoju.account.domain.ledger.LedgerEntryRepository;
import com.gongmoju.account.domain.ledger.LedgerEntryType;
import com.gongmoju.account.domain.margin.Margin;
import com.gongmoju.account.domain.margin.MarginEventPublisher;
import com.gongmoju.account.domain.margin.MarginRepository;
import com.gongmoju.account.domain.margin.MarginReserved;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 증거금 인출 처리한다. (margin.reserve-requested)
 * <p>
 * 계좌 락 → 소유권 → 기존 margin → 선행 반환 기록 → 잔액 → 인출 → margin → 원장 → 응답 Outbox → 처리 기록을 한 트랜잭션에서 처리한다
 */
@Service
@RequiredArgsConstructor
public class MarginReserveService {

    static final String CONSUMER_NAME = "account-margin-reserve";
    static final String TOPIC = "margin.reserve-requested";

    private final AccountRepository accountRepository;
    private final MarginRepository marginRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final MarginEventPublisher marginEventPublisher;
    private final ProcessedEventRecorder processedEventRecorder;

    /**
     * 증거금 인출을 처리한다.
     *
     * @param command 인출에 필요한 정보 (margin.reserve-requested 요청 본문)
     */
    @Transactional
    public void reserve(ReserveMarginCommand command) {
        // TODO(3단계): processed_event로 같은 eventId 재수신이면 아무것도 하지 않고 끝낸다
        // eventid 먼저 조회만으로 동시 중복 처리 방지되지 않음. unique 제약조건, 업무 처리, 기록 저장 동일 트랜잭션도 필요함.

        // 계좌 락: 기존 margin 조회보다 먼저 건다.
        // 같은 청약 요청이 동시에 와도 한줄씩 처리되어 두 번째 요청이 첫번째 요청으로 만들어진 margin을 조회하게 된다.
        Account account = accountRepository.findByIdForUpdate(command.accountId())
                .filter(found -> found.getUser().getId().equals(command.userId()))
                // TODO(2단계): 계좌 없음, 소유자 다름 둘 다 margin.reserve-failed(ACCOUNT_NOT_FOUND)를 저장하고 커밋 (예외로 끝내지 않음)
                .orElseThrow(() -> new IllegalStateException("미구현(2단계): 계좌 없음 또는 소유자 불일치. subscriptionId=" + command.subscriptionId()));

        // TODO(3단계): 기존 margin 확인. PENDING이면 margin.reserved 재발행, POSTED/VOIDED면 로그만, amount나 accountId가 다르면 DLT

        // TODO 4단계 (margin_void_intent): 선행 반환 기록이 있으면 자금을 움직이지 않고 로그만 남긴다 (E-16~E-19)
        //   미지원 위험: 반환 요청(TIMEOUT)이 인출 요청보다 먼저 처리되면, 청약은 CANCELLED인데 뒤늦게 온 인출이 성공해
        //   청약증거금이 묶인 채 남는다 (반환해 줄 이벤트가 다시 오지 않음)

        // TODO(2단계): 잔액 부족이면 margin.reserve-failed(INSUFFICIENT_BALANCE)를 저장하고 커밋. 지금은 예외가 나서 롤백된다

        // 인출
        account.reserveMargin(command.amount());

        // todo: save() 호출시 createdat이 정해진다고 단정 불가. jpa 영속화 시점에 따라 save 직후 createdat이 null일 수도 있다.-> marginreserved.from(margin)에서 확인해야 한다.

        // 증거금 상태를 관리하는 margin 저장
        // 원장이 margin_id를 참조하므로 margin을 먼저 저장한다.
        // 저장할 때 margin_id와 created_at(reservedAt)이 정해진다.
        Margin margin = marginRepository.save(
                Margin.withdraw(account, command.subscriptionId(), command.ipoId(), command.amount()));

        // 원장 생성, 저장: 인출 후 변경된 계좌 잔액 기록. 원장 생성 시 거래 후 잔고를 계좌에서 읽기 때문
        ledgerEntryRepository.save(
                LedgerEntry.ofMargin(
                account, LedgerEntryType.MARGIN_PENDING, command.amount(), margin
                ));

        // outbox 저장: 증거금 인출 성공 결과 객체를 생성하고 outbox 저장을 요청한다.
        marginEventPublisher.publishReserved(MarginReserved.from(margin));

        // processed_event 테이블에 처리 완료한 이벤트 식별 정보를 기록해 중복 처리를 방지한다.
        processedEventRecorder.record(command.eventId(), CONSUMER_NAME, TOPIC);
    }
}
