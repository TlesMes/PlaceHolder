import { useState } from 'react';
import { Link } from 'react-router-dom';
import { formatDateTime, formatPoint } from '../lib/format';
import Layout from '../components/Layout';

// === 모의 데이터 (백엔드 응답 DTO 형태와 1:1) ===
// 실제 API 연결은 다음 단계. 형태는 MyReservationsResponse / PointHistoryResponse 그대로.
const MOCK_RESERVATIONS = [
  {
    reservationId: 1001,
    eventId: 1,
    eventTitle: '재즈 나이트 라이브',
    eventVenue: '블루노트 서울',
    eventAt: '2026-07-12T20:00:00',
    seatId: 42,
    seatLabel: 'A-12',
    paidAmount: 45000,
    confirmedAt: '2026-06-09T14:32:10',
  },
  {
    reservationId: 1002,
    eventId: 2,
    eventTitle: '인디 록 페스티벌',
    eventVenue: '올림픽홀',
    eventAt: '2026-08-03T18:30:00',
    seatId: 88,
    seatLabel: 'C-07',
    paidAmount: 60000,
    confirmedAt: '2026-06-05T09:11:42',
  },
];

const MOCK_POINT_HISTORY = {
  items: [
    {
      transactionId: 5001,
      type: 'CHARGE',
      amount: 100000,
      reservationId: null,
      eventTitle: null,
      createdAt: '2026-06-04T10:00:00',
    },
    {
      transactionId: 5002,
      type: 'DEDUCT',
      amount: -45000,
      reservationId: 1001,
      eventTitle: '재즈 나이트 라이브',
      createdAt: '2026-06-09T14:32:10',
    },
    {
      transactionId: 5003,
      type: 'DEDUCT',
      amount: -60000,
      reservationId: 1002,
      eventTitle: '인디 록 페스티벌',
      createdAt: '2026-06-05T09:11:42',
    },
  ],
  // 다음 cursor 페이지가 있다는 시안. 실제 호출은 다음 단계.
  nextCursor: '2026-06-05T09:11:42',
};

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
  CHARGE: { label: '충전', text: 'text-success', badge: 'bg-success-soft text-success-soft-fg' },
  DEDUCT: { label: '사용', text: 'text-danger', badge: 'bg-danger-soft text-danger-soft-fg' },
  SETTLE: { label: '정산', text: 'text-primary', badge: 'bg-primary-soft text-primary-soft-fg' },
};

const TABS = [
  { key: 'reservations', label: '예약 내역' },
  { key: 'points', label: '포인트 이력' },
];

export default function MyPage() {
  const [tab, setTab] = useState('reservations');
  const [period, setPeriod] = useState(3);
  const [typeFilter, setTypeFilter] = useState('ALL');

  // 모의 클라이언트 필터 (시안용). 실제 서버 필터는 다음 단계.
  const visibleItems =
    typeFilter === 'ALL'
      ? MOCK_POINT_HISTORY.items
      : MOCK_POINT_HISTORY.items.filter((it) => it.type === typeFilter);

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

      {tab === 'reservations' ? (
        <ReservationsTab reservations={MOCK_RESERVATIONS} />
      ) : (
        <PointsTab
          items={visibleItems}
          hasMore={MOCK_POINT_HISTORY.nextCursor != null}
          period={period}
          onPeriodChange={setPeriod}
          typeFilter={typeFilter}
          onTypeFilterChange={setTypeFilter}
        />
      )}
    </Layout>
  );
}

function ReservationsTab({ reservations }) {
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

function PointsTab({
  items,
  hasMore,
  period,
  onPeriodChange,
  typeFilter,
  onTypeFilterChange,
}) {
  return (
    <div>
      {/* 필터 바: 기간 프리셋 + 거래 타입 (시안 — 동작은 클라이언트 모의) */}
      <div className="mb-5 flex flex-wrap items-center gap-3">
        <div className="flex gap-1 rounded-lg border border-border p-1">
          {PERIOD_PRESETS.map((p) => (
            <button
              key={p.value}
              onClick={() => onPeriodChange(p.value)}
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
              onClick={() => onTypeFilterChange(f.value)}
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

      {items.length === 0 ? (
        <div className="rounded-xl border border-dashed border-border py-20 text-center text-fg-subtle">
          포인트 이력이 없습니다.
        </div>
      ) : (
        <>
          <div className="divide-y divide-border overflow-hidden rounded-xl border border-border bg-surface shadow-sm">
            {items.map((it) => {
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
                    {it.amount > 0 ? '+' : ''}
                    {formatPoint(it.amount)}
                  </span>
                </div>
              );
            })}
          </div>

          {/* 무한 스크롤 UX 시안. 실제 cursor 호출은 다음 단계. */}
          <div className="mt-5 text-center">
            {hasMore ? (
              <button
                disabled
                title="다음 단계에서 API와 연결됩니다"
                className="cursor-not-allowed rounded-lg border border-border px-4 py-2 text-sm font-medium text-fg-subtle"
              >
                더 보기
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
