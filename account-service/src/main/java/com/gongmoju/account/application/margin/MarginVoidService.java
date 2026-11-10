package com.gongmoju.account.application.margin;

import com.gongmoju.account.domain.account.Account;
import com.gongmoju.account.domain.account.AccountRepository;
import com.gongmoju.common.event.ProcessedEventRepository;
import com.gongmoju.account.domain.ledger.LedgerEntryRepository;
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
    private final ProcessedEventRepository processedEventRepository;

    @Transactional
    public void voidMargin(VoidMarginCommand command) {

        // 1. processed_event: 같은 eventId 재수신이면 처리 없이 종료한다 (인출과 동일한 방식)
        if (!processedEventRepository.insertIfAbsent(command.eventId(), CONSUMER_NAME, TOPIC)) {
            log.info("[증거금 반환] 중복 수신, 건너뜀. eventId={}, subscriptionId={}",
                    command.eventId(), command.subscriptionId());
            return;
        }

        log.debug("[증거금 반환] 시작. eventId={}, subscriptionId={}, accountId={}, reason={}",
                command.eventId(), command.subscriptionId(), command.accountId(), command.reason());

        // 2. 계좌 락: margin 조회보다 먼저 획득해서, 같은 계좌의 인출, 반환이 순서대로 처리된다.
        //   - 계좌가 없는 경우 (E-20): 모든 요청을 DLT로 보내면 청약이 COMPENSATING에 잔류해 공모주 마감이 중단됨
        //     - margin이 있으면: 요청 계좌와 margin의 계좌가 다른 데이터 불일치라 DLT
        //     - margin이 없으면: 0원으로 반환 완료를 응답해 청약이 CANCELLED로 종료되도록 한다.
        //       인출, 반환 요청 모두 청약의 account_id를 사용하므로, 존재하지 않는 계좌로 요청된 인출은
        //       ACCOUNT_NOT_FOUND로 거절되어 예수금 차감이 발생하지 않는다.
        //       계좌가 없으면 FK 제약으로 반환 기록(margin_void_intent)은 생략한다.
        Account account = accountRepository.findByIdForUpdate(command.accountId()).orElse(null);
        if (account == null) {
            if (marginRepository.findBySubscriptionId(command.subscriptionId()).isPresent()) {
                log.error("[증거금 반환] 요청 계좌는 없는데 margin이 있어 DLT 대상. eventId={}, subscriptionId={}, accountId={}",
                        command.eventId(), command.subscriptionId(), command.accountId());
                throw new InvalidEventException("증거금 계좌와 요청 계좌가 다릅니다. subscriptionId=" + command.subscriptionId());
            }
            log.warn("[증거금 반환] 계좌 없음, amount 0으로 응답. eventId={}, subscriptionId={}, accountId={}",
                    command.eventId(), command.subscriptionId(), command.accountId());
            marginEventPublisher.publishVoided(zeroVoided(command));
            return;
        }

        log.debug("[증거금 반환] 계좌 락 획득. accountId={}, cashBalance={}, marginBalance={}",
                account.getId(), account.getCashBalance(), account.getMarginBalance());

        // 3. margin 조회: 응답 금액은 margin 상태로 결정한다
        Optional<Margin> found = marginRepository.findBySubscriptionId(command.subscriptionId());

        // 3-1. margin 계좌와 요청 계좌가 불일치하면 DLT (예외로 롤백, processed_event도 저장되지 않음)
        if (found.isPresent() && !found.get().getAccount().getId().equals(command.accountId())) {
            log.error("[증거금 반환] margin 계좌와 요청 계좌가 달라 DLT 대상. eventId={}, subscriptionId={}, marginId={}, "
                            + "margin accountId={}, 요청 accountId={}",
                    command.eventId(), command.subscriptionId(), found.get().getId(),
                    found.get().getAccount().getId(), command.accountId());
            throw new InvalidEventException("증거금 계좌와 요청 계좌가 다릅니다. subscriptionId=" + command.subscriptionId());
        }

        // 4. 반환 기록: 없으면 저장한다. 뒤늦은 인출 요청을 차단하는 용도이고 응답 금액은 결정하지 않는다 (E-16)
        if (!marginVoidIntentRepository.existsBySubscriptionId(command.subscriptionId())) {
            marginVoidIntentRepository.save(
                    MarginVoidIntent.record(account, command.subscriptionId(), command.reason()));
            log.debug("[증거금 반환] 반환 기록 저장. subscriptionId={}, reason={}", command.subscriptionId(), command.reason());
        }

        // 5. margin 상태로 분기
        if (found.isEmpty()) {
            // 5-1. margin 없음: 아직 인출 전이라 반환할 청약증거금이 없다
            log.info("[증거금 반환] margin 없음, amount 0으로 응답. eventId={}, subscriptionId={}, accountId={}",
                    command.eventId(), command.subscriptionId(), command.accountId());
            marginEventPublisher.publishVoided(zeroVoided(command));
            return;
        }

        Margin margin = found.get();
        switch (margin.getStatus()) {
            case PENDING -> voidPending(command, account, margin);
            case VOIDED -> {
                // 5-3. 이미 반환됨: 잔고 변경 없이 처음 응답과 동일한 금액으로 재발행한다 (eventId는 새로 생성)
                log.info("[증거금 반환] 재요청, margin.voided 재발행. eventId={}, subscriptionId={}, marginId={}, amount={}",
                        command.eventId(), command.subscriptionId(), margin.getId(), margin.getAmount());
                marginEventPublisher.publishVoided(voided(margin));
            }
            case POSTED -> {
                // 5-4. 이미 정산됨: 반환하면 같은 계좌의 다른 청약 증거금이 차감되므로 DLT
                log.error("[증거금 반환] 이미 정산된 증거금이라 DLT 대상. eventId={}, subscriptionId={}, marginId={}",
                        command.eventId(), command.subscriptionId(), margin.getId());
                throw new InvalidEventException(
                        "이미 정산된 증거금입니다. subscriptionId=" + command.subscriptionId());
            }

            // margin이 존재하지 않는 상태값인 경우도, DLT 처리한다. (분기에 해당하지 않으면 processed_event만 저장되기 때문)
            default -> {
                log.error("[증거금 반환] 처리할 수 없는 증거금 상태라 DLT 대상. eventId={}, subscriptionId={}, marginId={}, status={}",
                        command.eventId(), command.subscriptionId(), margin.getId(), margin.getStatus());
                throw new InvalidEventException(
                        "처리할 수 없는 증거금 상태입니다. status=" + margin.getStatus());
            }
        }
    }

    // 5-2. PENDING: 청약증거금 차감, 예수금 증가 (MARGIN_VOID 원장), margin VOIDED, 응답 Outbox 저장
    private void voidPending(VoidMarginCommand command, Account account, Margin margin) {
        // 원장은 거래 후 잔고를 계좌에서 조회하므로 잔고 변경 후에 만든다 (계좌가 잔고를 변경한 후 원장을 만들어 반환한다)
        ledgerEntryRepository.save(account.voidMargin(margin.getAmount(), margin));
        margin.voidPending();

        marginEventPublisher.publishVoided(voided(margin));

        log.info("[증거금 반환] 반환 저장 (커밋 전). eventId={}, subscriptionId={}, accountId={}, marginId={}, amount={}",
                command.eventId(), command.subscriptionId(), account.getId(), margin.getId(), margin.getAmount());
    }

    // margin이 있을 때 응답: 처음 인출한 금액 전액
    private MarginVoided voided(Margin margin) {
        return new MarginVoided(margin.getSubscriptionId(), margin.getId(), margin.getAccount().getId(), margin.getAmount());
    }

    // margin이 없을 때 응답: 인출한 금액이 없어 0원. marginId 없이 요청 계좌로 발행한다 (계좌 없음, 인출 전 두 경우가 함께 사용한다)
    private MarginVoided zeroVoided(VoidMarginCommand command) {
        return new MarginVoided(command.subscriptionId(), null, command.accountId(), 0);
    }
}
