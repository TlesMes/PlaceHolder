package com.placeholder.point;

import com.placeholder.domain.event.entity.Event;
import com.placeholder.domain.event.repository.EventRepository;
import com.placeholder.domain.point.dto.PointHistoryResponse;
import com.placeholder.domain.point.entity.PointTransaction;
import com.placeholder.domain.point.entity.PointTransaction.TransactionType;
import com.placeholder.domain.point.repository.PointTransactionRepository;
import com.placeholder.domain.point.service.PointHistoryService;
import com.placeholder.domain.reservation.entity.Reservation;
import com.placeholder.domain.reservation.repository.ReservationRepository;
import com.placeholder.domain.seat.entity.Seat;
import com.placeholder.domain.seat.repository.SeatRepository;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.support.MySQLIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PointHistoryService.getHistory (GET /api/points/history) 단위 테스트.
 *
 * <p>cursor 페이징 계약(ADR-012)을 못박는다:
 * <ul>
 *   <li>기본값: size=20, period=3개월(from=now-3M), cursor=now</li>
 *   <li>size 상한 100 cap</li>
 *   <li>from 필터: created_at &gt;= from 만 포함</li>
 *   <li>cursor: created_at &lt; cursor (strict, 경계 행 중복 없음)</li>
 *   <li>nextCursor: 결과 수 == size일 때만 마지막 createdAt, 아니면 null</li>
 *   <li>user_id 격리, amount는 타입 무관 양수 크기 저장</li>
 *   <li>CHARGE는 reservationId/eventTitle null, DEDUCT는 채워짐</li>
 * </ul>
 *
 * <p>created_at은 @PrePersist로 now() 고정이므로 저장 후 JdbcTemplate으로 덮어써
 * cursor/from 경계를 결정론적으로 만든다.
 */
@SuppressWarnings("null")
@SpringBootTest
@ActiveProfiles("test")
class PointHistoryServiceTest extends MySQLIntegrationTest {

    @Autowired PointHistoryService pointHistoryService;
    @Autowired PointTransactionRepository pointTransactionRepository;
    @Autowired ReservationRepository reservationRepository;
    @Autowired SeatRepository seatRepository;
    @Autowired EventRepository eventRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("기본값: size 20 적용, 25건 중 20건 반환 + nextCursor 비어있지 않음 + DESC 정렬")
    void getHistory_defaultSize_andNextCursor() {
        // given - 최근 3개월 내 25건 (분 단위로 createdAt 분리)
        User user = persistBooker();
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        for (int i = 0; i < 25; i++) {
            persistCharge(user, 1_000, base.minusMinutes(i));
        }

        // when - 모든 파라미터 null (기본값 경로)
        PointHistoryResponse response =
                pointHistoryService.getHistory(user.getId(), null, null, null, null);

        // then - 20건, DESC 정렬
        List<PointHistoryResponse.TransactionItem> items = response.getItems();
        assertThat(items).hasSize(20);
        assertThat(items).isSortedAccordingTo(
                (a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        // 결과 수 == size → nextCursor = 마지막(20번째) item의 createdAt
        assertThat(response.getNextCursor()).isEqualTo(items.get(19).getCreatedAt());
    }

    @Test
    @DisplayName("size 상한: size=150 요청해도 최대 100건 (MAX_SIZE cap)")
    void getHistory_sizeCappedAt100() {
        // given - 101건
        User user = persistBooker();
        persistManyCharges(user, 101, LocalDateTime.now().minusDays(1));

        // when - 100 초과 요청
        PointHistoryResponse response =
                pointHistoryService.getHistory(user.getId(), null, null, null, 150);

        // then - 100건으로 cap, 101 > 100 이므로 nextCursor 존재
        assertThat(response.getItems()).hasSize(100);
        assertThat(response.getNextCursor()).isNotNull();
    }

    @Test
    @DisplayName("from 필터: 기본 3개월보다 오래된 건 제외, 명시 from으로 확장 시 포함")
    void getHistory_fromFilter() {
        // given - 1개월 전 1건, 4개월 전 1건
        User user = persistBooker();
        LocalDateTime now = LocalDateTime.now();
        persistCharge(user, 1_000, now.minusMonths(1));
        persistCharge(user, 2_000, now.minusMonths(4));

        // when - 기본 from(now-3개월) → 4개월 전 제외
        PointHistoryResponse defaultRange =
                pointHistoryService.getHistory(user.getId(), null, null, null, null);
        // then
        assertThat(defaultRange.getItems()).hasSize(1);
        assertThat(defaultRange.getItems().get(0).getAmount()).isEqualTo(1_000);

        // when - 명시 from(now-6개월) → 둘 다 포함
        PointHistoryResponse widened = pointHistoryService.getHistory(
                user.getId(), now.minusMonths(6), null, null, null);
        // then
        assertThat(widened.getItems()).hasSize(2);
    }

    @Test
    @DisplayName("cursor strict(<): 경계 createdAt과 같은 행은 제외되어 중복 없음")
    void getHistory_cursorIsStrictlyLessThan() {
        // given - t1 < t2(cursor) < t3
        // base를 초 단위로 절삭: t2를 저장값이자 cursor 파라미터로 함께 쓰므로
        // 컬럼 정밀도 절삭으로 strict(<) 경계가 깨지지 않게 한다.
        User user = persistBooker();
        LocalDateTime base = LocalDateTime.now().minusDays(1).truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime t1 = base.minusMinutes(2);
        LocalDateTime t2 = base.minusMinutes(1);
        LocalDateTime t3 = base;
        persistCharge(user, 100, t1);
        persistCharge(user, 200, t2); // cursor 경계 행
        persistCharge(user, 300, t3);

        // when - cursor=t2 → created_at < t2 만 (t1만 해당)
        PointHistoryResponse response =
                pointHistoryService.getHistory(user.getId(), null, null, t2, null);

        // then - t2/t3 제외, t1만
        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getCreatedAt()).isEqualTo(t1);
    }

    @Test
    @DisplayName("cursor 페이징: size=2로 5건을 끝까지 — 누락/중복 없이 전건 DESC 커버")
    void getHistory_cursorPaging_walksAllPages() {
        // given - 5건, createdAt 분 단위 분리 (가장 최근이 amount=5)
        User user = persistBooker();
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        for (int i = 0; i < 5; i++) {
            persistCharge(user, (i + 1) * 100, base.minusMinutes(4 - i)); // amount 100..500, 500이 최신
        }

        // page1
        PointHistoryResponse p1 =
                pointHistoryService.getHistory(user.getId(), null, null, null, 2);
        assertThat(p1.getItems()).extracting(PointHistoryResponse.TransactionItem::getAmount)
                .containsExactly(500, 400);
        assertThat(p1.getNextCursor()).isNotNull();

        // page2 (cursor = p1.nextCursor)
        PointHistoryResponse p2 = pointHistoryService.getHistory(
                user.getId(), null, null, p1.getNextCursor(), 2);
        assertThat(p2.getItems()).extracting(PointHistoryResponse.TransactionItem::getAmount)
                .containsExactly(300, 200);
        assertThat(p2.getNextCursor()).isNotNull();

        // page3 (마지막, 1건 < size → nextCursor null)
        PointHistoryResponse p3 = pointHistoryService.getHistory(
                user.getId(), null, null, p2.getNextCursor(), 2);
        assertThat(p3.getItems()).extracting(PointHistoryResponse.TransactionItem::getAmount)
                .containsExactly(100);
        assertThat(p3.getNextCursor()).isNull();
    }

    @Test
    @DisplayName("nextCursor null: 결과 수가 size보다 작으면 다음 페이지 없음")
    void getHistory_nextCursorNull_whenLastPage() {
        // given - 3건, size=10
        User user = persistBooker();
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        for (int i = 0; i < 3; i++) {
            persistCharge(user, 100, base.minusMinutes(i));
        }

        // when
        PointHistoryResponse response =
                pointHistoryService.getHistory(user.getId(), null, null, null, 10);

        // then
        assertThat(response.getItems()).hasSize(3);
        assertThat(response.getNextCursor()).isNull();
    }

    @Test
    @DisplayName("user_id 격리: 타인의 거래는 반환되지 않음")
    void getHistory_isolation() {
        // given
        User me = persistBooker();
        User other = persistBooker();
        LocalDateTime base = LocalDateTime.now().minusDays(1);
        persistCharge(me, 100, base);
        persistCharge(other, 999, base);

        // when
        PointHistoryResponse response =
                pointHistoryService.getHistory(me.getId(), null, null, null, null);

        // then - 내 1건만
        assertThat(response.getItems()).hasSize(1);
        assertThat(response.getItems().get(0).getAmount()).isEqualTo(100);
    }

    @Test
    @DisplayName("매핑: DEDUCT는 reservationId/eventTitle 채워지고, CHARGE는 null. amount는 양수 크기")
    void getHistory_mapping_chargeVsDeduct() {
        // given - CHARGE 1건 + DEDUCT 1건(예약 연결)
        User provider = persistProvider();
        Event event = persistEvent(provider, "매핑 테스트 이벤트", "매핑홀");
        User booker = persistBooker();
        Seat seat = persistSeat(event, "M-1", 7_000);
        Reservation reservation = persistReservation(booker, seat, 7_000);

        LocalDateTime base = LocalDateTime.now().minusDays(1);
        persistCharge(booker, 50_000, base.minusMinutes(1));
        persistTx(booker, TransactionType.DEDUCT, 7_000, reservation, base);

        // when - DESC: DEDUCT(최신) 먼저, CHARGE 다음
        PointHistoryResponse response =
                pointHistoryService.getHistory(booker.getId(), null, null, null, null);

        // then
        List<PointHistoryResponse.TransactionItem> items = response.getItems();
        assertThat(items).hasSize(2);

        PointHistoryResponse.TransactionItem deduct = items.get(0);
        assertThat(deduct.getType()).isEqualTo("DEDUCT");
        assertThat(deduct.getAmount()).isEqualTo(7_000); // 부호 없는 양수 크기
        assertThat(deduct.getReservationId()).isEqualTo(reservation.getId());
        assertThat(deduct.getEventTitle()).isEqualTo("매핑 테스트 이벤트");

        PointHistoryResponse.TransactionItem charge = items.get(1);
        assertThat(charge.getType()).isEqualTo("CHARGE");
        assertThat(charge.getAmount()).isEqualTo(50_000);
        assertThat(charge.getReservationId()).isNull();
        assertThat(charge.getEventTitle()).isNull();
    }

    // --- 셋업 헬퍼 ---

    @Transactional
    User persistProvider() {
        return userRepository.save(User.builder()
                .email("provider-" + uniqueId() + "@test.com")
                .passwordHash("hash")
                .role(User.UserRole.PROVIDER)
                .build());
    }

    @Transactional
    User persistBooker() {
        return userRepository.save(User.builder()
                .email("booker-" + uniqueId() + "@test.com")
                .passwordHash("hash")
                .role(User.UserRole.BOOKER)
                .build());
    }

    @Transactional
    Event persistEvent(User provider, String title, String venue) {
        return eventRepository.save(Event.builder()
                .provider(provider)
                .title(title)
                .venue(venue)
                .eventAt(LocalDateTime.now().plusDays(7))
                .build());
    }

    @Transactional
    Seat persistSeat(Event event, String label, int price) {
        return seatRepository.save(Seat.builder()
                .event(event)
                .label(label)
                .price(price)
                .status(Seat.SeatStatus.CONFIRMED)
                .build());
    }

    @Transactional
    Reservation persistReservation(User booker, Seat seat, int paidAmount) {
        return reservationRepository.save(Reservation.builder()
                .booker(booker)
                .seat(seat)
                .paidAmount(paidAmount)
                .build());
    }

    @Transactional
    PointTransaction persistCharge(User user, int amount, LocalDateTime createdAt) {
        return persistTx(user, TransactionType.CHARGE, amount, null, createdAt);
    }

    @Transactional
    PointTransaction persistTx(User user, TransactionType type, int amount,
                               Reservation reservation, LocalDateTime createdAt) {
        PointTransaction tx = pointTransactionRepository.save(PointTransaction.builder()
                .user(user)
                .type(type)
                .amount(amount)
                .reservation(reservation)
                .build());
        overrideCreatedAt(tx.getId(), createdAt);
        return tx;
    }

    /** count건을 baseTime.minusMinutes(i)로 저장. */
    @Transactional
    void persistManyCharges(User user, int count, LocalDateTime baseTime) {
        for (int i = 0; i < count; i++) {
            persistCharge(user, 1_000, baseTime.minusMinutes(i));
        }
    }

    private void overrideCreatedAt(Long txId, LocalDateTime createdAt) {
        jdbcTemplate.update(
                "UPDATE point_transactions SET created_at = ? WHERE id = ?",
                Timestamp.valueOf(createdAt), txId);
    }
}
