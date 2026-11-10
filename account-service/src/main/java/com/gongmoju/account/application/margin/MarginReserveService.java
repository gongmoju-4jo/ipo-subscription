package com.gongmoju.account.application.margin;

import com.gongmoju.account.domain.account.Account;
import com.gongmoju.account.domain.account.AccountRepository;
import com.gongmoju.common.event.ProcessedEventRepository;
import com.gongmoju.account.domain.ledger.LedgerEntryRepository;
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
 * 이벤트 처리 기록 → 계좌 락 → 소유권 → 기존 margin → void intent 기록 → 예수금 검사 → margin → 인출 → 원장 → 응답 Outbox를 한 트랜잭션에서 처리한다
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarginReserveService {

    public static final String CONSUMER_NAME = "account-margin-reserve";
    public static final String TOPIC = "margin.reserve-requested";

    private final AccountRepository accountRepository;
    private final MarginRepository marginRepository;
    private final MarginVoidIntentRepository marginVoidIntentRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final MarginEventPublisher marginEventPublisher;
    private final ProcessedEventRepository processedEventRepository;

    /**
     * 증거금 인출을 처리한다.
     *
     * @param command 인출에 필요한 정보 (margin.reserve-requested 요청 본문)
     */
    @Transactional
    public void reserve(ReserveMarginCommand command) {
        // 1. processed_event로 같은 eventId 재수신이면 처리 없이 종료한다.
        //   - 조회 후 저장이 아니라 삽입 한 번으로 판단해서, 같은 eventId가 동시에 수신되어도 1건만 처리된다.
        //   - 이후 단계가 예외로 롤백되면 이 기록도 같이 롤백되어 재시도 때 다시 처리된다.
        if (!processedEventRepository.insertIfAbsent(command.eventId(), CONSUMER_NAME, TOPIC)) {

            log.info("[증거금 인출] 중복 수신, 건너뜀. eventId={}, subscriptionId={}",
                    command.eventId(), command.subscriptionId());
            return;
        }

        // 2. 계좌 락: 기존 margin 조회보다 먼저 건다.
        //   - 같은 청약 요청이 동시에 수신되어도 순서대로 처리되어, 두 번째 요청은 첫 번째 요청으로 생성된 margin을 조회한다.
        //   - 계좌가 없으면 잠글 행이 없어서 바로 거절한다.
        log.debug("[증거금 인출] 시작. eventId={}, subscriptionId={}, accountId={}, userId={}, amount={}",
                command.eventId(), command.subscriptionId(), command.accountId(), command.userId(), command.amount());

        Account account = accountRepository.findByIdForUpdate(command.accountId()).orElse(null);

        // 3. 소유권: 계좌 없음, 소유자 다름 둘 다 ACCOUNT_NOT_FOUND로 응답한다. (타인 계좌의 존재 여부 숨김). 실제 원인은 로그로 구분
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

        // 4. Margin 객체 존재하는지 확인
        Optional<Margin> existingMargin =
                marginRepository.findBySubscriptionId(command.subscriptionId());

        //   존재하는 경우
        if (existingMargin.isPresent()) {
            handleExistingMargin(command, existingMargin.get());
            return;
        }

        // 5. 선행 반환 기록:
        //   - 반환 요청(TIMEOUT 등)이 인출 요청보다 먼저 처리됐으면 청약은 이미 CANCELLED라서
        //     인출하지 않고(예수금 차감 없음) 응답 이벤트 없이 로그만 기록한다. (E-17). 차감한 금액이 없으므로 예외 없이 처리 기록만 커밋한다
        //   - 예수금 검사보다 먼저 조회해야 이미 취소된 청약에 reserve-failed가 발행되지 않는다. (E-19)
        if (marginVoidIntentRepository.existsBySubscriptionId(command.subscriptionId())) {
            log.warn("[증거금 인출] 선행 반환 기록이 있어 인출 안 함, 응답 없음. eventId={}, subscriptionId={}, accountId={}",
                    command.eventId(), command.subscriptionId(), command.accountId());
            return;
        }

        // 6. 예수금 검사: 예수금 차감 전에 먼저 검사해서 거절을 커밋한다.
        //   - reserveMargin()의 검사는 안전장치로 유지한다
        if (!account.hasCashBalance(command.amount())) {
            log.info("[증거금 인출] 거절 INSUFFICIENT_BALANCE(예수금 부족). eventId={}, subscriptionId={}, accountId={}, amount={}, cashBalance={}",
                    command.eventId(), command.subscriptionId(), command.accountId(), command.amount(), account.getCashBalance());
            reject(command, MarginReserveFailReason.INSUFFICIENT_BALANCE);
            return;
        }

        // 7. 증거금 상태를 관리하는 margin 저장
        //   - 저장 시 margin_id와 created_at(reservedAt)이 생성된다. (새 margin은 save()에서 persist되며 @CreatedDate로 값이 할당됨)
        //   - 비어 있으면 MarginReserved.from()에서 예외를 발생시킨다. (테스트: reservedAt은_margin의_created_at과_같은_시각이다)
        //   - 인출(잔고 이동)시 원장도 함께 생성되며, 원장이 margin을 참조하므로 잔고 이동 전에 margin을 저장한다.
        Margin margin = marginRepository.save(
                Margin.withdraw(
                        account, command.subscriptionId(), command.ipoId(), command.amount()
                ));
        log.debug("[증거금 인출] margin 저장. marginId={}, status={}, createdAt={}", margin.getId(), margin.getStatus(), margin.getCreatedAt());

        // 8. 인출과 원장: 계좌가 예수금 → 청약증거금으로 옮긴 후, 해당 거래의 원장(MARGIN_PENDING)을 만들어 반환하고, 이곳에서 저장한다.
        //   - 원장의 거래 후 잔고는 옮긴 후의 계좌 잔고라서 계좌 메서드 안에서 만든다.
        ledgerEntryRepository.save(account.reserveMargin(command.amount(), margin));
        log.debug("[증거금 인출] 잔고 이동. accountId={}, cashBalance={}, marginBalance={}",
                account.getId(), account.getCashBalance(), account.getMarginBalance());

        // 9. outbox 저장: 증거금 인출 성공 결과 객체를 생성하고 outbox 저장을 요청한다.
        //   - 잔고 변경과 같은 트랜잭션에 저장해서, 커밋되면 응답 이벤트도 필수로 발행된다. (Kafka로 바로 발행하면 커밋 실패, 발행 실패 시 잔고와 이벤트 불일치 발생)
        marginEventPublisher.publishReserved(MarginReserved.from(margin));

        // 트랜잭션 커밋 전 로그. 커밋이 실패하면 롤백 후 재시도되고 에러 로그가 별도로 기록된다
        log.info("[증거금 인출] 인출 저장 (커밋 전). eventId={}, subscriptionId={}, accountId={}, marginId={}, amount={}",
                command.eventId(), command.subscriptionId(), command.accountId(), margin.getId(), command.amount());
    }

    /**
     * 한 청약으로 인출 재요청한 경우 검증에 사용한다.
     * 잔고 변경은 없다.
     *
     * @param command 요청 본문
     * @param margin  기존 객체
     */
    private void handleExistingMargin(ReserveMarginCommand command, Margin margin) {

        // 4-1. accountId, amount, ipoId 일치 여부 검사
        if (!margin.matches(command.accountId(), command.amount(), command.ipoId())) {

            // 4-2. 불일치하면 DLT: 예외로 롤백해서 processed_event도 저장하지 않는다. DLT 전송은 4단계 Consumer 설정
            log.error("[증거금 인출] 기존 증거금과 요청이 달라 DLT 대상. eventId={}, subscriptionId={}, marginId={}, "
                            + "기존(accountId={}, amount={}, ipoId={}), 요청(accountId={}, amount={}, ipoId={})",
                    command.eventId(), command.subscriptionId(), margin.getId(),
                    margin.getAccount().getId(), margin.getAmount(), margin.getIpoId(),
                    command.accountId(), command.amount(), command.ipoId());

            throw new InvalidEventException("기존 증거금과 요청이 다릅니다. subscriptionId=" + command.subscriptionId());
        }

        MarginStatus status = margin.getStatus();

        if (status == MarginStatus.PENDING) {
            // 4-3. PENDING이면 기존 Margin 결과 재발행 (최초 reservedAt 유지, eventId는 새로)
            marginEventPublisher.publishReserved(MarginReserved.from(margin));

            log.info("[증거금 인출] 재요청, margin.reserved 재발행. eventId={}, subscriptionId={}, marginId={}",
                    command.eventId(), command.subscriptionId(), margin.getId());

        } else if (status == MarginStatus.POSTED || status == MarginStatus.VOIDED) {
            // 4-4. POSTED/VOIDED면 로그만 기록
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
