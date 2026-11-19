import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import { api } from '../api';
import { day, dt } from '../format';
import type { Category, DepthVerdict, ExerciseDetail, ExerciseListItem, Page, ReferenceSummary } from '../types';
import { Badge, Confirm, Drawer, ErrorBox, Modal, PageHead, Pager, Spinner, useLoad, useToast } from '../ui';
import { mannequinExerciseOf } from '../mannequinReference';

// 3D 장면(three.js + 인체 모델 base64, 약 1.4MB)은 버튼을 눌렀을 때만 내려받는다
const MannequinReferenceModal = lazy(() => import('../components/MannequinReferenceModal'));

type Sort = 'CREATED_AT' | 'NAME';

export default function Exercises() {
  const toast = useToast();
  const [keyword, setKeyword] = useState('');
  const [q, setQ] = useState({ keyword: '', categoryId: '', sort: 'NAME' as Sort, asc: true, page: 0 });
  const [openId, setOpenId] = useState<number | null>(null);
  const [creating, setCreating] = useState(false);
  const set = (patch: Partial<typeof q>) => setQ((p) => ({ ...p, page: 0, ...patch }));

  const categories = useLoad(() => api<Category[]>('/admin/categories'), []);
  const { data, error, loading, reload } = useLoad(
    () => api<Page<ExerciseListItem>>('/admin/exercises', { query: { ...q, size: 20 } }),
    [JSON.stringify(q)],
  );

  return (
    <>
      <PageHead title="운동 종목" sub="종목 정보 · 싱크로율 판정 기준 · 기준 동작 · 분석 지원 여부를 관리합니다. 분석은 AI 분석기가 있는 종목만 켤 수 있어요."
        actions={<button className="btn btn-lime" onClick={() => setCreating(true)}>+ 새 종목</button>} />
      <div className="card">
        <form className="filters" onSubmit={(e) => { e.preventDefault(); set({ keyword: keyword.trim() }); }}>
          <label className="field"><span>검색</span>
            <input id="exercise-keyword" className="input search" value={keyword} onChange={(e) => setKeyword(e.target.value)} placeholder="종목 이름" />
          </label>
          <label className="field"><span>카테고리</span>
            <select id="exercise-category" className="select" value={q.categoryId} onChange={(e) => set({ categoryId: e.target.value })}>
              <option value="">전체</option>
              {categories.data?.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          </label>
          <label className="field"><span>정렬</span>
            <select id="exercise-sort" className="select" value={`${q.sort}:${q.asc}`} onChange={(e) => { const [s, a] = e.target.value.split(':'); set({ sort: s as Sort, asc: a === 'true' }); }}>
              <option value="NAME:true">이름순</option>
              <option value="CREATED_AT:false">최근 등록순</option>
            </select>
          </label>
          <button className="btn btn-primary">검색</button>
        </form>
        {error ? <ErrorBox message={error} /> : loading && !data ? <Spinner /> : data && (
          <>
            <div className="table-wrap">
              <table className="data">
                <thead><tr><th>종목</th><th>코드</th><th>카테고리</th><th>AI 분석</th><th className="num">예상 시간</th><th>등록일</th></tr></thead>
                <tbody>
                  {data.content.length === 0 && <tr><td colSpan={6} className="empty">종목이 없어요.</td></tr>}
                  {data.content.map((x) => (
                    <tr key={x.id} className="clickable" onClick={() => setOpenId(x.id)}>
                      <td className="cell-main">{x.name}</td>
                      <td className="mono">{x.code ?? '—'}</td>
                      <td>{x.categoryName}</td>
                      <td>{x.analysisSupported ? <Badge tone="b-ok">지원</Badge> : <Badge tone="b-neutral">준비 중</Badge>}</td>
                      <td className="num">{x.expectedDurationMinutes ? `${x.expectedDurationMinutes}분` : '—'}</td>
                      <td className="date">{day(x.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pager page={data.page} totalPages={data.totalPages} total={data.totalElements} onPage={(p) => setQ((s) => ({ ...s, page: p }))} />
          </>
        )}
      </div>
      {creating && (
        <ExerciseForm categories={categories.data ?? []} onClose={() => setCreating(false)}
          onSaved={(d) => { setCreating(false); reload(); setOpenId(d.id); toast('종목을 만들었어요.'); }} />
      )}
      {openId != null && <ExerciseDrawer id={openId} categories={categories.data ?? []} onClose={() => setOpenId(null)} onChanged={reload} />}
    </>
  );
}

type FormState = { name: string; code: string; categoryId: string; description: string; preferredUrl: string; targetJoints: string; expectedDurationMinutes: string };

function ExerciseForm({ initial, categories, onClose, onSaved }: {
  initial?: ExerciseDetail; categories: Category[]; onClose: () => void; onSaved: (d: ExerciseDetail) => void;
}) {
  const [f, setF] = useState<FormState>({
    name: initial?.name ?? '', code: initial?.code ?? '', categoryId: String(initial?.categoryId ?? categories[0]?.id ?? ''),
    description: initial?.description ?? '', preferredUrl: initial?.preferredUrl ?? '', targetJoints: initial?.targetJoints ?? '',
    expectedDurationMinutes: String(initial?.expectedDurationMinutes ?? 15),
  });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const up = (k: keyof FormState) => (e: { target: { value: string } }) => setF((s) => ({ ...s, [k]: e.target.value }));

  const save = async () => {
    setBusy(true);
    setError(null);
    const body = {
      name: f.name.trim(), code: f.code.trim() || null, categoryId: Number(f.categoryId),
      description: f.description.trim() || null, preferredUrl: f.preferredUrl.trim() || null,
      targetJoints: f.targetJoints.trim() || null, expectedDurationMinutes: f.expectedDurationMinutes ? Number(f.expectedDurationMinutes) : null,
    };
    try {
      const d = initial
        ? await api<ExerciseDetail>(`/admin/exercises/${initial.id}`, { method: 'PATCH', body })
        : await api<ExerciseDetail>('/admin/exercises', { method: 'POST', body });
      onSaved(d);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal title={initial ? '종목 정보 수정' : '새 종목'} onClose={onClose} footer={
      <><button className="btn btn-outline" onClick={onClose}>취소</button>
        <button className="btn btn-primary" disabled={busy || !f.name.trim() || !f.categoryId} onClick={save}>{initial ? '저장' : '만들기'}</button></>
    }>
      {error && <div className="alert alert-danger">{error}</div>}
      <div className="form-grid">
        <label className="field"><span>종목 이름 *</span><input id="ex-name" className="input" maxLength={100} value={f.name} onChange={up('name')} placeholder="예: 스쿼트" /></label>
        <label className="field"><span>코드</span><input id="ex-code" className="input mono" value={f.code} onChange={(e) => setF((s) => ({ ...s, code: e.target.value.toUpperCase() }))} placeholder="SQUAT" /></label>
        <label className="field"><span>카테고리 *</span>
          <select id="ex-category" className="select" value={f.categoryId} onChange={up('categoryId')}>
            {categories.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </label>
        <label className="field"><span>예상 운동 시간 (분)</span><input id="ex-duration" className="input" type="number" min={1} value={f.expectedDurationMinutes} onChange={up('expectedDurationMinutes')} /></label>
        <label className="field full"><span>설명</span><textarea id="ex-desc" className="textarea" value={f.description} onChange={up('description')} /></label>
        <label className="field full"><span>참고 영상 URL</span><input id="ex-url" className="input" maxLength={500} value={f.preferredUrl} onChange={up('preferredUrl')} placeholder="https://youtu.be/…" /></label>
        <label className="field full"><span>주요 관절</span><input id="ex-joints" className="input" value={f.targetJoints} onChange={up('targetJoints')} placeholder="hip, knee, ankle" /></label>
      </div>
      <div className="cell-sub">코드는 대문자·숫자·밑줄 2~32자, 영문으로 시작해요.</div>
    </Modal>
  );
}

/** 기준 깊이 판정 → 배지. 경계는 백엔드 AdminReferenceSummaryDto 와 같다(최저 무릎 60° 미만 깐깐 · 95° 초과 후함) */
const VERDICT: Record<DepthVerdict, { label: string; tone: string; note: string }> = {
  OK: { label: '적정', tone: 'b-ok', note: '깊이가 적당해서 후하지도 깐깐하지도 않아요.' },
  STRICT: { label: '깐깐함', tone: 'b-warn', note: '기준이 아주 깊어서, 평행까지만 앉는 정상 자세도 감점돼요.' },
  LENIENT: { label: '후함', tone: 'b-danger', note: '기준이 얕아서, 덜 앉은 자세도 높은 점수를 받아요. 더 깊이 앉은 영상이나 3D 시범으로 바꾸세요.' },
};

const ADMIN_DEFAULT_GOOD = 80;
const ADMIN_DEFAULT_WARN = 60;

function ExerciseDrawer({ id, categories, onClose, onChanged }: { id: number; categories: Category[]; onClose: () => void; onChanged: () => void }) {
  const toast = useToast();
  const { data: x, error, setData } = useLoad(() => api<ExerciseDetail>(`/admin/exercises/${id}`), [id]);
  const [editing, setEditing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [th, setTh] = useState<{ good: string; warn: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [mannequinOpen, setMannequinOpen] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);
  // 기준 좌표 요약 — 상세와 별도 조회 (출처·프레임·깊이 판정·마지막 등록 상태)
  const ref = useLoad(() => api<ReferenceSummary>(`/admin/exercises/${id}/reference`), [id]);
  const extracting = ref.data?.extractionStatus === 'EXTRACTING';
  const refReload = ref.reload;

  // 업로드 영상은 AI 가 백그라운드로 읽는다 — 끝날 때까지 3초마다 다시 본다(서버가 10분 넘으면 FAILED 로 보고)
  useEffect(() => {
    if (!extracting) return;
    const t = setInterval(refReload, 3000);
    return () => clearInterval(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [extracting]);

  // EXTRACTING → READY/FAILED 로 바뀌는 순간 한 번 알린다
  const prevStatus = useRef(ref.data?.extractionStatus);
  useEffect(() => {
    const now = ref.data?.extractionStatus;
    if (prevStatus.current === 'EXTRACTING' && now === 'READY') { toast('업로드 영상에서 기준 동작을 뽑았어요.'); onChanged(); }
    if (prevStatus.current === 'EXTRACTING' && now === 'FAILED') toast(ref.data?.extractionError ?? '기준 동작을 뽑지 못했어요.', true);
    prevStatus.current = now;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ref.data?.extractionStatus]);

  if (error) return <Drawer title="종목" onClose={onClose}><div className="alert alert-danger">{error}</div></Drawer>;
  if (!x) return <Drawer title="종목" onClose={onClose}><Spinner /></Drawer>;

  const thresholds = th ?? { good: String(x.syncGoodThreshold ?? ADMIN_DEFAULT_GOOD), warn: String(x.syncWarnThreshold ?? ADMIN_DEFAULT_WARN) };
  const goodN = Number(thresholds.good), warnN = Number(thresholds.warn);
  const thError = thresholds.good === '' || thresholds.warn === '' || !Number.isFinite(goodN) || !Number.isFinite(warnN)
    ? '두 값을 모두 입력하세요.'
    : goodN > 100 || warnN <= 0 ? '0 초과 100 이하로 입력하세요.'
    : warnN >= goodN ? '위험 기준은 정석 기준보다 낮아야 해요.'
    : null;

  const run = async (fn: () => Promise<void>, ok: string) => {
    setBusy(true);
    try { await fn(); toast(ok); onChanged(); } catch (e: any) { toast(e.message, true); } finally { setBusy(false); }
  };

  const toggleAnalysis = () => run(async () => {
    setData(await api<ExerciseDetail>(`/admin/exercises/${id}/analysis-support`, { method: 'PATCH', body: { supported: !x.analysisSupported } }));
  }, x.analysisSupported ? 'AI 분석을 껐어요.' : 'AI 분석을 켰어요.');

  const saveThresholds = () => run(async () => {
    await api(`/admin/exercises/${id}/thresholds`, { method: 'PATCH', body: { good: goodN, warn: warnN } });
    setData(await api<ExerciseDetail>(`/admin/exercises/${id}`));
    setTh(null);
  }, '판정 기준을 저장했어요. 다음 운동부터 적용돼요.');

  const upload = (file: File) => run(async () => {
    const form = new FormData();
    form.append('file', file);
    setData(await api<ExerciseDetail>(`/admin/exercises/${id}/reference-video`, { method: 'POST', form }));
    ref.reload();
  }, '영상을 올렸어요. AI 가 기준 동작을 뽑는 중이에요(보통 1분 안쪽).');

  const mannequin = mannequinExerciseOf(x.code);
  const r = ref.data;
  const hasRef = !!r && r.frameCount > 0;
  const refLabel = !hasRef
    ? '등록된 기준이 없어요.'
    : `${r.source === 'MANNEQUIN_3D' ? '3D 시범 마네킹의 관절' : '업로드 영상에서 AI 가 추출'} · ${r.frameCount}프레임${r.durationSec != null ? ` · ${r.durationSec.toFixed(1)}초` : ''}`;
  const verdict = hasRef && r.depthVerdict ? VERDICT[r.depthVerdict] : null;

  return (
    <Drawer title={x.name} sub={<span className="mono">{x.code ?? '코드 없음'} · {x.categoryName}</span>} onClose={onClose}>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <button className="btn btn-outline btn-sm" onClick={() => setEditing(true)}>정보 수정</button>
        <button className="btn btn-danger btn-sm" onClick={() => setDeleting(true)}>삭제</button>
      </div>

      <div className="card card-pad" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12 }}>
        <div>
          <div className="cell-main">AI 분석 {x.analysisSupported ? '지원' : '꺼짐'}</div>
          <div className="cell-sub">켜려면 기준 좌표가 있어야 하고, AI 서버에 이 종목 분석기가 있어야 해요.</div>
        </div>
        <button className={`btn btn-sm ${x.analysisSupported ? 'btn-outline' : 'btn-lime'}`} disabled={busy} onClick={toggleAnalysis}>
          {x.analysisSupported ? '끄기' : '켜기'}
        </button>
      </div>

      <div>
        <h4 className="section-title">판정 기준 (싱크로율 %)</h4>
        <div className="form-grid">
          <label className="field"><span>정석 기준 — 이 값 이상이면 «자세 양호»</span>
            <input id="th-good" className="input num" type="number" min={1} max={100} step={0.5} value={thresholds.good}
              onChange={(e) => setTh({ ...thresholds, good: e.target.value })} />
          </label>
          <label className="field"><span>위험 기준 — 이 값 미만이면 «즉시 자세 수정»</span>
            <input id="th-warn" className="input num" type="number" min={1} max={100} step={0.5} value={thresholds.warn}
              onChange={(e) => setTh({ ...thresholds, warn: e.target.value })} />
          </label>
        </div>
        <div className="cell-sub" style={{ marginTop: 6 }}>
          사이 구간은 «자세 보정 필요»예요. 모든 사용자에게 같은 기준이 적용되고, 앱의 싱크로율 색·진동과 AI 회차 판정이 이 두 값을 써요.
          페르소나(초급자·고급자 등)는 피드백 말투만 바꿔요.
        </div>
        {th && <>
          {thError && <div className="alert alert-danger" style={{ marginTop: 10 }}>{thError}</div>}
          <div style={{ display: 'flex', gap: 8, marginTop: 10 }}>
            <button className="btn btn-primary btn-sm" disabled={busy || !!thError} onClick={saveThresholds}>판정 기준 저장</button>
            <button className="btn btn-outline btn-sm" onClick={() => setTh(null)}>되돌리기</button>
          </div>
        </>}
      </div>

      <div>
        <h4 className="section-title">기준 동작 (싱크로율의 잣대)</h4>
        <div className="card card-pad" style={{ display: 'grid', gap: 10 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
            <span className="cell-main">{ref.loading && !ref.data ? '불러오는 중…' : refLabel}</span>
            {verdict && <Badge tone={verdict.tone}>깊이 {verdict.label}</Badge>}
          </div>
          {hasRef && r.minKneeDeg != null && (
            <div className="cell-sub">
              최저 무릎 {r.minKneeDeg.toFixed(0)}°{r.minHipDeg != null && <> · 최저 고관절 {r.minHipDeg.toFixed(0)}°</>} (AI 가 재는 옆모습 각도)
              {verdict && <> — {verdict.note}</>}
            </div>
          )}

          {extracting && (
            <div className="alert alert-info" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span className="spinner" role="status" aria-label="추출 중" style={{ margin: 0, width: 14, height: 14, flex: 'none' }} />
              업로드한 영상에서 AI 가 기준 동작을 뽑는 중이에요. 끝나면 위 내용이 바뀌어요.
              {hasRef && ' 그동안은 이전 기준이 그대로 쓰여요.'}
            </div>
          )}
          {r?.extractionStatus === 'FAILED' && (
            <div className="alert alert-danger">
              마지막 영상 등록이 실패했어요{r.extractionAt ? ` (${dt(r.extractionAt)})` : ''}: {r.extractionError ?? '이유를 받지 못했어요.'}
              {hasRef && ' 기존 기준은 그대로예요.'}
            </div>
          )}

          <div className="cell-sub">
            운동 중 싱크로율은 이 기준 동작과 비교해 매겨요. <b>3D 시범</b>은 마네킹의 실제 관절 각도를 그대로 써서 가장 정확하고,
            앱의 3D 비교와 같은 잣대가 돼요. <b>영상</b>은 사람이 <b>옆에서</b> 찍은 반복 1회 이상(전신이 보이게, 10초 안팎)을 올리면
            AI 가 관절을 읽어 기준으로 써요 — 등록 뒤 깊이 판정이 «적정»인지 꼭 확인하세요.
            {x.referenceVideoPath && <> 마지막 업로드 영상: <span className="mono">{x.referenceVideoPath}</span></>}
          </div>
          <input ref={fileRef} type="file" accept="video/mp4" hidden onChange={(e) => { const f = e.target.files?.[0]; if (f) upload(f); e.target.value = ''; }} />
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            {mannequin && (
              <button className="btn btn-lime btn-sm" disabled={busy} onClick={() => setMannequinOpen(true)}>3D 시범을 기준으로 등록 (권장)</button>
            )}
            <button className="btn btn-outline btn-sm" disabled={busy || extracting} onClick={() => fileRef.current?.click()}>
              {busy ? '처리 중…' : extracting ? '추출 중…' : '영상(mp4) 올리기 · 최대 50MB'}
            </button>
          </div>
          {!mannequin && <div className="cell-sub">3D 시범은 스쿼트·런지 종목(코드 SQUAT·LUNGE)에만 있어요. 다른 종목은 영상을 올려 주세요.</div>}
        </div>
      </div>

      <div>
        <h4 className="section-title">정보</h4>
        <dl className="dl">
          <dt>설명</dt><dd>{x.description || '—'}</dd>
          <dt>참고 영상</dt><dd>{x.preferredUrl ? <a href={x.preferredUrl} target="_blank" rel="noreferrer">{x.preferredUrl}</a> : '—'}</dd>
          <dt>주요 관절</dt><dd>{x.targetJoints || '—'}</dd>
          <dt>예상 시간</dt><dd>{x.expectedDurationMinutes ? `${x.expectedDurationMinutes}분` : '—'}</dd>
        </dl>
      </div>

      {mannequinOpen && mannequin && (
        <Suspense fallback={<Modal title="3D 시범 불러오는 중" onClose={() => setMannequinOpen(false)}><Spinner /></Modal>}>
          <MannequinReferenceModal exerciseId={x.id} exerciseName={x.name} exercise={mannequin}
            onClose={() => setMannequinOpen(false)}
            onSaved={() => { setMannequinOpen(false); ref.reload(); onChanged(); toast('3D 시범을 기준으로 등록했어요.'); }} />
        </Suspense>
      )}
      {editing && <ExerciseForm initial={x} categories={categories} onClose={() => setEditing(false)}
        onSaved={(d) => { setData(d); setEditing(false); onChanged(); toast('저장했어요.'); }} />}
      {deleting && <Confirm title="종목 삭제" danger confirmLabel="삭제"
        message={<>«{x.name}» 을(를) 지울까요? 운동 기록이 있는 종목은 서버가 거부해요(기록 보존).</>}
        onClose={() => setDeleting(false)}
        onConfirm={async () => {
          try { await api(`/admin/exercises/${id}`, { method: 'DELETE' }); toast('삭제했어요.'); onChanged(); onClose(); }
          catch (e: any) { toast(e.message, true); }
        }} />}
    </Drawer>
  );
}
