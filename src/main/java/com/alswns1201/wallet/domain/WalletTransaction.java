package com.alswns1201.wallet.domain;

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
 * 생성 메서드는 충전·결제·취소 커밋에서 하나씩 추가한다.
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

	/** 거래 직후 잔액 */
	@Column(nullable = false)
	private long balanceAfter;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private TransactionStatus status;

	/** CANCEL 거래가 가리키는 원 결제 거래 */
	private Long originalTransactionId;

	@Column(nullable = false)
	private LocalDateTime createdAt;
}
