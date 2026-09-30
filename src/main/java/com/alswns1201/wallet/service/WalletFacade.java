package com.alswns1201.wallet.service;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * 잔액을 바꾸는 요청(충전·결제·취소)의 입구. 락이 트랜잭션 전체를 감싸게 한다.
 *
 *   락 획득 → [WalletService: 트랜잭션 시작 → 처리 → 커밋] → 락 해제
 *
 * 락과 트랜잭션을 한 클래스에 두지 않는 이유:
 * - @Transactional 메서드 안에서 락을 잡으면 락 해제 → 커밋 순서가 돼서, 그 틈에 다음 요청이 옛 잔액을 읽는다.
 * - 같은 클래스의 @Transactional 메서드를 this.charge()로 부르면 프록시를 안 거쳐 트랜잭션이 아예 안 열린다.
 * 그래서 락은 이 클래스가, 트랜잭션은 다른 빈인 WalletService가 맡는다.
 */
@Service
@RequiredArgsConstructor
public class WalletFacade {

	private final WalletLockManager lockManager;
	private final WalletService walletService;

	public TransactionResponse charge(Long walletId, long amount) {
		return lockManager.executeWithLock(walletId, () -> walletService.charge(walletId, amount));
	}

	public TransactionResponse pay(Long walletId, long amount) {
		return lockManager.executeWithLock(walletId, () -> walletService.pay(walletId, amount));
	}

	/** 취소는 거래 ID로 들어오므로, 어느 지갑을 잠글지 먼저 찾는다 (거래의 지갑은 바뀌지 않아 락 밖에서 읽어도 된다). */
	public TransactionResponse cancel(Long transactionId) {
		Long walletId = walletService.findWalletIdOf(transactionId);
		return lockManager.executeWithLock(walletId, () -> walletService.cancel(transactionId));
	}
}
