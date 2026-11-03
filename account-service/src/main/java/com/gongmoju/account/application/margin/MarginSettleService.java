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
import com.gongmoju.account.domain.margin.MarginSettled;
import com.gongmoju.account.global.exception.InvalidEventException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * 배정 결과(allocation.result)에 따라 청약증거금을 정산하고 잔여 금액을 환불한다.
 * <p>
 * 0주인 배정 시 증거금 전액을 예수금으로 반환한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarginSettleService {

    public static final String CONSUMER_NAME = "account-allocation-result";
    public static final String TOPIC = "allocation.result";

    private final AccountRepository accountRepository;
    private final MarginRepository marginRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final MarginEventPublisher marginEventPublisher;
    private final ProcessedEventRecorder processedEventRecorder;

    @Transactional
    public void settle(SettleMarginCommand command) {

        // 1. processed_event: 같은 eventId가 처리된 것 있다면 중복 정산하지 않고 종료
        if (!processedEventRecorder.insertIfAbsent(command.eventId(), CONSUMER_NAME, TOPIC)) {
            log.info("[증거금 정산] 중복 수신, 건너뜀. eventId={}, subscriptionId={}",
                    command.eventId(), command.subscriptionId());
            return;
        }

        log.debug("[증거금 정산] 시작. eventId={}, subscriptionId={}, accountId={}, allocatedAmount={}",
                command.eventId(), command.subscriptionId(), command.accountId(), command.allocatedAmount());

        // 2. 계좌 락: 같은 계좌의 인출, 반환, 정산이 순서대로 처리된다
        //   - 배정된 청약은 margin이 있어야 하므로 계좌가 없으면 값이 잘못된 것이라 DLT
        Account account = accountRepository.findByIdForUpdate(command.accountId())
                .orElseThrow(() -> invalid(command, "계좌가 없습니다."));

        // 3. margin 확인: 없음, 계좌, 공모주 불일치, 음수나 증거금보다 큰 배정 대금은 돈을 옮기지 않고 DLT
        Margin margin = marginRepository.findBySubscriptionId(command.subscriptionId())
                .orElseThrow(() -> invalid(command, "증거금이 없습니다."));

        if (!margin.getAccount().getId().equals(command.accountId())) {
            throw invalid(command, "증거금 계좌와 요청 계좌가 다릅니다. margin accountId=" + margin.getAccount().getId());
        }
        if (!margin.getIpoId().equals(command.ipoId())) {
            throw invalid(command, "증거금 공모주와 요청 공모주가 다릅니다. margin ipoId=" + margin.getIpoId());
        }
        if (command.allocatedAmount() < 0) {
            throw invalid(command, "배정 대금이 음수입니다.");
        }
        if (command.allocatedAmount() > margin.getAmount()) {
            throw invalid(command, "배정 대금이 증거금보다 큽니다. amount=" + margin.getAmount());
        }

        // 4. margin 상태로 분기
        switch (margin.getStatus()) {
            case PENDING -> {
                // 4-0. 계좌 청약증거금이 이 margin보다 적으면 잔고와 margin이 어긋난 것이라 재시도해도 같으므로 DLT
                if (account.getMarginBalance() < margin.getAmount()) {
                    throw invalid(command, "계좌 청약증거금이 증거금보다 적습니다. marginBalance=" + account.getMarginBalance());
                }
                if (command.allocatedAmount() == 0) {
                    voidZero(command, account, margin);
                } else {
                    post(command, account, margin);
                }
            }
            case POSTED -> {
                // 4-3. 이미 정산됨: 같은 배정 대금이면 돈은 안 옮기고 처음 응답을 다시 보낸다
                if (!Objects.equals(margin.getPostedAmount(), command.allocatedAmount())) {
                    throw invalid(command, "이미 다른 배정 대금으로 정산됐습니다. postedAmount=" + margin.getPostedAmount());
                }
                log.info("[증거금 정산] 재요청, margin.settled 재발행. eventId={}, subscriptionId={}, marginId={}, postedAmount={}",
                        command.eventId(), command.subscriptionId(), margin.getId(), margin.getPostedAmount());
                marginEventPublisher.publishSettled(settled(margin, margin.getPostedAmount()));
            }
            case VOIDED -> {
                // 4-4. 이미 반환됨: 0주 재요청이면 처음 응답을 다시 보내고, 배정이 있으면 돈이 없으므로 DLT
                if (command.allocatedAmount() != 0) {
                    throw invalid(command, "이미 반환된 증거금에 배정 대금이 있습니다.");
                }
                log.info("[증거금 정산] 0주 재요청, margin.settled 재발행. eventId={}, subscriptionId={}, marginId={}",
                        command.eventId(), command.subscriptionId(), margin.getId());
                marginEventPublisher.publishSettled(settled(margin, 0));
            }
            // 상태가 추가됐는데 분기가 없으면 응답 없이 처리 기록만 남으므로 막는다
            default -> throw invalid(command, "처리할 수 없는 증거금 상태입니다. status=" + margin.getStatus());
        }
    }

    // 4-1. 0주: 정산이 아니라 반환 (VOIDED, MARGIN_VOID). 응답은 margin.settled(0, 전액)
    private void voidZero(SettleMarginCommand command, Account account, Margin margin) {
        account.voidMargin(margin.getAmount());
        margin.voidPending();

        ledgerEntryRepository.save(
                LedgerEntry.ofMargin(account, LedgerEntryType.MARGIN_VOID, margin.getAmount(), margin));

        marginEventPublisher.publishSettled(settled(margin, 0));

        log.info("[증거금 정산] 0주 반환 저장 (커밋 전). eventId={}, subscriptionId={}, accountId={}, marginId={}, amount={}",
                command.eventId(), command.subscriptionId(), account.getId(), margin.getId(), margin.getAmount());
    }

    // 4-2. 배정 있음: 배정 대금 차감(MARGIN_POST), 나머지 환불(REFUND). 환불액이 0이면 환불 원장은 남기지 않는다
    private void post(SettleMarginCommand command, Account account, Margin margin) {
        long postedAmount = command.allocatedAmount();
        long refundAmount = margin.getAmount() - postedAmount;

        // 원장은 거래 후 잔액을 계좌에서 읽으므로 잔고 이동마다 바로 만든다
        account.postMargin(postedAmount);
        margin.postPending(postedAmount);
        ledgerEntryRepository.save(
                LedgerEntry.ofMargin(account, LedgerEntryType.MARGIN_POST, postedAmount, margin));

        if (refundAmount > 0) {
            account.refundMargin(refundAmount);
            ledgerEntryRepository.save(
                    LedgerEntry.ofMargin(account, LedgerEntryType.REFUND, refundAmount, margin));
        }

        marginEventPublisher.publishSettled(settled(margin, postedAmount));

        log.info("[증거금 정산] 정산 저장 (커밋 전). eventId={}, subscriptionId={}, accountId={}, marginId={}, postedAmount={}, refundAmount={}",
                command.eventId(), command.subscriptionId(), account.getId(), margin.getId(), postedAmount, refundAmount);
    }

    // 응답: postedAmount + refundAmount = margin.amount
    private MarginSettled settled(Margin margin, long postedAmount) {
        return new MarginSettled(margin.getSubscriptionId(), margin.getId(), margin.getAccount().getId(),
                postedAmount, margin.getAmount() - postedAmount);
    }

    // 값이 잘못된 요청: 예외로 롤백(processed_event도 남지 않음), 재시도 없이 DLT
    private InvalidEventException invalid(SettleMarginCommand command, String reason) {
        log.error("[증거금 정산] DLT 대상. {} eventId={}, subscriptionId={}, accountId={}, ipoId={}, allocatedAmount={}",
                reason, command.eventId(), command.subscriptionId(), command.accountId(), command.ipoId(), command.allocatedAmount());
        return new InvalidEventException(reason + " subscriptionId=" + command.subscriptionId());
    }
}
