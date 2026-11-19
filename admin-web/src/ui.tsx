import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { syncColor } from './format';

/* ───────── 아이콘 (lucide 모양을 손으로 옮김 — 의존성 없이) ───────── */
const P = { fill: 'none', stroke: 'currentColor', strokeWidth: 2, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const };
export const Icon = {
  dashboard: () => (<svg viewBox="0 0 24 24" {...P}><rect x="3" y="3" width="7" height="9" rx="1.5" /><rect x="14" y="3" width="7" height="5" rx="1.5" /><rect x="14" y="12" width="7" height="9" rx="1.5" /><rect x="3" y="16" width="7" height="5" rx="1.5" /></svg>),
  users: () => (<svg viewBox="0 0 24 24" {...P}><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2" /><circle cx="9" cy="7" r="4" /><path d="M22 21v-2a4 4 0 0 0-3-3.87" /><path d="M16 3.13a4 4 0 0 1 0 7.75" /></svg>),
  activity: () => (<svg viewBox="0 0 24 24" {...P}><path d="M22 12h-4l-3 9L9 3l-3 9H2" /></svg>),
  dumbbell: () => (<svg viewBox="0 0 24 24" {...P}><path d="M6.5 6.5 17.5 17.5" /><path d="m21 21-1-1" /><path d="m3 3 1 1" /><path d="m18 22 4-4" /><path d="m2 6 4-4" /><path d="m3 10 7-7" /><path d="m14 21 7-7" /></svg>),
  tag: () => (<svg viewBox="0 0 24 24" {...P}><path d="M12.6 2.6A2 2 0 0 0 11.2 2H4a2 2 0 0 0-2 2v7.2a2 2 0 0 0 .6 1.4l8.7 8.7a2.4 2.4 0 0 0 3.4 0l6.6-6.6a2.4 2.4 0 0 0 0-3.4z" /><circle cx="7.5" cy="7.5" r="1" /></svg>),
  group: () => (<svg viewBox="0 0 24 24" {...P}><circle cx="12" cy="8" r="3.2" /><path d="M5.5 20a6.5 6.5 0 0 1 13 0" /><circle cx="4.5" cy="10" r="2" /><circle cx="19.5" cy="10" r="2" /></svg>),
  image: () => (<svg viewBox="0 0 24 24" {...P}><rect x="3" y="3" width="18" height="18" rx="2" /><circle cx="9" cy="9" r="2" /><path d="m21 15-3.1-3.1a2 2 0 0 0-2.8 0L6 21" /></svg>),
  mark: () => (<svg viewBox="0 0 32 32" width="18" height="18"><path d="M7 22l5.5-11 4.5 7.5 3.5-4.5 4.5 8" stroke="#CAFF00" strokeWidth="2.8" fill="none" strokeLinecap="round" strokeLinejoin="round" /></svg>),
};

/* ───────── 페이지 머리 ───────── */
export function PageHead({ title, sub, actions }: { title: string; sub?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="page-head">
      <div>
        <h1>{title}</h1>
        {sub && <p>{sub}</p>}
      </div>
      {actions && <div className="page-actions">{actions}</div>}
    </div>
  );
}

export function Badge({ tone, children, plain }: { tone: string; children: ReactNode; plain?: boolean }) {
  return <span className={`badge ${tone}${plain ? ' plain' : ''}`}>{children}</span>;
}

export function Sync({ value }: { value: number | null | undefined }) {
  if (value == null) return <span className="cell-sub">—</span>;
  const v = Number(value);
  return (
    <span className="sync num">
      <span className="sync-bar"><i style={{ width: `${Math.min(100, v)}%`, background: syncColor(v) }} /></span>
      {v.toFixed(1)}%
    </span>
  );
}

export const Spinner = () => <div className="spinner" role="status" aria-label="불러오는 중" />;

/* ───────── 페이지 번호 ───────── */
export function Pager({ page, totalPages, total, onPage }: { page: number; totalPages: number; total: number; onPage: (p: number) => void }) {
  const start = Math.max(0, Math.min(page - 2, totalPages - 5));
  const pages = Array.from({ length: Math.min(5, totalPages) }, (_, i) => start + i);
  return (
    <div className="pager">
      <span className="total num">총 {total.toLocaleString()}건</span>
      <div className="pages">
        <button onClick={() => onPage(page - 1)} disabled={page <= 0} aria-label="이전 페이지">‹</button>
        {pages.map((p) => (
          <button key={p} className={p === page ? 'on' : ''} onClick={() => onPage(p)}>{p + 1}</button>
        ))}
        <button onClick={() => onPage(page + 1)} disabled={page >= totalPages - 1} aria-label="다음 페이지">›</button>
      </div>
    </div>
  );
}

/* ───────── 모달 · 서랍 ───────── */
export function Modal({ title, children, footer, onClose }: { title: string; children: ReactNode; footer?: ReactNode; onClose: () => void }) {
  useEscape(onClose);
  return (
    <div className="overlay" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="card modal" role="dialog" aria-modal="true" aria-label={title}>
        <div className="modal-head">
          <h3>{title}</h3>
          <button className="x" onClick={onClose} aria-label="닫기">×</button>
        </div>
        <div className="modal-body">{children}</div>
        {footer && <div className="modal-foot">{footer}</div>}
      </div>
    </div>
  );
}

export function Drawer({ title, sub, children, onClose }: { title: ReactNode; sub?: ReactNode; children: ReactNode; onClose: () => void }) {
  useEscape(onClose);
  return (
    <>
      <div className="drawer-overlay" onClick={onClose} />
      <aside className="drawer" role="dialog" aria-modal="true">
        <div className="drawer-head">
          <div>
            <h3>{title}</h3>
            {sub && <div className="cell-sub">{sub}</div>}
          </div>
          <button className="x" onClick={onClose} aria-label="닫기">×</button>
        </div>
        <div className="drawer-body">{children}</div>
      </aside>
    </>
  );
}

function useEscape(fn: () => void) {
  const ref = useRef(fn);
  ref.current = fn;
  useEffect(() => {
    const h = (e: KeyboardEvent) => e.key === 'Escape' && ref.current();
    window.addEventListener('keydown', h);
    return () => window.removeEventListener('keydown', h);
  }, []);
}

/* 확인창 — 지우기처럼 되돌릴 수 없는 동작 앞에 */
export function Confirm({ title, message, confirmLabel = '확인', danger, onConfirm, onClose }: {
  title: string; message: ReactNode; confirmLabel?: string; danger?: boolean; onConfirm: () => Promise<void> | void; onClose: () => void;
}) {
  const [busy, setBusy] = useState(false);
  return (
    <Modal title={title} onClose={onClose} footer={
      <>
        <button className="btn btn-outline" onClick={onClose}>취소</button>
        <button className={`btn ${danger ? 'btn-danger' : 'btn-primary'}`} disabled={busy}
          onClick={async () => { setBusy(true); try { await onConfirm(); onClose(); } finally { setBusy(false); } }}>
          {confirmLabel}
        </button>
      </>
    }>
      <div>{message}</div>
    </Modal>
  );
}

/* ───────── 토스트 ───────── */
type Toast = { id: number; text: string; err?: boolean };
const ToastCtx = createContext<(text: string, err?: boolean) => void>(() => {});
export function ToastProvider({ children }: { children: ReactNode }) {
  const [list, setList] = useState<Toast[]>([]);
  const push = useCallback((text: string, err?: boolean) => {
    const id = Date.now() + Math.random();
    setList((l) => [...l, { id, text, err }]);
    setTimeout(() => setList((l) => l.filter((t) => t.id !== id)), 3200);
  }, []);
  return (
    <ToastCtx.Provider value={push}>
      {children}
      <div className="toasts" aria-live="polite">
        {list.map((t) => <div key={t.id} className={`toast${t.err ? ' err' : ''}`}>{t.text}</div>)}
      </div>
    </ToastCtx.Provider>
  );
}
export const useToast = () => useContext(ToastCtx);

/* ───────── 비동기 로드 ───────── */
export function useLoad<T>(fn: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [tick, setTick] = useState(0);
  useEffect(() => {
    let alive = true;
    setLoading(true);
    setError(null);
    fn()
      .then((d) => alive && setData(d))
      .catch((e) => alive && setError(e?.message ?? '불러오지 못했어요.'))
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);
  return { data, error, loading, reload: () => setTick((t) => t + 1), setData };
}

export function ErrorBox({ message }: { message: string }) {
  return <div className="alert alert-danger" style={{ margin: 16 }}>{message}</div>;
}
