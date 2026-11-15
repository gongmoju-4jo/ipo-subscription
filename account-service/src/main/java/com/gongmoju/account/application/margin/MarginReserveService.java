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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
        reserveOne(command);
    }

    /**
     * 여러 건을 한 트랜잭션(커밋 1번)으로 처리한다. 인출 Consumer가 밀린 메시지를 묶어서 호출한다 (#162).
     * <p>
     * 판단 순서와 규칙은 {@link #reserve}와 같다 ({@link #decide}를 함께 쓴다). 다른 점은 DB를 오가는 방식이다.
     * <ol>
     *   <li>처리 기록: 건마다 저장한다. 같은 eventId가 이미 있는 건은 뺀다</li>
     *   <li>계좌 잠금, 기존 margin, 선행 반환 기록을 각각 한 번에 조회한다 (계좌는 ID 순으로 잠금)</li>
     *   <li>조회해 둔 값으로 메모리에서 건을 순서대로 판단한다. 같은 계좌의 앞 건이 바꾼 잔고와 이번 배치에서 만든 margin이 뒤 건에 보인다</li>
     *   <li>커밋 때 변경을 한 번에 DB로 보낸다. margin, 원장, Outbox INSERT와 계좌 UPDATE는 종류별로 JDBC 배치로 묶여 나간다</li>
     * </ol>
     * 한 건이라도 예외가 나면 전체가 롤백되고(처리 기록 포함) 예외가 그대로 전달된다.
     * 호출한 쪽이 건별 {@link #reserve}로 다시 처리해서 실패한 건만 가려낸다.
     * 거절(예수금 부족, 계좌 없음)은 예외가 아니라 응답 이벤트를 남기는 정상 결과라 롤백하지 않는다.
     * <p>
     * 바깥 트랜잭션 없이 호출해야 한다. 호출한 쪽에 트랜잭션이 있으면 이 메서드가 끝나도 커밋되지 않는다.
     *
     * @param commands 처리할 요청 (수신한 순서)
     * @return 처리 결과 건수
     */
    @Transactional
    public ReserveBatchResult reserveAll(List<ReserveMarginCommand> commands) {
        int reserved = 0;
        int rejected = 0;
        int skipped = 0;

        // 1. 처리 기록: 중복(같은 eventId)인 건은 이후 단계에서 뺀다
        List<ReserveMarginCommand> accepted = new ArrayList<>(commands.size());
        for (ReserveMarginCommand command : commands) {
            if (markProcessed(command)) {
                accepted.add(command);
            } else {
                skipped++;
            }
        }
        if (accepted.isEmpty()) {
            return new ReserveBatchResult(0, 0, skipped);
        }

        // 2. 조회: 계좌 잠금 → 기존 margin → 선행 반환 기록 순서는 건별 처리와 같다. 반환 Consumer도 계좌 잠금을 기다리므로
        //    잠근 뒤에 읽은 값은 이 트랜잭션이 끝날 때까지 바뀌지 않는다
        Map<UUID, Account> accounts = lockAccounts(accepted);
        BatchLookup lookup = new BatchLookup(findMargins(accepted), findVoidIntents(accepted));

        // 3. 메모리에서 순서대로 판단한다. 이 사이에는 쿼리가 나가지 않아서 자동 flush와 변경 감지가 건마다 반복되지 않는다
        for (ReserveMarginCommand command : accepted) {
            logStart(command);
            switch (decide(command, accounts.get(command.accountId()), lookup)) {
                case RESERVED -> reserved++;
                case REJECTED -> rejected++;
                case SKIPPED -> skipped++;
            }
        }
        return new ReserveBatchResult(reserved, rejected, skipped);
    }

    // 건 하나를 건별 쿼리로 처리한다. 트랜잭션은 호출한 메서드(reserve)가 연다
    private Outcome reserveOne(ReserveMarginCommand command) {
        // 1. processed_event로 같은 eventId 재수신이면 처리 없이 종료한다.
        if (!markProcessed(command)) {
            return Outcome.SKIPPED;
        }

        // 2. 계좌 락: 기존 margin 조회보다 먼저 건다.
        //   - 같은 청약 요청이 동시에 수신되어도 순서대로 처리되어, 두 번째 요청은 첫 번째 요청으로 생성된 margin을 조회한다.
        //   - 계좌가 없으면 잠글 행이 없어서 바로 거절한다.
        logStart(command);
        Account account = accountRepository.findByIdForUpdate(command.accountId()).orElse(null);

        return decide(command, account, new RepositoryLookup());
    }

    // processed_event로 같은 eventId 재수신인지 판단하고 기록한다. 새 이벤트면 true
    //   - 조회 후 저장이 아니라 삽입 한 번으로 판단해서, 같은 eventId가 동시에 수신되어도 1건만 처리된다.
    //   - 이후 단계가 예외로 롤백되면 이 기록도 같이 롤백되어 재시도 때 다시 처리된다.
    private boolean markProcessed(ReserveMarginCommand command) {
        if (processedEventRepository.insertIfAbsent(command.eventId(), CONSUMER_NAME, TOPIC)) {
            return true;
        }
        log.info("[증거금 인출] 중복 수신, 건너뜀. eventId={}, subscriptionId={}",
                command.eventId(), command.subscriptionId());
        return false;
    }

    private void logStart(ReserveMarginCommand command) {
        log.debug("[증거금 인출] 시작. eventId={}, subscriptionId={}, accountId={}, userId={}, amount={}",
                command.eventId(), command.subscriptionId(), command.accountId(), command.userId(), command.amount());
    }

    // 계좌 잠금 1회 (ID 순). 없는 계좌는 맵에 없다
    private Map<UUID, Account> lockAccounts(List<ReserveMarginCommand> commands) {
        Set<UUID> accountIds = commands.stream()
                .map(ReserveMarginCommand::accountId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, Account> accounts = new HashMap<>();
        for (Account account : accountRepository.findAllByIdForUpdate(accountIds)) {
            accounts.put(account.getId(), account);
        }
        return accounts;
    }

    // 기존 margin 조회 1회 (청약 ID로)
    private Map<UUID, Margin> findMargins(List<ReserveMarginCommand> commands) {
        Map<UUID, Margin> margins = new HashMap<>();
        for (Margin margin : marginRepository.findAllBySubscriptionIds(subscriptionIds(commands))) {
            margins.put(margin.getSubscriptionId(), margin);
        }
        return margins;
    }

    // 선행 반환 기록 조회 1회
    private Set<UUID> findVoidIntents(List<ReserveMarginCommand> commands) {
        return marginVoidIntentRepository.findSubscriptionIdsWithIntent(subscriptionIds(commands));
    }

    private Set<UUID> subscriptionIds(List<ReserveMarginCommand> commands) {
        return commands.stream()
                .map(ReserveMarginCommand::subscriptionId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    // 계좌를 잠근 뒤의 판단과 처리. 건별 경로(reserveOne)와 배치 경로(reserveAll)가 같이 쓴다. 조회 방식만 lookup이 다르다
    private Outcome decide(ReserveMarginCommand command, Account account, Lookup lookup) {
        // 3. 소유권: 계좌 없음, 소유자 다름 둘 다 ACCOUNT_NOT_FOUND로 응답한다. (타인 계좌의 존재 여부 숨김). 실제 원인은 로그로 구분
        if (account == null) {
            log.info("[증거금 인출] 거절 ACCOUNT_NOT_FOUND(계좌 없음). eventId={}, subscriptionId={}, accountId={}",
                    command.eventId(), command.subscriptionId(), command.accountId());

            reject(command, MarginReserveFailReason.ACCOUNT_NOT_FOUND);
            return Outcome.REJECTED;
        }

        if (!account.isOwnedBy(command.userId())) {
            log.warn("[증거금 인출] 거절 ACCOUNT_NOT_FOUND(소유자 다름). eventId={}, subscriptionId={}, accountId={}, userId={}",
                    command.eventId(), command.subscriptionId(), command.accountId(), command.userId());

            reject(command, MarginReserveFailReason.ACCOUNT_NOT_FOUND);
            return Outcome.REJECTED;
        }

        log.debug("[증거금 인출] 계좌 락 획득. accountId={}, cashBalance={}, marginBalance={}",
                account.getId(), account.getCashBalance(), account.getMarginBalance());

        // 4. Margin 객체 존재하는지 확인 (배치 경로는 같은 배치에서 앞서 만든 margin도 포함)
        Margin existingMargin = lookup.findMargin(command.subscriptionId());

        //   존재하는 경우
        if (existingMargin != null) {
            handleExistingMargin(command, existingMargin);
            return Outcome.SKIPPED;
        }

        // 5. 선행 반환 기록:
        //   - 반환 요청(TIMEOUT 등)이 인출 요청보다 먼저 처리됐으면 청약은 이미 CANCELLED라서
        //     인출하지 않고(예수금 차감 없음) 응답 이벤트 없이 로그만 기록한다. (E-17). 차감한 금액이 없으므로 예외 없이 처리 기록만 커밋한다
        //   - 예수금 검사보다 먼저 조회해야 이미 취소된 청약에 reserve-failed가 발행되지 않는다. (E-19)
        if (lookup.hasVoidIntent(command.subscriptionId())) {
            log.warn("[증거금 인출] 선행 반환 기록이 있어 인출 안 함, 응답 없음. eventId={}, subscriptionId={}, accountId={}",
                    command.eventId(), command.subscriptionId(), command.accountId());
            return Outcome.SKIPPED;
        }

        // 6. 예수금 검사: 예수금 차감 전에 먼저 검사해서 거절을 커밋한다.
        //   - reserveMargin()의 검사는 안전장치로 유지한다
        if (!account.hasCashBalance(command.amount())) {
            log.info("[증거금 인출] 거절 INSUFFICIENT_BALANCE(예수금 부족). eventId={}, subscriptionId={}, accountId={}, amount={}, cashBalance={}",
                    command.eventId(), command.subscriptionId(), command.accountId(), command.amount(), account.getCashBalance());
            reject(command, MarginReserveFailReason.INSUFFICIENT_BALANCE);
            return Outcome.REJECTED;
        }

        // 7. 증거금 상태를 관리하는 margin 저장
        //   - 저장 시 margin_id와 created_at(reservedAt)이 생성된다. (새 margin은 save()에서 persist되며 @CreatedDate로 값이 할당됨)
        //   - 비어 있으면 MarginReserved.from()에서 예외를 발생시킨다. (테스트: reservedAt은_margin의_created_at과_같은_시각이다)
        //   - 인출(잔고 이동)시 원장도 함께 생성되며, 원장이 margin을 참조하므로 잔고 이동 전에 margin을 저장한다.
        Margin margin = marginRepository.save(
                Margin.withdraw(
                        account, command.subscriptionId(), command.ipoId(), command.amount()
                ));
        lookup.created(margin);
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
        return Outcome.RESERVED;
    }

    // 건 하나의 처리 결과. 배치 처리 로그(인출, 거절, 건너뜀 건수)용
    private enum Outcome {
        // 인출함
        RESERVED,
        // 거절 응답(margin.reserve-failed)을 남김
        REJECTED,
        // 중복 수신, 재요청, 선행 반환 기록으로 인출하지 않음
        SKIPPED
    }

    // 판단에 필요한 조회. 건별 경로는 저장소를 매번 조회하고, 배치 경로는 미리 읽어 둔 값을 쓴다
    private interface Lookup {

        // 청약의 기존 margin. 없으면 null
        Margin findMargin(UUID subscriptionId);

        boolean hasVoidIntent(UUID subscriptionId);

        // 이번에 새로 만든 margin을 알려 준다 (같은 배치의 같은 청약 뒤 건이 재요청 경로로 가게)
        void created(Margin margin);
    }

    private final class RepositoryLookup implements Lookup {

        @Override
        public Margin findMargin(UUID subscriptionId) {
            return marginRepository.findBySubscriptionId(subscriptionId).orElse(null);
        }

        @Override
        public boolean hasVoidIntent(UUID subscriptionId) {
            return marginVoidIntentRepository.existsBySubscriptionId(subscriptionId);
        }

        @Override
        public void created(Margin margin) {
            // 건별 경로는 다음 건이 없다
        }
    }

    private static final class BatchLookup implements Lookup {

        private final Map<UUID, Margin> margins;
        private final Set<UUID> voidIntents;

        private BatchLookup(Map<UUID, Margin> margins, Set<UUID> voidIntents) {
            this.margins = margins;
            this.voidIntents = voidIntents;
        }

        @Override
        public Margin findMargin(UUID subscriptionId) {
            return margins.get(subscriptionId);
        }

        @Override
        public boolean hasVoidIntent(UUID subscriptionId) {
            return voidIntents.contains(subscriptionId);
        }

        @Override
        public void created(Margin margin) {
            margins.put(margin.getSubscriptionId(), margin);
        }
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
