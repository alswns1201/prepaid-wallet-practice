package com.alswns1201.wallet.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 선불 지갑. 사용자당 1개. */
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Wallet {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private Long userId;

	@Column(nullable = false)
	private long balance;

	@Column(nullable = false)
	private LocalDateTime createdAt;

	public Wallet(Long userId) {
		this.userId = userId;
		this.balance = 0;
		this.createdAt = LocalDateTime.now();
	}
}
