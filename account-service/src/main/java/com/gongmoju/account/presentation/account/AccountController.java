package com.gongmoju.account.presentation.account;

import com.gongmoju.account.application.account.AccountService;
import com.gongmoju.account.application.account.OpenAccountResult;
import com.gongmoju.account.global.swagger.ApiErrorCodes;
import com.gongmoju.account.presentation.support.RequestUser;
import com.gongmoju.common.exception.CommonErrorCode;
import com.gongmoju.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Account", description = "계좌 API")
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

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
}
