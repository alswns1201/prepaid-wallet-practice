package com.alswns1201.wallet.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.alswns1201.wallet.service.IdempotencyManager;
import com.alswns1201.wallet.service.TransactionResponse;
import com.alswns1201.wallet.service.WalletFacade;
import com.alswns1201.wallet.service.WalletResponse;
import com.alswns1201.wallet.service.WalletService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;

/**
 * 잔액을 바꾸는 충전·결제·취소는 Idempotency-Key 헤더를 선택으로 받는다.
 * 헤더가 있으면 같은 키의 재시도는 처리하지 않고 첫 응답을 돌려주고, 없으면 지금까지처럼 그냥 처리한다.
 * 조회(GET)는 원래 몇 번 해도 결과가 같고, 지갑 생성은 userId unique로 이미 두 번 만들어지지 않아서 받지 않는다.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class WalletController {

	private static final String IDEMPOTENCY_KEY = "Idempotency-Key";

	private final WalletService walletService;
	private final WalletFacade walletFacade;
	private final IdempotencyManager idempotencyManager;

	@PostMapping("/wallets")
	@ResponseStatus(HttpStatus.CREATED)
	public WalletResponse create(@Valid @RequestBody CreateWalletRequest request) {
		return walletService.create(request.userId());
	}

	@GetMapping("/wallets/{walletId}")
	public WalletResponse get(@PathVariable Long walletId) {
		return walletService.get(walletId);
	}

	@GetMapping("/wallets/{walletId}/transactions")
	public List<TransactionResponse> transactions(@PathVariable Long walletId) {
		return walletService.transactions(walletId);
	}

	/*
	 * fingerprint는 "이 키로 무슨 요청을 했나"의 요약이다. 같은 키로 금액이나 지갑이 다른 요청이 오면 422로 거절한다.
	 * 멱등 처리는 락 바깥이다 — 재시도는 저장된 응답만 돌려주면 되니 지갑 락을 잡을 필요가 없다.
	 */

	@PostMapping("/wallets/{walletId}/charge")
	public TransactionResponse charge(@PathVariable Long walletId,
			@RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
			@Valid @RequestBody AmountRequest request) {
		return idempotencyManager.execute(idempotencyKey, "CHARGE:" + walletId + ":" + request.amount(),
				() -> walletFacade.charge(walletId, request.amount()));
	}

	@PostMapping("/wallets/{walletId}/pay")
	public TransactionResponse pay(@PathVariable Long walletId,
			@RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
			@Valid @RequestBody AmountRequest request) {
		return idempotencyManager.execute(idempotencyKey, "PAY:" + walletId + ":" + request.amount(),
				() -> walletFacade.pay(walletId, request.amount()));
	}

	@PostMapping("/transactions/{transactionId}/cancel")
	public TransactionResponse cancel(@PathVariable Long transactionId,
			@RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey) {
		return idempotencyManager.execute(idempotencyKey, "CANCEL:" + transactionId,
				() -> walletFacade.cancel(transactionId));
	}

	public record CreateWalletRequest(@NotNull Long userId) {
	}

	/** 금액 검증(0 이하 거절)은 도메인(Wallet)이 한다 — 에러 응답을 ProblemDetail 하나로 맞추기 위해 */
	public record AmountRequest(@NotNull Long amount) {
	}
}
