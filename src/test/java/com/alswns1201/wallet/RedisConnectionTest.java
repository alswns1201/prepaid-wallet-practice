package com.alswns1201.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;

/** 두 클라이언트가 모두 Testcontainers의 Redis에 붙는지 확인한다. */
class RedisConnectionTest extends IntegrationTestSupport {

	@Autowired
	RedissonClient redissonClient;

	@Test
	@DisplayName("StringRedisTemplate(Lettuce)으로 쓰고 읽는다 — 일일 한도·멱등성 키에서 쓸 클라이언트")
	void redisTemplate() {
		redisTemplate.opsForValue().set("connection-test:template", "ok");

		assertThat(redisTemplate.opsForValue().get("connection-test:template")).isEqualTo("ok");
	}

	@Test
	@DisplayName("RedissonClient로 쓰고 읽는다 — 지갑 락에서 쓸 클라이언트")
	void redisson() {
		RBucket<String> bucket = redissonClient.getBucket("connection-test:redisson");
		bucket.set("ok");

		assertThat(bucket.get()).isEqualTo("ok");
	}
}
