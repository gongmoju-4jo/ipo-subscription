package com.gongmoju.account.infrastructure.persistence;

import com.gongmoju.account.domain.user.User;
import com.gongmoju.account.domain.user.UserRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

// findById, saveAndFlush는 JpaRepository가 이미 제공하고, existsByLoginId, findByLoginId는 메서드 이름으로 쿼리가 만들어진다
interface UserJpaRepository extends JpaRepository<User, UUID>, UserRepository {
}
