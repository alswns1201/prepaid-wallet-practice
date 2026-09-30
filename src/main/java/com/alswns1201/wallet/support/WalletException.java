package com.alswns1201.wallet.support;

import lombok.Getter;

@Getter
public class WalletException extends RuntimeException {

	private final ErrorCode errorCode;

	public WalletException(ErrorCode errorCode) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
	}
}
