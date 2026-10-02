package com.alswns1201.wallet.service;

import java.time.LocalDate;

import com.alswns1201.wallet.domain.TransactionType;
import com.alswns1201.wallet.domain.WalletTransaction;

/**
 * @param balanceAfter 이 거래가 반영된 직후의 잔액 (자세한 의미는 WalletTransaction.balanceAfter)
 * @param businessDate 일일 한도를 센 날짜. PAY는 결제한 날, CANCEL은 원 결제의 날, CHARGE는 null
 */
public record TransactionResponse(
		Long transactionId,
		Long walletId,
		TransactionType type,
		long amount,
		long balanceAfter,
		Long originalTransactionId,
		LocalDate businessDate) {

	public static TransactionResponse from(WalletTransaction tx) {
		return new TransactionResponse(tx.getId(), tx.getWalletId(), tx.getType(), tx.getAmount(),
				tx.getBalanceAfter(), tx.getOriginalTransactionId(), tx.getBusinessDate());
	}
}
