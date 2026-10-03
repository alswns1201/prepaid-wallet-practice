package com.alswns1201.wallet.service;

import java.time.LocalDate;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 잔액을 바꾸는 요청(충전·결제·취소)의 입구. 락이 트랜잭션 전체를 감싸게 한다.
 *
 *   락 획득 → [WalletService: 트랜잭션 시작 → 처리 → 커밋] → 락 해제
 *
 * 락과 트랜잭션을 한 클래스에 두지 않는 이유:
 * - @Transactional 메서드 안에서 락을 잡으면 락 해제 → 커밋 순서가 돼서, 그 틈에 다음 요청이 옛 잔액을 읽는다.
 * - 같은 클래스의 @Transactional 메서드를 this.charge()로 부르면 프록시를 안 거쳐 트랜잭션이 아예 안 열린다.
 * 그래서 락은 이 클래스가, 트랜잭션은 다른 빈인 WalletService가 맡는다.
 *
 * 일일 한도(Redis)도 이 클래스에서 다룬다. Redis는 DB 트랜잭션에 묶이지 않아서 DB가 롤백돼도 같이 되돌아가지 않는다
 * → 트랜잭션 밖에서 "한도 차지 → 결제 → 실패하면 한도 반환"을 직접 챙긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletFacade {

	private final WalletLockManager lockManager;
	private final WalletService walletService;
	private final DailyLimitManager dailyLimitManager;

	public TransactionResponse charge(Long walletId, long amount) {
		return lockManager.executeWithLock(walletId, () -> walletService.charge(walletId, amount));
	}

	/**
	 * 락 안에서: 한도 차지(Lua 스크립트) → 결제 트랜잭션.
	 * 결제가 실패하면(잔액 부족 등) DB는 롤백되지만 Redis에 더한 금액은 남으므로 release로 되돌린다 (보상).
	 */
	public TransactionResponse pay(Long walletId, long amount) {
		return lockManager.executeWithLock(walletId, () -> {
			LocalDate today = dailyLimitManager.today();
			dailyLimitManager.reserve(walletId, amount, today);
			try {
				return walletService.pay(walletId, amount, today);
			} catch (RuntimeException e) {
				dailyLimitManager.release(walletId, amount, today);
				throw e;
			}
		});
	}

	/**
	 * 취소는 거래 ID로 들어오므로, 어느 지갑을 잠글지 먼저 찾는다 (거래의 지갑은 바뀌지 않아 락 밖에서 읽어도 된다).
	 * 취소가 커밋된 뒤 원 결제한 날(businessDate)의 한도를 돌려준다.
	 */
	public TransactionResponse cancel(Long transactionId) {
		Long walletId = walletService.findWalletIdOf(transactionId);
		return lockManager.executeWithLock(walletId, () -> {
			TransactionResponse canceled = walletService.cancel(transactionId);
			try {
				dailyLimitManager.release(walletId, canceled.amount(), canceled.businessDate());
			} catch (RuntimeException e) {
				// 환불은 이미 커밋됐다. 여기서 예외를 던지면 사용자는 실패로 보지만 실제론 취소된 상태가 된다.
				// 한도 반환이 빠지는 쪽(사용자가 그날 덜 쓸 수 있음)이 덜 위험하다고 보고 로그만 남긴다.
				log.warn("일일 한도 반환 실패. walletId={}, transactionId={}", walletId, transactionId, e);
			}
			return canceled;
		});
	}
}
