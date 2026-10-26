package com.gongmoju.common.audit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

class RequestAuditorAwareTest {

    private final RequestAuditorAware auditorAware = new RequestAuditorAware("account-service");

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void 요청에_X_User_Id가_있으면_그_값을_기록한다() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "10000000-0000-4000-8000-000000000001");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(auditorAware.getCurrentAuditor()).contains("10000000-0000-4000-8000-000000000001");
    }

    @Test
    void 요청이_아니면_SYSTEM과_서비스_이름을_기록한다() {
        assertThat(auditorAware.getCurrentAuditor()).contains("SYSTEM:account-service");
    }

    @Test
    void 요청에_X_User_Id가_없으면_SYSTEM과_서비스_이름을_기록한다() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));

        assertThat(auditorAware.getCurrentAuditor()).contains("SYSTEM:account-service");
    }
}
