package com.placeholder.domain.seat.service;

import com.placeholder.domain.event.dto.EventCreateRequest;
import com.placeholder.domain.event.entity.Event;
import com.placeholder.domain.seat.dto.SeatHoldResponse;
import com.placeholder.domain.seat.dto.SeatReleaseResponse;
import com.placeholder.domain.seat.dto.SeatResponse;
import com.placeholder.domain.queue.repository.QueueRedisRepository;
import com.placeholder.domain.seat.entity.Seat;
import com.placeholder.domain.seat.repository.SeatGateProjection;
import com.placeholder.domain.seat.repository.SeatRepository;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.global.exception.custom.DuplicateSeatLabelException;
import com.placeholder.global.exception.custom.QueueAdmissionRequiredException;
import com.placeholder.global.exception.custom.SeatNotAvailableException;
import com.placeholder.global.exception.custom.SeatNotFoundException;
import com.placeholder.global.exception.custom.UserNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SeatService {

    private final SeatRepository seatRepository;
    private final UserRepository userRepository;
    private final QueueRedisRepository queueRepository;

    @Value("${seat.hold.ttl-minutes:5}")
    private int holdTtlMinutes;

    /**
     * 이벤트에 대한 좌석 일괄 생성
     * Phase B-4 결정: saveAll() 사용 (JPA batch insert)
     */
    @Transactional
    public int createSeatsForEvent(Event event, List<EventCreateRequest.SeatMetadataDto> seatMetadata) {
        // 좌석 라벨 중복 검증
        Set<String> labels = new HashSet<>();
        for (EventCreateRequest.SeatMetadataDto dto : seatMetadata) {
            if (!labels.add(dto.getLabel())) {
                throw new DuplicateSeatLabelException(
                        "중복된 좌석 라벨이 있습니다: " + dto.getLabel());
            }
        }

        // Seat 엔티티 리스트 생성
        List<Seat> seats = seatMetadata.stream()
                .map(dto -> Seat.builder()
                        .event(event)
                        .label(dto.getLabel())
                        .price(dto.getPrice())
                        .status(Seat.SeatStatus.AVAILABLE)
                        .build())
                .toList();

        // saveAll() - JPA batch insert 사용
        List<Seat> savedSeats = seatRepository.saveAll(seats);
        return savedSeats.size();
    }

    /**
     * 좌석 홀드(점유). 비관적 락으로 좌석 행을 잠근 뒤 점유 가능 여부를 판정하고 전이한다.
     * 동시 요청 시 락을 보유한 트랜잭션만 status를 검사·갱신하므로 한 명만 성공한다 (ADR-008).
     */
    @Transactional
    public SeatHoldResponse holdSeat(Long seatId, Long bookerId) {
        enforceQueueAdmission(seatId, bookerId);

        Seat seat = seatRepository.findByIdForUpdate(seatId)
                .orElseThrow(() -> new SeatNotFoundException("좌석을 찾을 수 없습니다"));

        LocalDateTime now = LocalDateTime.now();
        if (!seat.isHoldable(now)) {
            throw new SeatNotAvailableException("이미 점유된 좌석입니다");
        }

        User booker = userRepository.findByIdAndDeletedAtIsNull(bookerId)
                .orElseThrow(() -> new UserNotFoundException("예약자를 찾을 수 없습니다"));

        LocalDateTime heldUntil = now.plusMinutes(holdTtlMinutes);
        seat.hold(booker, heldUntil);

        return SeatHoldResponse.builder()
                .seatId(seat.getId())
                .status(seat.getStatus().name())
                .heldBy(booker.getId())
                .heldUntil(seat.getHeldUntil())
                .build();
    }

    /**
     * 좌석 hold 반환. 결제페이지 이탈 시 좌석(가장 희소한 자원)을 만료(5분) 전에 즉시 AVAILABLE로 되돌린다 (ADR-016).
     * 비관적 락으로 좌석 행을 잠근 뒤 "HELD && 내 홀드"일 때만 반환하고, 그 외(타인 홀드·CONFIRMED·이미 AVAILABLE)는
     * 예외 없이 no-op 한다 — pagehide/unmount에서 중복·경쟁 호출(만료 스케줄러 선점 등)이 들어와도 멱등해야 한다.
     * 입장 토큰은 건드리지 않는다: 뒤로가기(좌석만 반환·토큰 유지)와 완전 이탈(좌석+leave)을 프론트가 구분하려면
     * 토큰 회수는 별도 leave API가 담당해야 하기 때문 (ADR-016). release는 자원 반납이라 대기열 게이트도 불필요.
     */
    @Transactional
    public SeatReleaseResponse releaseSeat(Long seatId, Long bookerId) {
        Seat seat = seatRepository.findByIdForUpdate(seatId)
                .orElseThrow(() -> new SeatNotFoundException("좌석을 찾을 수 없습니다"));

        boolean mine = seat.getStatus() == Seat.SeatStatus.HELD
                && seat.getHeldBy() != null
                && seat.getHeldBy().getId().equals(bookerId);
        if (mine) {
            seat.release();
        }

        return SeatReleaseResponse.builder()
                .seatId(seat.getId())
                .status(seat.getStatus().name())
                .released(mine)
                .build();
    }

    /**
     * 대기열 게이트 (ADR-013). 좌석 행을 잠그기 전에 비잠금으로 이벤트의 대기열 활성화 여부를 확인하고,
     * 활성화된 이벤트라면 입장 토큰 없이는 fast-fail 한다 — 락/커넥션 점유 전에 거절해 트래픽을 보호한다.
     * 비활성 이벤트는 토큰 없이 그대로 통과한다(소형 이벤트 오버엔지니어링 방지).
     */
    private void enforceQueueAdmission(Long seatId, Long bookerId) {
        SeatGateProjection gate = seatRepository.findGateInfoBySeatId(seatId)
                .orElseThrow(() -> new SeatNotFoundException("좌석을 찾을 수 없습니다"));

        if (gate.isQueueEnabled() && !queueRepository.hasEntryToken(gate.getEventId(), bookerId)) {
            throw new QueueAdmissionRequiredException("대기열 입장 토큰이 필요합니다. 대기열에 진입해 주세요");
        }
    }

    /**
     * 이벤트의 좌석 목록 조회
     */
    public List<Seat> getSeatsByEventId(Long eventId) {
        return seatRepository.findByEventId(eventId);
    }

    /**
     * 좌석 목록 조회 Response 변환
     */
    public SeatResponse getSeatsResponse(Long eventId) {
        List<Seat> seats = getSeatsByEventId(eventId);

        List<SeatResponse.SeatInfo> seatInfos = seats.stream()
                .map(seat -> SeatResponse.SeatInfo.builder()
                        .seatId(seat.getId())
                        .label(seat.getLabel())
                        .price(seat.getPrice())
                        .status(seat.getStatus().name())
                        .heldUntil(seat.getHeldUntil())
                        .build())
                .toList();

        return SeatResponse.builder()
                .eventId(eventId)
                .seats(seatInfos)
                .build();
    }
}
