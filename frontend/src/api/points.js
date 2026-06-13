import client from './client';

// 포인트 이력 cursor 페이징 (BOOKER/PROVIDER)
// 파라미터: from/to(ISO LocalDateTime), cursor(이전 페이지 마지막 createdAt), size
// 응답: { items: [{ transactionId, type, amount, reservationId, eventTitle, createdAt }], nextCursor }
// 타입 필터(CHARGE/DEDUCT/SETTLE)는 서버 미지원 → 클라이언트에서 거른다.
export const getPointHistory = ({ from, to, cursor, size } = {}) => {
  const params = {};
  if (from) params.from = from;
  if (to) params.to = to;
  if (cursor) params.cursor = cursor;
  if (size) params.size = size;
  return client.get('/api/points/history', { params });
};
