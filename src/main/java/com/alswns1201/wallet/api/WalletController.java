package com.alswns1201.wallet.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.alswns1201.wallet.service.TransactionResponse;
import com.alswns1201.wallet.service.WalletResponse;
import com.alswns1201.wallet.service.WalletService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class WalletController {

	private final WalletService walletService;

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

	@PostMapping("/wallets/{walletId}/charge")
	public TransactionResponse charge(@PathVariable Long walletId, @Valid @RequestBody AmountRequest request) {
		return walletService.charge(walletId, request.amount());
	}

	@PostMapping("/wallets/{walletId}/pay")
	public TransactionResponse pay(@PathVariable Long walletId, @Valid @RequestBody AmountRequest request) {
		return walletService.pay(walletId, request.amount());
	}

	public record CreateWalletRequest(@NotNull Long userId) {
	}

	/** 금액 검증(0 이하 거절)은 도메인(Wallet)이 한다 — 에러 응답을 ProblemDetail 하나로 맞추기 위해 */
	public record AmountRequest(@NotNull Long amount) {
	}
}
