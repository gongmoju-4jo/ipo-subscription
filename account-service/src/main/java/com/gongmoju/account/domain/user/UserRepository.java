package com.gongmoju.account.domain.user;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository {

    Optional<User> findById(UUID userId);
}
