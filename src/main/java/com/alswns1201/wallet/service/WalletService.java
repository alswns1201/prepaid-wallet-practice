package com.alswns1201.wallet.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alswns1201.wallet.domain.Wallet;
import com.alswns1201.wallet.domain.WalletRepository;
import com.alswns1201.wallet.domain.WalletTransactionRepository;
import com.alswns1201.wallet.support.ErrorCode;
import com.alswns1201.wallet.support.WalletException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class WalletService {

	private final WalletRepository walletRepository;
	private final WalletTransactionRepository transactionRepository;

	@Transactional
	public WalletResponse create(Long userId) {
		if (walletRepository.existsByUserId(userId)) {
			throw new WalletException(ErrorCode.DUPLICATE_WALLET);
		}
		return WalletResponse.from(walletRepository.save(new Wallet(userId)));
	}

	@Transactional(readOnly = true)
	public WalletResponse get(Long walletId) {
		return WalletResponse.from(getWallet(walletId));
	}

	@Transactional(readOnly = true)
	public List<TransactionResponse> transactions(Long walletId) {
		getWallet(walletId);
		return transactionRepository.findByWalletIdOrderByIdDesc(walletId).stream()
				.map(TransactionResponse::from)
				.toList();
	}

	private Wallet getWallet(Long walletId) {
		return walletRepository.findById(walletId)
				.orElseThrow(() -> new WalletException(ErrorCode.WALLET_NOT_FOUND));
	}
}
