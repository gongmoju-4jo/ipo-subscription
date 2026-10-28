package com.gongmoju.account.infrastructure.persistence;

import com.gongmoju.account.domain.account.Account;
import com.gongmoju.account.domain.account.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class AccountRepositoryAdapter implements AccountRepository {

    private final AccountJpaRepository jpaRepository;

    @Override
    public Account saveAndFlush(Account account) {
        return jpaRepository.saveAndFlush(account);
    }

    @Override
    public Optional<Account> findByRequestId(UUID requestId) {
        return jpaRepository.findByRequestId(requestId);
    }

    @Override
    public Page<Account> findByUserId(UUID userId, Pageable pageable) {
        return jpaRepository.findByUserId(userId, pageable);
    }
}
