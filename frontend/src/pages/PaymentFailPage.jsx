import { Link, useSearchParams } from 'react-router-dom';
import Layout from '../components/Layout';

/**
 * 토스 결제창 실패·취소 콜백 (failUrl). 토스가 `?code=&message=&orderId=`로 리다이렉트한다.
 * 승인이 일어나지 않았으므로 주문은 READY로 남고 적립도 없다 — 서버 호출 없이 안내만 한다.
 */
export default function PaymentFailPage() {
  const [params] = useSearchParams();
  const code = params.get('code');
  const message = params.get('message');

  const isCancel = code === 'USER_CANCEL' || code === 'PAY_PROCESS_CANCELED';

  return (
    <Layout>
      <div className="mx-auto max-w-md rounded-2xl border border-border bg-surface p-6 text-center shadow-sm">
        <h1 className="mb-2 text-xl font-bold text-fg">
          {isCancel ? '결제를 취소했습니다' : '결제 실패'}
        </h1>
        <p className="rounded-lg bg-danger-soft px-4 py-3 text-sm text-danger-soft-fg">
          {message ?? '결제가 완료되지 않았습니다.'}
        </p>
        <p className="mt-3 text-xs text-fg-subtle">
          포인트는 적립되지 않았으며 요금도 청구되지 않습니다.
          {code && <span className="ml-1">(코드: {code})</span>}
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
