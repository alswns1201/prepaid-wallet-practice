package com.alswns1201.wallet.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.alswns1201.wallet.support.ErrorCode;
import com.alswns1201.wallet.support.WalletException;

/**
 * 지갑별 일일 결제 한도. 오늘 쓴 금액을 Redis 키 wallet:daily:{walletId}:{yyyyMMdd} 하나에 누적한다.
 * 날짜가 키에 들어 있어서 자정이 지나면 새 키를 쓰게 되고, 지난 키는 TTL로 알아서 사라진다.
 *
 * reserve는 GET → 비교 → INCRBY를 Lua 스크립트(scripts/daily_limit_reserve.lua) 하나로 Redis에 보낸다.
 * 9단계처럼 명령 세 개를 따로 보내면, 두 요청이 동시에 GET 해서 둘 다 "아직 여유 있음"을 보고 둘 다 INCRBY 할 수 있다.
 * Redis는 스크립트 하나를 실행하는 동안 다른 명령을 처리하지 않으므로, 지갑 락이 없어도 한도를 넘지 않는다.
 * (여기서 원자적 = "중간에 끼어들 수 없다". 실패 시 롤백한다는 뜻은 아니다)
 *
 * 지갑 락은 그대로 필요하다 — 락이 지키는 건 DB의 잔액·원장이고, Lua는 Redis 안의 이 카운터만 지킨다.
 */
@Component
public class DailyLimitManager {

	private static final String KEY_PREFIX = "wallet:daily:";
	private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
	/** 하루치 키는 하루만 있으면 되지만, 자정 직전 결제를 다음 날 취소하는 경우를 위해 여유 있게 2일. */
	private static final Duration KEY_TTL = Duration.ofDays(2);
	/** 스크립트가 돌려주는 "한도 초과" 표시. */
	private static final long EXCEEDED = -1;
	/**
	 * 실행은 redisTemplate.execute가 알아서 EVALSHA(해시로 실행)를 먼저 시도하고,
	 * Redis에 스크립트가 없으면 EVAL(본문 전송)로 다시 보낸다.
	 */
	private static final RedisScript<Long> RESERVE_SCRIPT =
			RedisScript.of(new ClassPathResource("scripts/daily_limit_reserve.lua"), Long.class);

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
		Long result = redisTemplate.execute(RESERVE_SCRIPT,
				List.of(key(walletId, date)),                     // KEYS[1]
				String.valueOf(amount),                           // ARGV[1]
				String.valueOf(dailyPayLimit),                    // ARGV[2]
				String.valueOf(KEY_TTL.toSeconds()));             // ARGV[3]
		if (result == null || result == EXCEEDED) {
			throw new WalletException(ErrorCode.DAILY_LIMIT_EXCEEDED);
		}
	}

	/**
	 * 결제 실패 보상, 또는 결제 취소 시 그 결제가 쓴 한도를 돌려준다. date는 결제한 날(businessDate)이어야 한다.
	 * DECRBY 명령 하나라서 이미 원자적이다 — 읽고 비교하는 단계가 없으니 Lua가 필요 없다.
	 */
	public void release(Long walletId, long amount, LocalDate date) {
		redisTemplate.opsForValue().decrement(key(walletId, date), amount);
	}

	public long used(Long walletId, LocalDate date) {
		String value = redisTemplate.opsForValue().get(key(walletId, date));
		return value == null ? 0 : Long.parseLong(value);
	}

	/** 테스트의 대조군(9단계 GET/INCRBY 방식)이 같은 키를 쓰도록 공개한다. */
	public static String key(Long walletId, LocalDate date) {
		return KEY_PREFIX + walletId + ":" + date.format(DATE);
	}
}
