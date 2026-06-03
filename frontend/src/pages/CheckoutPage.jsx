import { useEffect, useState } from 'react';
import { useParams, useNavigate, useLocation, Link } from 'react-router-dom';
import { getSeats, confirmSeat } from '../api/seats';
import { getEventDetail } from '../api/events';
import { useToast } from '../context/ToastContext';
import { toMessage } from '../lib/errors';
import { formatPrice, formatPoint } from '../lib/format';
import Layout from '../components/Layout';
import Spinner from '../components/Spinner';
import Countdown from '../components/Countdown';

const PAY_METHODS = [
  { value: 'CARD', label: '신용·체크카드', icon: '💳' },
  { value: 'BANK', label: '무통장입금', icon: '🏦' },
  { value: 'EASY', label: '간편결제', icon: '📱' },
];

export default function CheckoutPage() {
  const { id, seatId } = useParams();
  const navigate = useNavigate();
  const location = useLocation();
  const toast = useToast();

  // 홀드 직후 이동이면 router state로 좌석/이벤트/heldUntil을 전달받는다.
  const passed = location.state ?? {};
  const [seat, setSeat] = useState(passed.seat ?? null);
  const [event, setEvent] = useState(passed.event ?? null);
  const [heldUntil] = useState(passed.heldUntil ?? null);
  const [loading, setLoading] = useState(!passed.seat || !passed.event);

  const [method, setMethod] = useState('CARD');
  const [bookerName, setBookerName] = useState('');
  const [bookerPhone, setBookerPhone] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [done, setDone] = useState(false);

  // 직접 URL 접근/새로고침 시: 좌석·이벤트 정보를 조회해 채운다.
  // (단, heldUntil은 홀드 응답에만 있으므로 새로고침하면 카운트다운 추적은 끊긴다 → 아래 경고.)
  useEffect(() => {
    if (passed.seat && passed.event) return;
    let alive = true;
    Promise.all([getSeats(id), getEventDetail(id)])
      .then(([seatsRes, eventRes]) => {
        if (!alive) return;
        const found = seatsRes.data.seats.find((s) => String(s.seatId) === String(seatId));
        setSeat(found ?? null);
        setEvent(eventRes.data);
      })
      .catch(() => alive && toast.error('결제 정보를 불러오지 못했습니다.'))
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
  }, [id, seatId, passed.seat, passed.event, toast]);

  // 페이지 이탈/새로고침 경고 (결제 완료 전에만).
  useEffect(() => {
    if (done) return;
    const handler = (e) => {
      e.preventDefault();
      e.returnValue = '';
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, [done]);

  const handlePay = async () => {
    if (!bookerName.trim()) {
      toast.error('예약자 이름을 입력하세요.');
      return;
    }
    setSubmitting(true);
    try {
      // UI상 결제수단/예약자 정보를 받지만, 실제 확정은 포인트 기반 confirm 호출이다.
      const res = await confirmSeat(seatId);
      setDone(true);
      toast.success(
        `예약 완료! ${formatPrice(res.data.paidAmount)} 결제 · 잔액 ${formatPoint(
          res.data.remainingBalance
        )}`,
        4000
      );
      navigate(`/events/${id}`, { replace: true });
    } catch (err) {
      toast.error(toMessage(err, '결제에 실패했습니다.'));
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) {
    return (
      <Layout>
        <Spinner className="py-20" />
      </Layout>
    );
  }

  if (!seat) {
    return (
      <Layout>
        <p className="rounded-xl bg-rose-50 px-5 py-4 text-sm text-rose-600">
          좌석 정보를 찾을 수 없습니다.
        </p>
        <Link to={`/events/${id}`} className="mt-4 inline-block text-sm text-indigo-600">
          ← 좌석 선택으로
        </Link>
      </Layout>
    );
  }

  return (
    <Layout>
      <Link
        to={`/events/${id}`}
        className="mb-4 inline-flex items-center text-sm text-slate-500 hover:text-slate-700"
      >
        ← 좌석 다시 선택
      </Link>

      <h1 className="mb-1 text-2xl font-bold tracking-tight text-slate-900">결제</h1>
      <p className="mb-6 text-sm text-slate-500">
        홀드된 좌석은 제한 시간 내에 결제해야 합니다.
      </p>

      <div className="grid gap-6 lg:grid-cols-[1fr_320px]">
        {/* 입력 영역 */}
        <div className="space-y-6">
          {/* 예약자 정보 */}
          <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm sm:p-6">
            <h2 className="mb-4 text-base font-semibold text-slate-900">예약자 정보</h2>
            <div className="space-y-4">
              <label className="block">
                <span className="mb-1.5 block text-sm font-medium text-slate-700">이름</span>
                <input
                  value={bookerName}
                  onChange={(e) => setBookerName(e.target.value)}
                  placeholder="홍길동"
                  className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm outline-none transition focus:border-indigo-500 focus:ring-2 focus:ring-indigo-100"
                />
              </label>
              <label className="block">
                <span className="mb-1.5 block text-sm font-medium text-slate-700">연락처</span>
                <input
                  value={bookerPhone}
                  onChange={(e) => setBookerPhone(e.target.value)}
                  placeholder="010-1234-5678"
                  className="w-full rounded-lg border border-slate-300 px-3 py-2 text-sm outline-none transition focus:border-indigo-500 focus:ring-2 focus:ring-indigo-100"
                />
              </label>
            </div>
          </section>

          {/* 결제수단 */}
          <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm sm:p-6">
            <h2 className="mb-4 text-base font-semibold text-slate-900">결제수단</h2>
            <div className="grid gap-2 sm:grid-cols-3">
              {PAY_METHODS.map((m) => {
                const active = method === m.value;
                return (
                  <button
                    key={m.value}
                    type="button"
                    onClick={() => setMethod(m.value)}
                    className={`flex flex-col items-center gap-1 rounded-xl border px-3 py-4 transition ${
                      active
                        ? 'border-indigo-500 bg-indigo-50 ring-1 ring-indigo-200'
                        : 'border-slate-200 hover:border-slate-300'
                    }`}
                  >
                    <span className="text-xl">{m.icon}</span>
                    <span
                      className={`text-sm font-medium ${
                        active ? 'text-indigo-700' : 'text-slate-700'
                      }`}
                    >
                      {m.label}
                    </span>
                  </button>
                );
              })}
            </div>
            <p className="mt-3 text-xs text-slate-400">
              * 데모 환경에서는 결제수단 선택과 무관하게 보유 포인트로 확정됩니다.
            </p>
          </section>
        </div>

        {/* 요약 영역 */}
        <aside className="lg:sticky lg:top-20 lg:self-start">
          <div className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
            <h2 className="mb-4 text-base font-semibold text-slate-900">주문 요약</h2>

            <dl className="space-y-2 text-sm">
              <div className="flex justify-between">
                <dt className="text-slate-500">이벤트</dt>
                <dd className="font-medium text-slate-900">{event?.title ?? '-'}</dd>
              </div>
              <div className="flex justify-between">
                <dt className="text-slate-500">좌석</dt>
                <dd className="font-medium text-slate-900">{seat.label}</dd>
              </div>
              <div className="flex justify-between border-t border-slate-100 pt-2">
                <dt className="text-slate-500">결제 금액</dt>
                <dd className="text-base font-bold text-indigo-600">{formatPrice(seat.price)}</dd>
              </div>
            </dl>

            {heldUntil ? (
              <div className="mt-4 flex items-center justify-between rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-700">
                <span>결제 제한 시간</span>
                <Countdown until={heldUntil} className="font-semibold tabular-nums" />
              </div>
            ) : (
              <p className="mt-4 rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-500">
                새로고침으로 홀드 추적이 끊겼습니다. 제한 시간은 좌석 홀드 시점부터 계산됩니다.
              </p>
            )}

            <button
              onClick={handlePay}
              disabled={submitting}
              className="mt-5 w-full rounded-lg bg-indigo-600 px-4 py-3 text-sm font-semibold text-white transition hover:bg-indigo-700 disabled:opacity-60"
            >
              {submitting ? '결제 처리 중…' : `${formatPrice(seat.price)} 결제하기`}
            </button>
          </div>
        </aside>
      </div>
    </Layout>
  );
}
