import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

// 로그인하지 않은 사용자를 로그인 페이지로 보낸다.
export default function ProtectedRoute({ children }) {
  const { isAuthenticated } = useAuth();
  const location = useLocation();

  if (!isAuthenticated) {
    return <Navigate to="/login" state={{ from: location.pathname }} replace />;
  }
  return children;
}
