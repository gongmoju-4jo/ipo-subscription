package com.gongmoju.account.application.user;

import com.gongmoju.account.domain.user.User;
import com.gongmoju.account.domain.user.UserRepository;
import com.gongmoju.common.auth.UserRole;
import com.gongmoju.common.exception.BusinessException;
import com.gongmoju.common.exception.CommonErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 첫 관리자 시드. 계좌 서비스가 뜰 때 admin-seed.login-id(ADMIN_LOGIN_ID)의 계정이 없으면 ADMIN으로 만든다.
 * 다른 관리자가 있는지와 관계없이 그 로그인 ID의 계정이 있는지만 본다.
 *
 * <ul>
 *   <li>둘 다 비어 있음: 건너뜀 (CI, 로컬 기본)</li>
 *   <li>하나만 있거나 형식 오류: 기동 실패 (어느 값이 문제인지만 알리고 값은 출력하지 않는다)</li>
 *   <li>둘 다 있고 계정 없음: ADMIN으로 생성</li>
 *   <li>둘 다 있고 계정 있음: 아무것도 바꾸지 않음 (USER면 승격하지 않고 WARN)</li>
 * </ul>
 */
@Slf4j
@Component
public class AdminSeedRunner implements ApplicationRunner {

    static final String ADMIN_NAME = "관리자";

    // 회원가입(SignUpRequest)과 같은 형식 규칙
    private static final Pattern LOGIN_ID_PATTERN = Pattern.compile("^[a-z0-9]{4,20}$");
    private static final int PASSWORD_MIN_LENGTH = 8;
    private static final int PASSWORD_MAX_LENGTH = 20;

    private final UserService userService;
    private final UserRepository userRepository;
    private final String loginId;
    private final String password;

    /**
     * 값 검사는 생성할 때 한다. 형식이 틀리면 빈을 만들지 못해 서비스가 기동하지 않는다.
     *
     * @param loginId  첫 관리자 로그인 ID (ADMIN_LOGIN_ID). 비어 있으면 시드하지 않는다
     * @param password 첫 관리자 비밀번호 원문 (ADMIN_PASSWORD). 저장, 로그에 남기지 않는다
     * @throws IllegalStateException 하나만 있거나 형식이 틀림
     */
    public AdminSeedRunner(UserService userService,
                           UserRepository userRepository,
                           @Value("${admin-seed.login-id:}") String loginId,
                           @Value("${admin-seed.password:}") String password) {
        this.userService = userService;
        this.userRepository = userRepository;
        this.loginId = isBlank(loginId) ? null : loginId;
        this.password = isBlank(password) ? null : password;
        validate();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (loginId == null) {
            log.info("ADMIN_LOGIN_ID, ADMIN_PASSWORD가 비어 있어 첫 관리자 계정을 만들지 않습니다.");
            return;
        }
        Optional<User> existing = userRepository.findByLoginId(loginId);
        if (existing.isPresent()) {
            logExisting(existing.get());
            return;
        }
        try {
            userService.createAdmin(loginId, password, ADMIN_NAME);
            log.info("첫 관리자 계정을 만들었습니다 (ADMIN_LOGIN_ID).");
        } catch (BusinessException e) {
            // 계좌 서비스 여러 대가 동시에 떠서 다른 인스턴스가 먼저 만든 경우
            if (e.getErrorCode() != CommonErrorCode.DUPLICATE_RESOURCE) {
                throw e;
            }
            userRepository.findByLoginId(loginId).ifPresent(this::logExisting);
        }
    }

    private void logExisting(User user) {
        if (user.getRole() == UserRole.ADMIN) {
            log.info("ADMIN_LOGIN_ID의 관리자 계정이 이미 있어 그대로 둡니다.");
        } else {
            log.warn("ADMIN_LOGIN_ID가 관리자가 아닌 회원({})의 로그인 ID라 승격하지 않았습니다. 다른 ADMIN_LOGIN_ID를 쓰세요.",
                    user.getRole());
        }
    }

    private void validate() {
        if (loginId == null && password == null) {
            return;
        }
        if (loginId == null) {
            throw new IllegalStateException("ADMIN_PASSWORD만 있고 ADMIN_LOGIN_ID가 비어 있습니다. 둘 다 넣거나 둘 다 비웁니다.");
        }
        if (password == null) {
            throw new IllegalStateException("ADMIN_LOGIN_ID만 있고 ADMIN_PASSWORD가 비어 있습니다. 둘 다 넣거나 둘 다 비웁니다.");
        }
        if (!LOGIN_ID_PATTERN.matcher(loginId).matches()) {
            throw new IllegalStateException("ADMIN_LOGIN_ID 형식이 틀렸습니다. 4~20자의 영문 소문자, 숫자만 사용할 수 있습니다.");
        }
        if (password.length() < PASSWORD_MIN_LENGTH || password.length() > PASSWORD_MAX_LENGTH) {
            throw new IllegalStateException("ADMIN_PASSWORD 형식이 틀렸습니다. 8자 이상 20자 이하여야 합니다.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
