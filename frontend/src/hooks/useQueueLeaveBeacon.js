import { useEffect } from 'react';
import { leaveQueueBeacon } from '../api/queue';

/**
 * 페이지 이탈(탭 닫기·새로고침·외부 이동) 시 대기열 이탈을 서버에 통지한다 (ADR-015).
 *
 * pagehide는 문서 언로드에만 발생하고 SPA 라우트 전환에는 발생하지 않으므로,
 * 내부 이동(대기실→좌석 페이지 등)은 이탈로 치지 않는다. 백그라운드 탭(딴짓)도
 * 언로드가 아니라 세션이 유지된다 — 페이지를 실제로 닫아야 이탈.
 *
 * best-effort 통지다. 크래시·강제종료처럼 이 훅이 못 잡는 이탈은
 * 서버의 입장 토큰 TTL(5분)이 안전망으로 회수한다.
 *
 * @param {string|number} eventId
 * @param {boolean} enabled queueEnabled 이벤트에서만 활성화
 */
export function useQueueLeaveBeacon(eventId, enabled) {
  useEffect(() => {
    if (!eventId || !enabled) return;
    const onPageHide = () => leaveQueueBeacon(eventId);
    window.addEventListener('pagehide', onPageHide);
    return () => window.removeEventListener('pagehide', onPageHide);
  }, [eventId, enabled]);
}
