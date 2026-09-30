package com.alswns1201.wallet.service;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.alswns1201.wallet.domain.Wallet;
import com.alswns1201.wallet.domain.WalletRepository;
import com.alswns1201.wallet.domain.WalletTransaction;
import com.alswns1201.wallet.domain.WalletTransactionRepository;
import com.alswns1201.wallet.support.ErrorCode;
import com.alswns1201.wallet.support.WalletException;

import lombok.RequiredArgsConstructor;

/**
 * 지갑 트랜잭션 처리. 충전·결제·취소는 락 없이 부르면 동시 요청에서 lost update가 난다 —
 * 반드시 WalletFacade(락)를 거쳐 호출한다. 생성·조회는 락이 필요 없어 컨트롤러가 직접 부른다.
 */
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

	@Transactional
	public TransactionResponse charge(Long walletId, long amount) {
		Wallet wallet = getWallet(walletId);
		wallet.charge(amount);
		return TransactionResponse.from(transactionRepository.save(WalletTransaction.charge(wallet, amount)));
	}

	@Transactional
	public TransactionResponse pay(Long walletId, long amount) {
		Wallet wallet = getWallet(walletId);
		wallet.pay(amount);
		return TransactionResponse.from(transactionRepository.save(WalletTransaction.pay(wallet, amount)));
	}

	/**
	 * 결제 취소. 원 결제 행은 그대로 두고 CANCEL 행을 추가한다.
	 * exists 확인은 평소 경로용이고, 동시에 두 요청이 확인을 통과해도 originalTransactionId unique 제약이 하나를 막는다.
	 * 막힌 쪽은 예외로 트랜잭션 전체가 롤백되므로 환불도 반영되지 않는다.
	 */
	@Transactional
	public TransactionResponse cancel(Long transactionId) {
		WalletTransaction original = transactionRepository.findById(transactionId)
				.orElseThrow(() -> new WalletException(ErrorCode.TRANSACTION_NOT_FOUND));
		if (!original.isPay()) {
			throw new WalletException(ErrorCode.NOT_CANCELABLE);
		}
		if (transactionRepository.existsByOriginalTransactionId(transactionId)) {
			throw new WalletException(ErrorCode.ALREADY_CANCELED);
		}

		Wallet wallet = getWallet(original.getWalletId());
		wallet.refund(original.getAmount());
		try {
			// 제약 위반을 이 메서드 안에서 잡으려고 바로 flush 한다 (커밋 시점까지 미루면 ALREADY_CANCELED로 못 바꾼다)
			return TransactionResponse.from(transactionRepository.saveAndFlush(WalletTransaction.cancelOf(original, wallet)));
		} catch (DataIntegrityViolationException e) {
			throw new WalletException(ErrorCode.ALREADY_CANCELED);
		}
	}

	@Transactional(readOnly = true)
	public Long findWalletIdOf(Long transactionId) {
		return transactionRepository.findById(transactionId)
				.map(WalletTransaction::getWalletId)
				.orElseThrow(() -> new WalletException(ErrorCode.TRANSACTION_NOT_FOUND));
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
