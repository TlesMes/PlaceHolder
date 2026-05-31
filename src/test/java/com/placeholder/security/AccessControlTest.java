package com.placeholder.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.placeholder.domain.auth.dto.LoginRequest;
import com.placeholder.domain.auth.dto.SignupRequest;
import com.placeholder.domain.auth.service.AuthService;
import com.placeholder.domain.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Transactional
class AccessControlTest {

    @Autowired MockMvc mockMvc;
    @Autowired AuthService authService;
    @Autowired UserRepository userRepository;
    @Autowired EntityManager em;
    @Autowired ObjectMapper objectMapper;

    private String providerToken;
    private String bookerToken;

    @BeforeEach
    void setUp() {
        providerToken = signup("provider@ctrl.com", "PROVIDER");
        bookerToken   = signup("booker@ctrl.com",   "BOOKER");
    }

    @Nested
    @DisplayName("@PreAuthorize 역할 기반 접근 제어")
    class PreAuthorize {

        @Test
        @DisplayName("PROVIDER 토큰 → POST /api/events → 201")
        void provider_can_create_event() throws Exception {
            mockMvc.perform(post("/api/events")
                            .header("Authorization", "Bearer " + providerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(eventJson()))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("BOOKER 토큰 → POST /api/events → 403")
        void booker_cannot_create_event() throws Exception {
            mockMvc.perform(post("/api/events")
                            .header("Authorization", "Bearer " + bookerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(eventJson()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }

        @Test
        @DisplayName("토큰 없음 → POST /api/events → 401")
        void no_token_cannot_create_event() throws Exception {
            mockMvc.perform(post("/api/events")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(eventJson()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }

        @Test
        @DisplayName("토큰 없음 → GET /api/events → 200")
        void get_events_is_public() throws Exception {
            mockMvc.perform(get("/api/events"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @DisplayName("Soft Delete 필터링")
    class SoftDelete {

        @Test
        @DisplayName("탈퇴 유저는 로그인 불가 — 401")
        void deleted_user_cannot_login() throws Exception {
            signup("deleted@ctrl.com", "BOOKER");
            softDelete("deleted@ctrl.com");

            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(makeLoginRequest("deleted@ctrl.com", "pass1234"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }

        @Test
        @DisplayName("탈퇴 유저 토큰은 이후 API 요청에서 인증 실패 — 401")
        void deleted_user_token_is_rejected() throws Exception {
            signup("token-deleted@ctrl.com", "BOOKER");
            String token = login("token-deleted@ctrl.com");
            softDelete("token-deleted@ctrl.com");

            // 탈퇴 후 토큰으로 인증 필요한 API 호출 → 인증 실패(익명) → 401
            mockMvc.perform(post("/api/events")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(eventJson()))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
    }

    // --- helpers ---

    private String signup(String email, String role) {
        authService.signup(makeSignupRequest(email, role));
        return login(email);
    }

    private String login(String email) {
        return authService.login(makeLoginRequest(email, "pass1234")).getAccessToken();
    }

    private void softDelete(String email) {
        em.createNativeQuery("UPDATE users SET deleted_at = NOW() WHERE email = :email")
                .setParameter("email", email)
                .executeUpdate();
        em.flush();
        em.clear();
    }

    private String eventJson() {
        return """
                {"title":"Test","venue":"Seoul","eventAt":"2027-01-01T10:00:00",
                 "seats":[{"label":"A1","price":10000}]}
                """;
    }

    private SignupRequest makeSignupRequest(String email, String role) {
        return SignupRequest.builder()
                .email(email)
                .password("pass1234")
                .role(role)
                .build();
    }

    private LoginRequest makeLoginRequest(String email, String password) {
        return LoginRequest.builder()
                .email(email)
                .password(password)
                .build();
    }
}
