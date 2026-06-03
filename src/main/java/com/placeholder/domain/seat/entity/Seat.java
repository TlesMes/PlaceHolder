package com.placeholder.domain.seat.entity;

import com.placeholder.domain.event.entity.Event;
import com.placeholder.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "seats",
    uniqueConstraints = @UniqueConstraint(name = "uk_seats_event_label", columnNames = {"event_id", "label"}),
    indexes = @Index(name = "idx_seats_status_held_until", columnList = "status, held_until")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private int price;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeatStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "held_by")
    private User heldBy;

    @Column(name = "held_until")
    private LocalDateTime heldUntil;

    /**
     * 좌석을 점유(Hold) 가능한 상태인지 판정한다.
     * - AVAILABLE: 항상 점유 가능
     * - HELD: held_until이 만료됐으면 점유 가능 (lazy 재점유, ADR-008 옵션 A)
     * - CONFIRMED: 항상 점유 불가
     */
    public boolean isHoldable(LocalDateTime now) {
        if (status == SeatStatus.AVAILABLE) {
            return true;
        }
        if (status == SeatStatus.HELD) {
            return heldUntil != null && heldUntil.isBefore(now);
        }
        return false;
    }

    /**
     * 좌석을 점유한다. (AVAILABLE 또는 만료된 HELD → HELD)
     * 상태 변경은 이 도메인 메서드로만 수행한다 (setter 금지).
     */
    public void hold(User booker, LocalDateTime heldUntil) {
        this.status = SeatStatus.HELD;
        this.heldBy = booker;
        this.heldUntil = heldUntil;
    }

    public enum SeatStatus {
        AVAILABLE, HELD, CONFIRMED
    }
}
