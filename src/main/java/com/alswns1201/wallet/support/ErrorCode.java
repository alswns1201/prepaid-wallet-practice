package com.alswns1201.wallet.support;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
	INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "금액은 0보다 커야 합니다."),
	WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "지갑을 찾을 수 없습니다."),
	DUPLICATE_WALLET(HttpStatus.CONFLICT, "이미 지갑이 있는 사용자입니다."),
	INSUFFICIENT_BALANCE(HttpStatus.UNPROCESSABLE_ENTITY, "잔액이 부족합니다.");

	private final HttpStatus status;
	private final String message;
}
