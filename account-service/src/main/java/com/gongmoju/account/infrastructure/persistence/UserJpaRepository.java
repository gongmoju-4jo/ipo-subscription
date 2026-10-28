package com.gongmoju.account.infrastructure.persistence;

import com.gongmoju.account.domain.user.User;
import com.gongmoju.account.domain.user.UserRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

// findById는 JpaRepository가 이미 제공하므로 도메인 인터페이스를 그대로 구현한다
interface UserJpaRepository extends JpaRepository<User, UUID>, UserRepository {
}
