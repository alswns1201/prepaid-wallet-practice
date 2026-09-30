package com.alswns1201.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

import com.alswns1201.wallet.support.WalletException;

/**
 * 요청 N개를 스레드 N개에서 "같은 순간에" 보낸다.
 * 각 요청은 평범한 동기 호출이다 — 비동기가 아니라 동시에 여러 개가 들어오는 상황을 만드는 도구.
 *
 * - ready : 모든 스레드가 출발선에 설 때까지 main이 기다린다
 * - start : main이 한 번에 출발 신호를 준다 (스레드가 만들어지는 시간차를 없앤다)
 * - done  : 모든 요청이 끝날 때까지 main이 기다린다
 */
public final class ConcurrentRunner {

	private ConcurrentRunner() {
	}

	/** @param request 요청 번호(0 ~ N-1)를 받아 한 건을 처리한다. 예외 없이 끝나면 성공으로 센다. */
	public static Result run(int requestCount, IntConsumer request) throws InterruptedException {
		ExecutorService executor = Executors.newFixedThreadPool(requestCount);
		CountDownLatch ready = new CountDownLatch(requestCount);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(requestCount);

		AtomicInteger success = new AtomicInteger();
		Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();

		for (int i = 0; i < requestCount; i++) {
			int index = i;
			executor.submit(() -> {
				ready.countDown();
				try {
					start.await();
					request.accept(index);
					success.incrementAndGet();
				} catch (WalletException e) {
					failures.computeIfAbsent(e.getErrorCode().name(), k -> new AtomicInteger()).incrementAndGet();
				} catch (Throwable e) {
					// 예상 밖 예외는 원인을 알 수 있게 근본 원인을 찍어 둔다
					System.out.println(">>> 예상 밖 예외: " + e.getClass().getSimpleName() + " ← " + rootCause(e));
					failures.computeIfAbsent(e.getClass().getSimpleName(), k -> new AtomicInteger()).incrementAndGet();
				} finally {
					done.countDown();
				}
			});
		}

		ready.await();
		long begin = System.nanoTime();
		start.countDown();
		boolean finished = done.await(60, TimeUnit.SECONDS);
		long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
		executor.shutdown();

		assertThat(finished).as("모든 요청이 제한 시간 안에 끝나야 한다").isTrue();
		Result result = new Result(success.get(), failures.entrySet().stream()
				.collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> e.getValue().get())), elapsedMs);
		System.out.println(">>> " + result);
		return result;
	}

	private static Throwable rootCause(Throwable e) {
		Throwable root = e;
		while (root.getCause() != null) {
			root = root.getCause();
		}
		return root;
	}

	/** @param failures 실패 사유별 건수 (WalletException이면 ErrorCode 이름, 그 외는 예외 클래스 이름) */
	public record Result(int success, Map<String, Integer> failures, long elapsedMs) {

		public int failure(String code) {
			return failures.getOrDefault(code, 0);
		}
	}
}
