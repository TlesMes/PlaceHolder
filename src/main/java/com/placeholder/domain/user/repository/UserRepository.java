package com.placeholder.domain.user.repository;

import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.entity.User.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    Optional<User> findByEmailAndDeletedAtIsNull(String email);

    Optional<User> findByIdAndDeletedAtIsNull(Long id);

    List<User> findByRole(UserRole role);
}
