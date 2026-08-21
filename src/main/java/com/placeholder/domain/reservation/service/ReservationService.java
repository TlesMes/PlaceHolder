package com.placeholder.domain.reservation.service;

import com.placeholder.domain.booker.entity.BookerAccount;
import com.placeholder.domain.booker.repository.BookerAccountRepository;
import com.placeholder.domain.point.entity.PointAllocation;
import com.placeholder.domain.point.entity.PointTransaction;
import com.placeholder.domain.point.entity.PointTransaction.TransactionType;
import com.placeholder.domain.point.repository.PointTransactionRepository;
import com.placeholder.domain.queue.repository.QueueRedisRepository;
import com.placeholder.domain.reservation.dto.MyReservationsResponse;
import com.placeholder.domain.reservation.dto.ReservationConfirmResponse;
import com.placeholder.domain.reservation.entity.Reservation;
import com.placeholder.domain.reservation.repository.ReservationRepository;
import com.placeholder.domain.seat.entity.Seat;
import com.placeholder.domain.seat.entity.Seat.SeatStatus;
import com.placeholder.domain.seat.repository.SeatRepository;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.global.exception.custom.SeatNotAvailableException;
import com.placeholder.global.exception.custom.SeatNotFoundException;
import com.placeholder.global.exception.custom.SeatNotHeldByUserException;
import com.placeholder.global.exception.custom.UserNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;

@SuppressWarnings("null")
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationService {

    private final SeatRepository seatRepository;
    private final BookerAccountRepository bookerAccountRepository;
    private final ReservationRepository reservationRepository;
    private final PointTransactionRepository pointTransactionRepository;
    private final UserRepository userRepository;
    private final QueueRedisRepository queueRedisRepository;

    @Transactional
    public ReservationConfirmResponse confirmReservation(Long seatId, Long bookerId) {
        // 락 순서 규약: 좌석 → 예약자 계정. 전 경로가 같은 순서를 지켜야 순환 대기가 없다.
        // 제공자 계정은 잠그지 않는다 — 정산 잔액을 원장에서 파생시키므로 갱신할 행이 없다(ADR-021).

        // 1. 좌석 비관적 락 조회 — 동시 확정 요청 직렬화
        Seat seat = seatRepository.findByIdForUpdate(seatId)
                .orElseThrow(() -> new SeatNotFoundException("좌석을 찾을 수 없습니다"));

        // 2. 검증 (fail-fast)
        LocalDateTime now = LocalDateTime.now();
        validateHold(seat, bookerId, now);

        // 3. 예약자 계정 비관적 락 조회 — 동일 유저 동시 확정 시 잔액 이중 차감 방지
        BookerAccount bookerAccount = bookerAccountRepository.findByUserIdForUpdate(bookerId)
                .orElseThrow(() -> new UserNotFoundException("예약자 계정을 찾을 수 없습니다"));

        int price = seat.getPrice();

        // 4. 도메인 메서드로 상태 변경 — 잔액 부족 시 InsufficientPointException 발생 → 전체 롤백
        //    차감은 재원 계층 순서(EVENT→FREE→PAID)로 배분된다. 계산과 반영이 갈라지면 안 되므로
        //    계정 락을 이미 쥔 이 자리에서 수행한다 (ADR-020 4번).
        PointAllocation allocation = bookerAccount.deduct(price);
        seat.confirm();

        // 5. Reservation 저장
        User booker = userRepository.findByIdAndDeletedAtIsNull(bookerId)
                .orElseThrow(() -> new UserNotFoundException("예약자를 찾을 수 없습니다"));
        Reservation reservation = Reservation.builder()
                .booker(booker)
                .seat(seat)
                .paidAmount(price)
                .build();
        Reservation savedReservation = reservationRepository.save(reservation);

        // 6. PointTransaction 2행 저장 (DEDUCT: 예약자, SETTLE: 제공자)
        User provider = seat.getEvent().getProvider();
        pointTransactionRepository.save(PointTransaction.builder()
                .user(booker)
                .type(TransactionType.DEDUCT)
                .amount(price)
                .bucketEvent(allocation.event())
                .bucketFree(allocation.free())
                .bucketPaid(allocation.paid())
                .reservation(savedReservation)
                .build());
        // SETTLE은 제공자 원장이라 재원 계층이 없다 — 버킷은 0으로 두고 불변식에서도 제외된다 (ADR-020 5번).
        // 이 행이 제공자 정산의 유일한 진실이다. 잔액은 조회 시 이 행들의 합으로 파생된다(ADR-021) —
        // 확정이 제공자 계정을 건드리지 않으므로 같은 제공자의 판매가 서로를 기다리지 않는다.
        pointTransactionRepository.save(PointTransaction.builder()
                .user(provider)
                .type(TransactionType.SETTLE)
                .amount(price)
                .reservation(savedReservation)
                .build());

        // 7. 커밋 성공 시에만 대기열 입장 토큰 회수 — 구매 완료 유저가 잔여 TTL 동안 ceiling 슬롯을
        //    점유(유령 세션)하지 않도록 즉시 반환. 트랜잭션 안에서 바로 지우면 이후 커밋 실패 시
        //    "결제는 안 됐는데 토큰만 잃는" 역전이 생기므로 afterCommit으로 미룬다(롤백 시 미실행 = 토큰 보존).
        Long eventId = seat.getEvent().getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    queueRedisRepository.releaseEntryToken(eventId, bookerId);
                } catch (DataAccessException e) {
                    // Redis 장애가 confirm 결과를 바꾸면 안 됨 — 회수 실패 시 TTL이 안전망 (ADR-013 degradation)
                    log.warn("입장 토큰 회수 실패 — TTL 만료로 자연 회수 예정 (eventId={}, userId={})",
                            eventId, bookerId, e);
                }
            }
        });

        return ReservationConfirmResponse.builder()
                .reservationId(reservation.getId())
                .seatId(seatId)
                .paidAmount(price)
                .confirmedAt(reservation.getConfirmedAt())
                .remainingBalance(bookerAccount.getBalance())
                .build();
    }

    public MyReservationsResponse getMyReservations(Long bookerId) {
        List<Reservation> reservations =
                reservationRepository.findMyReservationsWithSeatAndEvent(bookerId);

        List<MyReservationsResponse.ReservationSummary> summaries = reservations.stream()
                .map(r -> {
                    Seat seat = r.getSeat();
                    return MyReservationsResponse.ReservationSummary.builder()
                            .reservationId(r.getId())
                            .eventId(seat.getEvent().getId())
                            .eventTitle(seat.getEvent().getTitle())
                            .eventVenue(seat.getEvent().getVenue())
                            .eventAt(seat.getEvent().getEventAt())
                            .seatId(seat.getId())
                            .seatLabel(seat.getLabel())
                            .paidAmount(r.getPaidAmount())
                            .confirmedAt(r.getConfirmedAt())
                            .build();
                })
                .toList();

        return MyReservationsResponse.builder()
                .reservations(summaries)
                .build();
    }

    private void validateHold(Seat seat, Long bookerId, LocalDateTime now) {
        if (seat.getStatus() != SeatStatus.HELD) {
            throw new SeatNotAvailableException("홀드 상태의 좌석만 확정할 수 있습니다");
        }
        if (seat.getHeldBy() == null || !seat.getHeldBy().getId().equals(bookerId)) {
            throw new SeatNotHeldByUserException("본인이 홀드한 좌석만 확정할 수 있습니다");
        }
        if (seat.getHeldUntil() == null || !seat.getHeldUntil().isAfter(now)) {
            throw new SeatNotAvailableException("홀드가 만료된 좌석입니다");
        }
    }
}
