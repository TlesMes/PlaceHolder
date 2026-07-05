import { useEffect, useRef } from 'react';
import { releaseSeat, releaseSeatBeacon } from '../api/seats';
import { leaveQueueBeacon } from '../api/queue';

/**
 * 결제페이지 이탈 시 좌석 hold(가장 희소한 자원)를 만료(5분) 전에 즉시 반환한다 (ADR-016).
 * 이탈 유형에 따라 다르게 동작한다:
 *
 * - pagehide(탭 닫기·새로고침·외부 이동): 좌석 반환 + (queueEnabled면) 대기열 leave — 완전 이탈.
 *   언로드 중이라 keepalive fetch(beacon)로 통지.
 * - unmount(SPA 뒤로가기·헤더 이동 등 결제 포기): 좌석만 반환, 입장 토큰은 유지 —
 *   "좌석 바꾸기"가 성립하도록(재대기 없이 좌석페이지에서 다른 좌석 선택). 페이지가 살아있으므로 일반 axios.
 *
 * 결제 완료(done) 시엔 좌석이 CONFIRMED가 되므로 아무것도 하지 않는다(설령 호출돼도 서버가 멱등 no-op).
 * best-effort 통지 — 이 훅이 못 잡는 이탈(크래시 등)은 서버의 hold TTL(5분)이 안전망.
 *
 * @param {object} params
 * @param {string|number} params.seatId
 * @param {string|number} params.eventId
 * @param {boolean} params.queueEnabled
 * @param {boolean} params.done 결제 완료 여부 (true면 이탈 처리 생략)
 */
export function useCheckoutLeaveRelease({ seatId, eventId, queueEnabled, done }) {
  // 핸들러/클린업은 최초 값으로 클로저에 갇히므로, 최신 값을 ref로 추적한다.
  const doneRef = useRef(done);
  doneRef.current = done;
  const leftRef = useRef(false); // pagehide로 이미 떠났는지 — unmount 중복 반환 방지
  const canReleaseRef = useRef(false); // 실제 마운트가 안정된 뒤에만 unmount 반환을 허용

  useEffect(() => {
    if (!seatId) return;

    const onPageHide = () => {
      if (doneRef.current || leftRef.current) return;
      leftRef.current = true;
      releaseSeatBeacon(seatId);
      if (queueEnabled && eventId) leaveQueueBeacon(eventId);
    };
    window.addEventListener('pagehide', onPageHide);

    // unmount 반환을 다음 틱에만 허용한다. React StrictMode(개발)는 마운트 직후 동기적으로
    // 언마운트→재마운트하며 그 probe 언마운트의 cleanup이 실행되는데, 아직 canRelease가 false라
    // 좌석을 오반환하지 않는다. 사람의 실제 이탈(뒤로가기 등)은 항상 이 틱 이후이므로 정상 반환된다.
    const releaseTimer = setTimeout(() => { canReleaseRef.current = true; }, 0);

    return () => {
      clearTimeout(releaseTimer);
      window.removeEventListener('pagehide', onPageHide);
      // SPA 이동에 의한 진짜 언마운트: 결제 미완료 & pagehide 미발생이면 좌석만 반환(토큰 유지).
      if (canReleaseRef.current && !doneRef.current && !leftRef.current) {
        releaseSeat(seatId).catch(() => {});
      }
    };
  }, [seatId, eventId, queueEnabled]);
}
