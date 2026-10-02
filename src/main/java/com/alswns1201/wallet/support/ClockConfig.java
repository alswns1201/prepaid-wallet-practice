package com.alswns1201.wallet.support;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

	/**
	 * "오늘"을 정하는 시계. 일일 한도의 하루는 서버 타임존과 상관없이 KST로 자른다.
	 * LocalDate.now()를 코드 곳곳에서 직접 부르지 않고 빈으로 두는 이유: 테스트에서 날짜를 바꿔 끼울 수 있게 하려고.
	 */
	@Bean
	public Clock clock() {
		return Clock.system(ZoneId.of("Asia/Seoul"));
	}
}
