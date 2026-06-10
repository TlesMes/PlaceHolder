import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

// 로그인하지 않은 사용자를 로그인 페이지로 보낸다.
// requiredRole 지정 시 역할이 다르면 홈으로 보낸다.
export default function ProtectedRoute({ children, requiredRole }) {
  const { isAuthenticated, role } = useAuth();
  const location = useLocation();

  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }
  if (requiredRole && role !== requiredRole) {
    return <Navigate to="/" replace />;
  }
  return children;
}
