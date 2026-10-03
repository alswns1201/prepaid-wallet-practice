package com.alswns1201.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.alswns1201.wallet.ConcurrentRunner.Result;
import com.alswns1201.wallet.service.IdempotencyManager;
import com.alswns1201.wallet.service.WalletFacade;
import com.alswns1201.wallet.service.WalletService;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Idempotency-Key 헤더: 같은 키의 재시도는 처리하지 않고 첫 응답을 돌려준다. */
@AutoConfigureMockMvc
class IdempotencyTest extends IntegrationTestSupport {

	private static final AtomicLong USER_SEQ = new AtomicLong(3_000_000);

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	WalletService walletService;

	@Autowired
	WalletFacade walletFacade;

	@Autowired
	IdempotencyManager idempotencyManager;

	@Test
	@DisplayName("같은 키로 결제를 두 번 보내면 한 번만 결제되고, 두 번째는 첫 응답(같은 거래 ID)을 그대로 받는다")
	void sameKeyPaysOnce() throws Exception {
		long walletId = newWalletWithBalance(10_000);
		String key = newKey();

		long first = transactionId(pay(walletId, 3_000, key));
		long second = transactionId(pay(walletId, 3_000, key));  // 응답이 끊겨 재시도한 상황

		assertThat(second).isEqualTo(first);
		assertThat(balanceOf(walletId)).isEqualTo(7_000);
		mockMvc.perform(get("/api/wallets/{id}/transactions", walletId))
				.andExpect(jsonPath("$", hasSize(2)));  // CHARGE 1 + PAY 1
	}

	@Test
	@DisplayName("키가 다르면 별개의 결제다 — 둘 다 처리된다")
	void differentKeysPayTwice() throws Exception {
		long walletId = newWalletWithBalance(10_000);

		long first = transactionId(pay(walletId, 3_000, newKey()));
		long second = transactionId(pay(walletId, 3_000, newKey()));

		assertThat(second).isNotEqualTo(first);
		assertThat(balanceOf(walletId)).isEqualTo(4_000);
	}

	@Test
	@DisplayName("헤더가 없으면 멱등 처리 없이 지금까지처럼 매번 처리한다")
	void noHeaderProcessesEveryTime() throws Exception {
		long walletId = newWalletWithBalance(10_000);

		pay(walletId, 3_000, null).andExpect(status().isOk());
		pay(walletId, 3_000, null).andExpect(status().isOk());

		assertThat(balanceOf(walletId)).isEqualTo(4_000);
	}

	@Test
	@DisplayName("같은 키로 금액이 다른 결제를 보내면 422 IDEMPOTENCY_KEY_REUSED — 첫 응답을 돌려주지도, 새로 결제하지도 않는다")
	void sameKeyDifferentRequest() throws Exception {
		long walletId = newWalletWithBalance(10_000);
		String key = newKey();
		pay(walletId, 3_000, key).andExpect(status().isOk());

		pay(walletId, 5_000, key)
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code", is("IDEMPOTENCY_KEY_REUSED")));

		assertThat(balanceOf(walletId)).isEqualTo(7_000);
	}

	@Test
	@DisplayName("실패한 요청(잔액 부족)은 저장하지 않는다 — 충전 후 같은 키로 다시 보내면 이번엔 결제된다")
	void failedRequestCanBeRetried() throws Exception {
		long walletId = newWalletWithBalance(1_000);
		String key = newKey();

		pay(walletId, 3_000, key)
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code", is("INSUFFICIENT_BALANCE")));
		walletFacade.charge(walletId, 5_000);

		pay(walletId, 3_000, key).andExpect(status().isOk());
		assertThat(balanceOf(walletId)).isEqualTo(3_000);
	}

	@Test
	@DisplayName("충전도 같은 키로 두 번 보내면 한 번만 충전된다")
	void sameKeyChargesOnce() throws Exception {
		long walletId = newWalletWithBalance(0);
		String key = newKey();

		charge(walletId, 5_000, key).andExpect(status().isOk());
		charge(walletId, 5_000, key).andExpect(status().isOk());

		assertThat(balanceOf(walletId)).isEqualTo(5_000);
	}

	@Test
	@DisplayName("취소를 같은 키로 재시도하면 409 ALREADY_CANCELED가 아니라 첫 취소 응답을 그대로 받는다")
	void sameKeyCancelReplays() throws Exception {
		long walletId = newWalletWithBalance(10_000);
		long payId = transactionId(pay(walletId, 3_000, newKey()));
		String key = newKey();

		long first = transactionId(cancel(payId, key));
		long second = transactionId(cancel(payId, key));

		assertThat(second).isEqualTo(first);
		assertThat(balanceOf(walletId)).isEqualTo(10_000);
		// 키 없이 다시 취소하면 지금까지처럼 409
		cancel(payId, null)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code", is("ALREADY_CANCELED")));
	}

	@Test
	@DisplayName("헤더 값이 비어 있으면 400 INVALID_IDEMPOTENCY_KEY")
	void blankKey() throws Exception {
		long walletId = newWalletWithBalance(10_000);

		pay(walletId, 3_000, " ")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code", is("INVALID_IDEMPOTENCY_KEY")));
	}

	@Test
	@DisplayName("[동시] 같은 키로 결제 100건이 동시에 들어와도 실제 결제는 1건 — 나머지는 재생(같은 거래 ID) 또는 409 처리 중")
	void concurrentSameKey() throws InterruptedException {
		Long walletId = walletService.create(USER_SEQ.incrementAndGet()).walletId();
		walletFacade.charge(walletId, 100_000);
		String key = newKey();
		Set<Long> transactionIds = ConcurrentHashMap.newKeySet();

		// 컨트롤러가 하는 일(멱등 처리 → Facade)을 그대로 100개 스레드에서 부른다
		Result result = ConcurrentRunner.run(100, i -> transactionIds.add(
				idempotencyManager.execute(key, "PAY:" + walletId + ":3000", () -> walletFacade.pay(walletId, 3_000))
						.transactionId()));

		System.out.printf(">>> [멱등성, 같은 키 100건] 성공(처리+재생) %d건 / 처리 중 409 %d건 → 거래 ID %s, 잔액 %,d원%n",
				result.success(), result.failure("IDEMPOTENCY_IN_PROGRESS"), transactionIds, balanceOf(walletId));

		assertThat(result.success() + result.failure("IDEMPOTENCY_IN_PROGRESS")).isEqualTo(100);
		assertThat(transactionIds).hasSize(1);  // 성공 응답은 전부 같은 거래
		assertThat(balanceOf(walletId)).isEqualTo(97_000);
		assertThat(walletService.transactions(walletId)).hasSize(2);  // CHARGE 1 + PAY 1
	}

	private Long newWalletWithBalance(long balance) {
		Long walletId = walletService.create(USER_SEQ.incrementAndGet()).walletId();
		if (balance > 0) {
			walletFacade.charge(walletId, balance);
		}
		return walletId;
	}

	private long balanceOf(Long walletId) {
		return walletService.get(walletId).balance();
	}

	private static String newKey() {
		return UUID.randomUUID().toString();
	}

	private ResultActions pay(long walletId, long amount, String key) throws Exception {
		var request = post("/api/wallets/{id}/pay", walletId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\": " + amount + "}");
		return mockMvc.perform(key == null ? request : request.header("Idempotency-Key", key));
	}

	private ResultActions charge(long walletId, long amount, String key) throws Exception {
		return mockMvc.perform(post("/api/wallets/{id}/charge", walletId).contentType(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", key)
				.content("{\"amount\": " + amount + "}"));
	}

	private ResultActions cancel(long transactionId, String key) throws Exception {
		var request = post("/api/transactions/{id}/cancel", transactionId);
		return mockMvc.perform(key == null ? request : request.header("Idempotency-Key", key));
	}

	private long transactionId(ResultActions result) throws Exception {
		String body = result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(body).get("transactionId").asLong();
	}
}
