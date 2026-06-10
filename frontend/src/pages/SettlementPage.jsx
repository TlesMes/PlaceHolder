import { Link } from 'react-router-dom';
import { formatDateTime, formatPoint } from '../lib/format';
import Layout from '../components/Layout';

// === 모의 데이터 (SettlementResponse 형태와 1:1) ===
// 실제 API 연결은 다음 단계. cursor 페이징은 사업자 API 강화 PR 머지 후 연결.
const MOCK_SETTLEMENT = {
  settlementBalance: 285000,
  settlements: [
    {
      transactionId: 7001,
      amount: 45000,
      reservationId: 1001,
      eventTitle: '재즈 나이트 라이브',
      seatLabel: 'A-12',
      confirmedAt: '2026-06-09T14:32:10',
    },
    {
      transactionId: 7002,
      amount: 60000,
      reservationId: 1002,
      eventTitle: '인디 록 페스티벌',
      seatLabel: 'C-07',
      confirmedAt: '2026-06-05T09:11:42',
    },
    {
      transactionId: 7003,
      amount: 180000,
      reservationId: 1003,
      eventTitle: '클래식 갈라 콘서트',
      seatLabel: 'VIP-03',
      confirmedAt: '2026-05-28T19:05:00',
    },
  ],
};

export default function SettlementPage() {
  const { settlementBalance, settlements } = MOCK_SETTLEMENT;

  return (
    <Layout>
      <div className="mb-6">
        <h1 className="text-2xl font-bold tracking-tight text-fg">정산 대시보드</h1>
        <p className="mt-1 text-sm text-fg-muted">
          확정된 예약으로 적립된 정산 예정액을 확인하세요.
        </p>
      </div>

      {/* 잔액 카드 */}
      <div className="mb-8 rounded-2xl border border-primary-soft bg-gradient-to-br from-primary-soft to-surface p-6 shadow-sm">
        <p className="text-sm font-medium text-primary-soft-fg">정산 예정 잔액</p>
        <p className="mt-2 text-4xl font-bold tracking-tight text-fg">
          {formatPoint(settlementBalance)}
        </p>
        <p className="mt-2 text-xs text-fg-subtle">
          현금 출금은 지원하지 않으며 적립 집계까지만 제공됩니다.
        </p>
      </div>

      {/* SETTLE 거래 테이블 */}
      <h2 className="mb-3 text-base font-semibold text-fg">정산 내역</h2>
      {settlements.length === 0 ? (
        <div className="rounded-xl border border-dashed border-border py-20 text-center text-fg-subtle">
          정산 내역이 없습니다.
        </div>
      ) : (
        <div className="overflow-hidden rounded-xl border border-border bg-surface shadow-sm">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border text-left text-xs font-medium text-fg-subtle">
                <th className="px-5 py-3">일시</th>
                <th className="px-5 py-3">이벤트</th>
                <th className="px-5 py-3">좌석</th>
                <th className="px-5 py-3 text-right">적립액</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {settlements.map((s) => (
                <tr key={s.transactionId} className="transition hover:bg-surface-muted">
                  <td className="px-5 py-4 text-fg-muted">{formatDateTime(s.confirmedAt)}</td>
                  <td className="px-5 py-4">
                    <Link
                      to={`/events/${s.reservationId}`}
                      className="font-medium text-fg transition hover:text-primary"
                    >
                      {s.eventTitle}
                    </Link>
                  </td>
                  <td className="px-5 py-4">
                    <span className="rounded-full bg-surface-muted px-2.5 py-1 text-xs font-medium text-fg-muted">
                      {s.seatLabel}
                    </span>
                  </td>
                  <td className="px-5 py-4 text-right font-semibold text-success">
                    +{formatPoint(s.amount)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Layout>
  );
}
