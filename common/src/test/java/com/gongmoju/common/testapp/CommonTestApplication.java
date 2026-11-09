package com.gongmoju.common.testapp;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * common의 자동 설정(엔티티, Repository 등록, Bean 구성)을 서비스 없이 확인하는 테스트 전용 앱.
 * 이 패키지만 스캔하므로 common의 엔티티, Repository는 자동 설정이 등록한 것만 쓰게 된다.
 */
@SpringBootApplication
public class CommonTestApplication {
}
