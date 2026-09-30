package com.alswns1201.wallet;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
class WalletApiTest extends IntegrationTestSupport {

	private static final AtomicLong USER_SEQ = new AtomicLong();

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ObjectMapper objectMapper;

	@Test
	@DisplayName("지갑을 만들면 잔액 0원, 거래 내역 없음")
	void createWallet() throws Exception {
		long walletId = createWallet(USER_SEQ.incrementAndGet());

		mockMvc.perform(get("/api/wallets/{id}", walletId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.balance", is(0)));

		mockMvc.perform(get("/api/wallets/{id}/transactions", walletId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	@DisplayName("같은 사용자가 지갑을 또 만들면 409")
	void duplicateWallet() throws Exception {
		long userId = USER_SEQ.incrementAndGet();
		createWallet(userId);

		mockMvc.perform(post("/api/wallets").contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\": " + userId + "}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code", is("DUPLICATE_WALLET")));
	}

	@Test
	@DisplayName("없는 지갑 조회 → 404 ProblemDetail")
	void walletNotFound() throws Exception {
		mockMvc.perform(get("/api/wallets/{id}", 999_999))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code", is("WALLET_NOT_FOUND")));
	}

	@Test
	@DisplayName("충전하면 잔액이 늘고 CHARGE 거래가 쌓인다")
	void charge() throws Exception {
		long walletId = createWallet(USER_SEQ.incrementAndGet());

		charge(walletId, 10_000)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.type", is("CHARGE")))
				.andExpect(jsonPath("$.amount", is(10_000)))
				.andExpect(jsonPath("$.balanceAfter", is(10_000)));
		charge(walletId, 5_000)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.balanceAfter", is(15_000)));

		mockMvc.perform(get("/api/wallets/{id}", walletId))
				.andExpect(jsonPath("$.balance", is(15_000)));
		mockMvc.perform(get("/api/wallets/{id}/transactions", walletId))
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[0].amount", is(5_000)));
	}

	@ParameterizedTest
	@ValueSource(longs = {0, -1_000})
	@DisplayName("0원 이하 충전 → 400, 잔액·거래 변화 없음")
	void chargeInvalidAmount(long amount) throws Exception {
		long walletId = createWallet(USER_SEQ.incrementAndGet());

		charge(walletId, amount)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code", is("INVALID_AMOUNT")));

		mockMvc.perform(get("/api/wallets/{id}", walletId))
				.andExpect(jsonPath("$.balance", is(0)));
		mockMvc.perform(get("/api/wallets/{id}/transactions", walletId))
				.andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	@DisplayName("없는 지갑에 충전 → 404")
	void chargeWalletNotFound() throws Exception {
		charge(999_999, 1_000)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code", is("WALLET_NOT_FOUND")));
	}

	@Test
	@DisplayName("결제하면 잔액이 줄고 PAY 거래가 쌓인다")
	void pay() throws Exception {
		long walletId = createWallet(USER_SEQ.incrementAndGet());
		charge(walletId, 10_000).andExpect(status().isOk());

		pay(walletId, 3_000)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.type", is("PAY")))
				.andExpect(jsonPath("$.amount", is(3_000)))
				.andExpect(jsonPath("$.balanceAfter", is(7_000)));
		// 잔액을 정확히 0원까지 쓰는 건 허용
		pay(walletId, 7_000)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.balanceAfter", is(0)));

		mockMvc.perform(get("/api/wallets/{id}", walletId))
				.andExpect(jsonPath("$.balance", is(0)));
		mockMvc.perform(get("/api/wallets/{id}/transactions", walletId))
				.andExpect(jsonPath("$", hasSize(3)))
				.andExpect(jsonPath("$[0].type", is("PAY")));
	}

	@Test
	@DisplayName("잔액보다 큰 결제 → 422, 잔액·거래 변화 없음")
	void payInsufficientBalance() throws Exception {
		long walletId = createWallet(USER_SEQ.incrementAndGet());
		charge(walletId, 5_000).andExpect(status().isOk());

		pay(walletId, 5_001)
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code", is("INSUFFICIENT_BALANCE")));

		mockMvc.perform(get("/api/wallets/{id}", walletId))
				.andExpect(jsonPath("$.balance", is(5_000)));
		mockMvc.perform(get("/api/wallets/{id}/transactions", walletId))
				.andExpect(jsonPath("$", hasSize(1)));
	}

	@ParameterizedTest
	@ValueSource(longs = {0, -1_000})
	@DisplayName("0원 이하 결제 → 400")
	void payInvalidAmount(long amount) throws Exception {
		long walletId = createWallet(USER_SEQ.incrementAndGet());
		charge(walletId, 5_000).andExpect(status().isOk());

		pay(walletId, amount)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code", is("INVALID_AMOUNT")));
	}

	@Test
	@DisplayName("결제를 취소하면 환불되고, 원 결제 행은 그대로 둔 채 CANCEL 행이 추가된다")
	void cancel() throws Exception {
		long walletId = createWallet(USER_SEQ.incrementAndGet());
		charge(walletId, 10_000).andExpect(status().isOk());
		long payId = transactionId(pay(walletId, 3_000));

		cancel(payId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.type", is("CANCEL")))
				.andExpect(jsonPath("$.amount", is(3_000)))
				.andExpect(jsonPath("$.balanceAfter", is(10_000)))
				.andExpect(jsonPath("$.originalTransactionId", is((int) payId)));

		mockMvc.perform(get("/api/wallets/{id}", walletId))
				.andExpect(jsonPath("$.balance", is(10_000)));
		mockMvc.perform(get("/api/wallets/{id}/transactions", walletId))
				.andExpect(jsonPath("$", hasSize(3)))
				.andExpect(jsonPath("$[0].type", is("CANCEL")))
				.andExpect(jsonPath("$[1].type", is("PAY")))
				.andExpect(jsonPath("$[1].balanceAfter", is(7_000)));
	}

	@Test
	@DisplayName("같은 결제를 두 번 취소 → 409, 두 번째는 환불 안 됨")
	void cancelTwice() throws Exception {
		long walletId = createWallet(USER_SEQ.incrementAndGet());
		charge(walletId, 10_000).andExpect(status().isOk());
		long payId = transactionId(pay(walletId, 3_000));
		cancel(payId).andExpect(status().isOk());

		cancel(payId)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code", is("ALREADY_CANCELED")));

		mockMvc.perform(get("/api/wallets/{id}", walletId))
				.andExpect(jsonPath("$.balance", is(10_000)));
		mockMvc.perform(get("/api/wallets/{id}/transactions", walletId))
				.andExpect(jsonPath("$", hasSize(3)));
	}

	@Test
	@DisplayName("충전 거래나 취소 거래를 취소 → 422")
	void cancelNotPay() throws Exception {
		long walletId = createWallet(USER_SEQ.incrementAndGet());
		long chargeId = transactionId(charge(walletId, 10_000));
		long cancelId = transactionId(cancel(transactionId(pay(walletId, 3_000))));

		cancel(chargeId)
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code", is("NOT_CANCELABLE")));
		cancel(cancelId)
				.andExpect(status().isUnprocessableEntity())
				.andExpect(jsonPath("$.code", is("NOT_CANCELABLE")));
	}

	@Test
	@DisplayName("없는 거래 취소 → 404")
	void cancelNotFound() throws Exception {
		cancel(999_999)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code", is("TRANSACTION_NOT_FOUND")));
	}

	private ResultActions cancel(long transactionId) throws Exception {
		return mockMvc.perform(post("/api/transactions/{id}/cancel", transactionId));
	}

	private long transactionId(ResultActions result) throws Exception {
		String body = result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(body).get("transactionId").asLong();
	}

	private ResultActions pay(long walletId, long amount) throws Exception {
		return mockMvc.perform(post("/api/wallets/{id}/pay", walletId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\": " + amount + "}"));
	}

	private ResultActions charge(long walletId, long amount) throws Exception {
		return mockMvc.perform(post("/api/wallets/{id}/charge", walletId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"amount\": " + amount + "}"));
	}

	private long createWallet(long userId) throws Exception {
		String body = mockMvc.perform(post("/api/wallets").contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\": " + userId + "}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(body).get("walletId").asLong();
	}
}
