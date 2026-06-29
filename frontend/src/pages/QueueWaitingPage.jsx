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
    clearInterval(timerRef.current);
    navigate(`/events/${eventId}`, { replace: true });
  }, [eventId, navigate]);

  useEffect(() => {
    let cancelled = false;

    async function init() {
      try {
        const res = await enterQueue(eventId);
        if (cancelled) return;
        const data = res.data;
        setStatus(data);
        if (data.admitted) {
          goToEvent();
          return;
        }

        timerRef.current = setInterval(async () => {
          try {
            const pollRes = await getQueueStatus(eventId);
            if (cancelled) return;
            const pollData = pollRes.data;
            setStatus(pollData);
            if (pollData.admitted) {
              goToEvent();
            }
          } catch {
            // 일시 네트워크 오류는 무시하고 폴링 계속
          }
        }, 2000);
      } catch (err) {
        if (!cancelled) setError(toMessage(err, '대기열 진입에 실패했습니다.'));
      }
    }

    init();

    return () => {
      cancelled = true;
      clearInterval(timerRef.current);
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

        <p className="mt-8 text-xs text-fg-subtle">약 2초마다 자동으로 갱신됩니다.</p>
      </div>
    </Layout>
  );
}
