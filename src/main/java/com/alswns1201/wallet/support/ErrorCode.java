package com.alswns1201.wallet.support;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
	INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "금액은 0보다 커야 합니다."),
	WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "지갑을 찾을 수 없습니다."),
	TRANSACTION_NOT_FOUND(HttpStatus.NOT_FOUND, "거래를 찾을 수 없습니다."),
	DUPLICATE_WALLET(HttpStatus.CONFLICT, "이미 지갑이 있는 사용자입니다."),
	INSUFFICIENT_BALANCE(HttpStatus.UNPROCESSABLE_ENTITY, "잔액이 부족합니다."),
	NOT_CANCELABLE(HttpStatus.UNPROCESSABLE_ENTITY, "결제 거래만 취소할 수 있습니다."),
	ALREADY_CANCELED(HttpStatus.CONFLICT, "이미 취소된 거래입니다."),
	DAILY_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "오늘 결제 한도를 넘었습니다."),
	LOCK_TIMEOUT(HttpStatus.SERVICE_UNAVAILABLE, "요청이 몰려 처리하지 못했습니다. 잠시 후 다시 시도해주세요."),
	INVALID_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "Idempotency-Key 헤더가 비어 있습니다."),
	IDEMPOTENCY_IN_PROGRESS(HttpStatus.CONFLICT, "같은 Idempotency-Key 요청을 처리하고 있습니다. 잠시 후 다시 시도해주세요."),
	IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY, "이미 다른 요청에 사용한 Idempotency-Key입니다.");

	private final HttpStatus status;
	private final String message;
}
