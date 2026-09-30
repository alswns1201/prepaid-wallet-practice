package com.alswns1201.wallet;

import org.springframework.boot.test.context.SpringBootTest;
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
}
