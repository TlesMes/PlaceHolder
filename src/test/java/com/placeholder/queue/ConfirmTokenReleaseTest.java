package com.placeholder.queue;

import com.placeholder.domain.booker.entity.BookerAccount;
import com.placeholder.domain.booker.repository.BookerAccountRepository;
import com.placeholder.domain.event.entity.Event;
import com.placeholder.domain.event.repository.EventRepository;
import com.placeholder.domain.provider.entity.ProviderAccount;
import com.placeholder.domain.provider.repository.ProviderAccountRepository;
import com.placeholder.domain.queue.repository.QueueRedisRepository;
import com.placeholder.domain.queue.service.QueueAdmissionService;
import com.placeholder.domain.reservation.dto.ReservationConfirmResponse;
import com.placeholder.domain.reservation.service.ReservationService;
import com.placeholder.domain.seat.entity.Seat;
import com.placeholder.domain.seat.repository.SeatRepository;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.global.exception.custom.InsufficientPointException;
import com.placeholder.support.RedisIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * confirm 시 입장 토큰 즉시 회수 검증 (대기열 백로그 #1).
 *
 * <p>구매를 끝낸 유저의 토큰이 잔여 TTL 동안 ceiling 슬롯을 점유하는 유령 세션 문제의 해소를 검증한다.
 * <ul>
 *   <li>confirm 성공(커밋) → 게이트 토큰 + 전역 활성 세션 쌍으로 회수</li>
 *   <li>confirm 롤백(잔액 부족) → 토큰 보존 (afterCommit이라 커밋 전 삭제 역전 없음)</li>
 *   <li>대기열 비활성 이벤트 → 토큰 없어도 회수 no-op, confirm 정상 (멱등)</li>
 *   <li>회수로 빈 ceiling 슬롯에 다음 대기자가 입장 (기능의 존재 이유)</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class ConfirmTokenReleaseTest extends RedisIntegrationTest {

    private static final int CEILING = 8; // test 프로파일 queue.admission.max-active-sessions

    @Autowired ReservationService reservationService;
    @Autowired QueueAdmissionService admissionService;
    @Autowired QueueRedisRepository queueRepository;
    @Autowired StringRedisTemplate redis;
    @Autowired SeatRepository seatRepository;
    @Autowired BookerAccountRepository bookerAccountRepository;
    @Autowired ProviderAccountRepository providerAccountRepository;
    @Autowired EventRepository eventRepository;
    @Autowired UserRepository userRepository;

    // 테스트 간 Redis·deficit 장부 정리는 RedisIntegrationTest가 일괄 수행한다.

    @Test
    @DisplayName("confirm 성공: 입장 토큰 + 전역 활성 세션 즉시 회수")
    void confirm_success_releasesToken() {
        TestFixture f = persistFixture(true, 10_000, 50_000);
        queueRepository.issueEntryToken(f.eventId, f.bookerId, Duration.ofMinutes(5));

        reservationService.confirmReservation(f.seat.getId(), f.bookerId);

        assertThat(queueRepository.hasEntryToken(f.eventId, f.bookerId)).isFalse();
        assertThat(redis.opsForZSet().score("active:all", f.eventId + ":" + f.bookerId)).isNull();
    }

    @Test
    @DisplayName("confirm 롤백(잔액 부족): 토큰 보존 — 결제 실패했는데 토큰만 잃는 역전 없음")
    void confirm_rollback_keepsToken() {
        TestFixture f = persistFixture(true, 10_000, 500); // 잔액 < 가격 → 롤백
        queueRepository.issueEntryToken(f.eventId, f.bookerId, Duration.ofMinutes(5));

        assertThatThrownBy(() -> reservationService.confirmReservation(f.seat.getId(), f.bookerId))
                .isInstanceOf(InsufficientPointException.class);

        assertThat(queueRepository.hasEntryToken(f.eventId, f.bookerId)).isTrue();
        assertThat(redis.opsForZSet().score("active:all", f.eventId + ":" + f.bookerId)).isNotNull();
    }

    @Test
    @DisplayName("대기열 비활성 이벤트: 토큰 없어도 회수 no-op, confirm 정상 (멱등)")
    void confirm_queueDisabled_noopRelease() {
        TestFixture f = persistFixture(false, 10_000, 50_000);

        ReservationConfirmResponse response =
                reservationService.confirmReservation(f.seat.getId(), f.bookerId);

        assertThat(response.getPaidAmount()).isEqualTo(10_000);
        Seat confirmed = seatRepository.findById(f.seat.getId()).orElseThrow();
        assertThat(confirmed.getStatus()).isEqualTo(Seat.SeatStatus.CONFIRMED);
    }

    @Test
    @DisplayName("ceiling 회전: confirm 회수로 빈 슬롯에 다음 대기자 입장")
    void confirm_freesSlot_nextWaiterAdmitted() {
        TestFixture f = persistFixture(true, 10_000, 50_000);
        // ceiling을 가득 채움: 구매자 1명(실제 confirm 대상) + 합성 세션 7명
        queueRepository.issueEntryToken(f.eventId, f.bookerId, Duration.ofMinutes(5));
        for (long u = 900_001; u < 900_001 + CEILING - 1; u++) {
            queueRepository.issueEntryToken(f.eventId, u, Duration.ofMinutes(5));
        }
        long waiterId = 900_100;
        queueRepository.enqueue(f.eventId, waiterId, System.currentTimeMillis());

        // 가득 찬 상태에선 입장 불가
        assertThat(admissionService.admitWaiting()).isZero();
        assertThat(queueRepository.rank(f.eventId, waiterId)).isZero();

        // 구매 완료 → 슬롯 1개 회수
        reservationService.confirmReservation(f.seat.getId(), f.bookerId);
        deleteByPattern("rate:*"); // 다음 초 모사 (rate 윈도 초기화)

        assertThat(admissionService.admitWaiting()).isEqualTo(1);
        assertThat(queueRepository.hasEntryToken(f.eventId, waiterId)).isTrue();
        assertThat(queueRepository.rank(f.eventId, waiterId)).isNull(); // 입장 → 대기열 이탈
    }

    // --- 테스트 데이터 셋업 헬퍼 (ReservationConfirmServiceTest 패턴 + queueEnabled) ---

    record TestFixture(Seat seat, Long eventId, Long bookerId) {}

    @Transactional
    TestFixture persistFixture(boolean queueEnabled, int price, int initialBalance) {
        User provider = userRepository.save(User.builder()
                .email("provider-" + uniqueId() + "@test.com")
                .passwordHash("hash")
                .role(User.UserRole.PROVIDER)
                .build());
        providerAccountRepository.save(ProviderAccount.builder()
                .user(provider)
                .build());
        Event event = eventRepository.save(Event.builder()
                .provider(provider)
                .title("토큰 회수 테스트 " + uniqueId())
                .venue("테스트홀")
                .eventAt(LocalDateTime.now().plusDays(1))
                .queueEnabled(queueEnabled)
                .build());

        User booker = userRepository.save(User.builder()
                .email("booker-" + uniqueId() + "@test.com")
                .passwordHash("hash")
                .role(User.UserRole.BOOKER)
                .build());
        bookerAccountRepository.save(BookerAccount.builder()
                .user(booker)
                .balance(initialBalance)
                .build());

        Seat seat = seatRepository.save(Seat.builder()
                .event(event)
                .label("A-" + uniqueId())
                .price(price)
                .status(Seat.SeatStatus.HELD)
                .heldBy(booker)
                .heldUntil(LocalDateTime.now().plusMinutes(5))
                .build());
        return new TestFixture(seat, event.getId(), booker.getId());
    }

    private void deleteByPattern(String pattern) {
        var keys = redis.keys(pattern);
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }
}
