import { useState } from 'react';
import { api } from '../api';
import { dt, minutesBetween, SESSION_STATUS } from '../format';
import type { ExerciseListItem, Page, SessionListItem, SessionStatus } from '../types';
import { Badge, ErrorBox, PageHead, Pager, Spinner, Sync, useLoad } from '../ui';

type Sort = 'START_TIME' | 'AVG_SYNC_RATE' | 'TOTAL_REPS';

export default function Sessions() {
  const [q, setQ] = useState({
    status: '' as SessionStatus | '', exerciseId: '', startedFrom: '', startedTo: '',
    sort: 'START_TIME' as Sort, asc: false, page: 0,
  });
  const set = (patch: Partial<typeof q>) => setQ((p) => ({ ...p, page: 0, ...patch }));

  const exercises = useLoad(() => api<Page<ExerciseListItem>>('/admin/exercises', { query: { size: 100, sort: 'NAME', asc: true } }), []);
  const { data, error, loading } = useLoad(
    () => api<Page<SessionListItem>>('/admin/sessions', { query: { ...q, size: 20 } }),
    [JSON.stringify(q)],
  );

  const sortBy = (s: Sort) => set({ sort: s, asc: q.sort === s ? !q.asc : false });
  const arrow = (s: Sort) => (q.sort === s ? (q.asc ? ' ↑' : ' ↓') : '');

  return (
    <>
      <PageHead title="운동 세션" sub="실패·타임아웃 세션을 추적하고 싱크로율·횟수로 정렬할 수 있어요." />
      <div className="card">
        <div className="filters">
          <label className="field"><span>상태</span>
            <select id="session-status" className="select" value={q.status} onChange={(e) => set({ status: e.target.value as SessionStatus | '' })}>
              <option value="">전체</option>
              {Object.entries(SESSION_STATUS).map(([k, v]) => <option key={k} value={k}>{v.label}</option>)}
            </select>
          </label>
          <label className="field"><span>종목</span>
            <select id="session-exercise" className="select" value={q.exerciseId} onChange={(e) => set({ exerciseId: e.target.value })}>
              <option value="">전체</option>
              {exercises.data?.content.map((x) => <option key={x.id} value={x.id}>{x.name}</option>)}
            </select>
          </label>
          <label className="field"><span>시작일 (부터)</span>
            <input id="session-from" className="input" type="date" value={q.startedFrom} onChange={(e) => set({ startedFrom: e.target.value })} />
          </label>
          <label className="field"><span>시작일 (까지)</span>
            <input id="session-to" className="input" type="date" value={q.startedTo} onChange={(e) => set({ startedTo: e.target.value })} />
          </label>
          {(q.status || q.exerciseId || q.startedFrom || q.startedTo) && (
            <button className="btn btn-outline" onClick={() => set({ status: '', exerciseId: '', startedFrom: '', startedTo: '' })}>필터 초기화</button>
          )}
        </div>
        {error ? <ErrorBox message={error} /> : loading && !data ? <Spinner /> : data && (
          <>
            <div className="table-wrap">
              <table className="data">
                <thead><tr>
                  <th className="num">#</th><th>회원</th><th>종목</th><th>상태</th>
                  <th className="sortable" onClick={() => sortBy('START_TIME')}>시작{arrow('START_TIME')}</th>
                  <th className="num">운동 시간</th>
                  <th className="num sortable" onClick={() => sortBy('TOTAL_REPS')}>횟수{arrow('TOTAL_REPS')}</th>
                  <th className="sortable" onClick={() => sortBy('AVG_SYNC_RATE')}>평균 싱크로율{arrow('AVG_SYNC_RATE')}</th>
                </tr></thead>
                <tbody>
                  {data.content.length === 0 && <tr><td colSpan={8} className="empty">조건에 맞는 세션이 없어요.</td></tr>}
                  {data.content.map((s) => {
                    const mins = minutesBetween(s.startTime, s.endTime);
                    return (
                      <tr key={s.id}>
                        <td className="num mono">{s.id}</td>
                        <td className="cell-main">{s.username}</td>
                        <td>{s.exerciseName}</td>
                        <td><Badge tone={SESSION_STATUS[s.status].tone}>{SESSION_STATUS[s.status].label}</Badge></td>
                        <td className="date">{dt(s.startTime)}</td>
                        <td className="num">{mins == null ? '—' : `${mins}분`}</td>
                        <td className="num">{s.totalReps ?? '—'}</td>
                        <td><Sync value={s.avgSyncRate} /></td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            <Pager page={data.page} totalPages={data.totalPages} total={data.totalElements} onPage={(p) => setQ((s) => ({ ...s, page: p }))} />
          </>
        )}
      </div>
    </>
  );
}
