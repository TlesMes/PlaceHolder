package com.placeholder.domain.event.service;

import com.placeholder.domain.event.dto.EventCreateRequest;
import com.placeholder.domain.event.dto.EventCreateResponse;
import com.placeholder.domain.event.dto.EventDetailResponse;
import com.placeholder.domain.event.dto.EventListResponse;
import com.placeholder.domain.event.entity.Event;
import com.placeholder.domain.event.repository.EventRepository;
import com.placeholder.domain.seat.entity.Seat;
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

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventService {

    private final EventRepository eventRepository;
    private final SeatService seatService;
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

        List<EventListResponse.EventSummary> summaries = events.stream()
                .map(event -> EventListResponse.EventSummary.builder()
                        .eventId(event.getId())
                        .title(event.getTitle())
                        .venue(event.getVenue())
                        .eventAt(event.getEventAt())
                        .build())
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
                .seats(seatInfos)
                .build();
    }
}
