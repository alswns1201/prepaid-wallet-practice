package com.alswns1201.wallet;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 스프링 컨텍스트를 띄우는 테스트의 공통 부모. 실제 Redis를 Testcontainers로 띄운다.
 * Redisson은 시작할 때 Redis에 바로 접속하므로, Redis 의존성이 들어간 뒤로는 @SpringBootTest 테스트가 전부 Redis를 필요로 한다.
 * (@DataJpaTest는 JPA 관련 빈만 띄워서 필요 없다)
 */
@SpringBootTest
@Import(IntegrationTestSupport.TestClockConfig.class)
public abstract class IntegrationTestSupport {

	@SuppressWarnings("resource")
	static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
			.withExposedPorts(6379);

	static {
		// 모든 테스트 클래스가 컨테이너 하나를 공유한다 (싱글턴 컨테이너 패턴). JVM이 끝날 때 Testcontainers가 정리한다.
		REDIS.start();
	}

	/** 컨테이너는 빈 포트에 뜨므로, application.yml의 localhost:6379 대신 실제 주소·포트를 넣어 준다. */
	@DynamicPropertySource
	static void redisProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
	}

	@Autowired
	protected StringRedisTemplate redisTemplate;

	@Autowired
	protected TestClock clock;

	/**
	 * Redis 컨테이너는 모든 테스트가 같이 쓰고, 지갑 ID는 컨텍스트마다 1부터 다시 시작한다.
	 * 지우지 않으면 다른 테스트가 남긴 wallet:daily:1:... 한도 키를 이어받는다.
	 */
	@BeforeEach
	void resetRedisAndClock() {
		try (RedisConnection connection = redisTemplate.getRequiredConnectionFactory().getConnection()) {
			connection.serverCommands().flushAll();
		}
		clock.reset();
	}

	@TestConfiguration
	static class TestClockConfig {

		/** 운영용 ClockConfig.clock() 대신 이 시계가 주입되도록 @Primary. */
		@Bean
		@Primary
		TestClock testClock() {
			return new TestClock();
		}
	}
}
