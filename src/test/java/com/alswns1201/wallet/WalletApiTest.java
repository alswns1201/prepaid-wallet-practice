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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

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

	private long createWallet(long userId) throws Exception {
		String body = mockMvc.perform(post("/api/wallets").contentType(MediaType.APPLICATION_JSON)
						.content("{\"userId\": " + userId + "}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(body).get("walletId").asLong();
	}
}
