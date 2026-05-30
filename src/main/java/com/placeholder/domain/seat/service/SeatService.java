package com.placeholder.domain.seat.service;

import com.placeholder.domain.event.dto.EventCreateRequest;
import com.placeholder.domain.event.entity.Event;
import com.placeholder.domain.seat.dto.SeatResponse;
import com.placeholder.domain.seat.entity.Seat;
import com.placeholder.domain.seat.repository.SeatRepository;
import com.placeholder.global.exception.custom.DuplicateSeatLabelException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SeatService {

    private final SeatRepository seatRepository;

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
                        .build())
                .toList();

        return SeatResponse.builder()
                .eventId(eventId)
                .seats(seatInfos)
                .build();
    }
}
