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
import com.gongmoju.account.domain.margin.MarginVoidIntent;
import com.gongmoju.account.domain.margin.MarginVoidIntentRepository;
import com.gongmoju.account.domain.margin.MarginVoided;
import com.gongmoju.account.global.exception.InvalidEventException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 증거금 반환 처리한다. (margin.void-requested)
 * <p>
 * 이벤트 처리 기록 → 계좌 락 → margin 조회, 계좌 확인 → void intent 기록 → 상태 분기 → 원장 → 응답 Outbox를 한 트랜잭션에서 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarginVoidService {

    public static final String CONSUMER_NAME = "account-margin-void";
    public static final String TOPIC = "margin.void-requested";

    private final AccountRepository accountRepository;
    private final MarginRepository marginRepository;
    private final MarginVoidIntentRepository marginVoidIntentRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final MarginEventPublisher marginEventPublisher;
    private final ProcessedEventRecorder processedEventRecorder;

    @Transactional
    public void voidMargin(VoidMarginCommand command) {

        // 1. processed_event: 같은 eventId 재수신이면 끝낸다 (인출과 같은 방식)
        if (!processedEventRecorder.insertIfAbsent(command.eventId(), CONSUMER_NAME, TOPIC)) {
            log.info("[증거금 반환] 중복 수신, 건너뜀. eventId={}, subscriptionId={}",
                    command.eventId(), command.subscriptionId());
            return;
        }

        log.debug("[증거금 반환] 시작. eventId={}, subscriptionId={}, accountId={}, reason={}",
                command.eventId(), command.subscriptionId(), command.accountId(), command.reason());

        // 2. 계좌 락: margin 조회보다 먼저 걸어서, 같은 계좌의 인출, 반환이 순서대로 처리된다.
        //   - 계좌가 없으면 옮긴 돈도 없어서 amount 0으로 응답한다 (E-20). FK 때문에 반환 기록도 남길 수 없다
        Account account = accountRepository.findByIdForUpdate(command.accountId()).orElse(null);
        if (account == null) {

            // 요청 계좌는 없는데 margin이 있으면, margin의 계좌와 다른 것이므로 계좌 불일치가 우선, DLT로 전송 (E-20)
            //   - 락을 걸 계좌가 없어서, 처리 중인 다른 계좌의 인출과 충돌 가능
            if (marginRepository.findBySubscriptionId(command.subscriptionId()).isPresent()) {

                log.error("[증거금 반환] 요청 계좌는 없는데 margin이 있어 DLT 대상. eventId={}, subscriptionId={}, accountId={}",
                        command.eventId(), command.subscriptionId(), command.accountId());

                throw new InvalidEventException(
                        "증거금 계좌와 요청 계좌가 다릅니다. subscriptionId=" + command.subscriptionId()
                );
            }
            log.warn("[증거금 반환] 계좌 없음, amount 0으로 응답. eventId={}, subscriptionId={}, accountId={}",
                    command.eventId(), command.subscriptionId(), command.accountId());

            marginEventPublisher.publishVoided( // 0원으로 이벤트 발행
                    new MarginVoided(
                            command.subscriptionId(), null, command.accountId(), 0
                    ));
            return;
        }

        log.debug("[증거금 반환] 계좌 락 획득. accountId={}, cashBalance={}, marginBalance={}",
                account.getId(), account.getCashBalance(), account.getMarginBalance());

        // 3. margin 조회: 응답 금액은 margin 상태가 정한다
        Optional<Margin> found = marginRepository.findBySubscriptionId(command.subscriptionId());

        // 3-1. 다른 계좌의 margin이면 요청이 잘못된 것이라 DLT (예외로 롤백, processed_event도 남지 않음)
        if (found.isPresent() && !found.get().getAccount().getId().equals(command.accountId())) {
            log.error("[증거금 반환] margin 계좌와 요청 계좌가 달라 DLT 대상. eventId={}, subscriptionId={}, marginId={}, "
                            + "margin accountId={}, 요청 accountId={}",
                    command.eventId(), command.subscriptionId(), found.get().getId(),
                    found.get().getAccount().getId(), command.accountId());
            throw new InvalidEventException("증거금 계좌와 요청 계좌가 다릅니다. subscriptionId=" + command.subscriptionId());
        }

        // 4. 반환 기록: 없으면 남긴다. 뒤늦은 인출 요청을 막는 용도이고 응답은 정하지 않는다 (E-16)
        if (!marginVoidIntentRepository.existsBySubscriptionId(command.subscriptionId())) {
            marginVoidIntentRepository.save(
                    MarginVoidIntent.record(account, command.subscriptionId(), command.reason()));
            log.debug("[증거금 반환] 반환 기록 저장. subscriptionId={}, reason={}", command.subscriptionId(), command.reason());
        }

        // 5. margin 상태로 분기
        if (found.isEmpty()) {
            // 5-1. margin 없음: 아직 인출 전이라 돌려줄 돈이 없다
            log.info("[증거금 반환] margin 없음, amount 0으로 응답. eventId={}, subscriptionId={}, accountId={}",
                    command.eventId(), command.subscriptionId(), command.accountId());
            marginEventPublisher.publishVoided(
                    new MarginVoided(command.subscriptionId(), null, command.accountId(), 0));
            return;
        }

        Margin margin = found.get();
        switch (margin.getStatus()) {
            case PENDING -> voidPending(command, account, margin);
            case VOIDED -> {
                // 5-3. 이미 반환됨: 돈은 안 옮기고 처음 응답과 같은 금액을 다시 보낸다 (eventId는 새로)
                log.info("[증거금 반환] 재요청, margin.voided 재발행. eventId={}, subscriptionId={}, marginId={}, amount={}",
                        command.eventId(), command.subscriptionId(), margin.getId(), margin.getAmount());
                marginEventPublisher.publishVoided(voided(margin));
            }
            case POSTED -> {
                // 5-4. 이미 정산됨: 반환하면 같은 계좌의 다른 청약 증거금이 빠지므로 DLT
                log.error("[증거금 반환] 이미 정산된 증거금이라 DLT 대상. eventId={}, subscriptionId={}, marginId={}",
                        command.eventId(), command.subscriptionId(), margin.getId());
                throw new InvalidEventException("이미 정산된 증거금입니다. subscriptionId=" + command.subscriptionId());
            }
        }
    }

    // 5-2. PENDING: 청약증거금 → 예수금, margin VOIDED, 원장, 응답 Outbox
    private void voidPending(VoidMarginCommand command, Account account, Margin margin) {
        account.voidMargin(margin.getAmount());
        margin.voidPending();

        // 원장은 거래 후 잔액를 계좌에서 읽으므로 잔고 이동 뒤에 만든다
        ledgerEntryRepository.save(
                LedgerEntry.ofMargin(account, LedgerEntryType.MARGIN_VOID, margin.getAmount(), margin));

        marginEventPublisher.publishVoided(voided(margin));

        log.info("[증거금 반환] 반환 저장 (커밋 전). eventId={}, subscriptionId={}, accountId={}, marginId={}, amount={}",
                command.eventId(), command.subscriptionId(), account.getId(), margin.getId(), margin.getAmount());
    }

    // margin이 있을 때 응답: 처음 인출한 금액 전액
    private MarginVoided voided(Margin margin) {
        return new MarginVoided(margin.getSubscriptionId(), margin.getId(), margin.getAccount().getId(), margin.getAmount());
    }
}
