package com.alswns1201.wallet.service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.alswns1201.wallet.support.ErrorCode;
import com.alswns1201.wallet.support.WalletException;

/**
 * 지갑 단위 분산 락. "이 지갑은 지금 누가 쓰는 중"이라는 표시를 Redis 키(wallet:lock:{walletId}) 하나로 남긴다.
 * 서버가 여러 대여도 모두 같은 Redis를 보므로, 같은 지갑에 대한 요청은 한 줄로 서서 하나씩 처리된다.
 *
 * 반드시 @Transactional 바깥에서 호출해야 한다 — 락 해제가 커밋보다 먼저 일어나면
 * 다음 요청이 커밋 전 잔액을 읽어 lost update가 다시 생긴다. (그래서 WalletFacade → 락 → WalletService 순서)
 */
@Component
public class WalletLockManager {

	private static final String LOCK_PREFIX = "wallet:lock:";

	private final RedissonClient redissonClient;
	private final long waitMillis;

	public WalletLockManager(RedissonClient redissonClient, @Value("${wallet.lock.wait-millis}") long waitMillis) {
		this.redissonClient = redissonClient;
		this.waitMillis = waitMillis;
	}

	public <T> T executeWithLock(Long walletId, Supplier<T> task) {
		RLock lock = redissonClient.getLock(LOCK_PREFIX + walletId);
		boolean acquired = false;
		try {
			// 최대 waitMillis 동안 기다린다. 락이 풀리면 Redis pub/sub 알림을 받고 깨어난다 (계속 물어보지 않는다).
			// leaseTime(자동 만료)을 주지 않으면 watchdog이 작업하는 동안 락을 자동 연장하고,
			// 서버가 죽어 연장이 멈추면 기본 30초 뒤 만료돼 락이 영원히 남지 않는다.
			acquired = lock.tryLock(waitMillis, TimeUnit.MILLISECONDS);
			if (!acquired) {
				throw new WalletException(ErrorCode.LOCK_TIMEOUT);
			}
			return task.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new WalletException(ErrorCode.LOCK_TIMEOUT);
		} finally {
			// 락 값에는 주인(이 스레드) ID가 들어 있어서 남의 락은 풀 수 없다. 내가 잡은 락만 푼다.
			if (acquired && lock.isHeldByCurrentThread()) {
				lock.unlock();
			}
		}
	}
}
