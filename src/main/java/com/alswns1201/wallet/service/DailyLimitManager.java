package com.alswns1201.wallet.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.alswns1201.wallet.support.ErrorCode;
import com.alswns1201.wallet.support.WalletException;

/**
 * 지갑별 일일 결제 한도. 오늘 쓴 금액을 Redis 키 wallet:daily:{walletId}:{yyyyMMdd} 하나에 누적한다.
 * 날짜가 키에 들어 있어서 자정이 지나면 새 키를 쓰게 되고, 지난 키는 TTL로 알아서 사라진다.
 *
 * reserve는 GET → 비교 → INCRBY 세 단계로 나뉘어 있어 그 자체로는 원자적이지 않다.
 * 두 요청이 동시에 GET 하면 둘 다 "아직 여유 있음"을 보고 둘 다 INCRBY 해서 한도를 넘길 수 있다.
 * 지금은 반드시 지갑 락 안에서만 부르기 때문에 안전하다 (같은 지갑 요청은 한 번에 하나씩).
 */
@Component
public class DailyLimitManager {

	private static final String KEY_PREFIX = "wallet:daily:";
	private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
	/** 하루치 키는 하루만 있으면 되지만, 자정 직전 결제를 다음 날 취소하는 경우를 위해 여유 있게 2일. */
	private static final Duration KEY_TTL = Duration.ofDays(2);

	private final StringRedisTemplate redisTemplate;
	private final Clock clock;
	private final long dailyPayLimit;

	public DailyLimitManager(StringRedisTemplate redisTemplate, Clock clock,
			@Value("${wallet.daily-pay-limit}") long dailyPayLimit) {
		this.redisTemplate = redisTemplate;
		this.clock = clock;
		this.dailyPayLimit = dailyPayLimit;
	}

	public LocalDate today() {
		return LocalDate.now(clock);
	}

	/** 한도를 먼저 차지한다. 넘으면 DAILY_LIMIT_EXCEEDED. 결제가 실패하면 호출한 쪽이 release로 되돌려야 한다. */
	public void reserve(Long walletId, long amount, LocalDate date) {
		String key = key(walletId, date);
		long used = used(walletId, date);                         // 1. GET
		if (used + amount > dailyPayLimit) {                      // 2. 비교
			throw new WalletException(ErrorCode.DAILY_LIMIT_EXCEEDED);
		}
		redisTemplate.opsForValue().increment(key, amount);       // 3. INCRBY
		redisTemplate.expire(key, KEY_TTL);
	}

	/** 결제 실패 보상, 또는 결제 취소 시 그 결제가 쓴 한도를 돌려준다. date는 결제한 날(businessDate)이어야 한다. */
	public void release(Long walletId, long amount, LocalDate date) {
		redisTemplate.opsForValue().decrement(key(walletId, date), amount);
	}

	public long used(Long walletId, LocalDate date) {
		String value = redisTemplate.opsForValue().get(key(walletId, date));
		return value == null ? 0 : Long.parseLong(value);
	}

	private static String key(Long walletId, LocalDate date) {
		return KEY_PREFIX + walletId + ":" + date.format(DATE);
	}
}
