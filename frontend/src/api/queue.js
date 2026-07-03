import client from './client';

export const enterQueue = (eventId) => client.post(`/api/queue/${eventId}/enter`);
export const getQueueStatus = (eventId) => client.get(`/api/queue/${eventId}/status`);

// 페이지 이탈(pagehide) 시점의 이탈 통지 전용. axios 요청은 페이지 종료와 함께 취소될 수 있어
// keepalive fetch를 쓴다. sendBeacon은 Authorization 헤더를 실을 수 없어 대안이 아님.
// best-effort — 실패해도 서버의 토큰 TTL(5분)이 안전망 (ADR-015).
export const leaveQueueBeacon = (eventId) => {
  const token = localStorage.getItem('accessToken');
  fetch(`${client.defaults.baseURL}/api/queue/${eventId}/leave`, {
    method: 'POST',
    keepalive: true,
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  }).catch(() => {});
};
