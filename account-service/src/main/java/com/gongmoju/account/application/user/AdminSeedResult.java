package com.gongmoju.account.application.user;

/**
 * 첫 관리자 시드 결과. 로그 문구(환경변수 이름 안내 등)는 실행하는 쪽(AdminSeedRunner)이 정한다.
 */
public enum AdminSeedResult {
    // 그 로그인 ID의 계정이 없어서 관리자로 만들었다
    CREATED,
    // 그 로그인 ID의 관리자가 이미 있어서 그대로 뒀다
    EXISTING_ADMIN,
    // 그 로그인 ID가 관리자가 아닌 회원이라 승격하지 않았다
    EXISTING_NON_ADMIN
}
