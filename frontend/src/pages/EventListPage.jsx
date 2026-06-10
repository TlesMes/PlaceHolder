import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getEvents } from '../api/events';
import { useAuth } from '../context/AuthContext';
import { toMessage } from '../lib/errors';
import { formatDateTime } from '../lib/format';
import Layout from '../components/Layout';
import Spinner from '../components/Spinner';

export default function EventListPage() {
  const { isAuthenticated } = useAuth();
  const [events, setEvents] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    getEvents()
      .then((res) => setEvents(res.data.events))
      .catch((err) => setError(toMessage(err, '이벤트 목록을 불러오지 못했습니다.')))
      .finally(() => setLoading(false));
  }, []);

  return (
    <Layout>
      {!isAuthenticated && (
        <div className="mb-6 flex items-center justify-between rounded-xl border border-primary-soft bg-primary-soft px-5 py-4">
          <p className="text-sm text-primary-soft-fg">
            좌석을 예약하려면 로그인이 필요합니다.
          </p>
          <Link
            to="/login"
            className="rounded-lg bg-primary px-3 py-1.5 text-sm font-medium text-white transition hover:bg-primary-hover"
          >
            로그인
          </Link>
        </div>
      )}

      <div className="mb-6">
        <h1 className="text-2xl font-bold tracking-tight text-fg">이벤트</h1>
        <p className="mt-1 text-sm text-fg-muted">예약 가능한 이벤트를 둘러보세요.</p>
      </div>

      {loading ? (
        <Spinner className="py-20" />
      ) : error ? (
        <p className="rounded-xl bg-danger-soft px-5 py-4 text-sm text-danger-soft-fg">{error}</p>
      ) : events.length === 0 ? (
        <div className="rounded-xl border border-dashed border-border py-20 text-center text-fg-subtle">
          등록된 이벤트가 없습니다.
        </div>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {events.map((event) => (
            <Link
              key={event.eventId}
              to={`/events/${event.eventId}`}
              className="group rounded-xl border border-border bg-surface p-5 shadow-sm transition hover:-translate-y-0.5 hover:border-primary/40 hover:shadow-md"
            >
              <h2 className="text-base font-semibold text-fg group-hover:text-primary">
                {event.title}
              </h2>
              <dl className="mt-3 space-y-1.5 text-sm text-fg-muted">
                <div className="flex items-center gap-2">
                  <span aria-hidden>📍</span>
                  <span>{event.venue}</span>
                </div>
                <div className="flex items-center gap-2">
                  <span aria-hidden>🕐</span>
                  <span>{formatDateTime(event.eventAt)}</span>
                </div>
              </dl>
            </Link>
          ))}
        </div>
      )}
    </Layout>
  );
}
