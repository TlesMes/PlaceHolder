package com.placeholder.domain.reservation.service;

import com.placeholder.domain.booker.entity.BookerAccount;
import com.placeholder.domain.booker.repository.BookerAccountRepository;
import com.placeholder.domain.point.entity.PointTransaction;
import com.placeholder.domain.point.entity.PointTransaction.TransactionType;
import com.placeholder.domain.point.repository.PointTransactionRepository;
import com.placeholder.domain.provider.entity.ProviderAccount;
import com.placeholder.domain.provider.repository.ProviderAccountRepository;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@SuppressWarnings("null")
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationService {

    private final SeatRepository seatRepository;
    private final BookerAccountRepository bookerAccountRepository;
    private final ProviderAccountRepository providerAccountRepository;
    private final ReservationRepository reservationRepository;
    private final PointTransactionRepository pointTransactionRepository;
    private final UserRepository userRepository;

    @Transactional
    public ReservationConfirmResponse confirmReservation(Long seatId, Long bookerId) {
        // 1. 좌석 비관적 락 조회 — 동시 확정 요청 직렬화
        Seat seat = seatRepository.findByIdForUpdate(seatId)
                .orElseThrow(() -> new SeatNotFoundException("좌석을 찾을 수 없습니다"));

        // 2. 검증 (fail-fast)
        LocalDateTime now = LocalDateTime.now();
        validateHold(seat, bookerId, now);

        // 3. 예약자 계정 비관적 락 조회 — 동일 유저 동시 확정 시 잔액 이중 차감 방지
        BookerAccount bookerAccount = bookerAccountRepository.findByUserIdForUpdate(bookerId)
                .orElseThrow(() -> new UserNotFoundException("예약자 계정을 찾을 수 없습니다"));

        // 4. 제공자 계정 조회
        Long providerId = seat.getEvent().getProvider().getId();
        ProviderAccount providerAccount = providerAccountRepository.findByUserId(providerId)
                .orElseThrow(() -> new UserNotFoundException("제공자 계정을 찾을 수 없습니다"));

        int price = seat.getPrice();

        // 5. 도메인 메서드로 상태 변경 — 잔액 부족 시 InsufficientPointException 발생 → 전체 롤백
        bookerAccount.deduct(price);
        providerAccount.settle(price);
        seat.confirm();

        // 6. Reservation 저장
        User booker = userRepository.findByIdAndDeletedAtIsNull(bookerId)
                .orElseThrow(() -> new UserNotFoundException("예약자를 찾을 수 없습니다"));
        Reservation reservation = Reservation.builder()
                .booker(booker)
                .seat(seat)
                .paidAmount(price)
                .build();
        Reservation savedReservation = reservationRepository.save(reservation);

        // 7. PointTransaction 2행 저장 (DEDUCT: 예약자, SETTLE: 제공자)
        User provider = seat.getEvent().getProvider();
        pointTransactionRepository.save(PointTransaction.builder()
                .user(booker)
                .type(TransactionType.DEDUCT)
                .amount(price)
                .reservation(savedReservation)
                .build());
        pointTransactionRepository.save(PointTransaction.builder()
                .user(provider)
                .type(TransactionType.SETTLE)
                .amount(price)
                .reservation(savedReservation)
                .build());

        return ReservationConfirmResponse.builder()
                .reservationId(reservation.getId())
                .seatId(seatId)
                .paidAmount(price)
                .confirmedAt(reservation.getConfirmedAt())
                .remainingBalance(bookerAccount.getBalance())
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
