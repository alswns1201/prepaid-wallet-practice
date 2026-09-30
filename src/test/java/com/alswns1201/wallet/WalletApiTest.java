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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class WalletApiTest {

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
