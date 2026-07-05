package com.placeholder.domain.event.repository;

import com.placeholder.domain.event.entity.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {

    /**
     * 대기열 게이트 판정용 경량 조회 (A안, ADR-013 개정). 좌석 조회 경로가 이벤트 단위로
     * queueEnabled 여부만 확인하도록 엔티티 전체 로드 없이 플래그만 가져온다.
     */
    @Query("select e.queueEnabled from Event e where e.id = :id")
    Optional<Boolean> findQueueEnabledById(@Param("id") Long id);

    List<Event> findByProviderId(Long providerId);

    List<Event> findByProviderIdOrderByEventAtDesc(Long providerId);

    List<Event> findByEventAtAfter(LocalDateTime dateTime);

    List<Event> findByEventAtBetween(LocalDateTime start, LocalDateTime end);
}
