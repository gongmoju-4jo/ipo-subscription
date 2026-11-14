package com.gongmoju.account.infrastructure.startup;

import com.gongmoju.account.application.user.AdminSeedResult;
import com.gongmoju.account.application.user.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

import static com.gongmoju.account.domain.user.UserInputRules.LOGIN_ID_REGEX;
import static com.gongmoju.account.domain.user.UserInputRules.LOGIN_ID_RULE;
import static com.gongmoju.account.domain.user.UserInputRules.PASSWORD_MAX_LENGTH;
import static com.gongmoju.account.domain.user.UserInputRules.PASSWORD_MIN_LENGTH;
import static com.gongmoju.account.domain.user.UserInputRules.PASSWORD_RULE;

/**
 * 첫 관리자 시드. 계좌 서비스가 뜰 때 admin-seed.login-id(ADMIN_LOGIN_ID)로 {@link UserService#seedAdmin}을 부른다.
 * 이 클래스는 실행 시점, 설정값 읽기, 형식 검사, 로그만 맡고, 계정이 있는지 보고 만들지는 UserService가 정한다.
 *
 * <ul>
 *   <li>둘 다 비어 있음: 건너뜀 (CI, 로컬 기본)</li>
 *   <li>하나만 있거나 형식 오류: 기동 실패 (어느 값이 문제인지만 알리고 값은 출력하지 않는다)</li>
 *   <li>둘 다 있음: seedAdmin 호출 (없으면 생성, 있으면 그대로, USER면 승격하지 않고 WARN)</li>
 * </ul>
 */
@Slf4j
@Component
public class AdminSeedRunner implements ApplicationRunner {

    // 회원가입(SignUpRequest)과 같은 형식 규칙 (UserInputRules)
    private static final Pattern LOGIN_ID_PATTERN = Pattern.compile(LOGIN_ID_REGEX);

    private final UserService userService;
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
                           @Value("${admin-seed.login-id:}") String loginId,
                           @Value("${admin-seed.password:}") String password) {
        this.userService = userService;
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
        AdminSeedResult result = userService.seedAdmin(loginId, password);
        switch (result) {
            case CREATED -> log.info("첫 관리자 계정을 만들었습니다 (ADMIN_LOGIN_ID).");
            case EXISTING_ADMIN -> log.info("ADMIN_LOGIN_ID의 관리자 계정이 이미 있어 그대로 둡니다.");
            case EXISTING_NON_ADMIN -> log.warn(
                    "ADMIN_LOGIN_ID가 관리자가 아닌 회원(USER)의 로그인 ID라 승격하지 않았습니다. 다른 ADMIN_LOGIN_ID를 쓰세요.");
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
            throw new IllegalStateException("ADMIN_LOGIN_ID 형식이 틀렸습니다. " + LOGIN_ID_RULE + "만 사용할 수 있습니다.");
        }
        if (password.length() < PASSWORD_MIN_LENGTH || password.length() > PASSWORD_MAX_LENGTH) {
            throw new IllegalStateException("ADMIN_PASSWORD 형식이 틀렸습니다. " + PASSWORD_RULE + "여야 합니다.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
