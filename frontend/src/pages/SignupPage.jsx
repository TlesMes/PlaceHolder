import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { signup } from '../api/auth';
import { useToast } from '../context/ToastContext';
import { toMessage } from '../lib/errors';
import AuthCard, { Field, SubmitButton } from '../components/AuthCard';

const ROLES = [
  { value: 'BOOKER', label: '예약자', desc: '좌석을 예약합니다' },
  { value: 'PROVIDER', label: '제공자', desc: '좌석을 등록합니다' },
];

export default function SignupPage() {
  const navigate = useNavigate();
  const toast = useToast();
  const [form, setForm] = useState({ email: '', password: '', role: 'BOOKER' });
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    setSubmitting(true);
    try {
      await signup(form);
      toast.success('회원가입이 완료되었습니다. 로그인하세요.');
      navigate('/login');
    } catch (err) {
      setError(toMessage(err, '회원가입에 실패했습니다.'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <AuthCard
      title="회원가입"
      subtitle="PlaceHolder 계정을 만드세요."
      footer={
        <>
          이미 계정이 있나요?{' '}
          <Link to="/login" className="font-medium text-primary hover:text-primary-hover">
            로그인
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

        <div>
          <span className="mb-1.5 block text-sm font-medium text-fg-muted">역할</span>
          <div className="grid grid-cols-2 gap-2">
            {ROLES.map((r) => {
              const active = form.role === r.value;
              return (
                <button
                  key={r.value}
                  type="button"
                  onClick={() => setForm({ ...form, role: r.value })}
                  className={`rounded-lg border px-3 py-2.5 text-left transition ${
                    active
                      ? 'border-primary bg-primary-soft ring-1 ring-primary/40'
                      : 'border-border hover:border-fg-subtle'
                  }`}
                >
                  <span
                    className={`block text-sm font-semibold ${
                      active ? 'text-primary-soft-fg' : 'text-fg'
                    }`}
                  >
                    {r.label}
                  </span>
                  <span className="block text-xs text-fg-muted">{r.desc}</span>
                </button>
              );
            })}
          </div>
        </div>

        {error && (
          <p className="rounded-lg bg-danger-soft px-3 py-2 text-sm text-danger-soft-fg">{error}</p>
        )}
        <SubmitButton disabled={submitting}>{submitting ? '가입 중…' : '가입하기'}</SubmitButton>
      </form>
    </AuthCard>
  );
}
