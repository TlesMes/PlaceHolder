package com.placeholder.user;

import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.entity.User.UserRole;
import com.placeholder.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.placeholder.support.MySQLIntegrationTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * soft delete 쿼리(findBy...AndDeletedAtIsNull)가 deleted_at이 설정된 행을
 * 제외하는지 직접 검증한다.
 *
 * 별도 테스트 DB가 없어 실제 로컬 MySQL을 사용한다(replace = NONE).
 * → 테스트 실행 시 로컬 DB 스키마가 재생성된다(메모리 기록된 향후 격리 대상).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class UserRepositoryTest extends MySQLIntegrationTest {

    @Autowired UserRepository userRepository;

    @Test
    @DisplayName("findByEmailAndDeletedAtIsNull — 살아있는 유저는 조회된다")
    void find_by_email_returns_active_user() {
        User active = userRepository.save(buildUser("active@test.com", null));

        Optional<User> found = userRepository.findByEmailAndDeletedAtIsNull("active@test.com");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(active.getId());
    }

    @Test
    @DisplayName("findByEmailAndDeletedAtIsNull — 탈퇴 유저는 제외된다")
    void find_by_email_excludes_deleted_user() {
        userRepository.save(buildUser("deleted@test.com", LocalDateTime.now()));

        Optional<User> found = userRepository.findByEmailAndDeletedAtIsNull("deleted@test.com");

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("findByIdAndDeletedAtIsNull — 탈퇴 유저는 제외된다")
    void find_by_id_excludes_deleted_user() {
        User deleted = userRepository.save(buildUser("deleted-id@test.com", LocalDateTime.now()));

        Optional<User> found = userRepository.findByIdAndDeletedAtIsNull(deleted.getId());

        assertThat(found).isEmpty();
    }

    private User buildUser(String email, LocalDateTime deletedAt) {
        return User.builder()
                .email(email)
                .passwordHash("hashed")
                .role(UserRole.BOOKER)
                .deletedAt(deletedAt)
                .build();
    }
}
