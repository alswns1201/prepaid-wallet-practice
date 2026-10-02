package com.alswns1201.wallet.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 지갑 거래 기록 (충전/결제/취소). 잔액이 바뀔 때마다 한 줄씩 쌓인다.
 * 추가만 하는(append-only) 원장이라 한 번 쌓인 행은 수정하지 않는다 — 결제 취소도 원 결제 행을 고치지 않고 CANCEL 행을 새로 쌓는다.
 */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(indexes = @Index(name = "idx_tx_wallet", columnList = "walletId"))
public class WalletTransaction {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long walletId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private TransactionType type;

	@Column(nullable = false)
	private long amount;

	/**
	 * 이 거래가 반영된 직후의 지갑 잔액 (통장의 "잔액" 칸). amount가 "얼마가 움직였나"라면 이건 "그래서 얼마가 남았나".
	 * 예) CHARGE 10,000 → 10,000 / PAY 3,000 → 7,000 / CANCEL 3,000 → 10,000
	 * - 순서대로 보면 "직전 balanceAfter ± amount = 이번 balanceAfter"가 항상 맞아야 한다 → 원장 검증(대사)에 쓴다.
	 * - 동시 요청이 같은 잔액을 읽고 덮어쓰면(lost update) 여러 행에 같은 값이 찍힌다 → 동시성 문제의 흔적.
	 * - 원장은 추가만 하므로 한 번 찍힌 값은 바뀌지 않는다 (결제를 취소해도 PAY 행의 값은 "그때 잔액"으로 남는다).
	 */
	@Column(nullable = false)
	private long balanceAfter;

	/**
	 * CANCEL 거래가 가리키는 원 결제 거래.
	 * unique 제약 — 한 결제에 CANCEL 행은 하나만 들어갈 수 있어서, 동시에 취소가 들어와도 DB가 이중 취소를 막는다.
	 * (CHARGE/PAY 행은 NULL이고, unique 칼럼이라도 NULL은 여러 개 허용된다)
	 */
	@Column(unique = true)
	private Long originalTransactionId;

	/**
	 * 일일 한도를 어느 날짜로 셌는지 (KST 기준 "결제한 날").
	 * - PAY: 결제한 날. 그날의 한도 키에 금액이 더해졌다.
	 * - CANCEL: 원 결제의 날짜를 그대로 복사한다 → 그날의 한도를 돌려줬다는 뜻.
	 *   어제 결제를 오늘 취소하면 어제 한도를 돌려줘야 하는데, 원 결제의 날짜를 모르면 오늘 한도를 잘못 늘리게 된다.
	 * - CHARGE: 한도와 상관없어 NULL.
	 * createdAt에서 날짜를 뽑지 않는 이유: createdAt은 서버 시계라 테스트에서 날짜를 고정할 수 없고 서버 타임존에 따라 달라진다.
	 */
	private LocalDate businessDate;

	@Column(nullable = false)
	private LocalDateTime createdAt;

	private WalletTransaction(Long walletId, TransactionType type, long amount, long balanceAfter,
			Long originalTransactionId, LocalDate businessDate) {
		this.walletId = walletId;
		this.type = type;
		this.amount = amount;
		this.balanceAfter = balanceAfter;
		this.originalTransactionId = originalTransactionId;
		this.businessDate = businessDate;
		this.createdAt = LocalDateTime.now();
	}

	/** 충전이 반영된 지갑으로 CHARGE 거래를 만든다. */
	public static WalletTransaction charge(Wallet wallet, long amount) {
		return new WalletTransaction(wallet.getId(), TransactionType.CHARGE, amount, wallet.getBalance(), null, null);
	}

	/** 결제가 반영된 지갑으로 PAY 거래를 만든다. businessDate는 일일 한도를 센 날짜. */
	public static WalletTransaction pay(Wallet wallet, long amount, LocalDate businessDate) {
		return new WalletTransaction(wallet.getId(), TransactionType.PAY, amount, wallet.getBalance(), null,
				businessDate);
	}

	/** 환불이 반영된 지갑으로, 원 결제를 가리키는 CANCEL 거래를 만든다. businessDate는 원 결제의 날짜를 물려받는다. */
	public static WalletTransaction cancelOf(WalletTransaction original, Wallet wallet) {
		return new WalletTransaction(wallet.getId(), TransactionType.CANCEL, original.getAmount(), wallet.getBalance(),
				original.getId(), original.getBusinessDate());
	}

	public boolean isPay() {
		return type == TransactionType.PAY;
	}
}
