package com.placeholder.domain.event.entity;

import com.placeholder.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "events",
    indexes = @Index(name = "idx_events_provider_id", columnList = "provider_id")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "provider_id", nullable = false)
    private User provider;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String venue;

    @Column(name = "event_at", nullable = false)
    private LocalDateTime eventAt;

    /**
     * 대기열 활성화 여부 (ADR-013). 인기 이벤트만 true로 두어 hold 진입점에 대기열 게이트를 건다.
     * 기본 false — 좌석 몇 개짜리 소형 이벤트엔 대기열이 오버엔지니어링이므로 hold가 자유롭게 통과한다.
     */
    @Column(name = "queue_enabled", nullable = false)
    private boolean queueEnabled;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    private void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}
