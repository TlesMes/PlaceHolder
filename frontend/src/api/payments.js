import client from './client';

// 결제 주문 생성 (BOOKER)
// 응답: { orderId, amount, clientKey }
// amount는 서버가 확정 저장한다 → 이후 confirm에서 위변조 검증 기준이 된다 (ADR-018).
export const createPaymentOrder = (amount) =>
  client.post('/api/payments/orders', { amount });

// 동기 승인 (BOOKER). 토스 결제창 성공 후 successUrl 콜백에서 호출한다.
// 응답: { orderId, chargedAmount, balance, status }
// 서버가 멱등 처리하므로 같은 orderId로 두 번 호출해도 적립은 1회다.
export const confirmPayment = ({ orderId, paymentKey, amount }) =>
  client.post('/api/payments/confirm', { orderId, paymentKey, amount });

// 내 결제·환불 내역 (BOOKER)
// 응답: { payments: [{ orderId, amount, status, canceledAmount, createdAt, approvedAt,
//                      canceledAt, refundStatus }] }
// refundStatus는 저장값이 아니라 서버가 두 시각(canceledAt/cancelConfirmedAt)에서 파생한다.
// PENDING = 포인트는 회수됐지만 현금 환불 요청이 아직 토스에 도달하지 않은 상태 (ADR-019).
export const getMyPayments = () => client.get('/api/payments/my');
