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
import com.gongmoju.account.domain.margin.MarginReserveFailReason;
import com.gongmoju.account.domain.margin.MarginReserveFailed;
import com.gongmoju.account.domain.margin.MarginReserved;
import com.gongmoju.account.domain.margin.MarginStatus;
import com.gongmoju.account.domain.margin.MarginVoidIntentRepository;
import com.gongmoju.account.global.exception.InvalidEventException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 증거금 인출 처리한다. (margin.reserve-requested)
 * <p>
 * 처리 기록 → 계좌 락 → 소유권 → 기존 margin → 선행 반환 기록 → 잔액 → 인출 → margin → 원장 → 응답 Outbox를 한 트랜잭션에서 처리한다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarginReserveService {

    static final String CONSUMER_NAME = "account-margin-reserve";
    static final String TOPIC = "margin.reserve-requested";

    private final AccountRepository accountRepository;
    private final MarginRepository marginRepository;
    private final MarginVoidIntentRepository marginVoidIntentRepository;
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
        // 1. 처리 기록: 같은 eventId를 다시 받았으면 아무것도 하지 않고 끝낸다 (processed_event)
        //   - 조회 후 저장이 아니라 삽입 한 번으로 판단해서, 같은 eventId가 동시에 와도 하나만 통과한다
        //   - 뒤 단계가 예외로 롤백되면 이 기록도 같이 롤백되어, 재시도 때 다시 처리된다
        if (!processedEventRecorder.insertIfAbsent(command.eventId(), CONSUMER_NAME, TOPIC)) {

            log.info("[증거금 인출] 중복 수신, 건너뜀. eventId={}, subscriptionId={}",
                    command.eventId(), command.subscriptionId());
            return;
        }

        // 2. 계좌 락: 기존 margin 조회보다 먼저 건다
        //   - 같은 청약 요청이 동시에 와도 한 줄씩 처리되어, 두 번째 요청은 첫 번째 요청이 만든 margin을 조회하게 된다
        //   - 계좌가 없으면 잠글 행이 없어서 바로 거절한다
        log.debug("[증거금 인출] 시작. eventId={}, subscriptionId={}, accountId={}, userId={}, amount={}",
                command.eventId(), command.subscriptionId(), command.accountId(), command.userId(), command.amount());
        Account account = accountRepository.findByIdForUpdate(command.accountId()).orElse(null);

        // 3. 소유권: 계좌 없음, 소유자 다름 둘 다 ACCOUNT_NOT_FOUND로 응답한다
        //   - 타인 계좌의 존재 여부를 숨긴다. 실제 원인은 로그로 구분
        if (account == null) {
            log.info("[증거금 인출] 거절 ACCOUNT_NOT_FOUND(계좌 없음). eventId={}, subscriptionId={}, accountId={}",
                    command.eventId(), command.subscriptionId(), command.accountId());
            reject(command, MarginReserveFailReason.ACCOUNT_NOT_FOUND);
            return;
        }

        if (!account.isOwnedBy(command.userId())) {
            log.warn("[증거금 인출] 거절 ACCOUNT_NOT_FOUND(소유자 다름). eventId={}, subscriptionId={}, accountId={}, userId={}",
                    command.eventId(), command.subscriptionId(), command.accountId(), command.userId());
            reject(command, MarginReserveFailReason.ACCOUNT_NOT_FOUND);
            return;
        }

        log.debug("[증거금 인출] 계좌 락 획득. accountId={}, cashBalance={}, marginBalance={}",
                account.getId(), account.getCashBalance(), account.getMarginBalance());

        // 4. 기존 margin: 같은 청약의 margin이 있는지 확인한다
        Optional<Margin> existingMargin =
                marginRepository.findBySubscriptionId(command.subscriptionId());

        if (existingMargin.isPresent()) {
            handleExistingMargin(command, existingMargin.get());
            return;
        }

        // 5. 선행 반환 기록: 있으면 자금을 움직이지 않고 응답 이벤트 없이 로그만 남긴다 (E-17)
        //   - 반환 요청(TIMEOUT 등)이 인출 요청보다 먼저 처리됐으면 청약은 이미 CANCELLED다
        //   - 옮긴 돈이 없으니 예외 없이 처리 기록만 커밋한다
        //   - 잔액보다 먼저 봐야 이미 취소된 청약에 reserve-failed가 나가지 않는다 (E-19)
        // TODO(반환 Consumer 이슈): margin_void_intent 삽입. 그 전까지는 기록이 생기지 않아 이 분기를 타지 않는다
        if (marginVoidIntentRepository.existsBySubscriptionId(command.subscriptionId())) {
            log.warn("[증거금 인출] 선행 반환 기록이 있어 인출 안 함, 응답 없음. eventId={}, subscriptionId={}, accountId={}",
                    command.eventId(), command.subscriptionId(), command.accountId());
            return;
        }

        // 6. 잔액: 돈을 옮기기 전에 먼저 검사해서 거절을 커밋한다
        //   - reserveMargin()의 검사는 안전장치로 남겨 둔다
        if (!account.hasCashBalance(command.amount())) {
            log.info("[증거금 인출] 거절 INSUFFICIENT_BALANCE(예수금 부족). eventId={}, subscriptionId={}, accountId={}, amount={}, cashBalance={}",
                    command.eventId(), command.subscriptionId(), command.accountId(), command.amount(), account.getCashBalance());
            reject(command, MarginReserveFailReason.INSUFFICIENT_BALANCE);
            return;
        }

        // 7. 인출: 예수금 → 청약증거금
        account.reserveMargin(command.amount());
        log.debug("[증거금 인출] 잔고 이동. accountId={}, cashBalance={}, marginBalance={}",
                account.getId(), account.getCashBalance(), account.getMarginBalance());

        // TODO: save() 호출 시 createdAt이 정해진다고 단정할 수 없다
        //   - JPA 영속화 시점에 따라 save 직후 createdAt이 null일 수도 있다 → MarginReserved.from(margin)에서 확인해야 한다

        // 8. margin 저장: 증거금 상태를 관리한다
        //   - 원장이 margin_id를 참조하므로 margin을 먼저 저장한다
        //   - 저장할 때 margin_id와 created_at(reservedAt)이 정해진다
        Margin margin = marginRepository.save(
                Margin.withdraw(account, command.subscriptionId(), command.ipoId(), command.amount()));
        log.debug("[증거금 인출] margin 저장. marginId={}, status={}, createdAt={}", margin.getId(), margin.getStatus(), margin.getCreatedAt());

        // 9. 원장: 인출 후 변경된 계좌 잔액을 기록한다
        //   - 원장 생성 시 거래 후 잔고를 계좌에서 읽기 때문에 인출 뒤에 만든다
        ledgerEntryRepository.save(
                LedgerEntry.ofMargin(
                        account, LedgerEntryType.MARGIN_PENDING, command.amount(), margin
                ));

        // 10. 응답 Outbox: 인출 성공 결과 객체를 만들고 outbox 저장을 요청한다
        marginEventPublisher.publishReserved(MarginReserved.from(margin));

        log.info("[증거금 인출] 완료. eventId={}, subscriptionId={}, accountId={}, marginId={}, amount={}",
                command.eventId(), command.subscriptionId(), command.accountId(), margin.getId(), command.amount());
    }

    // 같은 청약의 margin이 이미 있을 때 (재요청). 돈은 움직이지 않는다
    private void handleExistingMargin(ReserveMarginCommand command, Margin margin) {
        // 4-1. 요청과 비교: accountId, amount, ipoId가 같은지 검사
        if (!margin.matches(command.accountId(), command.amount(), command.ipoId())) {

            // 4-2. 다르면 DLT: 예외로 롤백해서 processed_event도 남기지 않는다
            //   - DLT 전송은 4단계 Consumer 설정
            log.error("[증거금 인출] 기존 증거금과 요청이 달라 DLT 대상. eventId={}, subscriptionId={}, marginId={}, "
                            + "기존(accountId={}, amount={}, ipoId={}), 요청(accountId={}, amount={}, ipoId={})",
                    command.eventId(), command.subscriptionId(), margin.getId(),
                    margin.getAccount().getId(), margin.getAmount(), margin.getIpoId(),
                    command.accountId(), command.amount(), command.ipoId());

            throw new InvalidEventException("기존 증거금과 요청이 다릅니다. subscriptionId=" + command.subscriptionId());
        }

        // margin 상태에 따라 분기
        MarginStatus status = margin.getStatus();

        if (status == MarginStatus.PENDING) {
            // 4-3. PENDING: 기존 margin 결과 재발행 (최초 reservedAt 유지, eventId는 새로)
            marginEventPublisher.publishReserved(MarginReserved.from(margin));

            log.info("[증거금 인출] 재요청, margin.reserved 재발행. eventId={}, subscriptionId={}, marginId={}",
                    command.eventId(), command.subscriptionId(), margin.getId());

        } else if (status == MarginStatus.POSTED || status == MarginStatus.VOIDED) {
            // 4-4. POSTED/VOIDED: 로그만 남긴다
            log.info("[증거금 인출] 재요청, 이미 {}라 응답 안 함. eventId={}, subscriptionId={}, marginId={}",
                    status, command.eventId(), command.subscriptionId(), margin.getId());
        }
    }

    /**
     * 증거금 인출 거절 결과를 Outbox에 저장한다.
     *
     * @param command 인출 요청 정보
     * @param reason  인출 거절 사유
     */
    private void reject(ReserveMarginCommand command, MarginReserveFailReason reason) {
        marginEventPublisher.publishReserveFailed(
                new MarginReserveFailed(
                        command.subscriptionId(), command.accountId(), reason
                ));
    }
}
