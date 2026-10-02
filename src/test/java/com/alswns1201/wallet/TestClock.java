package com.alswns1201.wallet;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 테스트용 시계. "어제 결제 → 오늘 취소" 같은 날짜 넘어가는 상황을 기다리지 않고 만들 수 있다.
 * 기본은 실제 현재 시각을 따르고, setDate로 날짜를 고정하면 그 날짜 정오로 멈춘다.
 */
public class TestClock extends Clock {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	private volatile Instant fixed;

	public void setDate(LocalDate date) {
		this.fixed = date.atTime(12, 0).atZone(KST).toInstant();
	}

	public void reset() {
		this.fixed = null;
	}

	@Override
	public Instant instant() {
		Instant current = fixed;
		return current != null ? current : Instant.now();
	}

	@Override
	public ZoneId getZone() {
		return KST;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		throw new UnsupportedOperationException();
	}
}
