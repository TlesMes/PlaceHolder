import { useEffect, useRef, useState, useCallback } from 'react';
import { getSeats } from '../api/seats';

/**
 * 좌석 목록을 주기적으로 폴링한다.
 *
 * [교체 지점] 현재는 setInterval 기반 단기 폴링이다. 백엔드에 좌석 상태 변경
 * push(SSE/WebSocket)가 추가되면 이 훅 내부만 EventSource로 교체하면 되고,
 * 호출부(EventDetailPage)는 그대로 둔다. 폴링→푸시 트레이드오프는 의도된 설계.
 *
 * @param {string|number} eventId
 * @param {number} intervalMs 폴링 주기 (기본 2500ms)
 */
export function useSeatPolling(eventId, intervalMs = 2500) {
  const [seats, setSeats] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const timerRef = useRef(null);

  const fetchSeats = useCallback(
    async (silent = false) => {
      if (!eventId) return;
      try {
        const res = await getSeats(eventId);
        setSeats(res.data.seats ?? []);
        setError(null);
      } catch (err) {
        // 폴링 중 일시 오류는 화면을 깨지 않도록 silent 처리, 최초 로드 오류만 노출.
        if (!silent) setError(err);
      } finally {
        if (!silent) setLoading(false);
      }
    },
    [eventId]
  );

  // 즉시 갱신 (홀드/확정 직후 폴링을 기다리지 않기 위함).
  const refetch = useCallback(() => fetchSeats(true), [fetchSeats]);

  useEffect(() => {
    if (!eventId) return;
    // eventId 변경 시 로딩 표시 — 외부(API) 동기화의 일부.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLoading(true);
    fetchSeats(false);

    timerRef.current = setInterval(() => {
      // 탭이 백그라운드면 폴링 생략 (불필요한 요청 절약).
      if (document.visibilityState === 'visible') fetchSeats(true);
    }, intervalMs);

    return () => clearInterval(timerRef.current);
  }, [eventId, intervalMs, fetchSeats]);

  return { seats, loading, error, refetch };
}
