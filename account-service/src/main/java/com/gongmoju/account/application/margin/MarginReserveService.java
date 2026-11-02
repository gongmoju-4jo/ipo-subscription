package com.gongmoju.account.application.margin;

import com.gongmoju.account.domain.account.Account;
import com.gongmoju.account.domain.account.AccountRepository;
import com.gongmoju.account.domain.event.ProcessedEventRecorder;
import com.gongmoju.account.domain.ledger.LedgerEntry;
import com.gongmoju.account.domain.ledger.LedgerEntryRepository;
import com.gongmoju.account.domain.ledger.LedgerEntryType;
import com.gongmoju.account.domain.margin.*;
import com.gongmoju.account.infrastructure.messaging.ProcessedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 증거금 인출 처리한다. (margin.reserve-requested)
 * <p>
 * 계좌 락 → 소유권 → 기존 margin → 선행 반환 기록 → 잔액 → 인출 → margin → 원장 → 응답 Outbox → 처리 기록을 한 트랜잭션에서 처리한다
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
        // 1. TODO(3단계): processed_event로 같은 eventId 재수신이면 아무것도 하지 않고 끝낸다


        // 2. 계좌 락: 기존 margin 조회보다 먼저 건다.
        //   같은 청약 요청이 동시에 와도 한줄씩 처리되어 두 번째 요청이 첫번째 요청으로 만들어진 margin을 조회하게 된다.
        //   계좌가 없으면 잠글 행이 없어서 바로 거절한다
        log.debug("증거금 인출 시작. eventId={}, subscriptionId={}, accountId={}, userId={}, amount={}",
                command.eventId(), command.subscriptionId(), command.accountId(), command.userId(), command.amount());
        Account account = accountRepository.findByIdForUpdate(command.accountId()).orElse(null);

        // 3. 소유권: 계좌 없음, 소유자 다름 둘 다 ACCOUNT_NOT_FOUND로 응답한다. (타인 계좌의 존재 여부를 숨김). 실제 원인은 로그로 구분
        if (account == null) {
            log.info("증거금 인출 거절: 계좌 없음. subscriptionId={}, accountId={}", command.subscriptionId(), command.accountId());
            reject(command, MarginReserveFailReason.ACCOUNT_NOT_FOUND);
            return;
        }

        if (!account.getUser().getId().equals(command.userId())) {
            log.warn("증거금 인출 거절: 계좌 소유자 다름. subscriptionId={}, accountId={}, userId={}",
                    command.subscriptionId(), command.accountId(), command.userId());
            reject(command, MarginReserveFailReason.ACCOUNT_NOT_FOUND);
            return;
        }

        log.debug("계좌 락 획득, 소유권 확인. accountId={}, cashBalance={}, marginBalance={}",
                account.getId(), account.getCashBalance(), account.getMarginBalance());

        // 4. Margin 객체 존재하는지 확인
        Optional<Margin> existingMargin =
                marginRepository.findBySubscriptionId(command.subscriptionId());

        // 존재하는 경우
//        if (existingMargin.isPresent()) {
//            Margin margin = existingMargin.get();
//
//            // 4-1. accountId, amount, ipoId 일치 여부 검사 (long 타입만 != 사용)
//            if (!command.accountId().equals(margin.getAccount().getId())
//                    || command.amount() != margin.getAmount()
//                    || !command.ipoId().equals(margin.getIpoId())) {
//
//                // 4-2. 불일치하면 DLT
//                // todo
//            }
//
//            MarginStatus status = margin.getStatus();
//            if (status == MarginStatus.PENDING) {
//                // 4-3. PENDING이면 기존 Margin 결과 재발행
//                marginEventPublisher.publishReserved(MarginReserved.from(margin));
//
//            } else if (status == MarginStatus.POSTED || status == MarginStatus.VOIDED) {
//                // 4-4. POSTED/VOIDED면 로그만 기록
//                log.info("이미 처리된 마진입니다. 상태: {}", status);
//            }
//
//            // 4-5. 정상 중복 처리한 eventId를 ProcessedEvent에 저장
//            processedEventRecorder.record(command.eventId(), CONSUMER_NAME, TOPIC);
//
//            return;
//        }

        // 4. 선행 반환 기록:
        //   반환 요청(TIMEOUT 등)이 인출 요청보다 먼저 처리됐으면 청약은 이미 CANCELLED라서
        //   자금을 움직이지 않고 응답 이벤트 없이 로그만 남긴다 (E-17). 옮긴 돈이 없으니 예외 없이 처리 기록만 커밋한다
        //   잔액보다 먼저 봐야 이미 취소된 청약에 reserve-failed가 나가지 않는다 (E-19)
        // TODO(반환 Consumer 이슈): margin_void_intent 삽입. 그 전까지는 기록이 생기지 않아 이 분기를 타지 않는다
        if (marginVoidIntentRepository.existsBySubscriptionId(command.subscriptionId())) {
            log.warn("증거금 인출 안 함: 선행 반환 기록 있음. subscriptionId={}, accountId={}",
                    command.subscriptionId(), command.accountId());
            processedEventRecorder.record(command.eventId(), CONSUMER_NAME, TOPIC);
            return;
        }

        // 5. 잔액: 돈을 옮기기 전에 먼저 검사해서 거절을 커밋한다. reserveMargin()의 검사는 안전장치로 남겨 둔다
        if (!account.hasCashBalance(command.amount())) {
            log.info("증거금 인출 거절: 예수금 부족. subscriptionId={}, accountId={}, amount={}",
                    command.subscriptionId(), command.accountId(), command.amount());
            reject(command, MarginReserveFailReason.INSUFFICIENT_BALANCE);
            return;
        }

        // 6. 인출
        account.reserveMargin(command.amount());
        log.debug("잔고 이동. accountId={}, cashBalance={}, marginBalance={}",
                account.getId(), account.getCashBalance(), account.getMarginBalance());

        // todo: save() 호출시 createdat이 정해진다고 단정 불가. jpa 영속화 시점에 따라 save 직후 createdat이 null일 수도 있다.-> marginreserved.from(margin)에서 확인해야 한다.

        // 7. 증거금 상태를 관리하는 margin 저장
        // 원장이 margin_id를 참조하므로 margin을 먼저 저장한다.
        // 저장할 때 margin_id와 created_at(reservedAt)이 정해진다.
        Margin margin = marginRepository.save(
                Margin.withdraw(account, command.subscriptionId(), command.ipoId(), command.amount()));
        log.debug("margin 저장. marginId={}, status={}, createdAt={}", margin.getId(), margin.getStatus(), margin.getCreatedAt());

        // 8. 원장 생성, 저장: 인출 후 변경된 계좌 잔액 기록. 원장 생성 시 거래 후 잔고를 계좌에서 읽기 때문
        ledgerEntryRepository.save(
                LedgerEntry.ofMargin(
                        account, LedgerEntryType.MARGIN_PENDING, command.amount(), margin
                ));

        // 9. outbox 저장: 증거금 인출 성공 결과 객체를 생성하고 outbox 저장을 요청한다.
        marginEventPublisher.publishReserved(MarginReserved.from(margin));

        // processed_event 테이블에 처리 완료한 이벤트 식별 정보를 기록해 중복 처리를 방지한다.
        processedEventRecorder.record(command.eventId(), CONSUMER_NAME, TOPIC);
        log.info("증거금 인출 완료. subscriptionId={}, accountId={}, marginId={}, amount={}",
                command.subscriptionId(), command.accountId(), margin.getId(), command.amount());
    }

    /**
     * 거절 처리를 한다.
     * <p>
     * 잔액 이동 이전 단계.
     * 예외 나면 롤백, 재시도 후 DLT로 이동한다.
     * Outbox 저장, processedEvent에 저장한다.
     *
     * @param command 기록 저장에 필요한 정보 (margin.reserve-requested 요청 본문)
     * @param reason  거절 사유 enum (계좌를 찾을 수 없음, 예수금 부족)
     */
    private void reject(ReserveMarginCommand command, MarginReserveFailReason reason) {
        marginEventPublisher.publishReserveFailed(
                new MarginReserveFailed(
                        command.subscriptionId(), command.accountId(), reason
                ));

        processedEventRecorder.record(
                command.eventId(), CONSUMER_NAME, TOPIC
        );
    }
}
