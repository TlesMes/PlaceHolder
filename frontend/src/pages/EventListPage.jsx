import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { getEvents } from '../api/events';

export default function EventListPage() {
  const navigate = useNavigate();
  const [events, setEvents] = useState([]);
  const [error, setError] = useState('');
  const isLoggedIn = !!localStorage.getItem('accessToken');

  useEffect(() => {
    getEvents()
      .then((res) => setEvents(res.data.events))
      .catch(() => setError('이벤트 목록을 불러오지 못했습니다.'));
  }, []);

  const handleLogout = () => {
    localStorage.removeItem('accessToken');
    navigate('/login');
  };

  return (
    <div style={styles.page}>
      <header style={styles.header}>
        <h1 style={styles.logo}>PlaceHolder</h1>
        <div style={styles.nav}>
          {isLoggedIn ? (
            <button style={styles.navBtn} onClick={handleLogout}>로그아웃</button>
          ) : (
            <>
              <Link to="/login" style={styles.navLink}>로그인</Link>
              <Link to="/signup" style={styles.navLink}>회원가입</Link>
            </>
          )}
        </div>
      </header>

      <main style={styles.main}>
        <h2 style={styles.sectionTitle}>이벤트 목록</h2>
        {error && <p style={styles.error}>{error}</p>}
        {events.length === 0 && !error && <p style={styles.empty}>등록된 이벤트가 없습니다.</p>}
        <div style={styles.grid}>
          {events.map((event) => (
            <Link to={`/events/${event.eventId}`} key={event.eventId} style={styles.card}>
              <div style={styles.cardTitle}>{event.title}</div>
              <div style={styles.cardMeta}>{event.venue}</div>
              <div style={styles.cardMeta}>{new Date(event.eventAt).toLocaleString('ko-KR')}</div>
            </Link>
          ))}
        </div>
      </main>
    </div>
  );
}

const styles = {
  page: { minHeight: '100vh', background: '#f9fafb' },
  header: { display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '1rem 2rem', background: '#fff', borderBottom: '1px solid #e5e7eb' },
  logo: { margin: 0, fontSize: '1.3rem', color: '#2563eb' },
  nav: { display: 'flex', gap: '1rem', alignItems: 'center' },
  navLink: { color: '#2563eb', textDecoration: 'none', fontSize: '0.9rem' },
  navBtn: { background: 'none', border: '1px solid #ccc', borderRadius: '4px', padding: '0.3rem 0.8rem', cursor: 'pointer', fontSize: '0.9rem' },
  main: { maxWidth: '900px', margin: '2rem auto', padding: '0 1rem' },
  sectionTitle: { marginBottom: '1.5rem' },
  grid: { display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))', gap: '1rem' },
  card: { display: 'block', background: '#fff', border: '1px solid #e5e7eb', borderRadius: '8px', padding: '1.2rem', textDecoration: 'none', color: 'inherit', transition: 'box-shadow 0.2s' },
  cardTitle: { fontWeight: '600', fontSize: '1.05rem', marginBottom: '0.5rem' },
  cardMeta: { fontSize: '0.85rem', color: '#6b7280', marginTop: '0.2rem' },
  error: { color: '#dc2626' },
  empty: { color: '#9ca3af' },
};
