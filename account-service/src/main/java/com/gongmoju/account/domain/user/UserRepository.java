package com.gongmoju.account.domain.user;

import java.util.Optional;
import java.util.UUID;

// 회원 저장소. 구현은 infrastructure/persistence (JPA)
public interface UserRepository {

    Optional<User> findById(UUID userId);

    /**
     * 저장하고 바로 DB에 반영한다. 같은 loginId로 동시에 가입해도 유니크 위반을 호출한 트랜잭션 안에서 알 수 있다.
     *
     * @param user 저장할 회원
     * @return 저장된 회원
     */
    User saveAndFlush(User user);

    boolean existsByLoginId(String loginId);

    Optional<User> findByLoginId(String loginId);
}
