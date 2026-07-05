import client from './client';

// 좌석 목록 조회 (인증 불필요) — 응답: { eventId, seats: [{ seatId, label, price, status }] }
export const getSeats = (eventId) => client.get(`/api/events/${eventId}/seats`);

// 좌석 홀드 (BOOKER) — 응답: { seatId, status, heldBy, heldUntil }
export const holdSeat = (seatId) => client.post(`/api/seats/${seatId}/hold`);

// 예약 확정 (BOOKER) — 응답: { reservationId, seatId, paidAmount, confirmedAt, remainingBalance }
export const confirmSeat = (seatId) => client.post(`/api/seats/${seatId}/confirm`);

// 좌석 hold 반환 (BOOKER) — SPA 이동(뒤로가기)용 일반 axios 호출. 응답: { seatId, status, released }
export const releaseSeat = (seatId) => client.post(`/api/seats/${seatId}/release`);

// 페이지 이탈(pagehide) 시점의 hold 반환 전용. axios 요청은 페이지 종료와 함께 취소될 수 있어
// keepalive fetch를 쓴다. sendBeacon은 Authorization 헤더를 실을 수 없어 대안이 아님.
// best-effort — 실패해도 좌석 홀드 TTL(5분)이 안전망 (ADR-016).
export const releaseSeatBeacon = (seatId) => {
  const token = localStorage.getItem('accessToken');
  fetch(`${client.defaults.baseURL}/api/seats/${seatId}/release`, {
    method: 'POST',
    keepalive: true,
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  }).catch(() => {});
};
