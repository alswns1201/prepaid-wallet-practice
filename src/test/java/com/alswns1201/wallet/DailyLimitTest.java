package com.alswns1201.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.alswns1201.wallet.ConcurrentRunner.Result;
import com.alswns1201.wallet.service.DailyLimitManager;
import com.alswns1201.wallet.service.WalletFacade;
import com.alswns1201.wallet.service.WalletService;
import com.alswns1201.wallet.support.ErrorCode;
import com.alswns1201.wallet.support.WalletException;

/** 일일 결제 한도 1,000,000원 (application.yml wallet.daily-pay-limit). */
class DailyLimitTest extends IntegrationTestSupport {

	private static final long LIMIT = 1_000_000;
	private static final LocalDate YESTERDAY = LocalDate.of(2026, 10, 1);
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 2);
	private static final AtomicLong USER_SEQ = new AtomicLong(2_000_000);

	@Autowired
	WalletFacade walletFacade;

	@Autowired
	WalletService walletService;

	@Autowired
	DailyLimitManager dailyLimitManager;

	@Test
	@DisplayName("한도까지는 결제되고, 1원이라도 넘으면 422 DAILY_LIMIT_EXCEEDED — 잔액은 그대로")
	void exceedLimit() {
		clock.setDate(TODAY);
		Long walletId = newWalletWithBalance(2_000_000);

		walletFacade.pay(walletId, 600_000);
		walletFacade.pay(walletId, 400_000);  // 정확히 한도까지는 허용

		assertErrorCode(() -> walletFacade.pay(walletId, 1), ErrorCode.DAILY_LIMIT_EXCEEDED);
		assertThat(dailyLimitManager.used(walletId, TODAY)).isEqualTo(LIMIT);
		assertThat(balanceOf(walletId)).isEqualTo(1_000_000);
	}

	@Test
	@DisplayName("잔액 부족으로 결제가 실패하면, 먼저 차지했던 한도를 되돌린다 (보상)")
	void releaseWhenPayFails() {
		clock.setDate(TODAY);
		Long walletId = newWalletWithBalance(1_000);

		assertErrorCode(() -> walletFacade.pay(walletId, 5_000), ErrorCode.INSUFFICIENT_BALANCE);

		// 보상이 없으면 Redis에 5,000원이 남아서, 결제는 안 됐는데 한도만 줄어든다
		assertThat(dailyLimitManager.used(walletId, TODAY)).isZero();
	}

	@Test
	@DisplayName("결제를 취소하면 그 금액만큼 한도가 돌아온다")
	void releaseOnCancel() {
		clock.setDate(TODAY);
		Long walletId = newWalletWithBalance(2_000_000);
		Long payId = walletFacade.pay(walletId, LIMIT).transactionId();
		assertErrorCode(() -> walletFacade.pay(walletId, 1), ErrorCode.DAILY_LIMIT_EXCEEDED);

		walletFacade.cancel(payId);

		assertThat(dailyLimitManager.used(walletId, TODAY)).isZero();
		walletFacade.pay(walletId, LIMIT);  // 돌아온 한도로 다시 결제할 수 있다
	}

	@Test
	@DisplayName("어제 결제를 오늘 취소하면 어제 한도가 돌아오고, 오늘 한도는 그대로다 (businessDate)")
	void cancelYesterdayPayment() {
		Long walletId = newWalletWithBalance(2_000_000);
		clock.setDate(YESTERDAY);
		Long yesterdayPayId = walletFacade.pay(walletId, 300_000).transactionId();

		clock.setDate(TODAY);
		walletFacade.pay(walletId, 200_000);
		var canceled = walletFacade.cancel(yesterdayPayId);

		assertThat(canceled.businessDate()).isEqualTo(YESTERDAY);
		assertThat(dailyLimitManager.used(walletId, YESTERDAY)).isZero();
		// 오늘 날짜로 돌려줬다면 0원이 됐을 것 (오늘 결제 200,000원이 사라짐)
		assertThat(dailyLimitManager.used(walletId, TODAY)).isEqualTo(200_000);
	}

	@Test
	@DisplayName("날짜가 바뀌면 한도가 새로 시작된다")
	void newDayNewLimit() {
		Long walletId = newWalletWithBalance(2_000_000);
		clock.setDate(YESTERDAY);
		walletFacade.pay(walletId, LIMIT);
		assertErrorCode(() -> walletFacade.pay(walletId, 1), ErrorCode.DAILY_LIMIT_EXCEEDED);

		clock.setDate(TODAY);
		walletFacade.pay(walletId, 1_000);

		assertThat(dailyLimitManager.used(walletId, TODAY)).isEqualTo(1_000);
	}

	@Test
	@DisplayName("[락 안에서 GET/INCRBY] 20,000원 결제 100건 동시 요청 → 정확히 50건만 성공, 한도를 넘지 않는다")
	void concurrentPayWithinLimit() throws InterruptedException {
		clock.setDate(TODAY);
		Long walletId = newWalletWithBalance(3_000_000);

		Result result = ConcurrentRunner.run(100, i -> walletFacade.pay(walletId, 20_000));

		long used = dailyLimitManager.used(walletId, TODAY);
		System.out.printf(">>> [일일 한도, 락 안] 성공 %d건 / 한도 초과 %d건 → 사용액 %,d원 (한도 %,d원)%n",
				result.success(), result.failure("DAILY_LIMIT_EXCEEDED"), used, LIMIT);

		assertThat(result.success()).isEqualTo(50);
		assertThat(result.failure("DAILY_LIMIT_EXCEEDED")).isEqualTo(50);
		assertThat(used).isEqualTo(LIMIT);
		assertThat(balanceOf(walletId)).isEqualTo(3_000_000 - LIMIT);
	}

	private Long newWalletWithBalance(long balance) {
		Long walletId = walletService.create(USER_SEQ.incrementAndGet()).walletId();
		walletFacade.charge(walletId, balance);
		return walletId;
	}

	private long balanceOf(Long walletId) {
		return walletService.get(walletId).balance();
	}

	private static void assertErrorCode(Runnable call, ErrorCode expected) {
		assertThatThrownBy(call::run)
				.isInstanceOf(WalletException.class)
				.extracting("errorCode")
				.isEqualTo(expected);
	}
}
