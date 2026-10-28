package com.gongmoju.account.presentation.account;

import com.gongmoju.account.application.account.AccountService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final AccountService accountService;

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
            description = "본인 계좌만 개설 시각 최신순으로 조회한다. 정렬은 지원하지 않으며 sort 파라미터를 보내면 400이다. 계좌가 없으면 빈 목록이다.")
    @ApiErrorCodes(common = {CommonErrorCode.VALIDATION_ERROR, CommonErrorCode.UNAUTHORIZED})
    @GetMapping
    public ApiResponse<PageResponse<AccountSummaryResponse>> list(
            @Parameter(hidden = true) @RequestHeader(value = RequestUser.HEADER, required = false) String userIdHeader,
            @Parameter(description = "페이지 번호 (0 이상, 기본 0)") @RequestParam(required = false) Integer page,
            @Parameter(description = "페이지 크기 (1~100, 기본 20)") @RequestParam(required = false) Integer size,
            @Parameter(hidden = true) @RequestParam(required = false) String sort) {
        UUID userId = RequestUser.userId(userIdHeader);
        rejectSort(sort);
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        validatePaging(pageNumber, pageSize);
        return ApiResponse.success(
                PageResponse.from(accountService.list(userId, pageNumber, pageSize), AccountSummaryResponse::from),
                "목록을 조회했습니다.");
    }

    // 계좌 목록은 개설 시각 최신순으로 고정이다. 정렬을 요청하면 적용된 줄 알고 넘어가지 않도록 400으로 알려준다 (정렬 가능 필드는 API별로 제한, 없는 필드는 400: PROJECT_SETUP.md)
    private void rejectSort(String sort) {
        if (sort != null) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR,
                    "정렬을 지원하지 않습니다. 목록은 개설 시각 최신순으로 고정입니다.", "sort");
        }
    }

    // 공통 Pageable 해석은 범위를 벗어난 값을 조용히 기본값/최대값으로 바꾼다. API 명세 v1.1은 400이므로 여기서 직접 검증한다
    private void validatePaging(int page, int size) {
        if (page < 0) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "페이지 요청 값이 올바르지 않습니다.", "page");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException(CommonErrorCode.VALIDATION_ERROR, "페이지 요청 값이 올바르지 않습니다.", "size");
        }
    }
}
