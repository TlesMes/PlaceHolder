import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getMyReservations } from '../api/reservations';
import { getPointHistory } from '../api/points';
import { toMessage } from '../lib/errors';
import { formatDateTime, formatPoint, monthsAgoLocalDateTime } from '../lib/format';
import Layout from '../components/Layout';
import Spinner from '../components/Spinner';

const PERIOD_PRESETS = [
  { value: 1, label: '1개월' },
  { value: 3, label: '3개월' },
  { value: 6, label: '6개월' },
];

const TYPE_FILTERS = [
  { value: 'ALL', label: '전체' },
  { value: 'CHARGE', label: '충전' },
  { value: 'DEDUCT', label: '사용' },
  { value: 'SETTLE', label: '정산' },
];

// 거래 타입별 색상/부호 표현
const TYPE_META = {
  CHARGE: { label: '충전', sign: '+', text: 'text-success', badge: 'bg-success-soft text-success-soft-fg' },
  DEDUCT: { label: '사용', sign: '-', text: 'text-danger', badge: 'bg-danger-soft text-danger-soft-fg' },
  SETTLE: { label: '정산', sign: '+', text: 'text-primary', badge: 'bg-primary-soft text-primary-soft-fg' },
};

const TABS = [
  { key: 'reservations', label: '예약 내역' },
  { key: 'points', label: '포인트 이력' },
];

export default function MyPage() {
  const [tab, setTab] = useState('reservations');

  return (
    <Layout>
      <div className="mb-6">
        <h1 className="text-2xl font-bold tracking-tight text-fg">마이페이지</h1>
        <p className="mt-1 text-sm text-fg-muted">예약 내역과 포인트 이력을 확인하세요.</p>
      </div>

      {/* 탭 바 */}
      <div className="mb-6 flex gap-1 border-b border-border">
        {TABS.map((t) => (
          <button
            key={t.key}
            onClick={() => setTab(t.key)}
            className={`-mb-px border-b-2 px-4 py-2.5 text-sm font-medium transition ${
              tab === t.key
                ? 'border-primary text-primary'
                : 'border-transparent text-fg-muted hover:text-fg'
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {tab === 'reservations' ? <ReservationsTab /> : <PointsTab />}
    </Layout>
  );
}

function ReservationsTab() {
  const [reservations, setReservations] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    let alive = true;
    getMyReservations()
      .then((res) => alive && setReservations(res.data.reservations))
      .catch((err) => alive && setError(toMessage(err, '예약 내역을 불러오지 못했습니다.')))
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
  }, []);

  if (loading) return <Spinner className="py-20" />;
  if (error)
    return (
      <p className="rounded-xl bg-danger-soft px-5 py-4 text-sm text-danger-soft-fg">{error}</p>
    );

  if (reservations.length === 0) {
    return (
      <div className="rounded-xl border border-dashed border-border py-20 text-center text-fg-subtle">
        예약 내역이 없습니다.
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {reservations.map((r) => (
        <div
          key={r.reservationId}
          className="rounded-xl border border-border bg-surface p-5 shadow-sm"
        >
          <div className="flex items-start justify-between gap-4">
            <div>
              <Link
                to={`/events/${r.eventId}`}
                className="text-base font-semibold text-fg transition hover:text-primary"
              >
                {r.eventTitle}
              </Link>
              <dl className="mt-2 space-y-1 text-sm text-fg-muted">
                <div className="flex items-center gap-2">
                  <span aria-hidden>📍</span>
                  <span>{r.eventVenue}</span>
                </div>
                <div className="flex items-center gap-2">
                  <span aria-hidden>🕐</span>
                  <span>{formatDateTime(r.eventAt)}</span>
                </div>
              </dl>
            </div>
            <span className="rounded-full bg-primary-soft px-2.5 py-1 text-xs font-medium text-primary-soft-fg">
              {r.seatLabel}
            </span>
          </div>
          <div className="mt-4 flex items-center justify-between border-t border-border pt-3 text-sm">
            <span className="text-fg-subtle">
              {formatDateTime(r.confirmedAt)} 결제 완료
            </span>
            <span className="font-semibold text-fg">{formatPoint(r.paidAmount)}</span>
          </div>
        </div>
      ))}
    </div>
  );
}

function PointsTab() {
  const [period, setPeriod] = useState(3);
  const [typeFilter, setTypeFilter] = useState('ALL');

  const [items, setItems] = useState([]);
  const [nextCursor, setNextCursor] = useState(null);
  const [loading, setLoading] = useState(true); // 첫 로드 / 기간 변경
  const [loadingMore, setLoadingMore] = useState(false); // "더 보기"
  const [error, setError] = useState('');

  // 기간(period)이 바뀌면 from을 새로 계산해 처음부터 다시 조회.
  // useCallback으로 묶어 "더 보기"는 같은 from·기존 cursor로 이어 부른다.
  const fetchPage = useCallback(
    async (cursor) => {
      const from = monthsAgoLocalDateTime(period);
      const res = await getPointHistory({ from, cursor });
      return res.data; // { items, nextCursor }
    },
    [period]
  );

  // 기간 변경(fetchPage 갱신) 시 목록을 처음부터 다시 조회.
  useEffect(() => {
    let alive = true;
    (async () => {
      setLoading(true);
      setError('');
      try {
        const data = await fetchPage(undefined);
        if (!alive) return;
        setItems(data.items);
        setNextCursor(data.nextCursor);
      } catch (err) {
        if (alive) setError(toMessage(err, '포인트 이력을 불러오지 못했습니다.'));
      } finally {
        if (alive) setLoading(false);
      }
    })();
    return () => {
      alive = false;
    };
  }, [fetchPage]);

  const handleLoadMore = async () => {
    if (!nextCursor || loadingMore) return;
    setLoadingMore(true);
    try {
      const data = await fetchPage(nextCursor);
      setItems((prev) => [...prev, ...data.items]);
      setNextCursor(data.nextCursor);
    } catch (err) {
      setError(toMessage(err, '추가 이력을 불러오지 못했습니다.'));
    } finally {
      setLoadingMore(false);
    }
  };

  // 타입 필터는 서버 미지원 → 로드된 목록을 클라이언트에서 거른다 (ADR-012: 사용자당 거래량 적음 전제).
  const visibleItems =
    typeFilter === 'ALL' ? items : items.filter((it) => it.type === typeFilter);

  return (
    <div>
      {/* 필터 바: 기간 프리셋(서버) + 거래 타입(클라이언트) */}
      <div className="mb-5 flex flex-wrap items-center gap-3">
        <div className="flex gap-1 rounded-lg border border-border p-1">
          {PERIOD_PRESETS.map((p) => (
            <button
              key={p.value}
              onClick={() => setPeriod(p.value)}
              className={`rounded-md px-3 py-1 text-xs font-medium transition ${
                period === p.value
                  ? 'bg-primary text-white'
                  : 'text-fg-muted hover:bg-surface-muted'
              }`}
            >
              {p.label}
            </button>
          ))}
        </div>
        <div className="flex flex-wrap gap-1.5">
          {TYPE_FILTERS.map((f) => (
            <button
              key={f.value}
              onClick={() => setTypeFilter(f.value)}
              className={`rounded-full border px-3 py-1 text-xs font-medium transition ${
                typeFilter === f.value
                  ? 'border-primary bg-primary-soft text-primary-soft-fg'
                  : 'border-border text-fg-muted hover:bg-surface-muted'
              }`}
            >
              {f.label}
            </button>
          ))}
        </div>
      </div>

      {loading ? (
        <Spinner className="py-20" />
      ) : error ? (
        <p className="rounded-xl bg-danger-soft px-5 py-4 text-sm text-danger-soft-fg">{error}</p>
      ) : (
        <>
          {visibleItems.length === 0 ? (
            <div className="rounded-xl border border-dashed border-border py-20 text-center text-fg-subtle">
              {typeFilter === 'ALL'
                ? '포인트 이력이 없습니다.'
                : '해당 유형의 이력이 없습니다. 더 보기로 이전 기록을 확인하세요.'}
            </div>
          ) : (
          <div className="divide-y divide-border overflow-hidden rounded-xl border border-border bg-surface shadow-sm">
            {visibleItems.map((it) => {
              const meta = TYPE_META[it.type] ?? TYPE_META.DEDUCT;
              return (
                <div key={it.transactionId} className="flex items-center justify-between px-5 py-4">
                  <div className="flex items-center gap-3">
                    <span
                      className={`rounded-full px-2.5 py-1 text-xs font-medium ${meta.badge}`}
                    >
                      {meta.label}
                    </span>
                    <div>
                      <p className="text-sm font-medium text-fg">
                        {it.eventTitle ?? '포인트 충전'}
                      </p>
                      <p className="text-xs text-fg-subtle">{formatDateTime(it.createdAt)}</p>
                    </div>
                  </div>
                  <span className={`text-sm font-semibold ${meta.text}`}>
                    {meta.sign}
                    {formatPoint(it.amount)}
                  </span>
                </div>
              );
            })}
          </div>
          )}

          {/* cursor 페이징: nextCursor가 있으면 다음 페이지를 이어 붙인다. */}
          <div className="mt-5 text-center">
            {nextCursor ? (
              <button
                onClick={handleLoadMore}
                disabled={loadingMore}
                className="rounded-lg border border-border px-4 py-2 text-sm font-medium text-fg-muted transition hover:bg-surface-muted disabled:opacity-60"
              >
                {loadingMore ? '불러오는 중…' : '더 보기'}
              </button>
            ) : (
              <p className="text-xs text-fg-subtle">모든 이력을 불러왔습니다.</p>
            )}
          </div>
        </>
      )}
    </div>
  );
}
