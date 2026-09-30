package com.alswns1201.wallet.service;

import com.alswns1201.wallet.domain.Wallet;

public record WalletResponse(Long walletId, Long userId, long balance) {

	public static WalletResponse from(Wallet wallet) {
		return new WalletResponse(wallet.getId(), wallet.getUserId(), wallet.getBalance());
	}
}
