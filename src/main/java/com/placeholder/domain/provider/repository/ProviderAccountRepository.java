package com.placeholder.domain.provider.repository;

import com.placeholder.domain.provider.entity.ProviderAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProviderAccountRepository extends JpaRepository<ProviderAccount, Long> {

    Optional<ProviderAccount> findByUserId(Long userId);
}
