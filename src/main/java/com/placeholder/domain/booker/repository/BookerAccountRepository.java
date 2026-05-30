package com.placeholder.domain.booker.repository;

import com.placeholder.domain.booker.entity.BookerAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BookerAccountRepository extends JpaRepository<BookerAccount, Long> {

    Optional<BookerAccount> findByUserId(Long userId);
}
