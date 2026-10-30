package com.gongmoju.account.presentation.account;

import com.gongmoju.account.application.account.AccountService;
import com.gongmoju.account.application.account.DepositResult;
import com.gongmoju.account.application.account.DepositService;
import com.gongmoju.account.application.account.OpenAccountResult;
import com.gongmoju.account.global.swagger.ApiErrorCodes;
import com.gongmoju.account.presentation.support.RequestUser;
import com.gongmoju.common.exception.BusinessException;
import com.gongmoju.common.exception.CommonErrorCode;
import com.gongmoju.common.response.ApiResponse;
import com.gongmoju.common.response.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Account", description = "계좌 API")
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AccountService accountService;
    private final DepositService depositService;

    @Operation(summary = "계좌 개설", description = "같은 requestId로 다시 요청하면 계좌를 새로 만들지 않고 처음 계좌를 200으로 반환한다.")
    @ApiErrorCodes(common = {CommonErrorCode.VALIDATION_ERROR, CommonErrorCode.UNAUTHORIZED, CommonErrorCode.DUPLICATE_RESOURCE})
    @PostMapping
    public ResponseEntity<ApiResponse<AccountResponse>> open(
            @Parameter(hidden = true) @RequestHeader(value = RequestUser.HEADER, required = false) String userIdHeader,
            @Valid @RequestBody AccountOpenRequest request) {
        UUID userId = RequestUser.userId(userIdHeader);
        OpenAccountResult result = accountService.open(userId, UUID.fromString(request.requestId()));
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .body(ApiResponse.success(AccountResponse.from(result.account()), "계좌가 개설되었습니다."));
    }

    @Operation(summary = "내 계좌 목록 조회",
            description = "본인 계좌만 조회한다. 정렬은 createdAt만 가능하고 기본은 개설 시각 최신순이다. 계좌가 없으면 빈 목록이다.")
    @ApiErrorCodes(common = {CommonErrorCode.VALIDATION_ERROR, CommonErrorCode.UNAUTHORIZED})
    @GetMapping
    public ApiResponse<PageResponse<AccountSummaryResponse>> list(
            @Parameter(hidden = true) @RequestHeader(value = RequestUser.HEADER, required = false) String userIdHeader,
            @ParameterObject Pageable pageable,
            @Parameter(hidden = true) @RequestParam(required = false) Integer page,
            @Parameter(hidden = true) @RequestParam(required = false) Integer size) {
        UUID userId = RequestUser.userId(userIdHeader);
        validatePaging(page, size);
        return ApiResponse.success(
                PageResponse.from(accountService.list(userId, pageable), AccountSummaryResponse::from),
                "목록을 조회했습니다.");
    }

    @Operation(summary = "계좌 상세 조회", description = "본인 계좌만 조회한다. 없는 계좌와 남의 계좌는 모두 404이다.")
    @ApiErrorCodes(common = {CommonErrorCode.VALIDATION_ERROR, CommonErrorCode.UNAUTHORIZED, CommonErrorCode.RESOURCE_NOT_FOUND})
    @GetMapping("/{accountId}")
    public ApiResponse<AccountResponse> get(
            @Parameter(hidden = true) @RequestHeader(value = RequestUser.HEADER, required = false) String userIdHeader,
            @PathVariable UUID accountId) {
        UUID userId = RequestUser.userId(userIdHeader);
        return ApiResponse.success(AccountResponse.from(accountService.get(userId, accountId)), "계좌를 조회했습니다.");
    }

    @Operation(summary = "가상 입금",
            description = "본인 계좌에 입금한다. 같은 requestId로 다시 요청하면 입금을 다시 하지 않고 처음 결과를 200으로 반환한다. 청약증거금(marginBalance)은 바뀌지 않는다.")
    @ApiErrorCodes(common = {CommonErrorCode.VALIDATION_ERROR, CommonErrorCode.UNAUTHORIZED,
            CommonErrorCode.RESOURCE_NOT_FOUND, CommonErrorCode.DUPLICATE_RESOURCE})
    @PostMapping("/{accountId}/deposits")
    public ResponseEntity<ApiResponse<DepositResponse>> deposit(
            @Parameter(hidden = true) @RequestHeader(value = RequestUser.HEADER, required = false) String userIdHeader,
            @PathVariable UUID accountId,
            @Valid @RequestBody DepositRequest request) {
        UUID userId = RequestUser.userId(userIdHeader);
        DepositResult result = depositService.deposit(accountId, userId, request.amount(), UUID.fromString(request.requestId()));
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status)
                .body(ApiResponse.success(DepositResponse.from(result), "입금이 완료되었습니다."));
    }

    // 공통 Pageable 해석은 범위를 벗어난 값을 조용히 기본값/최대값으로 바꾼다. API 명세 v1.1은 400이므로 원래 요청 값을 여기서 직접 검증한다
    // (숫자가 아닌 값은 Integer 변환에서 먼저 400이 된다)
    private void validatePaging(Integer page, Integer size) {
        if (page != null && page < 0) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "페이지 요청 값이 올바르지 않습니다.", "page");
        }
        if (size != null && (size < 1 || size > MAX_PAGE_SIZE)) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "페이지 요청 값이 올바르지 않습니다.", "size");
        }
    }
}
