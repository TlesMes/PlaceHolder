package com.placeholder.domain.booker.repository;

import com.placeholder.domain.booker.entity.BookerAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BookerAccountRepository extends JpaRepository<BookerAccount, Long> {

    Optional<BookerAccount> findByUserId(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BookerAccount b where b.user.id = :userId")
    Optional<BookerAccount> findByUserIdForUpdate(@Param("userId") Long userId);
}
