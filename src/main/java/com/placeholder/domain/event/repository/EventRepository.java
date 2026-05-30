package com.placeholder.domain.event.repository;

import com.placeholder.domain.event.entity.Event;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface EventRepository extends JpaRepository<Event, Long> {

    List<Event> findByProviderId(Long providerId);

    List<Event> findByProviderIdOrderByEventAtDesc(Long providerId);

    List<Event> findByEventAtAfter(LocalDateTime dateTime);

    List<Event> findByEventAtBetween(LocalDateTime start, LocalDateTime end);
}
