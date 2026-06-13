import client from './client';

// 내 예약 내역 (BOOKER) — 응답: { reservations: [{ reservationId, eventId, eventTitle, eventVenue, eventAt, seatId, seatLabel, paidAmount, confirmedAt }] }
export const getMyReservations = () => client.get('/api/reservations/my');
