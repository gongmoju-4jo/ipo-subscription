package com.gongmoju.common.feign;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * 들어온 요청의 사용자 헤더를 Feign 요청에 그대로 실어 보낸다 (호출받은 서비스의 감사 필드, 권한 확인용).
 * <p>
 * 헤더 값은 게이트웨이가 JWT 검증 후 덮어쓴 값이다.
 */
public class FeignHeaderInterceptor implements RequestInterceptor {

    private static final List<String> FORWARD_HEADERS = List.of("X-User-Id", "X-User-Role");

    /**
     * 현재 요청의 X-User-Id, X-User-Role 헤더를 Feign 요청에 추가한다.
     *
     * @param template Feign 요청 템플릿
     */
    @Override
    public void apply(RequestTemplate template) {
        // 요청 스레드가 아닌 곳(스케줄러, 비동기)에서 호출하면 전달할 헤더가 없다
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return;
        }
        HttpServletRequest request = attributes.getRequest();
        for (String header : FORWARD_HEADERS) {
            String value = request.getHeader(header);
            if (value != null) {
                template.header(header, value);
            }
        }
    }
}
