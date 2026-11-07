import { useState } from 'react';
import { api } from '../api';
import { day, dt, LEVEL, PERSONA, SESSION_STATUS } from '../format';
import type { MemberDetail, MemberListItem, Page, Persona, WorkoutLevel } from '../types';
import { Badge, Drawer, ErrorBox, PageHead, Pager, Spinner, Sync, useLoad } from '../ui';

type Sort = 'CREATED_AT' | 'USERNAME';

export default function Members() {
  const [keyword, setKeyword] = useState('');
  const [q, setQ] = useState({
    keyword: '', persona: '' as Persona | '', workoutLevel: '' as WorkoutLevel | '', onboardingCompleted: '',
    joinedFrom: '', joinedTo: '', sort: 'CREATED_AT' as Sort, asc: false, page: 0,
  });
  const [openId, setOpenId] = useState<number | null>(null);
  const set = (patch: Partial<typeof q>) => setQ((p) => ({ ...p, page: 0, ...patch }));

  const { data, error, loading } = useLoad(
    () => api<Page<MemberListItem>>('/admin/members', { query: { ...q, size: 20 } }),
    [JSON.stringify(q)],
  );

  const sortBy = (s: Sort) => set({ sort: s, asc: q.sort === s ? !q.asc : s === 'USERNAME' });
  const arrow = (s: Sort) => (q.sort === s ? (q.asc ? ' ↑' : ' ↓') : '');

  return (
    <>
      <PageHead title="회원" sub="닉네임·이메일 검색과 페르소나·운동 수준·온보딩·가입일 필터를 조합할 수 있어요." />
      <div className="card">
        <form className="filters" onSubmit={(e) => { e.preventDefault(); set({ keyword: keyword.trim() }); }}>
          <label className="field"><span>검색</span>
            <input id="member-keyword" className="input search" value={keyword} onChange={(e) => setKeyword(e.target.value)} placeholder="닉네임 또는 이메일" />
          </label>
          <label className="field"><span>페르소나</span>
            <select id="member-persona" className="select" value={q.persona} onChange={(e) => set({ persona: e.target.value as Persona | '' })}>
              <option value="">전체</option>
              {Object.entries(PERSONA).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
          </label>
          <label className="field"><span>운동 수준</span>
            <select id="member-level" className="select" value={q.workoutLevel} onChange={(e) => set({ workoutLevel: e.target.value as WorkoutLevel | '' })}>
              <option value="">전체</option>
              {Object.entries(LEVEL).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
            </select>
          </label>
          <label className="field"><span>온보딩</span>
            <select id="member-onboarding" className="select" value={q.onboardingCompleted} onChange={(e) => set({ onboardingCompleted: e.target.value })}>
              <option value="">전체</option><option value="true">완료</option><option value="false">미완료</option>
            </select>
          </label>
          <label className="field"><span>가입일 (부터)</span>
            <input id="member-from" className="input" type="date" value={q.joinedFrom} onChange={(e) => set({ joinedFrom: e.target.value })} />
          </label>
          <label className="field"><span>가입일 (까지)</span>
            <input id="member-to" className="input" type="date" value={q.joinedTo} onChange={(e) => set({ joinedTo: e.target.value })} />
          </label>
          <button className="btn btn-primary">검색</button>
        </form>
        {error ? <ErrorBox message={error} /> : loading && !data ? <Spinner /> : data && (
          <>
            <div className="table-wrap">
              <table className="data">
                <thead><tr>
                  <th className="sortable" onClick={() => sortBy('USERNAME')}>회원{arrow('USERNAME')}</th>
                  <th>페르소나</th><th>운동 수준</th><th>온보딩</th>
                  <th className="sortable" onClick={() => sortBy('CREATED_AT')}>가입일{arrow('CREATED_AT')}</th>
                </tr></thead>
                <tbody>
                  {data.content.length === 0 && <tr><td colSpan={5} className="empty">조건에 맞는 회원이 없어요.</td></tr>}
                  {data.content.map((m) => (
                    <tr key={m.id} className="clickable" onClick={() => setOpenId(m.id)}>
                      <td><div className="cell-main">{m.username}</div><div className="cell-sub">{m.email}</div></td>
                      <td>{PERSONA[m.selectedPersona]}</td>
                      <td>{m.workoutLevel ? LEVEL[m.workoutLevel] : <span className="cell-sub">—</span>}</td>
                      <td>{m.onboardingCompleted ? <Badge tone="b-ok">완료</Badge> : <Badge tone="b-neutral">미완료</Badge>}</td>
                      <td className="date">{day(m.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pager page={data.page} totalPages={data.totalPages} total={data.totalElements} onPage={(p) => setQ((s) => ({ ...s, page: p }))} />
          </>
        )}
      </div>
      {openId != null && <MemberDrawer id={openId} onClose={() => setOpenId(null)} />}
    </>
  );
}

function MemberDrawer({ id, onClose }: { id: number; onClose: () => void }) {
  const { data: m, error } = useLoad(() => api<MemberDetail>(`/admin/members/${id}`), [id]);
  return (
    <Drawer title={m?.username ?? '회원'} sub={m?.email} onClose={onClose}>
      {error ? <div className="alert alert-danger">{error}</div> : !m ? <Spinner /> : (
        <>
          <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
            {m.role === 'ADMIN' ? <Badge tone="b-ink" plain>관리자</Badge> : <Badge tone="b-neutral" plain>일반 회원</Badge>}
            <Badge tone="b-info" plain>{PERSONA[m.selectedPersona]}</Badge>
            {m.workoutLevel && <Badge tone="b-neutral" plain>{LEVEL[m.workoutLevel]}</Badge>}
          </div>
          <div className="mini-stats">
            <div className="card"><b>{m.sessionCount}</b><span>전체 세션</span></div>
            <div className="card"><b>{m.completedSessionCount}</b><span>완료</span></div>
            <div className="card"><b>{m.averageSyncRate == null ? '—' : `${m.averageSyncRate}%`}</b><span>평균 싱크로율</span></div>
          </div>
          <div>
            <h4 className="section-title">프로필</h4>
            <dl className="dl">
              <dt>회원 번호</dt><dd className="mono">#{m.id}</dd>
              <dt>성별</dt><dd>{m.sex === 'MALE' ? '남성' : m.sex === 'FEMALE' ? '여성' : '선택 안 함'}</dd>
              <dt>키 · 몸무게</dt><dd>{m.height ? `${m.height}cm` : '—'} · {m.weight ? `${m.weight}kg` : '—'}</dd>
              <dt>온보딩</dt><dd>{m.onboardingCompleted ? '완료' : '미완료'}</dd>
              <dt>가입</dt><dd>{dt(m.createdAt)}</dd>
              <dt>마지막 운동</dt><dd>{dt(m.lastSessionAt)}</dd>
            </dl>
          </div>
          <div>
            <h4 className="section-title">참여 모임 {m.groups.length}</h4>
            {m.groups.length === 0 ? <div className="cell-sub">참여 중인 모임이 없어요.</div> : (
              <ul className="list-plain">
                {m.groups.map((g) => (
                  <li key={g.groupId}><span>{g.name}</span>{g.role === 'OWNER' ? <Badge tone="b-ink" plain>그룹장</Badge> : <Badge tone="b-neutral" plain>멤버</Badge>}</li>
                ))}
              </ul>
            )}
          </div>
          <div>
            <h4 className="section-title">최근 세션</h4>
            {m.recentSessions.length === 0 ? <div className="cell-sub">운동 기록이 없어요.</div> : (
              <table className="data">
                <tbody>
                  {m.recentSessions.map((s) => (
                    <tr key={s.id}>
                      <td><div className="cell-main">{s.exerciseName}</div><div className="cell-sub num">{dt(s.startTime)}</div></td>
                      <td><Badge tone={SESSION_STATUS[s.status].tone}>{SESSION_STATUS[s.status].label}</Badge></td>
                      <td className="num">{s.totalReps ?? '—'}회</td>
                      <td><Sync value={s.avgSyncRate} /></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </>
      )}
    </Drawer>
  );
}
