package com.placeholder.domain.queue.controller;

import com.placeholder.domain.queue.dto.QueueStatusResponse;
import com.placeholder.domain.queue.service.QueueService;
import com.placeholder.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 대기열 API (ADR-013). hold 진입 전 트래픽을 셰이핑한다.
 *
 * <p>대기열 게이트는 hold 진입점({@code POST /seats/{id}/hold})에만 건다. 조회(좌석 그리드)는 자유,
 * confirm은 hold 하위집합이라 별도 게이트가 불필요하다(E-1 3단계에서 hold 게이트 연결).
 */
@RestController
@RequestMapping("/api/queue")
@RequiredArgsConstructor
public class QueueController {

    private final QueueService queueService;

    /**
     * 대기열 진입 - 예약자만 가능. 현재 순번/대기 인원/입장 토큰 보유 여부 반환.
     */
    @PreAuthorize("hasRole('BOOKER')")
    @PostMapping("/{eventId}/enter")
    public ResponseEntity<QueueStatusResponse> enter(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long eventId) {

        return ResponseEntity.ok(queueService.enter(eventId, userDetails.getUserId()));
    }

    /**
     * 대기 상태 조회 - 예약자만 가능. 프론트는 이 엔드포인트를 폴링한다(추후 SSE 교체 가능).
     */
    @PreAuthorize("hasRole('BOOKER')")
    @GetMapping("/{eventId}/status")
    public ResponseEntity<QueueStatusResponse> status(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long eventId) {

        return ResponseEntity.ok(queueService.status(eventId, userDetails.getUserId()));
    }
}
