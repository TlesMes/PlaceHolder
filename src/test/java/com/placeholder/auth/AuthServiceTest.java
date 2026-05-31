package com.placeholder.auth;

import com.placeholder.domain.auth.dto.LoginRequest;
import com.placeholder.domain.auth.dto.LoginResponse;
import com.placeholder.domain.auth.dto.SignupRequest;
import com.placeholder.domain.auth.dto.SignupResponse;
import com.placeholder.domain.auth.service.AuthService;
import com.placeholder.domain.booker.entity.BookerAccount;
import com.placeholder.domain.booker.repository.BookerAccountRepository;
import com.placeholder.domain.provider.entity.ProviderAccount;
import com.placeholder.domain.provider.repository.ProviderAccountRepository;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.entity.User.UserRole;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.global.exception.custom.DuplicateEmailException;
import com.placeholder.global.exception.custom.InvalidCredentialsException;
import com.placeholder.global.exception.custom.InvalidUserRoleException;
import com.placeholder.global.jwt.JwtProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @Mock BookerAccountRepository bookerAccountRepository;
    @Mock ProviderAccountRepository providerAccountRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtProvider jwtProvider;

    @InjectMocks AuthService authService;

    @Nested
    @DisplayName("회원가입")
    class Signup {

        private SignupRequest bookerRequest;
        private SignupRequest providerRequest;

        @BeforeEach
        void setUp() {
            bookerRequest = makeRequest("booker@test.com", "BOOKER");
            providerRequest = makeRequest("provider@test.com", "PROVIDER");
        }

        @Test
        @DisplayName("BOOKER 가입 시 User + BookerAccount 생성")
        void booker_signup_creates_user_and_booker_account() {
            User savedUser = buildUser(1L, "booker@test.com", UserRole.BOOKER);
            when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
            when(passwordEncoder.encode(anyString())).thenReturn("hashed");
            when(userRepository.save(any())).thenReturn(savedUser);

            SignupResponse response = authService.signup(bookerRequest);

            assertThat(response.getUserId()).isEqualTo(1L);
            assertThat(response.getRole()).isEqualTo("BOOKER");
            verify(bookerAccountRepository).save(any(BookerAccount.class));
            verify(providerAccountRepository, never()).save(any());
        }

        @Test
        @DisplayName("PROVIDER 가입 시 User + ProviderAccount 생성")
        void provider_signup_creates_user_and_provider_account() {
            User savedUser = buildUser(2L, "provider@test.com", UserRole.PROVIDER);
            when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
            when(passwordEncoder.encode(anyString())).thenReturn("hashed");
            when(userRepository.save(any())).thenReturn(savedUser);

            SignupResponse response = authService.signup(providerRequest);

            assertThat(response.getRole()).isEqualTo("PROVIDER");
            verify(providerAccountRepository).save(any(ProviderAccount.class));
            verify(bookerAccountRepository, never()).save(any());
        }

        @Test
        @DisplayName("중복 이메일은 DuplicateEmailException, User 저장 없음")
        void duplicate_email_throws_and_does_not_save_user() {
            when(userRepository.findByEmail(anyString()))
                    .thenReturn(Optional.of(mock(User.class)));

            assertThatThrownBy(() -> authService.signup(bookerRequest))
                    .isInstanceOf(DuplicateEmailException.class);

            verify(userRepository, never()).save(any());
            verify(bookerAccountRepository, never()).save(any());
        }

        @Test
        @DisplayName("지원하지 않는 역할(ADMIN)은 InvalidUserRoleException, User 저장 없음")
        void admin_role_throws_and_does_not_save_user() {
            when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.signup(makeRequest("admin@test.com", "ADMIN")))
                    .isInstanceOf(InvalidUserRoleException.class);

            verify(userRepository, never()).save(any());
            verify(bookerAccountRepository, never()).save(any());
            verify(providerAccountRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("로그인")
    class Login {

        @Test
        @DisplayName("정상 로그인 시 토큰 반환")
        void login_success_returns_token() {
            User user = buildUser(1L, "booker@test.com", UserRole.BOOKER);
            when(userRepository.findByEmailAndDeletedAtIsNull(anyString()))
                    .thenReturn(Optional.of(user));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
            when(jwtProvider.generateToken(any(), any(), any())).thenReturn("jwt-token");
            when(jwtProvider.getExpirationMs()).thenReturn(86400000L);

            LoginResponse response = authService.login(makeLoginRequest("booker@test.com", "pass"));

            assertThat(response.getAccessToken()).isEqualTo("jwt-token");
            assertThat(response.getTokenType()).isEqualTo("Bearer");
        }

        @Test
        @DisplayName("존재하지 않는 이메일은 InvalidCredentialsException")
        void login_unknown_email_throws_invalid_credentials() {
            when(userRepository.findByEmailAndDeletedAtIsNull(anyString()))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login(makeLoginRequest("x@test.com", "pass")))
                    .isInstanceOf(InvalidCredentialsException.class);
        }

        @Test
        @DisplayName("비밀번호 불일치 시 InvalidCredentialsException")
        void login_wrong_password_throws_invalid_credentials() {
            User user = buildUser(1L, "booker@test.com", UserRole.BOOKER);
            when(userRepository.findByEmailAndDeletedAtIsNull(anyString()))
                    .thenReturn(Optional.of(user));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

            assertThatThrownBy(() -> authService.login(makeLoginRequest("booker@test.com", "wrong")))
                    .isInstanceOf(InvalidCredentialsException.class);
        }
    }

    // --- helpers ---

    private LoginRequest makeLoginRequest(String email, String password) {
        return LoginRequest.builder()
                .email(email)
                .password(password)
                .build();
    }

    private SignupRequest makeRequest(String email, String role) {
        return SignupRequest.builder()
                .email(email)
                .password("pass1234")
                .role(role)
                .build();
    }

    private User buildUser(Long id, String email, UserRole role) {
        return User.builder().id(id).email(email).passwordHash("hashed").role(role).build();
    }
}
