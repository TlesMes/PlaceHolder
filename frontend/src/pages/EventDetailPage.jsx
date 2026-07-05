import { useEffect, useState } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import { getEventDetail } from '../api/events';
import { holdSeat } from '../api/seats';
import { getQueueStatus } from '../api/queue';
import { useSeatPolling } from '../hooks/useSeatPolling';
import { useQueueLeaveBeacon } from '../hooks/useQueueLeaveBeacon';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import { toMessage } from '../lib/errors';
import { effectiveStatus } from '../lib/seatStyle';
import { formatDateTime, formatPrice } from '../lib/format';
import Layout from '../components/Layout';
import Spinner from '../components/Spinner';
import SeatGrid from '../components/SeatGrid';
import StatusLegend from '../components/StatusLegend';

export default function EventDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { isBooker } = useAuth();
  const toast = useToast();

  const [event, setEvent] = useState(null);
  const [eventError, setEventError] = useState('');
  // queueEnabled 이벤트 진입 게이트 (A안, ADR-013 개정): 입장 토큰이 있어야 좌석 그리드를 마운트한다.
  const [admitted, setAdmitted] = useState(false);
  const [checking, setChecking] = useState(true);

  const queueGated = Boolean(event?.queueEnabled);
  // 폴링은 비-큐 이벤트이거나, 큐 이벤트에서 입장(admitted)한 뒤에만 돈다. 미입장 상태에서
  // 헛된 GET seats(게이트가 429로 거절)를 애초에 안 쏜다.
  const pollEnabled = Boolean(event) && (!queueGated || admitted);
  const { seats, loading: seatsLoading, refetch } = useSeatPolling(id, pollEnabled);

  const [selectedSeatId, setSelectedSeatId] = useState(null);
  const [busy, setBusy] = useState(false);

  // queueEnabled 이벤트: 좌석 페이지를 닫거나 새로고침하면 입장 토큰을 회수한다 (ADR-015).
  // 재진입하려면 재대기 — 새로고침도 이탈로 취급하는 의도된 동작.
  useQueueLeaveBeacon(id, queueGated && isBooker);

  useEffect(() => {
    getEventDetail(id)
      .then((res) => setEvent(res.data))
      .catch((err) => setEventError(toMessage(err, '이벤트 정보를 불러오지 못했습니다.')));
  }, [id]);

  // 진입 게이트: queueEnabled 이벤트는 입장 토큰을 먼저 확인한다.
  // 토큰 없는 booker는 대기실로 리다이렉트(좌석 그리드/폴링을 마운트하지 않음).
  useEffect(() => {
    if (!event) return;
    if (!event.queueEnabled) {
      setChecking(false);
      return; // 비-큐 이벤트: 게이트 없음
    }
    if (!isBooker) {
      setChecking(false);
      return; // 비-booker: 좌석 조회/예약 불가 — 그리드 대신 안내 문구 (리다이렉트 없음)
    }
    let cancelled = false;
    // getQueueStatus는 비변경 조회(enqueue는 대기실이 담당). 토큰 보유 시에만 입장 처리.
    getQueueStatus(id)
      .then((res) => {
        if (cancelled) return;
        if (res.data.admitted) {
          setAdmitted(true);
          setChecking(false);
        } else {
          navigate(`/queue/${id}/waiting`, { replace: true });
        }
      })
      .catch(() => {
        if (!cancelled) navigate(`/queue/${id}/waiting`, { replace: true });
      });
    return () => {
      cancelled = true;
    };
  }, [event, isBooker, id, navigate]);

  const selectedSeat = seats.find((s) => s.seatId === selectedSeatId);

  const handleSeatClick = (seat) => {
    // 만료된 HELD도 effectiveStatus가 AVAILABLE이면 선택 가능.
    if (effectiveStatus(seat) === 'AVAILABLE') {
      setSelectedSeatId((prev) => (prev === seat.seatId ? null : seat.seatId));
    }
  };

  // 홀드 성공 → 결제 페이지로 이동 (좌석/이벤트/heldUntil을 state로 전달).
  const handleHold = async () => {
    if (!selectedSeat) return;
    setBusy(true);
    try {
      const res = await holdSeat(selectedSeat.seatId);
      navigate(`/events/${id}/seats/${selectedSeat.seatId}/checkout`, {
        state: { seat: selectedSeat, event, heldUntil: res.data.heldUntil },
      });
    } catch (err) {
      // 입장 토큰 만료 등으로 자격을 잃으면 재대기 (게이트 429).
      if (err?.response?.data?.code === 'QUEUE_ADMISSION_REQUIRED') {
        navigate(`/queue/${id}/waiting`, { replace: true });
        return;
      }
      toast.error(toMessage(err, '홀드에 실패했습니다.'));
      setSelectedSeatId(null);
      refetch();
    } finally {
      setBusy(false);
    }
  };

  if (eventError) {
    return (
      <Layout>
        <p className="rounded-xl bg-danger-soft px-5 py-4 text-sm text-danger-soft-fg">{eventError}</p>
        <Link to="/" className="mt-4 inline-block text-sm text-primary hover:text-primary-hover">
          ← 목록으로
        </Link>
      </Layout>
    );
  }

  return (
    <Layout>
      <Link
        to="/"
        className="mb-4 inline-flex items-center text-sm text-fg-muted hover:text-fg"
      >
        ← 목록으로
      </Link>

      {!event ? (
        <Spinner className="py-20" />
      ) : (
        <>
          <div className="mb-6">
            <div className="flex flex-wrap items-center gap-3">
              <h1 className="text-2xl font-bold tracking-tight text-fg">{event.title}</h1>
              {event.queueEnabled && (
                <span className="rounded-full bg-warning-soft px-2.5 py-0.5 text-xs font-medium text-warning-soft-fg">
                  대기열 이벤트
                </span>
              )}
            </div>
            <div className="mt-2 flex flex-wrap gap-x-5 gap-y-1 text-sm text-fg-muted">
              <span className="flex items-center gap-1.5">
                <span aria-hidden>📍</span>
                {event.venue}
              </span>
              <span className="flex items-center gap-1.5">
                <span aria-hidden>🕐</span>
                {formatDateTime(event.eventAt)}
              </span>
            </div>
          </div>

          <div className="rounded-2xl border border-border bg-surface p-5 shadow-sm sm:p-6">
            <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
              <h2 className="text-base font-semibold text-fg">좌석 선택</h2>
              <StatusLegend />
            </div>

            {!isBooker && !queueGated && (
              <p className="mb-4 rounded-lg bg-surface-muted px-4 py-2.5 text-sm text-fg-muted">
                좌석 홀드·예약은 BOOKER 계정만 가능합니다. 좌석 현황은 실시간으로 갱신됩니다.
              </p>
            )}

            {queueGated && !isBooker ? (
              // 대기열 이벤트의 라이브 좌석 그리드는 입장 토큰 뒤에 있고, 토큰은 BOOKER만 받는다 (A안).
              <p className="rounded-lg bg-warning-soft px-4 py-3 text-sm text-warning-soft-fg">
                대기열 이벤트입니다. 좌석 조회·예약은 대기열에 입장한 BOOKER 계정만 가능합니다.
              </p>
            ) : checking ? (
              <Spinner className="py-16" />
            ) : seatsLoading ? (
              <Spinner className="py-16" />
            ) : seats.length === 0 ? (
              <div className="py-16 text-center text-sm text-fg-subtle">좌석이 없습니다.</div>
            ) : (
              <SeatGrid
                seats={seats}
                myHeldSeatId={null}
                selectedSeatId={selectedSeatId}
                disabled={!isBooker || busy}
                onSeatClick={handleSeatClick}
              />
            )}
          </div>
        </>
      )}

      {/* 액션바: 좌석을 선택하면 홀드→결제 진행 버튼 노출 */}
      {isBooker && selectedSeat && (
        <div className="sticky bottom-4 mt-6">
          <div className="mx-auto flex max-w-2xl flex-wrap items-center justify-between gap-3 rounded-xl border border-border bg-surface px-5 py-4 shadow-lg">
            <div className="text-sm">
              <span className="font-semibold text-fg">{selectedSeat.label}</span>
              <span className="text-fg-muted"> · {formatPrice(selectedSeat.price)} 선택됨</span>
            </div>
            {/* 입장(admitted)한 뒤에만 그리드가 뜨므로, 큐/비-큐 모두 hold로 통일 (A안). */}
            <button
              onClick={handleHold}
              disabled={busy}
              className="rounded-lg bg-primary px-5 py-2.5 text-sm font-semibold text-white transition hover:bg-primary-hover disabled:opacity-60"
            >
              {busy ? '처리 중…' : '홀드하고 결제하기'}
            </button>
          </div>
        </div>
      )}
    </Layout>
  );
}
