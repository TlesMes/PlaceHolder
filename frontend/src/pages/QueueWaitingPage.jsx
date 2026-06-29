import { useCallback, useEffect, useRef, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { enterQueue, getQueueStatus } from '../api/queue';
import { toMessage } from '../lib/errors';
import Layout from '../components/Layout';
import Spinner from '../components/Spinner';

export default function QueueWaitingPage() {
  const { id: eventId } = useParams();
  const navigate = useNavigate();
  const [status, setStatus] = useState(null);
  const [error, setError] = useState('');
  const timerRef = useRef(null);

  const goToEvent = useCallback(() => {
    clearTimeout(timerRef.current);
    navigate(`/events/${eventId}`, { replace: true });
  }, [eventId, navigate]);

  useEffect(() => {
    let cancelled = false;

    // 폴링 주기는 서버가 status 응답의 nextPollDelayMs로 동적 제어한다(앞 인원/rate 기반).
    // 주기가 매번 달라지므로 setInterval 대신 응답 수신 후 재귀 setTimeout으로 다음 폴을 예약한다.
    const DEFAULT_DELAY = 3000; // 응답에 값이 없거나 일시 오류 시 폴백
    const scheduleNext = (delayMs) => {
      timerRef.current = setTimeout(poll, delayMs || DEFAULT_DELAY);
    };

    async function poll() {
      try {
        const res = await getQueueStatus(eventId);
        if (cancelled) return;
        setStatus(res.data);
        if (res.data.admitted) {
          goToEvent();
          return;
        }
        scheduleNext(res.data.nextPollDelayMs);
      } catch {
        if (!cancelled) scheduleNext(DEFAULT_DELAY); // 일시 오류 — 기본 간격 재시도
      }
    }

    async function init() {
      try {
        const res = await enterQueue(eventId);
        if (cancelled) return;
        setStatus(res.data);
        if (res.data.admitted) {
          goToEvent();
          return;
        }
        scheduleNext(res.data.nextPollDelayMs);
      } catch (err) {
        if (!cancelled) setError(toMessage(err, '대기열 진입에 실패했습니다.'));
      }
    }

    init();

    return () => {
      cancelled = true;
      clearTimeout(timerRef.current);
    };
  }, [eventId, goToEvent]);

  if (error) {
    return (
      <Layout>
        <p className="rounded-xl bg-danger-soft px-5 py-4 text-sm text-danger-soft-fg">{error}</p>
      </Layout>
    );
  }

  return (
    <Layout>
      <div className="flex flex-col items-center justify-center py-24 text-center">
        <div className="mb-8 flex h-20 w-20 items-center justify-center rounded-full bg-primary-soft">
          <span className="text-3xl" aria-hidden>⏳</span>
        </div>

        <h1 className="text-2xl font-bold text-fg">입장 대기 중</h1>
        <p className="mt-2 text-sm text-fg-muted">
          순서가 되면 자동으로 좌석 선택 화면으로 이동합니다.
        </p>

        {status ? (
          <div className="mt-10 flex gap-6">
            <div className="rounded-2xl border border-border bg-surface px-8 py-6 shadow-sm">
              <p className="text-xs font-medium uppercase tracking-wide text-fg-subtle">내 순번</p>
              <p className="mt-1 text-4xl font-bold text-primary">
                {status.position ?? '—'}
              </p>
            </div>
            <div className="rounded-2xl border border-border bg-surface px-8 py-6 shadow-sm">
              <p className="text-xs font-medium uppercase tracking-wide text-fg-subtle">전체 대기</p>
              <p className="mt-1 text-4xl font-bold text-fg">{status.waiting}</p>
            </div>
          </div>
        ) : (
          <Spinner className="mt-10" />
        )}

        <p className="mt-8 text-xs text-fg-subtle">대기 인원에 맞춰 자동으로 갱신됩니다.</p>
      </div>
    </Layout>
  );
}
