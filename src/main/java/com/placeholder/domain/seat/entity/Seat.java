package com.placeholder.domain.seat.entity;

import com.placeholder.domain.event.entity.Event;
import com.placeholder.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "seats",
    uniqueConstraints = @UniqueConstraint(name = "uk_seats_event_label", columnNames = {"event_id", "label"}),
    indexes = @Index(name = "idx_seats_status_held_until", columnList = "status, held_until")
)
/**
 * <b>{@code @DynamicUpdate}인 이유:</b> Hibernate 기본값은 엔티티마다 UPDATE 문을 하나 만들어
 * 재사용하는 것이고, 그 문장에는 안 바뀐 칸도 전부 들어간다. 좌석 확정은 {@code status}·
 * {@code heldBy}·{@code heldUntil} 세 칸만 바꾸는데 {@code event_id}·{@code label}까지 쓰고 있었다.
 *
 * <p>그 두 칸이 {@code uk_seats_event_label}을 이루고 이 인덱스는 {@code event_id} 순으로 정렬돼
 * 있다. 한 이벤트의 좌석들은 인덱스에서 같은 자리에 모여 있으므로, 인기 이벤트처럼 좌석이 한
 * 이벤트에 몰리면 동시 확정들이 매번 같은 자리를 거치며 짧게 순서를 기다린다.
 *
 * <p>정합성 문제가 아니라 헛일이다. 문장에서 두 칸을 빼면 그 인덱스를 볼 이유가 사라진다.
 * 실측: 좌석 240석이 한 이벤트에 몰린 조건에서 확정 처리량 266 → 323건/초(+21%),
 * 이벤트가 8개로 흩어진 조건에서는 변화 없음(392 → 408). 자세한 근거는
 * {@code docs/performance/provider-settlement-throughput.md} 9절.
 */
@DynamicUpdate
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

    public void confirm() {
        this.status = SeatStatus.CONFIRMED;
        this.heldBy = null;
        this.heldUntil = null;
    }

    /**
     * 만료된 HELD 좌석을 AVAILABLE로 되돌린다. (스케줄러 자동 만료, ADR-009)
     * 상태 변경은 이 도메인 메서드로만 수행한다 (setter 금지).
     */
    public void release() {
        this.status = SeatStatus.AVAILABLE;
        this.heldBy = null;
        this.heldUntil = null;
    }

    public enum SeatStatus {
        AVAILABLE, HELD, CONFIRMED
    }
}
