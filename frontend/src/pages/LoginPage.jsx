import { useState } from 'react';
import { useNavigate, useLocation, Link } from 'react-router-dom';
import { login } from '../api/auth';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import { toMessage } from '../lib/errors';
import AuthCard, { Field, SubmitButton } from '../components/AuthCard';

export default function LoginPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { login: setAuth } = useAuth();
  const toast = useToast();
  const [form, setForm] = useState({ email: '', password: '' });
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    setSubmitting(true);
    try {
      const res = await login(form);
      setAuth(res.data.accessToken);
      toast.success('로그인되었습니다.');
      navigate(location.state?.from ?? '/');
    } catch (err) {
      setError(toMessage(err, '로그인에 실패했습니다.'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthCard
      title="로그인"
      subtitle="좌석을 예약하려면 로그인하세요."
      footer={
        <>
          계정이 없나요?{' '}
          <Link to="/signup" className="font-medium text-primary hover:text-primary-hover">
            회원가입
          </Link>
        </>
      }
    >
      <form onSubmit={handleSubmit} className="space-y-4">
        <Field
          label="이메일"
          type="email"
          value={form.email}
          onChange={(e) => setForm({ ...form, email: e.target.value })}
          placeholder="you@example.com"
          required
        />
        <Field
          label="비밀번호"
          type="password"
          value={form.password}
          onChange={(e) => setForm({ ...form, password: e.target.value })}
          placeholder="••••••••"
          required
        />
        {error && (
          <p className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger-soft-fg">{error}</p>
        )}
        <SubmitButton disabled={submitting}>{submitting ? '로그인 중…' : '로그인'}</SubmitButton>
      </form>
    </AuthCard>
  );
}
