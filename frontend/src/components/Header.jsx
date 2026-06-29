import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import ThemeToggle from './ThemeToggle';

export default function Header() {
  const { isAuthenticated, email, role, logout } = useAuth();
  const navLinkClass =
    'rounded-lg px-3 py-1.5 font-medium text-fg-muted transition hover:bg-surface-muted';
  const navigate = useNavigate();

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  return (
    <header className="sticky top-0 z-40 border-b border-border bg-surface/80 backdrop-blur">
      <div className="mx-auto flex h-16 max-w-5xl items-center justify-between px-4 sm:px-6">
        <Link to="/" className="flex items-center gap-2">
          <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary text-sm font-bold text-white">
            P
          </span>
          <span className="text-lg font-semibold tracking-tight text-fg">PlaceHolder</span>
        </Link>

        <nav className="flex items-center gap-3 text-sm">
          {isAuthenticated ? (
            <>
              {role === 'BOOKER' && (
                <Link to="/me" className={navLinkClass}>
                  마이페이지
                </Link>
              )}
              {role === 'PROVIDER' && (
                <>
                  <Link to="/events/create" className={navLinkClass}>
                    이벤트 등록
                  </Link>
                  <Link to="/provider/settlement" className={navLinkClass}>
                    정산
                  </Link>
                </>
              )}
              <span className="hidden items-center gap-2 text-fg-muted sm:flex">
                <span className="font-medium text-fg">{email}</span>
                <span className="rounded-full bg-primary-soft px-2 py-0.5 text-xs font-medium text-primary-soft-fg">
                  {role}
                </span>
              </span>
              <button
                onClick={handleLogout}
                className="rounded-lg border border-border px-3 py-1.5 font-medium text-fg-muted transition hover:bg-surface-muted"
              >
                로그아웃
              </button>
            </>
          ) : (
            <>
              <Link
                to="/login"
                className="rounded-lg px-3 py-1.5 font-medium text-fg-muted transition hover:bg-surface-muted"
              >
                로그인
              </Link>
              <Link
                to="/signup"
                className="rounded-lg bg-primary px-3 py-1.5 font-medium text-white transition hover:bg-primary-hover"
              >
                회원가입
              </Link>
            </>
          )}
          <ThemeToggle />
        </nav>
      </div>
    </header>
  );
}
