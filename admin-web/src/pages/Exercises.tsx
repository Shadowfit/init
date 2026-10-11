import { useRef, useState } from 'react';
import { api } from '../api';
import { day } from '../format';
import type { Category, ExerciseDetail, ExerciseListItem, Page } from '../types';
import { Badge, Confirm, Drawer, ErrorBox, Modal, PageHead, Pager, Spinner, useLoad, useToast } from '../ui';

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
      <PageHead title="운동 종목" sub="종목 정보 · 판정 임계값 · 기준 영상 · 분석 지원 여부를 관리합니다. 분석은 AI 분석기가 있는 종목만 켤 수 있어요."
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

function ExerciseDrawer({ id, categories, onClose, onChanged }: { id: number; categories: Category[]; onClose: () => void; onChanged: () => void }) {
  const toast = useToast();
  const { data: x, error, setData } = useLoad(() => api<ExerciseDetail>(`/admin/exercises/${id}`), [id]);
  const [editing, setEditing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [th, setTh] = useState<Record<string, string> | null>(null);
  const [busy, setBusy] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);

  if (error) return <Drawer title="종목" onClose={onClose}><div className="alert alert-danger">{error}</div></Drawer>;
  if (!x) return <Drawer title="종목" onClose={onClose}><Spinner /></Drawer>;

  const thresholds = th ?? {
    beginner: String(x.syncThresholdBeginner ?? ''), advanced: String(x.syncThresholdAdvanced ?? ''),
    diet: String(x.syncThresholdDiet ?? ''), rehab: String(x.syncThresholdRehab ?? ''),
  };

  const run = async (fn: () => Promise<void>, ok: string) => {
    setBusy(true);
    try { await fn(); toast(ok); onChanged(); } catch (e: any) { toast(e.message, true); } finally { setBusy(false); }
  };

  const toggleAnalysis = () => run(async () => {
    setData(await api<ExerciseDetail>(`/admin/exercises/${id}/analysis-support`, { method: 'PATCH', body: { supported: !x.analysisSupported } }));
  }, x.analysisSupported ? 'AI 분석을 껐어요.' : 'AI 분석을 켰어요.');

  const saveThresholds = () => run(async () => {
    await api(`/admin/exercises/${id}/thresholds`, { method: 'PATCH', body: {
      beginner: Number(thresholds.beginner), advanced: Number(thresholds.advanced), diet: Number(thresholds.diet), rehab: Number(thresholds.rehab),
    } });
    setData(await api<ExerciseDetail>(`/admin/exercises/${id}`));
    setTh(null);
  }, '임계값을 저장했어요.');

  const upload = (file: File) => run(async () => {
    const form = new FormData();
    form.append('file', file);
    setData(await api<ExerciseDetail>(`/admin/exercises/${id}/reference-video`, { method: 'POST', form }));
  }, '기준 영상을 등록했어요. AI 가 기준 좌표를 뽑았어요.');

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
        <h4 className="section-title">판정 임계값 (싱크로율 %)</h4>
        <div className="form-grid">
          {([['beginner', '초급자'], ['advanced', '고급자'], ['diet', '다이어터'], ['rehab', '재활']] as const).map(([k, label]) => (
            <label key={k} className="field"><span>{label}</span>
              <input id={`th-${k}`} className="input num" type="number" min={0} max={100} step={0.5} value={thresholds[k]}
                onChange={(e) => setTh({ ...thresholds, [k]: e.target.value })} />
            </label>
          ))}
        </div>
        {th && <div style={{ display: 'flex', gap: 8, marginTop: 10 }}>
          <button className="btn btn-primary btn-sm" disabled={busy} onClick={saveThresholds}>임계값 저장</button>
          <button className="btn btn-outline btn-sm" onClick={() => setTh(null)}>되돌리기</button>
        </div>}
      </div>

      <div>
        <h4 className="section-title">기준 영상</h4>
        <div className="card card-pad" style={{ display: 'grid', gap: 10 }}>
          <div className="cell-sub">{x.referenceVideoPath ? <>등록됨 · <span className="mono">{x.referenceVideoPath}</span></> : '등록된 업로드 영상이 없어요.'}</div>
          <input ref={fileRef} type="file" accept="video/mp4" hidden onChange={(e) => { const f = e.target.files?.[0]; if (f) upload(f); e.target.value = ''; }} />
          <button className="btn btn-outline btn-sm" style={{ justifySelf: 'start' }} disabled={busy} onClick={() => fileRef.current?.click()}>
            {busy ? '처리 중…' : 'mp4 올리기 (최대 50MB)'}
          </button>
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
