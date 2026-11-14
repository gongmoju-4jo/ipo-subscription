package com.gongmoju.account.domain.user;

import com.gongmoju.common.auth.UserRole;
import com.gongmoju.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

// 회원. 테이블 이름 users (user는 PostgreSQL 예약어)
@Getter
@Entity
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntity {

    // API의 userId, JWT sub
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "user_id")
    private UUID id;

    @Column(nullable = false, unique = true, length = 20)
    private String loginId;

    // BCrypt 해시. 원문은 저장, 응답, 로그 어디에도 기록하지 않음
    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Column(nullable = false, length = 20)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role = UserRole.USER;

    /**
     * 가입하는 회원을 만든다. 역할은 요청으로 받지 않고 항상 USER다 (ADMIN은 {@link #createAdmin}으로만 만든다).
     *
     * @param loginId      로그인 ID
     * @param passwordHash BCrypt 해시 (원문 아님)
     * @param name         이름
     * @return 저장 전 회원
     */
    public static User signUp(String loginId, String passwordHash, String name) {
        return of(loginId, passwordHash, name, UserRole.USER);
    }

    /**
     * 관리자 계정을 만든다. 관리자가 관리자 계정 생성 API로 만들거나, 첫 관리자를 시드로 넣을 때만 쓴다.
     *
     * @param loginId      로그인 ID
     * @param passwordHash BCrypt 해시 (원문 아님)
     * @param name         이름
     * @return 저장 전 관리자
     */
    public static User createAdmin(String loginId, String passwordHash, String name) {
        return of(loginId, passwordHash, name, UserRole.ADMIN);
    }

    private static User of(String loginId, String passwordHash, String name, UserRole role) {
        User user = new User();
        user.loginId = loginId;
        user.passwordHash = passwordHash;
        user.name = name;
        user.role = role;
        return user;
    }
}
