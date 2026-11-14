package com.gongmoju.account.application.user;

import com.gongmoju.account.domain.user.User;
import com.gongmoju.account.domain.user.UserRepository;
import com.gongmoju.common.auth.UserRole;
import com.gongmoju.common.exception.BusinessException;
import com.gongmoju.common.exception.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    // PostgreSQL unique_violation = SQLState 23505
    private static final String UNIQUE_VIOLATION = "23505";

    // 첫 관리자 시드로 만드는 계정의 이름
    public static final String SEED_ADMIN_NAME = "관리자";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * 회원가입. 해시는 시간이 걸려서 트랜잭션 밖에서 하고, 저장은 repository의 트랜잭션으로 바로 반영한다.
     * 확인과 저장 사이에 같은 loginId가 먼저 가입한 경우도 유니크 위반을 잡아서 같은 409로 응답한다.
     *
     * @param loginId     로그인 ID
     * @param rawPassword 비밀번호 원문 (저장, 로그에 남기지 않는다)
     * @param name        이름
     * @return 가입 결과
     * @throws BusinessException 이미 쓰는 loginId면 DUPLICATE_RESOURCE(409)
     */
    public SignUpResult signUp(String loginId, String rawPassword, String name) {
        if (userRepository.existsByLoginId(loginId)) {
            throw duplicateLoginId();
        }
        return save(User.signUp(loginId, passwordEncoder.encode(rawPassword), name));
    }

    /**
     * 관리자 계정 생성. 역할만 ADMIN이고 중복 처리, 해시, 저장 방식은 회원가입과 같다.
     * 누가 만들었는지는 Audit이 created_by에 요청자(X-User-Id)로 남긴다.
     *
     * @param loginId     로그인 ID
     * @param rawPassword 비밀번호 원문 (저장, 로그에 남기지 않는다)
     * @param name        이름
     * @return 생성 결과 (회원가입 결과와 같은 항목)
     * @throws BusinessException 이미 쓰는 loginId면 DUPLICATE_RESOURCE(409)
     */
    public SignUpResult createAdmin(String loginId, String rawPassword, String name) {
        if (userRepository.existsByLoginId(loginId)) {
            throw duplicateLoginId();
        }
        SignUpResult result = save(User.createAdmin(loginId, passwordEncoder.encode(rawPassword), name));
        log.info("관리자 계정 생성: userId={}, loginId={}", result.userId(), result.loginId());
        return result;
    }

    /**
     * 첫 관리자 시드. 그 로그인 ID의 계정이 있는지만 본다 (다른 관리자가 있는지와 관계없음).
     * 없으면 관리자로 만들고, 있으면 역할, 비밀번호를 바꾸지 않는다 (USER여도 승격하지 않는다).
     * 계좌 서비스 여러 대가 동시에 떠서 다른 인스턴스가 먼저 만든 경우(중복)는 이미 있는 것으로 본다.
     *
     * @param loginId     시드 관리자 로그인 ID (형식은 호출하는 쪽이 확인한다)
     * @param rawPassword 비밀번호 원문 (저장, 로그에 남기지 않는다)
     * @return 만들었는지, 이미 있었는지
     */
    public AdminSeedResult seedAdmin(String loginId, String rawPassword) {
        Optional<User> existing = userRepository.findByLoginId(loginId);
        if (existing.isPresent()) {
            return existingResult(existing.get());
        }
        try {
            createAdmin(loginId, rawPassword, SEED_ADMIN_NAME);
            return AdminSeedResult.CREATED;
        } catch (BusinessException e) {
            if (e.getErrorCode() != CommonErrorCode.DUPLICATE_RESOURCE) {
                throw e;
            }
            return userRepository.findByLoginId(loginId).map(this::existingResult).orElseThrow(() -> e);
        }
    }

    private AdminSeedResult existingResult(User user) {
        return user.getRole() == UserRole.ADMIN ? AdminSeedResult.EXISTING_ADMIN : AdminSeedResult.EXISTING_NON_ADMIN;
    }

    // 확인과 저장 사이에 같은 loginId가 먼저 저장된 경우도 유니크 위반을 잡아 같은 409로 응답한다
    private SignUpResult save(User user) {
        try {
            return SignUpResult.from(userRepository.saveAndFlush(user));
        } catch (DataIntegrityViolationException e) {
            if (isUniqueViolation(e)) {
                throw duplicateLoginId();
            }
            throw e;
        }
    }

    private BusinessException duplicateLoginId() {
        return new BusinessException(CommonErrorCode.DUPLICATE_RESOURCE, "이미 사용 중인 로그인 ID입니다.", "loginId");
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
