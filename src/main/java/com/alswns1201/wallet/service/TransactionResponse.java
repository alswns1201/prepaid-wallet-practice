package com.alswns1201.wallet.service;

import com.alswns1201.wallet.domain.TransactionStatus;
import com.alswns1201.wallet.domain.TransactionType;
import com.alswns1201.wallet.domain.WalletTransaction;

public record TransactionResponse(
		Long transactionId,
		Long walletId,
		TransactionType type,
		long amount,
		long balanceAfter,
		TransactionStatus status,
		Long originalTransactionId) {

	public static TransactionResponse from(WalletTransaction tx) {
		return new TransactionResponse(tx.getId(), tx.getWalletId(), tx.getType(), tx.getAmount(),
				tx.getBalanceAfter(), tx.getStatus(), tx.getOriginalTransactionId());
	}
}
