package com.alswns1201.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import com.alswns1201.wallet.domain.Wallet;
import com.alswns1201.wallet.domain.WalletRepository;
import com.alswns1201.wallet.domain.WalletTransaction;
import com.alswns1201.wallet.domain.WalletTransactionRepository;

/**
 * 서비스의 exists 확인을 건너뛰고 DB 제약만 본다.
 * 동시에 들어온 두 취소가 모두 exists 확인을 통과한 상황 = 같은 원 결제로 CANCEL 행을 두 번 넣는 상황.
 */
@DataJpaTest
class WalletTransactionConstraintTest {

	@Autowired
	WalletRepository walletRepository;

	@Autowired
	WalletTransactionRepository transactionRepository;

	@Test
	@DisplayName("같은 결제를 가리키는 CANCEL 행은 DB가 두 번째를 거절한다")
	void uniqueOriginalTransactionId() {
		Wallet wallet = walletRepository.save(new Wallet(1L));
		wallet.charge(10_000);
		wallet.pay(3_000);
		WalletTransaction pay = transactionRepository.save(WalletTransaction.pay(wallet, 3_000, LocalDate.now()));

		transactionRepository.saveAndFlush(WalletTransaction.cancelOf(pay, wallet));

		assertThatThrownBy(() -> transactionRepository.saveAndFlush(WalletTransaction.cancelOf(pay, wallet)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("CHARGE/PAY 행은 originalTransactionId가 NULL이라 여러 개여도 제약에 안 걸린다")
	void nullsAreAllowed() {
		Wallet wallet = walletRepository.save(new Wallet(2L));
		wallet.charge(1_000);
		transactionRepository.saveAndFlush(WalletTransaction.charge(wallet, 1_000));
		wallet.charge(1_000);
		transactionRepository.saveAndFlush(WalletTransaction.charge(wallet, 1_000));

		assertThat(transactionRepository.findByWalletIdOrderByIdDesc(wallet.getId())).hasSize(2);
	}
}
