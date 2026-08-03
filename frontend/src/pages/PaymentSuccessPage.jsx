import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { confirmPayment } from '../api/payments';
import { toMessage } from '../lib/errors';
import { formatPoint } from '../lib/format';
import Layout from '../components/Layout';
import Spinner from '../components/Spinner';

/**
 * 토스 결제창 성공 콜백 (successUrl). OAuth의 redirect_uri 콜백 핸들러와 같은 자리다.
 *
 * 토스가 `?paymentKey=&orderId=&amount=` 쿼리스트링으로 리다이렉트해주면, 그 값을 서버
 * confirm으로 넘겨 최종 승인·적립을 확정한다. 승인 권한은 서버 시크릿 키에만 있으므로
 * 프론트는 값 전달만 한다 (ADR-018).
 *
 * 새로고침하면 confirm이 다시 호출되지만 서버가 orderId 기준 멱등 처리하므로 중복 적립되지 않는다.
 */
export default function PaymentSuccessPage() {
  const [params] = useSearchParams();
  const paymentKey = params.get('paymentKey');
  const orderId = params.get('orderId');
  const amount = Number(params.get('amount'));

  const [state, setState] = useState('loading'); // loading | done | error
  const [result, setResult] = useState(null);
  const [message, setMessage] = useState('');
  const requested = useRef(false);

  useEffect(() => {
    if (requested.current) return; // StrictMode 이중 마운트 방지 (서버는 멱등이지만 호출은 1회로)
    requested.current = true;

    if (!paymentKey || !orderId || !Number.isFinite(amount)) {
      setState('error');
      setMessage('결제 정보가 올바르지 않습니다.');
      return;
    }

    confirmPayment({ orderId, paymentKey, amount })
      .then(({ data }) => {
        setResult(data);
        setState('done');
      })
      .catch((err) => {
        setMessage(toMessage(err, '결제 승인에 실패했습니다.'));
        setState('error');
      });
  }, [paymentKey, orderId, amount]);

  if (state === 'loading') {
    return (
      <Layout>
        <Spinner className="py-20" />
        <p className="text-center text-sm text-fg-muted">결제를 승인하고 있습니다…</p>
      </Layout>
    );
  }

  if (state === 'error') {
    return (
      <Layout>
        <div className="mx-auto max-w-md rounded-2xl border border-border bg-surface p-6 text-center shadow-sm">
          <h1 className="mb-2 text-xl font-bold text-fg">결제 승인 실패</h1>
          <p className="rounded-lg bg-danger-soft px-4 py-3 text-sm text-danger-soft-fg">
            {message}
          </p>
          <div className="mt-5 flex justify-center gap-2">
            <Link
              to="/points/charge"
              className="rounded-lg bg-primary px-4 py-2.5 text-sm font-semibold text-white transition hover:bg-primary-hover"
            >
              다시 시도
            </Link>
            <Link
              to="/me"
              className="rounded-lg border border-border px-4 py-2.5 text-sm font-medium text-fg-muted transition hover:bg-surface-muted"
            >
              마이페이지
            </Link>
          </div>
        </div>
      </Layout>
    );
  }

  return (
    <Layout>
      <div className="mx-auto max-w-md rounded-2xl border border-border bg-surface p-6 text-center shadow-sm">
        <h1 className="mb-1 text-xl font-bold text-fg">충전 완료</h1>
        <p className="mb-5 text-sm text-fg-muted">포인트가 적립되었습니다.</p>

        <dl className="space-y-2 text-sm">
          <div className="flex justify-between">
            <dt className="text-fg-muted">충전 금액</dt>
            <dd className="font-semibold text-success">+{formatPoint(result.chargedAmount)}</dd>
          </div>
          <div className="flex justify-between border-t border-border pt-2">
            <dt className="text-fg-muted">현재 잔액</dt>
            <dd className="text-base font-bold text-primary">{formatPoint(result.balance)}</dd>
          </div>
        </dl>

        <div className="mt-5 flex justify-center gap-2">
          <Link
            to="/"
            className="rounded-lg bg-primary px-4 py-2.5 text-sm font-semibold text-white transition hover:bg-primary-hover"
          >
            이벤트 둘러보기
          </Link>
          <Link
            to="/me"
            className="rounded-lg border border-border px-4 py-2.5 text-sm font-medium text-fg-muted transition hover:bg-surface-muted"
          >
            포인트 이력
          </Link>
        </div>
      </div>
    </Layout>
  );
}
