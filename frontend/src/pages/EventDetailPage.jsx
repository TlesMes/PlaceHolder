import { useEffect, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { getEventDetail } from '../api/events';

const SEAT_STATUS_STYLE = {
  AVAILABLE: { background: '#d1fae5', color: '#065f46', label: '예약 가능' },
  HELD:      { background: '#fef9c3', color: '#854d0e', label: '홀드 중' },
  CONFIRMED: { background: '#fee2e2', color: '#991b1b', label: '확정' },
};

export default function EventDetailPage() {
  const { id } = useParams();
  const [event, setEvent] = useState(null);
  const [error, setError] = useState('');

  useEffect(() => {
    getEventDetail(id)
      .then((res) => setEvent(res.data))
      .catch(() => setError('이벤트 정보를 불러오지 못했습니다.'));
  }, [id]);

  const handleSeatClick = (seat) => {
    if (seat.status !== 'AVAILABLE') return;
    // Phase C 연결 포인트: 좌석 홀드 API 호출 예정
    console.log('좌석 선택:', seat);
    alert(`[Phase C 준비] 좌석 ${seat.label} 선택됨 (홀드 미구현)`);
  };

  if (error) return <div style={{ padding: '2rem', color: '#dc2626' }}>{error}</div>;
  if (!event) return <div style={{ padding: '2rem', color: '#9ca3af' }}>불러오는 중...</div>;

  return (
    <div style={styles.page}>
      <header style={styles.header}>
        <Link to="/" style={styles.back}>← 목록으로</Link>
      </header>

      <main style={styles.main}>
        <h2 style={styles.title}>{event.title}</h2>
        <div style={styles.meta}>
          <span>📍 {event.venue}</span>
          <span>🕐 {new Date(event.eventAt).toLocaleString('ko-KR')}</span>
        </div>

        <h3 style={styles.sectionTitle}>좌석 현황</h3>
        <div style={styles.legend}>
          {Object.entries(SEAT_STATUS_STYLE).map(([status, s]) => (
            <span key={status} style={{ ...styles.legendItem, background: s.background, color: s.color }}>
              {s.label}
            </span>
          ))}
        </div>

        <div style={styles.seatGrid}>
          {event.seats?.map((seat) => {
            const s = SEAT_STATUS_STYLE[seat.status] ?? SEAT_STATUS_STYLE.AVAILABLE;
            return (
              <div
                key={seat.seatId}
                style={{
                  ...styles.seat,
                  background: s.background,
                  color: s.color,
                  cursor: seat.status === 'AVAILABLE' ? 'pointer' : 'default',
                  opacity: seat.status === 'CONFIRMED' ? 0.6 : 1,
                }}
                onClick={() => handleSeatClick(seat)}
                title={`${seat.label} — ${seat.price.toLocaleString()}원`}
              >
                <div style={styles.seatLabel}>{seat.label}</div>
                <div style={styles.seatPrice}>{seat.price.toLocaleString()}원</div>
              </div>
            );
          })}
        </div>
      </main>
    </div>
  );
}

const styles = {
  page: { minHeight: '100vh', background: '#f9fafb' },
  header: { padding: '1rem 2rem', background: '#fff', borderBottom: '1px solid #e5e7eb' },
  back: { color: '#2563eb', textDecoration: 'none', fontSize: '0.95rem' },
  main: { maxWidth: '900px', margin: '2rem auto', padding: '0 1rem' },
  title: { fontSize: '1.6rem', marginBottom: '0.5rem' },
  meta: { display: 'flex', gap: '1.5rem', color: '#6b7280', fontSize: '0.95rem', marginBottom: '2rem' },
  sectionTitle: { marginBottom: '0.75rem' },
  legend: { display: 'flex', gap: '0.5rem', marginBottom: '1rem', flexWrap: 'wrap' },
  legendItem: { padding: '0.2rem 0.7rem', borderRadius: '12px', fontSize: '0.8rem', fontWeight: '500' },
  seatGrid: { display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(100px, 1fr))', gap: '0.75rem' },
  seat: { padding: '0.75rem', borderRadius: '6px', textAlign: 'center', userSelect: 'none' },
  seatLabel: { fontWeight: '600', fontSize: '0.95rem' },
  seatPrice: { fontSize: '0.78rem', marginTop: '0.2rem' },
};
