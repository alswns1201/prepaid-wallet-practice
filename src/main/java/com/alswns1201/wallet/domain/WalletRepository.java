package com.alswns1201.wallet.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletRepository extends JpaRepository<Wallet, Long> {

	boolean existsByUserId(Long userId);
}
