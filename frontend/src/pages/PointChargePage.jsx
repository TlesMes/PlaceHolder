import { useState } from 'react';
import { Link } from 'react-router-dom';
import { createPaymentOrder } from '../api/payments';
import { requestTossPayment } from '../lib/tossSdk';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import { toMessage } from '../lib/errors';
import { formatPrice } from '../lib/format';
import Layout from '../components/Layout';

const PRESETS = [5_000, 10_000, 30_000, 50_000];
const MIN_AMOUNT = 1_000;

export default function PointChargePage() {
  const { email } = useAuth();
  const toast = useToast();
  const [amount, setAmount] = useState(10_000);
  const [submitting, setSubmitting] = useState(false);
  const [keyMissing, setKeyMissing] = useState(false);

  const handleCharge = async () => {
    if (!Number.isInteger(amount) || amount < MIN_AMOUNT) {
      toast.error(`최소 ${formatPrice(MIN_AMOUNT)}부터 충전할 수 있습니다.`);
      return;
    }
    setSubmitting(true);
    setKeyMissing(false);
    try {
      // ① 주문 생성 — 서버가 orderId 발급 + 금액 확정 저장 (위변조 검증 기준)
      const { data } = await createPaymentOrder(amount);

      // ② 결제창 — 성공 시 브라우저가 successUrl로 리다이렉트된다 (OAuth redirect와 동형)
      await requestTossPayment({
        clientKey: data.clientKey,
        orderId: data.orderId,
        amount: data.amount,
        orderName: `포인트 ${data.amount.toLocaleString('ko-KR')}P 충전`,
        customerEmail: email,
      });
    } catch (err) {
      if (err?.message === 'NO_CLIENT_KEY') {
        setKeyMissing(true);
      } else if (err?.message === 'SDK_LOAD_FAILED') {
        toast.error('결제 모듈을 불러오지 못했습니다. 네트워크를 확인해주세요.');
      } else if (err?.code === 'USER_CANCEL') {
        // 사용자가 결제창을 닫음 — 주문은 READY로 남고 적립되지 않는다(정상 흐름)
      } else if (err?.response) {
        toast.error(toMessage(err, '주문 생성에 실패했습니다.'));
      } else {
        toast.error(err?.message ?? '결제를 시작하지 못했습니다.');
      }
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Layout>
      <Link to="/me" className="mb-4 inline-flex items-center text-sm text-fg-muted hover:text-fg">
        ← 마이페이지
      </Link>

      <h1 className="mb-1 text-2xl font-bold tracking-tight text-fg">포인트 충전</h1>
      <p className="mb-6 text-sm text-fg-muted">
        현금 결제 금액만큼 포인트가 적립됩니다 (1원 = 1P).
      </p>

      <div className="max-w-md space-y-6">
        <section className="rounded-2xl border border-border bg-surface p-5 shadow-sm sm:p-6">
          <h2 className="mb-4 text-base font-semibold text-fg">충전 금액</h2>

          <div className="mb-4 grid grid-cols-2 gap-2 sm:grid-cols-4">
            {PRESETS.map((v) => {
              const active = amount === v;
              return (
                <button
                  key={v}
                  type="button"
                  onClick={() => setAmount(v)}
                  className={`rounded-xl border px-3 py-3 text-sm font-medium transition ${
                    active
                      ? 'border-primary bg-primary-soft text-primary-soft-fg ring-1 ring-primary/40'
                      : 'border-border text-fg hover:border-fg-subtle'
                  }`}
                >
                  {v.toLocaleString('ko-KR')}
                </button>
              );
            })}
          </div>

          <label className="block">
            <span className="mb-1.5 block text-sm font-medium text-fg-muted">직접 입력 (원)</span>
            <input
              type="number"
              min={MIN_AMOUNT}
              step={1000}
              value={amount}
              onChange={(e) => setAmount(Number(e.target.value))}
              className="w-full rounded-lg border border-border bg-surface px-3 py-2 text-sm text-fg outline-none transition placeholder:text-fg-subtle focus:border-primary focus:ring-2 focus:ring-primary/20"
            />
          </label>

          <dl className="mt-4 flex justify-between border-t border-border pt-3 text-sm">
            <dt className="text-fg-muted">적립 예정 포인트</dt>
            <dd className="text-base font-bold text-primary">
              {Number.isFinite(amount) ? amount.toLocaleString('ko-KR') : 0}P
            </dd>
          </dl>

          <button
            onClick={handleCharge}
            disabled={submitting}
            className="mt-5 w-full rounded-lg bg-primary px-4 py-3 text-sm font-semibold text-white transition hover:bg-primary-hover disabled:opacity-60"
          >
            {submitting ? '결제창 여는 중…' : '결제하고 충전하기'}
          </button>
        </section>

        {keyMissing && (
          <div className="rounded-xl bg-warning-soft px-4 py-3 text-sm text-warning-soft-fg">
            <p className="font-medium">결제 키가 설정되지 않았습니다.</p>
            <p className="mt-1 text-xs">
              토스페이먼츠 테스트 키를 발급받아 서버 환경변수(<code>TOSS_CLIENT_KEY</code>,{' '}
              <code>TOSS_SECRET_KEY</code>)에 주입하면 결제창이 열립니다. 주문 생성까지는 정상
              동작합니다.
            </p>
          </div>
        )}
      </div>
    </Layout>
  );
}
