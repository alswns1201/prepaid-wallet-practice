package com.alswns1201.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import com.alswns1201.wallet.ConcurrentRunner.Result;
import com.alswns1201.wallet.service.TransactionResponse;
import com.alswns1201.wallet.service.WalletFacade;
import com.alswns1201.wallet.service.WalletLockManager;
import com.alswns1201.wallet.service.WalletService;

/**
 * 동시 요청 테스트. 테스트 메서드에 @Transactional을 붙이지 않는다 —
 * 요청마다 자기 트랜잭션에서 커밋돼야 실제 서버에 요청이 몰린 상황과 같아진다.
 */
@Import(WalletConcurrencyTest.LockInsideTransaction.class)
class WalletConcurrencyTest extends IntegrationTestSupport {

	private static final int REQUESTS = 100;
	private static final AtomicLong USER_SEQ = new AtomicLong(1_000_000);

	@Autowired
	WalletService walletService;

	@Autowired
	WalletFacade walletFacade;

	@Autowired
	LockInsideTransaction lockInsideTransaction;

	@Test
	@DisplayName("[락 없음] 1,000원 충전 100건 동시 요청 → 전부 성공인데 잔액은 100,000원보다 적다 (lost update)")
	void concurrentChargeWithoutLock() throws InterruptedException {
		Long walletId = newWallet();

		// 1_000 == 1000. 숫자 사이의 밑줄은 자릿수를 읽기 쉽게 하는 구분자일 뿐 값에는 영향이 없다 (Java 7+)
		// WalletService를 직접 부른다 = 락을 건너뛴다 (대조군)
		Result result = ConcurrentRunner.run(REQUESTS, i -> walletService.charge(walletId, 1_000));

		long balance = balanceOf(walletId);
		List<TransactionResponse> charges = walletService.transactions(walletId);
		long distinctBalanceAfter = distinctBalanceAfter(charges);
		System.out.printf(">>> [락 없음] 성공 %d건 → 기대 잔액 %,d원 / 실제 잔액 %,d원, balanceAfter 서로 다른 값 %d개%n",
				result.success(), result.success() * 1_000L, balance, distinctBalanceAfter);

		// 요청은 성공했고 원장에도 성공 건수만큼 쌓였다
		assertThat(charges).hasSize(result.success());
		// 그런데 잔액은 서로의 갱신을 덮어써서 성공 건수만큼 늘지 않았다
		assertThat(balance).isLessThan(result.success() * 1_000L);
		// 원인 쪽 증거: "서로 다른 값 개수 < 행 개수" = 겹치는 balanceAfter가 적어도 하나 있다
		// = 여러 요청이 같은 잔액(예: 0원)을 읽고 각자 +1,000 해서 1,000원으로 덮어썼다.
		// (위 잔액 검증은 "돈이 사라졌다"는 결과, 이 검증은 "같은 잔액을 동시에 읽었다"는 원인을 본다)
		assertThat(distinctBalanceAfter).isLessThan(charges.size());
	}

	@Test
	@DisplayName("[지갑 락] 1,000원 충전 100건 동시 요청 → 잔액 정확히 100,000원, balanceAfter 100개 전부 다름")
	void concurrentChargeWithLock() throws InterruptedException {
		Long walletId = newWallet();

		// WalletFacade를 거친다 = 락 → 트랜잭션 → 커밋 → 락 해제
		Result result = ConcurrentRunner.run(REQUESTS, i -> walletFacade.charge(walletId, 1_000));

		long balance = balanceOf(walletId);
		List<TransactionResponse> charges = walletService.transactions(walletId);
		System.out.printf(">>> [지갑 락] 성공 %d건 → 잔액 %,d원, balanceAfter 서로 다른 값 %d개 (%dms)%n",
				result.success(), balance, distinctBalanceAfter(charges), result.elapsedMs());

		assertThat(result.success()).isEqualTo(REQUESTS);
		assertThat(balance).isEqualTo(100_000);
		// 한 줄로 서서 처리됐으니 balanceAfter가 1,000 / 2,000 / … / 100,000 으로 전부 달라야 한다
		assertThat(distinctBalanceAfter(charges)).isEqualTo(REQUESTS);
	}

	@Test
	@DisplayName("[지갑 락] 잔액 20,000원에 1,000원 결제 100건 동시 요청 → 정확히 20건 성공, 80건 잔액 부족, 잔액 0원")
	void concurrentPayWithLock() throws InterruptedException {
		Long walletId = newWallet();
		walletFacade.charge(walletId, 20_000);

		Result result = ConcurrentRunner.run(REQUESTS, i -> walletFacade.pay(walletId, 1_000));

		// 잔액 검사와 차감이 락 안에서 한 번에 일어나서, 20건을 넘겨 결제되는 일(초과 결제)이 없다
		assertThat(result.success()).isEqualTo(20);
		assertThat(result.failure("INSUFFICIENT_BALANCE")).isEqualTo(80);
		assertThat(balanceOf(walletId)).isZero();
	}

	@Test
	@DisplayName("[잘못된 락 위치] @Transactional 안에서 락을 잡으면 락 해제 → 커밋 사이 틈으로 lost update가 다시 생긴다")
	void lockInsideTransaction() throws InterruptedException {
		Long walletId = newWallet();

		Result result = ConcurrentRunner.run(REQUESTS, i -> {
			try {
				lockInsideTransaction.charge(walletId, 1_000);
			} catch (InterruptedException e) {
				throw new IllegalStateException(e);
			}
		});

		long balance = balanceOf(walletId);
		System.out.printf(">>> [잘못된 락 위치] 성공 %d건 → 기대 잔액 %,d원 / 실제 잔액 %,d원%n",
				result.success(), result.success() * 1_000L, balance);

		assertThat(balance).isLessThan(result.success() * 1_000L);
	}

	private Long newWallet() {
		return walletService.create(USER_SEQ.incrementAndGet()).walletId();
	}

	private long balanceOf(Long walletId) {
		return walletService.get(walletId).balance();
	}

	private static long distinctBalanceAfter(List<TransactionResponse> transactions) {
		return transactions.stream().mapToLong(TransactionResponse::balanceAfter).distinct().count();
	}

	/**
	 * 일부러 잘못 만든 예: 트랜잭션이 먼저 열리고 그 안에서 락을 잡는다.
	 *
	 *   트랜잭션 시작 → 락 획득 → 충전 → 락 해제 → (메서드가 끝난 뒤) 커밋
	 *                                        ↑ 이 틈에 다음 요청이 락을 잡고, 아직 커밋 안 된 = 옛 잔액을 읽는다
	 *
	 * 안쪽 walletService.charge()의 @Transactional은 이미 열린 트랜잭션에 참여(REQUIRED)할 뿐이라 커밋은 여기서 일어난다.
	 * 게다가 JPA는 변경을 커밋 직전에 flush 하므로, 잔액 UPDATE 자체가 락을 푼 뒤에 DB로 나간다.
	 *
	 * 그대로 두면 이 틈이 아주 짧아서 5번 중 2번은 멀쩡하고, 깨져도 1,000~2,000원 차이였다.
	 * 운영에서는 GC 멈춤, 느린 커밋, 락 뒤의 후처리 코드 때문에 틈이 벌어진다 — 테스트에서는 후처리 10ms로 그 상황을 만든다.
	 */
	@TestConfiguration
	static class LockInsideTransaction {

		@Autowired
		WalletLockManager lockManager;

		@Autowired
		WalletService walletService;

		@Transactional
		public TransactionResponse charge(Long walletId, long amount) throws InterruptedException {
			TransactionResponse response = lockManager.executeWithLock(walletId, () -> walletService.charge(walletId, amount));
			Thread.sleep(10); // 락 해제 후 커밋 전까지의 후처리 (응답 조립, 로그, 이벤트 발행 등)를 흉내
			return response;
		}
	}
}
