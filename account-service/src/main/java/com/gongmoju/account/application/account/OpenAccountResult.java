package com.gongmoju.account.application.account;

/**
 * 계좌 개설 결과
 *
 * @param account 개설한 계좌 (재전송이면 처음 개설한 계좌)
 * @param created 이번 요청으로 새로 만들었으면 true, 같은 requestId의 재전송이면 false
 */
public record OpenAccountResult(AccountResult account, boolean created) {
}
