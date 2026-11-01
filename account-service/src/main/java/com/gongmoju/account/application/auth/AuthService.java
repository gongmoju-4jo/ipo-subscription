package com.gongmoju.account.application.auth;

import com.gongmoju.account.domain.auth.AccessToken;
import com.gongmoju.account.domain.auth.AccessTokenIssuer;
import com.gongmoju.account.domain.user.User;
import com.gongmoju.account.domain.user.UserRepository;
import com.gongmoju.account.global.exception.AccountErrorCode;
import com.gongmoju.common.exception.BusinessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Service
public class AuthService {

    // BCrypt는 비밀번호를 72바이트까지만 쓴다. 더 긴 입력은 가입할 수 없었으므로 해시 비교 없이 틀린 것으로 본다
    private static final int BCRYPT_MAX_BYTES = 72;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenIssuer accessTokenIssuer;
    // 없는 로그인 ID도 해시 비교를 한 번 하게 해서, 응답 시간으로 가입된 ID를 알아내지 못하게 한다
    private final String dummyHash;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       AccessTokenIssuer accessTokenIssuer) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.accessTokenIssuer = accessTokenIssuer;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    /**
     * 로그인하고 액세스 토큰을 발급한다. 로그인 ID가 없는 경우와 비밀번호가 틀린 경우는 같은 오류로 응답한다.
     *
     * @param loginId     로그인 ID
     * @param rawPassword 비밀번호 원문 (저장, 로그에 남기지 않는다)
     * @return 발급한 토큰
     * @throws BusinessException 로그인 ID가 없거나 비밀번호가 틀리면 INVALID_CREDENTIALS(401)
     */
    public AccessToken login(String loginId, String rawPassword) {
        Optional<User> user = userRepository.findByLoginId(loginId);
        boolean matched = matches(rawPassword, user.map(User::getPasswordHash).orElse(dummyHash));
        if (user.isEmpty() || !matched) {
            throw new BusinessException(AccountErrorCode.INVALID_CREDENTIALS);
        }
        return accessTokenIssuer.issue(user.get().getId(), user.get().getRole());
    }

    private boolean matches(String rawPassword, String hash) {
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            return false;
        }
        return passwordEncoder.matches(rawPassword, hash);
    }
}
