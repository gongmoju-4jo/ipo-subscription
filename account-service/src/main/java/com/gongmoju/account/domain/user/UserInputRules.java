package com.gongmoju.account.domain.user;

/**
 * 회원 입력 형식 규칙. 회원가입(SignUpRequest), 관리자 계정 생성(AdminUserCreateRequest), 첫 관리자 시드(AdminSeedRunner)가 같이 쓴다.
 * 어노테이션(@Pattern, @Size)에도 쓰도록 모두 컴파일 시점 상수로 둔다.
 * <p>
 * 바꾸면 같이 맞출 곳 (Java 상수를 가져다 쓸 수 없음): deploy/deploy.sh의 SMOKE_*, ADMIN_* 형식 검사,
 * frontend의 LoginPage(회원가입), AdminUserCreatePage 화면 검사
 */
public final class UserInputRules {

    public static final int LOGIN_ID_MIN_LENGTH = 4;
    public static final int LOGIN_ID_MAX_LENGTH = 20;
    public static final String LOGIN_ID_REGEX = "^[a-z0-9]{" + LOGIN_ID_MIN_LENGTH + "," + LOGIN_ID_MAX_LENGTH + "}$";
    // 오류 문구에 쓰는 규칙 설명 (예: "4~20자의 영문 소문자, 숫자")
    public static final String LOGIN_ID_RULE = LOGIN_ID_MIN_LENGTH + "~" + LOGIN_ID_MAX_LENGTH + "자의 영문 소문자, 숫자";

    public static final int PASSWORD_MIN_LENGTH = 8;
    public static final int PASSWORD_MAX_LENGTH = 20;
    // 오류 문구에 쓰는 규칙 설명 (예: "8자 이상 20자 이하")
    public static final String PASSWORD_RULE = PASSWORD_MIN_LENGTH + "자 이상 " + PASSWORD_MAX_LENGTH + "자 이하";

    public static final int NAME_MAX_LENGTH = 20;

    private UserInputRules() {
    }
}
