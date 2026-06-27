package com.placeholder.domain.event.service;

import com.placeholder.domain.event.dto.EventCreateRequest;
import com.placeholder.domain.event.dto.EventCreateResponse;
import com.placeholder.domain.event.dto.EventDetailResponse;
import com.placeholder.domain.event.dto.EventListResponse;
import com.placeholder.domain.event.entity.Event;
import com.placeholder.domain.event.repository.EventRepository;
import com.placeholder.domain.seat.entity.Seat;
import com.placeholder.domain.seat.repository.SeatCountProjection;
import com.placeholder.domain.seat.repository.SeatRepository;
import com.placeholder.domain.seat.service.SeatService;
import com.placeholder.domain.user.entity.User;
import com.placeholder.domain.user.repository.UserRepository;
import com.placeholder.global.exception.custom.EventNotFoundException;
import com.placeholder.global.exception.custom.UserNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventService {

    private final EventRepository eventRepository;
    private final SeatService seatService;
    private final SeatRepository seatRepository;
    private final UserRepository userRepository;

    @PreAuthorize("hasRole('PROVIDER')")
    @Transactional
    public EventCreateResponse createEvent(Long providerId, EventCreateRequest request) {
        User provider = userRepository.findByIdAndDeletedAtIsNull(providerId)
                .orElseThrow(() -> new UserNotFoundException("제공자를 찾을 수 없습니다"));

        // 2. Event 엔티티 생성 및 저장
        Event event = Event.builder()
                .provider(provider)
                .title(request.getTitle())
                .venue(request.getVenue())
                .eventAt(request.getEventAt())
                .queueEnabled(request.isQueueEnabled())
                .build();

        Event savedEvent = eventRepository.save(event);

        // 3. 좌석 일괄 생성 위임
        int createdSeatCount = seatService.createSeatsForEvent(savedEvent, request.getSeats());

        // 4. Response 반환
        return EventCreateResponse.builder()
                .eventId(savedEvent.getId())
                .title(savedEvent.getTitle())
                .createdSeatCount(createdSeatCount)
                .build();
    }

    /**
     * 이벤트 목록 조회 (예약자)
     */
    public EventListResponse getEvents() {
        List<Event> events = eventRepository.findAll();

        // [Phase D-1 after] GROUP BY 집계 1쿼리로 이벤트별 좌석 통계를 한 번에 조회 (ADR-011).
        // events 1쿼리 + 집계 1쿼리 = 총 2쿼리 (이벤트 수 무관 상수). before의 N+1(1+2N) 해소.
        List<Long> eventIds = events.stream().map(Event::getId).toList();
        Map<Long, SeatCountProjection> countsByEventId = eventIds.isEmpty()
                ? Map.of()
                : seatRepository.countSeatsByEventIds(eventIds).stream()
                        .collect(Collectors.toMap(SeatCountProjection::getEventId, c -> c));

        List<EventListResponse.EventSummary> summaries = events.stream()
                .map(event -> {
                    SeatCountProjection counts = countsByEventId.get(event.getId());
                    int totalSeats = counts != null ? (int) counts.getTotal() : 0;
                    int availableSeats = counts != null ? (int) counts.getAvailable() : 0;
                    return EventListResponse.EventSummary.builder()
                            .eventId(event.getId())
                            .title(event.getTitle())
                            .venue(event.getVenue())
                            .eventAt(event.getEventAt())
                            .totalSeats(totalSeats)
                            .availableSeats(availableSeats)
                            .build();
                })
                .toList();

        return EventListResponse.builder()
                .events(summaries)
                .build();
    }

    /**
     * 이벤트 상세 조회 (좌석 포함)
     */
    public EventDetailResponse getEventDetail(Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new EventNotFoundException("이벤트를 찾을 수 없습니다"));

        List<Seat> seats = seatService.getSeatsByEventId(eventId);

        List<EventDetailResponse.SeatInfo> seatInfos = seats.stream()
                .map(seat -> EventDetailResponse.SeatInfo.builder()
                        .seatId(seat.getId())
                        .label(seat.getLabel())
                        .price(seat.getPrice())
                        .status(seat.getStatus().name())
                        .build())
                .toList();

        return EventDetailResponse.builder()
                .eventId(event.getId())
                .title(event.getTitle())
                .venue(event.getVenue())
                .eventAt(event.getEventAt())
                .queueEnabled(event.isQueueEnabled())
                .seats(seatInfos)
                .build();
    }
}
