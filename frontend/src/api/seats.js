import client from './client';

// 좌석 목록 조회 (인증 불필요) — 응답: { eventId, seats: [{ seatId, label, price, status }] }
export const getSeats = (eventId) => client.get(`/api/events/${eventId}/seats`);

// 좌석 홀드 (BOOKER) — 응답: { seatId, status, heldBy, heldUntil }
export const holdSeat = (seatId) => client.post(`/api/seats/${seatId}/hold`);

// 예약 확정 (BOOKER) — 응답: { reservationId, seatId, paidAmount, confirmedAt, remainingBalance }
export const confirmSeat = (seatId) => client.post(`/api/seats/${seatId}/confirm`);
