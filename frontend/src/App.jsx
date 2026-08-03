import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { ThemeProvider } from './context/ThemeContext';
import { AuthProvider } from './context/AuthContext';
import { ToastProvider } from './context/ToastContext';
import ProtectedRoute from './components/ProtectedRoute';
import SignupPage from './pages/SignupPage';
import LoginPage from './pages/LoginPage';
import EventListPage from './pages/EventListPage';
import EventDetailPage from './pages/EventDetailPage';
import EventCreatePage from './pages/EventCreatePage';
import CheckoutPage from './pages/CheckoutPage';
import MyPage from './pages/MyPage';
import SettlementPage from './pages/SettlementPage';
import QueueWaitingPage from './pages/QueueWaitingPage';
import PointChargePage from './pages/PointChargePage';
import PaymentSuccessPage from './pages/PaymentSuccessPage';
import PaymentFailPage from './pages/PaymentFailPage';

export default function App() {
  return (
    <ThemeProvider>
      <AuthProvider>
      <ToastProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/" element={<EventListPage />} />
            <Route path="/signup" element={<SignupPage />} />
            <Route path="/login" element={<LoginPage />} />
            <Route
              path="/events/create"
              element={
                <ProtectedRoute requiredRole="PROVIDER">
                  <EventCreatePage />
                </ProtectedRoute>
              }
            />
            <Route
              path="/events/:id"
              element={
                <ProtectedRoute>
                  <EventDetailPage />
                </ProtectedRoute>
              }
            />
            <Route
              path="/queue/:id/waiting"
              element={
                <ProtectedRoute requiredRole="BOOKER">
                  <QueueWaitingPage />
                </ProtectedRoute>
              }
            />
            <Route
              path="/events/:id/seats/:seatId/checkout"
              element={
                <ProtectedRoute>
                  <CheckoutPage />
                </ProtectedRoute>
              }
            />
            <Route
              path="/me"
              element={
                <ProtectedRoute requiredRole="BOOKER">
                  <MyPage />
                </ProtectedRoute>
              }
            />
            <Route
              path="/points/charge"
              element={
                <ProtectedRoute requiredRole="BOOKER">
                  <PointChargePage />
                </ProtectedRoute>
              }
            />
            {/* 토스 결제창 콜백 (successUrl/failUrl). OAuth redirect_uri 콜백과 같은 자리. */}
            <Route
              path="/payments/success"
              element={
                <ProtectedRoute requiredRole="BOOKER">
                  <PaymentSuccessPage />
                </ProtectedRoute>
              }
            />
            <Route
              path="/payments/fail"
              element={
                <ProtectedRoute requiredRole="BOOKER">
                  <PaymentFailPage />
                </ProtectedRoute>
              }
            />
            <Route
              path="/provider/settlement"
              element={
                <ProtectedRoute requiredRole="PROVIDER">
                  <SettlementPage />
                </ProtectedRoute>
              }
            />
            <Route path="*" element={<Navigate to="/" />} />
          </Routes>
        </BrowserRouter>
      </ToastProvider>
      </AuthProvider>
    </ThemeProvider>
  );
}
