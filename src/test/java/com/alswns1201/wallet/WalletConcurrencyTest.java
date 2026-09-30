package com.alswns1201.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.alswns1201.wallet.ConcurrentRunner.Result;
import com.alswns1201.wallet.service.TransactionResponse;
import com.alswns1201.wallet.service.WalletService;

/**
 * 동시 요청 테스트. 테스트 메서드에 @Transactional을 붙이지 않는다 —
 * 요청마다 자기 트랜잭션에서 커밋돼야 실제 서버에 요청이 몰린 상황과 같아진다.
 */
@SpringBootTest
class WalletConcurrencyTest {

	private static final int REQUESTS = 100;
	private static final AtomicLong USER_SEQ = new AtomicLong(1_000_000);

	@Autowired
	WalletService walletService;

	@Test
	@DisplayName("[락 없음] 1,000원 충전 100건 동시 요청 → 전부 성공인데 잔액은 100,000원보다 적다 (lost update)")
	void concurrentChargeWithoutLock() throws InterruptedException {
		Long walletId = walletService.create(USER_SEQ.incrementAndGet()).walletId();

		Result result = ConcurrentRunner.run(REQUESTS, i -> walletService.charge(walletId, 1_000));

		long balance = walletService.get(walletId).balance();
		List<TransactionResponse> charges = walletService.transactions(walletId);
		long distinctBalanceAfter = charges.stream().mapToLong(TransactionResponse::balanceAfter).distinct().count();
		System.out.printf(">>> 성공 %d건 → 기대 잔액 %,d원 / 실제 잔액 %,d원%n", result.success(), result.success() * 1_000L, balance);
		System.out.printf(">>> CHARGE 행 %d줄, balanceAfter 서로 다른 값 %d개 (같은 잔액을 동시에 읽은 흔적)%n",
				charges.size(), distinctBalanceAfter);

		// 요청은 성공했고 원장에도 성공 건수만큼 쌓였다
		assertThat(charges).hasSize(result.success());
		// 그런데 잔액은 서로의 갱신을 덮어써서 성공 건수만큼 늘지 않았다
		assertThat(balance).isLessThan(result.success() * 1_000L);
		// 락이 없으니 balanceAfter가 겹치는 행이 생긴다
		assertThat(distinctBalanceAfter).isLessThan(charges.size());
	}
}
