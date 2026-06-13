import { useEffect, useState } from 'react';
import { getMySettlement } from '../api/providers';
import { toMessage } from '../lib/errors';
import { formatDateTime, formatPoint } from '../lib/format';
import Layout from '../components/Layout';
import Spinner from '../components/Spinner';

export default function SettlementPage() {
  const [settlementBalance, setSettlementBalance] = useState(0);
  const [settlements, setSettlements] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    let alive = true;
    getMySettlement()
      .then((res) => {
        if (!alive) return;
        setSettlementBalance(res.data.settlementBalance);
        setSettlements(res.data.settlements);
      })
      .catch((err) => alive && setError(toMessage(err, '정산 정보를 불러오지 못했습니다.')))
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
  }, []);

  if (loading) {
    return (
      <Layout>
        <Spinner className="py-20" />
      </Layout>
    );
  }

  if (error) {
    return (
      <Layout>
        <p className="rounded-xl bg-danger-soft px-5 py-4 text-sm text-danger-soft-fg">{error}</p>
      </Layout>
    );
  }

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
                    {/* SettlementItem DTO에 eventId가 없어 이벤트 상세로 연결 불가 → 텍스트 표기 */}
                    <span className="font-medium text-fg">{s.eventTitle}</span>
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
