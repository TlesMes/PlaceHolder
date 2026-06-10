import { useEffect, useState } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import { getEventDetail } from '../api/events';
import { holdSeat } from '../api/seats';
import { useSeatPolling } from '../hooks/useSeatPolling';
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
  const { seats, loading: seatsLoading, refetch } = useSeatPolling(id);

  const [selectedSeatId, setSelectedSeatId] = useState(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    getEventDetail(id)
      .then((res) => setEvent(res.data))
      .catch((err) => setEventError(toMessage(err, '이벤트 정보를 불러오지 못했습니다.')));
  }, [id]);

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
            <h1 className="text-2xl font-bold tracking-tight text-fg">{event.title}</h1>
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

            {!isBooker && (
              <p className="mb-4 rounded-lg bg-surface-muted px-4 py-2.5 text-sm text-fg-muted">
                좌석 홀드·예약은 BOOKER 계정만 가능합니다. 좌석 현황은 실시간으로 갱신됩니다.
              </p>
            )}

            {seatsLoading ? (
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
