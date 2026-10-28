package com.gongmoju.account.application.account;

import com.gongmoju.account.domain.account.Account;
import com.gongmoju.account.domain.account.AccountNumberGenerator;
import com.gongmoju.account.domain.account.AccountRepository;
import com.gongmoju.account.domain.user.User;
import com.gongmoju.account.domain.user.UserRepository;
import com.gongmoju.common.exception.BusinessException;
import com.gongmoju.common.exception.CommonErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class AccountService {

    // PostgreSQL unique_violation = SQLState 23505
    private static final String UNIQUE_VIOLATION = "23505";
    // 12자리 난수가 겹칠 일은 거의 없다. 겹치면 번호만 새로 뽑아 다시 시도하고, 이 횟수를 넘으면 서버 오류로 본다
    private static final int MAX_NUMBER_ATTEMPTS = 5;

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final AccountNumberGenerator accountNumberGenerator;
    private final TransactionTemplate transactionTemplate;

    public AccountService(AccountRepository accountRepository, UserRepository userRepository,
                          AccountNumberGenerator accountNumberGenerator, PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.accountNumberGenerator = accountNumberGenerator;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 계좌를 개설한다. 같은 requestId로 다시 요청하면 새로 만들지 않고 처음 만든 계좌를 돌려준다.
     * <p>
     * 유니크 위반이 나면 원인이 둘 중 하나다. 공통 예외 처리기는 둘 다 409로 바꾸므로 여기서 먼저 구분한다.
     * <ol>
     *   <li>같은 requestId가 동시에 들어와 먼저 저장된 경우: requestId로 다시 조회해서 재전송과 똑같이 응답한다</li>
     *   <li>계좌번호가 겹친 경우: requestId로 조회되는 계좌가 없으므로 새 번호로 다시 시도한다</li>
     * </ol>
     *
     * @param userId    소유자 (토큰의 회원 ID)
     * @param requestId 개설 요청 ID (멱등키)
     * @return 개설 결과
     * @throws BusinessException 회원이 없으면 UNAUTHORIZED(401), 다른 회원이 쓴 requestId면 DUPLICATE_RESOURCE(409)
     */
    public OpenAccountResult open(UUID userId, UUID requestId) {
        for (int attempt = 1; attempt <= MAX_NUMBER_ATTEMPTS; attempt++) {
            String accountNumber = accountNumberGenerator.generate();
            try {
                return transactionTemplate.execute(status -> openInTransaction(userId, requestId, accountNumber));
            } catch (DataIntegrityViolationException e) {
                if (!isUniqueViolation(e)) {
                    throw e;
                }
                Optional<OpenAccountResult> replay = transactionTemplate.execute(status -> findReplay(userId, requestId));
                if (replay.isPresent()) {
                    return replay.get();
                }
                log.warn("계좌번호가 겹쳐 다시 시도합니다. attempt={}", attempt);
            }
        }
        throw new IllegalStateException("계좌번호를 " + MAX_NUMBER_ATTEMPTS + "번 만들었지만 모두 겹쳤습니다.");
    }

    private OpenAccountResult openInTransaction(UUID userId, UUID requestId, String accountNumber) {
        Optional<OpenAccountResult> replay = findReplay(userId, requestId);
        if (replay.isPresent()) {
            return replay.get();
        }
        // 토큰은 유효한데 회원이 없는 경우 (DB 초기화 후 옛 토큰 등). FK 위반은 500이 되므로 먼저 확인해서 401로 응답한다
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.UNAUTHORIZED));
        Account saved = accountRepository.saveAndFlush(Account.open(user, requestId, accountNumber));
        return new OpenAccountResult(AccountResult.from(saved), true);
    }

    private Optional<OpenAccountResult> findReplay(UUID userId, UUID requestId) {
        return accountRepository.findByRequestId(requestId).map(account -> {
            if (!account.getUser().getId().equals(userId)) {
                throw new BusinessException(CommonErrorCode.DUPLICATE_RESOURCE, "이미 사용된 requestId입니다.", "requestId");
            }
            return new OpenAccountResult(AccountResult.from(account), false);
        });
    }

    private boolean isUniqueViolation(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && UNIQUE_VIOLATION.equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
