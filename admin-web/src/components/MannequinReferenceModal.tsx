import { useEffect, useMemo, useRef, useState } from 'react';
import { buildMannequinHtml } from '../../../frontend/components/exercise/mannequin/buildMannequinHtml';
import { api } from '../api';
import { Modal, Spinner } from '../ui';
import { cycleToFrames, summarizeCycle, type ReferenceCycle } from '../mannequinReference';
import type { ReferenceSummary } from '../types';

/**
 * «3D 시범을 기준으로 등록» — 앱과 같은 마네킹 장면을 iframe 으로 띄워 기준 동작 관절(ReferenceCycle)을 받고,
 * 확인을 누르면 PUT /admin/exercises/{id}/reference-poses 로 보낸다. 장면은 앱 번들과 같은 파일(frontend/…/mannequin)을 쓴다.
 */
export default function MannequinReferenceModal({ exerciseId, exerciseName, exercise, onClose, onSaved }: {
  exerciseId: number;
  exerciseName: string;
  exercise: 'squat' | 'lunge';
  onClose: () => void;
  onSaved: (summary: ReferenceSummary) => void;
}) {
  const html = useMemo(() => buildMannequinHtml(exercise, { compare: true }), [exercise]);
  const frame = useRef<HTMLIFrameElement>(null);
  const [cycles, setCycles] = useState<ReferenceCycle[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const handler = (e: MessageEvent) => {
      if (e.source !== frame.current?.contentWindow || typeof e.data !== 'string') return;
      try {
        const m = JSON.parse(e.data);
        if (m?.type === 'reference' && Array.isArray(m.cycles)) setCycles(m.cycles);
      } catch { /* 장면의 다른 메시지는 무시 */ }
    };
    window.addEventListener('message', handler);
    return () => window.removeEventListener('message', handler);
  }, []);

  // 런지는 오른발·왼발 두 사이클이 온다 — 기준은 한 반복이면 되므로 첫 사이클(오른발)을 쓴다
  const cycle = cycles?.[0] ?? null;
  const summary = cycle ? summarizeCycle(cycle) : null;

  const save = async () => {
    if (!cycle) return;
    setBusy(true);
    setError(null);
    try {
      const saved = await api<ReferenceSummary>(`/admin/exercises/${exerciseId}/reference-poses`, {
        method: 'PUT',
        body: { source: 'MANNEQUIN_3D', frames: cycleToFrames(cycle) },
      });
      onSaved(saved);
    } catch (e: any) {
      setError(e.message);
      setBusy(false);
    }
  };

  return (
    <Modal title={`${exerciseName} · 3D 시범을 기준으로 등록`} onClose={onClose} footer={
      <>
        <button className="btn btn-outline" onClick={onClose} disabled={busy}>취소</button>
        <button className="btn btn-primary" onClick={save} disabled={!cycle || busy}>{busy ? '등록 중…' : '이 시범을 기준으로 등록'}</button>
      </>
    }>
      <div style={{ display: 'grid', gap: 12 }}>
        <div style={{ height: 300, background: '#111', borderRadius: 10, overflow: 'hidden' }}>
          <iframe ref={frame} title="3D 시범" srcDoc={html} style={{ border: 0, width: '100%', height: '100%' }} />
        </div>
        {summary && cycle ? (
          <div className="card card-pad" style={{ display: 'grid', gap: 4 }}>
            <div className="cell-main">기준 동작 {cycle.samples.length}프레임 · {summary.seconds.toFixed(1)}초 (0.1초 간격)</div>
            <div className="cell-sub">최저 무릎 {Math.round(summary.minKnee)}° · 최저 고관절 {Math.round(summary.minHip)}° — AI 가 재는 옆모습 각도이고, 앱의 3D 비교 기준과 1° 안쪽으로 같아요.</div>
            {exercise === 'lunge' && (
              <div className="cell-sub">런지는 지금 횟수만 세는 종목이라 AI 가 이 기준으로 점수를 매기지 않아요. 등록은 되지만 싱크로율엔 영향이 없어요.</div>
            )}
          </div>
        ) : (
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}><Spinner /><span className="cell-sub">장면에서 기준 동작을 읽는 중…</span></div>
        )}
        <div className="cell-sub">
          영상(mp4)을 올리는 대신 마네킹의 실제 관절 각도를 그대로 기준으로 씁니다. 마네킹을 영상으로 찍어 다시 읽으면 투영 때문에 무릎이 20° 넘게 얕게 잡혀요.
        </div>
        {error && <div className="alert alert-danger">{error}</div>}
      </div>
    </Modal>
  );
}
