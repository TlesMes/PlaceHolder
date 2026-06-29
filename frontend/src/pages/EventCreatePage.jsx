import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { createEvent } from '../api/events';
import { toMessage } from '../lib/errors';
import Layout from '../components/Layout';

export default function EventCreatePage() {
  const navigate = useNavigate();
  const [form, setForm] = useState({
    title: '',
    venue: '',
    eventAt: '',
    queueEnabled: false,
  });
  const [seats, setSeats] = useState([{ label: '', price: '' }]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const setField = (field, value) => setForm((f) => ({ ...f, [field]: value }));

  const addSeat = () => setSeats((s) => [...s, { label: '', price: '' }]);
  const removeSeat = (i) => setSeats((s) => s.filter((_, idx) => idx !== i));
  const setSeatField = (i, field, value) =>
    setSeats((s) => s.map((seat, idx) => (idx === i ? { ...seat, [field]: value } : seat)));

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
      const res = await createEvent({
        title: form.title,
        venue: form.venue,
        eventAt: form.eventAt,
        queueEnabled: form.queueEnabled,
        seats: seats.map((s) => ({ label: s.label, price: Number(s.price) })),
      });
      navigate(`/events/${res.data.eventId}`);
    } catch (err) {
      setError(toMessage(err, '이벤트 생성에 실패했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  const inputClass =
    'w-full rounded-lg border border-border bg-base px-3 py-2 text-sm text-fg placeholder:text-fg-subtle focus:outline-none focus:ring-2 focus:ring-primary/50';

  return (
    <Layout>
      <div className="mx-auto max-w-2xl">
        <h1 className="mb-6 text-2xl font-bold text-fg">이벤트 등록</h1>

        {error && (
          <p className="mb-4 rounded-xl bg-danger-soft px-5 py-4 text-sm text-danger-soft-fg">
            {error}
          </p>
        )}

        <form onSubmit={handleSubmit} className="space-y-6">
          {/* 기본 정보 */}
          <div className="space-y-4 rounded-2xl border border-border bg-surface p-6 shadow-sm">
            <h2 className="text-base font-semibold text-fg">기본 정보</h2>

            <div>
              <label className="mb-1.5 block text-sm font-medium text-fg">이벤트 제목</label>
              <input
                type="text"
                required
                value={form.title}
                onChange={(e) => setField('title', e.target.value)}
                placeholder="예: 2026 여름 콘서트"
                className={inputClass}
              />
            </div>

            <div>
              <label className="mb-1.5 block text-sm font-medium text-fg">장소</label>
              <input
                type="text"
                required
                value={form.venue}
                onChange={(e) => setField('venue', e.target.value)}
                placeholder="예: 올림픽공원 체조경기장"
                className={inputClass}
              />
            </div>

            <div>
              <label className="mb-1.5 block text-sm font-medium text-fg">이벤트 일시</label>
              <input
                type="datetime-local"
                required
                value={form.eventAt}
                onChange={(e) => setField('eventAt', e.target.value)}
                className={inputClass}
              />
            </div>

            {/* queueEnabled 토글 */}
            <div className="flex items-center justify-between rounded-lg border border-border bg-surface-muted px-4 py-3">
              <div>
                <p className="text-sm font-medium text-fg">대기열 활성화</p>
                <p className="text-xs text-fg-muted">
                  동시 트래픽이 많은 인기 이벤트에 사용합니다.
                </p>
              </div>
              <button
                type="button"
                role="switch"
                aria-checked={form.queueEnabled}
                onClick={() => setField('queueEnabled', !form.queueEnabled)}
                className={`relative h-6 w-11 flex-shrink-0 rounded-full transition-colors ${
                  form.queueEnabled ? 'bg-primary' : 'bg-border'
                }`}
              >
                <span
                  className={`absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform ${
                    form.queueEnabled ? 'translate-x-5' : 'translate-x-0.5'
                  }`}
                />
              </button>
            </div>
          </div>

          {/* 좌석 목록 */}
          <div className="rounded-2xl border border-border bg-surface p-6 shadow-sm">
            <div className="mb-4 flex items-center justify-between">
              <h2 className="text-base font-semibold text-fg">좌석 목록</h2>
              <button
                type="button"
                onClick={addSeat}
                className="rounded-lg border border-border px-3 py-1.5 text-sm text-fg-muted transition hover:bg-surface-muted"
              >
                + 좌석 추가
              </button>
            </div>

            <div className="space-y-3">
              {seats.map((seat, i) => (
                <div key={i} className="flex items-center gap-3">
                  <input
                    type="text"
                    required
                    value={seat.label}
                    onChange={(e) => setSeatField(i, 'label', e.target.value)}
                    placeholder={`좌석 ${i + 1} 라벨 (예: A-${i + 1})`}
                    className="flex-1 rounded-lg border border-border bg-base px-3 py-2 text-sm text-fg placeholder:text-fg-subtle focus:outline-none focus:ring-2 focus:ring-primary/50"
                  />
                  <input
                    type="number"
                    min="0"
                    required
                    value={seat.price}
                    onChange={(e) => setSeatField(i, 'price', e.target.value)}
                    placeholder="가격"
                    className="w-28 rounded-lg border border-border bg-base px-3 py-2 text-sm text-fg placeholder:text-fg-subtle focus:outline-none focus:ring-2 focus:ring-primary/50"
                  />
                  {seats.length > 1 && (
                    <button
                      type="button"
                      onClick={() => removeSeat(i)}
                      className="text-sm text-danger-soft-fg transition hover:text-danger"
                    >
                      ✕
                    </button>
                  )}
                </div>
              ))}
            </div>
          </div>

          <button
            type="submit"
            disabled={busy}
            className="w-full rounded-xl bg-primary px-5 py-3 font-semibold text-white transition hover:bg-primary-hover disabled:opacity-60"
          >
            {busy ? '등록 중…' : '이벤트 등록'}
          </button>
        </form>
      </div>
    </Layout>
  );
}
