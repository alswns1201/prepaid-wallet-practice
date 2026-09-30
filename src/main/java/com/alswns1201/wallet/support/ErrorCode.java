package com.alswns1201.wallet.support;

import org.springframework.http.HttpStatus;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {
	WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "지갑을 찾을 수 없습니다."),
	DUPLICATE_WALLET(HttpStatus.CONFLICT, "이미 지갑이 있는 사용자입니다.");

	private final HttpStatus status;
	private final String message;
}
