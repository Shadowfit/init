import { useState, type FormEvent } from 'react';
import { useAuth } from '../auth';
import { Icon } from '../ui';

export default function Login() {
  const { login } = useAuth();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(email.trim(), password);
    } catch (err: any) {
      setError(err?.status === 401 ? '이메일 또는 비밀번호가 맞지 않아요.' : err?.message ?? '로그인하지 못했어요.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="auth-page">
      <section className="auth-art">
        <div className="brand" style={{ padding: 0 }}>
          <div className="brand-mark">{Icon.mark()}</div>
          <div>
            <div className="brand-name">ShadowFit</div>
            <div className="brand-sub">Admin Console</div>
          </div>
        </div>
        <div>
          <h1>안전한 반복을 <em>기록하는</em><br />운동 페이스메이커,<br />그 뒤편의 운영실.</h1>
          <p>회원과 세션, 종목·기준 영상, 모임과 피드 사진을 한 곳에서 관리합니다.</p>
        </div>
        <svg viewBox="0 0 400 90" style={{ width: '100%', maxWidth: 420 }} aria-hidden="true">
          {Array.from({ length: 24 }, (_, i) => {
            const h = 18 + Math.abs(Math.sin(i * 0.9)) * 60;
            return <rect key={i} x={i * 16.5} y={90 - h} width="10" height={h} rx="3" fill={i % 6 === 4 ? '#CAFF00' : '#2c3125'} />;
          })}
        </svg>
      </section>
      <section className="auth-panel">
        <form className="auth-card" onSubmit={submit}>
          <div>
            <h2>관리자 로그인</h2>
            <p className="cell-sub" style={{ margin: '6px 0 0' }}>관리자 권한이 있는 ShadowFit 계정으로 로그인합니다.</p>
          </div>
          {error && <div className="alert alert-danger">{error}</div>}
          <label className="field">
            <span>이메일</span>
            <input id="login-email" className="input" type="email" autoComplete="username" value={email}
              onChange={(e) => setEmail(e.target.value)} required placeholder="admin@shadowfit.kr" />
          </label>
          <label className="field">
            <span>비밀번호</span>
            <input id="login-password" className="input" type="password" autoComplete="current-password" value={password}
              onChange={(e) => setPassword(e.target.value)} required />
          </label>
          <button className="btn btn-primary" style={{ height: 42 }} disabled={busy}>{busy ? '확인 중…' : '로그인'}</button>
        </form>
      </section>
    </div>
  );
}
