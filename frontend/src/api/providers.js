import client from './client';

// 내 정산 (PROVIDER) — 응답: { settlementBalance, settlements: [{ transactionId, amount, reservationId, eventTitle, seatLabel, confirmedAt }] }
export const getMySettlement = () => client.get('/api/providers/my/settlement');
