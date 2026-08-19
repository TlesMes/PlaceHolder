import { useEffect, useState } from 'react';
import { getPointBalance } from '../api/points';
import { formatPoint } from '../lib/format';
import { SPEND_ORDER, BUCKET_LABELS } from '../lib/pointAllocation';

/**
 * 보유 포인트 — 합산을 주로 보여주고, 펼치면 재원 계층으로 분해한다 (ADR-020).
 *
 * 계층을 소모 순서대로(위→아래) 배치하는 것이 곧 설명이다 — "위에서부터 쓰인다".
 * 접힌 상태에서는 이 규칙이 보이지 않으므로, 체크아웃의 소모 미리보기가 그 공백을 메운다.
 */
export default function PointBalanceCard() {
  const [balance, setBalance] = useState(null);
  const [expanded, setExpanded] = useState(false);
  const [error, setError] = useState(false);

  useEffect(() => {
    let alive = true;
    getPointBalance()
      .then((res) => alive && setBalance(res.data))
      .catch(() => alive && setError(true));
    return () => {
      alive = false;
    };
  }, []);

  if (error) return null;

  return (
    <div className="mb-6 rounded-xl border border-border bg-surface">
      <button
        type="button"
        onClick={() => setExpanded((v) => !v)}
        aria-expanded={expanded}
        className="flex w-full items-center justify-between px-5 py-4 text-left"
      >
        <span className="text-sm font-medium text-fg-muted">보유 포인트</span>
        <span className="flex items-center gap-2">
          <span className="text-xl font-bold tabular-nums text-fg">
            {balance ? formatPoint(balance.total) : '—'}
          </span>
          <span className="text-xs text-fg-subtle" aria-hidden="true">
            {expanded ? '▲' : '▼'}
          </span>
        </span>
      </button>

      {expanded && balance && (
        <div className="border-t border-border px-5 py-3">
          <ul className="space-y-2">
            {SPEND_ORDER.map((bucket) => (
              <li key={bucket} className="flex items-center justify-between text-sm">
                <span className="text-fg-muted">{BUCKET_LABELS[bucket]}</span>
                <span className="tabular-nums text-fg">{formatPoint(balance[bucket])}</span>
              </li>
            ))}
          </ul>
          <p className="mt-3 text-xs text-fg-subtle">
            위에서부터 순서대로 사용됩니다. 환불 가능 금액은 유료 캐시{' '}
            <span className="tabular-nums">{formatPoint(balance.refundable)}</span>입니다.
          </p>
        </div>
      )}
    </div>
  );
}
