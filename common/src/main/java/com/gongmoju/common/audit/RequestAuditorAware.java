package com.gongmoju.common.audit;

import org.springframework.data.domain.AuditorAware;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

/**
 * created_by, updated_by 값을 정한다.
 * <p>
 * HTTP 요청이면 게이트웨이가 넘겨준 X-User-Id, 요청이 아니거나(Kafka Consumer, 스케줄러) 헤더가 없으면(회원가입) SYSTEM:{서비스 이름}
 */
public class RequestAuditorAware implements AuditorAware<String> {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String SYSTEM_PREFIX = "SYSTEM:";

    private final String systemAuditor;

    /**
     * 시스템 작업 주체 이름(SYSTEM:{서비스 이름})을 정한다.
     *
     * @param applicationName 시스템 작업일 때 기록할 서비스 이름 (spring.application.name)
     */
    public RequestAuditorAware(String applicationName) {
        this.systemAuditor = SYSTEM_PREFIX + applicationName;
    }

    /**
     * 현재 작업을 한 주체를 반환한다.
     *
     * @return 요청자 회원 ID, 없으면 SYSTEM:{서비스 이름}
     */
    @Override
    public Optional<String> getCurrentAuditor() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            String userId = attributes.getRequest().getHeader(USER_ID_HEADER);
            if (userId != null && !userId.isBlank()) {
                return Optional.of(userId);
            }
        }
        return Optional.of(systemAuditor);
    }
}
