package com.placeholder.auth;

import com.placeholder.domain.auth.dto.SignupRequest;
import com.placeholder.domain.auth.service.AuthService;
import com.placeholder.domain.booker.repository.BookerAccountRepository;
import com.placeholder.domain.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@ActiveProfiles("local")
class SignupTransactionTest {

    @Autowired AuthService authService;
    @Autowired UserRepository userRepository;
    @MockitoBean BookerAccountRepository bookerAccountRepository;

    @AfterEach
    void cleanup() {
        userRepository.findByEmail("tx-booker@test.com").ifPresent(userRepository::delete);
        userRepository.findByEmail("rollback@test.com").ifPresent(userRepository::delete);
    }

    @Test
    @DisplayName("BOOKER 회원가입 후 User가 DB에 존재한다")
    void booker_signup_persists_user() {
        authService.signup(makeSignupRequest("tx-booker@test.com", "BOOKER"));

        assertThat(userRepository.findByEmail("tx-booker@test.com")).isPresent();
    }

    @Test
    @DisplayName("BookerAccount 저장 실패 시 User도 롤백된다")
    void booker_account_save_failure_rolls_back_user() {
        doThrow(new RuntimeException("forced DB failure"))
                .when(bookerAccountRepository).save(any());

        assertThatThrownBy(() -> authService.signup(makeSignupRequest("rollback@test.com", "BOOKER")))
                .isInstanceOf(RuntimeException.class);

        // signup 트랜잭션이 롤백되었으므로 User가 존재하지 않아야 함
        assertThat(userRepository.findByEmail("rollback@test.com")).isEmpty();
    }

    private SignupRequest makeSignupRequest(String email, String role) {
        return SignupRequest.builder()
                .email(email)
                .password("pass1234")
                .role(role)
                .build();
    }
}
