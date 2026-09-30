package com.alswns1201.wallet.domain;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

	List<WalletTransaction> findByWalletIdOrderByIdDesc(Long walletId);

	boolean existsByOriginalTransactionId(Long originalTransactionId);
}
