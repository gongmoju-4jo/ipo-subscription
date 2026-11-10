package com.gongmoju.gateway.queue;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * 접속 대기열 API. 게이트웨이가 직접 응답하고 서비스로 라우팅하지 않는다.
 * 인증은 다른 API처럼 JWT가 필요하고(보안 설정의 API 체인), 대기열 주인은 토큰의 sub다. 요청으로 받은 사용자 ID는 쓰지 않는다.
 * 공모주가 실제로 있는지, 청약 중인지는 확인하지 않는다 (접속이 몰리는 경로에 서비스 동기 호출을 넣지 않는다).
 */
@Tag(name = "Queue", description = "접속 대기열 API")
@RestController
@RequestMapping("/api/v1/queues/{ipoId}/entries")
class QueueController {

    private final QueueService queueService;

    QueueController(QueueService queueService) {
        this.queueService = queueService;
    }

    @Operation(summary = "대기열 진입", description = "새로 진입하면 201, 이미 줄에 있거나 입장 허가 상태면 200 (순번 유지)")
    @PostMapping
    Mono<ResponseEntity<ApiResponse<QueueEntryResponse>>> enter(@PathVariable UUID ipoId,
                                                               @AuthenticationPrincipal Jwt jwt) {
        return queueService.enter(ipoId, userId(jwt)).map(view -> ResponseEntity
                .status(view.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.success(QueueEntryResponse.from(view), "대기열에 진입했습니다.")));
    }

    private static UUID userId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidUserIdException();
        }
    }

    static class InvalidUserIdException extends RuntimeException {
        InvalidUserIdException() {
            super("토큰의 sub가 UUID가 아닙니다.");
        }
    }
}
