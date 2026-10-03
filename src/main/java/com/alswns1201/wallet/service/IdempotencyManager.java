package com.alswns1201.wallet.service;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.alswns1201.wallet.support.ErrorCode;
import com.alswns1201.wallet.support.WalletException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

/**
 * 멱등성 키(Idempotency-Key) 처리. 같은 키로 요청이 여러 번 와도 실제 처리는 한 번만 하고, 나머지는 첫 응답을 그대로 돌려준다.
 *
 * 멱등성 = "같은 요청을 여러 번 해도 한 번 한 것과 결과가 같다"는 성질. 멱등성 키는 클라이언트가 요청마다 만들어 붙이는 고유값이다.
 * 응답이 끊겨 클라이언트가 재시도해도(같은 키) 두 번 결제되지 않게 하려는 것.
 * 락 키(지갑)·한도 키(지갑+날짜)와 달리 이 키는 "요청 하나"를 구분한다 — 서로 대신할 수 없다.
 *
 * Redis 키 idem:{키} 하나에 상태를 둔다.
 *   1. SET NX로 PROCESSING을 먼저 쓴 요청 하나만 실제로 처리한다 (NX = 키가 없을 때만 쓰기, 명령 하나라 끼어들 수 없음)
 *   2. 성공하면 COMPLETED + 응답을 저장 → 이후 같은 키는 저장된 응답을 그대로 재생
 *   3. 아직 PROCESSING이면 409 IDEMPOTENCY_IN_PROGRESS (동시에 같은 키가 두 번 들어온 경우)
 *   4. 같은 키인데 요청 내용(fingerprint)이 다르면 422 IDEMPOTENCY_KEY_REUSED
 *   5. 처리가 실패하면 키를 지운다 → 클라이언트가 같은 키로 다시 시도할 수 있다
 */
@Component
@RequiredArgsConstructor
public class IdempotencyManager {

	private static final String KEY_PREFIX = "idem:";
	/** 처리 중에 서버가 죽어도 키가 영원히 막히지 않도록 PROCESSING은 짧게. (지갑 락 대기가 최대 5초라 처리는 그보다 훨씬 짧다) */
	private static final Duration PROCESSING_TTL = Duration.ofMinutes(1);
	/** 이 시간 안의 재시도만 막는다. 지나면 같은 키도 새 요청으로 처리된다. */
	private static final Duration COMPLETED_TTL = Duration.ofHours(24);

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;

	/**
	 * @param idempotencyKey 헤더 값. null이면(헤더 없음) 멱등 처리 없이 그냥 실행한다.
	 * @param fingerprint    요청 내용 요약 (예: "PAY:1:3000"). 같은 키를 다른 요청에 재사용했는지 가려낸다.
	 */
	public TransactionResponse execute(String idempotencyKey, String fingerprint, Supplier<TransactionResponse> task) {
		if (idempotencyKey == null) {
			return task.get();
		}
		if (idempotencyKey.isBlank()) {
			throw new WalletException(ErrorCode.INVALID_IDEMPOTENCY_KEY);
		}
		String key = KEY_PREFIX + idempotencyKey;

		Boolean acquired = redisTemplate.opsForValue()
				.setIfAbsent(key, write(Entry.processing(fingerprint)), PROCESSING_TTL);
		if (!Boolean.TRUE.equals(acquired)) {
			return replay(key, fingerprint);
		}

		try {
			TransactionResponse response = task.get();
			redisTemplate.opsForValue().set(key, write(Entry.completed(fingerprint, response)), COMPLETED_TTL);
			return response;
		} catch (RuntimeException e) {
			// 잔액 부족 같은 실패는 저장하지 않는다. 키를 지워서 같은 키로 다시 시도할 수 있게 한다.
			redisTemplate.delete(key);
			throw e;
		}
	}

	private TransactionResponse replay(String key, String fingerprint) {
		String raw = redisTemplate.opsForValue().get(key);
		if (raw == null) {
			// 먼저 온 요청이 실패해서 방금 키를 지운 경우. 처리 중으로 보고 클라이언트 재시도에 맡긴다.
			throw new WalletException(ErrorCode.IDEMPOTENCY_IN_PROGRESS);
		}
		Entry entry = read(raw);
		if (!entry.fingerprint().equals(fingerprint)) {
			throw new WalletException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
		}
		if (entry.status() == Status.PROCESSING) {
			throw new WalletException(ErrorCode.IDEMPOTENCY_IN_PROGRESS);
		}
		return entry.response();
	}

	private String write(Entry entry) {
		try {
			return objectMapper.writeValueAsString(entry);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
	}

	private Entry read(String json) {
		try {
			return objectMapper.readValue(json, Entry.class);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
	}

	enum Status {
		PROCESSING, COMPLETED
	}

	/** Redis에 JSON으로 저장되는 값. 예) {"status":"COMPLETED","fingerprint":"PAY:1:3000","response":{...}} */
	record Entry(Status status, String fingerprint, TransactionResponse response) {

		static Entry processing(String fingerprint) {
			return new Entry(Status.PROCESSING, fingerprint, null);
		}

		static Entry completed(String fingerprint, TransactionResponse response) {
			return new Entry(Status.COMPLETED, fingerprint, response);
		}
	}
}
