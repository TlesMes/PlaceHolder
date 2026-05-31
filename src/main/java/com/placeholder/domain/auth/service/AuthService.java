package com.placeholder.domain.auth.service;

import com.placeholder.domain.auth.dto.LoginRequest;
import com.placeholder.domain.auth.dto.LoginResponse;
import com.placeholder.domain.auth.dto.SignupRequest;
import com.placeholder.domain.auth.dto.SignupResponse;
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
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final BookerAccountRepository bookerAccountRepository;
    private final ProviderAccountRepository providerAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new DuplicateEmailException("이미 사용 중인 이메일입니다");
        }

        UserRole role = UserRole.valueOf(request.getRole());
        if (role != UserRole.BOOKER && role != UserRole.PROVIDER) {
            throw new InvalidUserRoleException("지원하지 않는 역할입니다: " + role);
        }

        User user = User.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(role)
                .build();
        User savedUser = userRepository.save(user);

        if (role == UserRole.BOOKER) {
            bookerAccountRepository.save(BookerAccount.builder().user(savedUser).build());
        } else {
            providerAccountRepository.save(ProviderAccount.builder().user(savedUser).build());
        }

        return SignupResponse.builder()
                .userId(savedUser.getId())
                .email(savedUser.getEmail())
                .role(savedUser.getRole().name())
                .build();
    }

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmailAndDeletedAtIsNull(request.getEmail())
                .orElseThrow(() -> new InvalidCredentialsException("이메일 또는 비밀번호가 올바르지 않습니다"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("이메일 또는 비밀번호가 올바르지 않습니다");
        }

        String token = jwtProvider.generateToken(user.getId(), user.getEmail(), user.getRole());

        return LoginResponse.builder()
                .accessToken(token)
                .tokenType("Bearer")
                .expiresIn(jwtProvider.getExpirationMs())
                .build();
    }
}
