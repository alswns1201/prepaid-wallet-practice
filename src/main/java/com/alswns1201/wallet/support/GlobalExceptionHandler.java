package com.alswns1201.wallet.support;

import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler(WalletException.class)
	public ProblemDetail handle(WalletException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.getErrorCode().getStatus(), e.getMessage());
		problem.setProperty("code", e.getErrorCode().name());
		return problem;
	}
}
