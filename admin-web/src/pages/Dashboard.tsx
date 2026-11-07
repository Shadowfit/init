import { Link } from 'react-router-dom';
import { api } from '../api';
import { day, dt, SESSION_STATUS, toDate } from '../format';
import type { CommunityStats, DailyStat, Page, SessionListItem, SessionStatus, StatsOverview } from '../types';
import { Badge, ErrorBox, PageHead, Spinner, Sync, useLoad } from '../ui';

const DAYS = 14;

export default function Dashboard() {
  const { data, error, loading } = useLoad(async () => {
    const [overview, daily, community, recent] = await Promise.all([
      api<StatsOverview>('/admin/stats/overview'),
      api<DailyStat[]>('/admin/stats/daily', { query: { days: DAYS } }),
      api<CommunityStats>('/admin/stats/community'),
      api<Page<SessionListItem>>('/admin/sessions', { query: { size: 6 } }),
    ]);
    return { overview, daily, community, recent };
  }, []);

  if (loading && !data) return <Spinner />;
  if (error) return <ErrorBox message={error} />;
  if (!data) return null;
  const { overview: o, daily, community: c, recent } = data;
  const weekSessions = daily.slice(-7).reduce((s, d) => s + d.sessionCount, 0);
  const statusTotal = Object.values(o.sessionCountByStatus).reduce((s, n) => s + (n ?? 0), 0);

  return (
    <>
      <PageHead title="대시보드" sub={`${day(o.baseDate)} 기준 · 숫자는 요청할 때마다 새로 셉니다`} />

      <div className="grid-kpi">
        <div className="card kpi accent">
          <span className="label">오늘 운동 세션</span>
          <span className="value">{o.todaySessionCount}<small>회</small></span>
          <span className="sub">최근 7일 {weekSessions}회</span>
        </div>
        <div className="card kpi">
          <span className="label">오늘 평균 싱크로율</span>
          <span className="value">{o.todayAverageSyncRate == null ? '—' : o.todayAverageSyncRate.toFixed(1)}<small>{o.todayAverageSyncRate == null ? '' : '%'}</small></span>
          <span className="sub">완료된 세션 기준</span>
        </div>
        <div className="card kpi">
          <span className="label">활성 회원</span>
          <span className="value">{o.activeMemberCount}<small>명</small></span>
          <span className="sub">최근 {o.activeMemberWindowDays}일 안에 운동</span>
        </div>
        <div className="card kpi">
          <span className="label">오늘 신규 가입</span>
          <span className="value">{o.todayNewMemberCount}<small>명</small></span>
          <span className="sub">14일 합계 {daily.reduce((s, d) => s + d.newMemberCount, 0)}명</span>
        </div>
      </div>

      <div className="grid-2">
        <div className="card">
          <div className="card-head">
            <h2>최근 {DAYS}일 운동 추이</h2>
            <div className="legend">
              <span><i style={{ background: 'var(--ink)' }} />완료</span>
              <span><i style={{ background: '#d9dece' }} />미완료</span>
              <span><i style={{ background: 'var(--lime-deep)', height: 3, borderRadius: 2 }} />평균 싱크로율</span>
            </div>
          </div>
          <DailyChart data={daily} />
        </div>
        <div className="card">
          <div className="card-head"><h2>세션 상태 분포</h2><span className="hint">전체 기간 {statusTotal.toLocaleString()}회</span></div>
          <StatusBars counts={o.sessionCountByStatus} total={statusTotal} />
          <div className="card-head" style={{ paddingTop: 4 }}><h2>커뮤니티</h2></div>
          <div className="status-rows" style={{ gridTemplateColumns: '1fr 1fr' }}>
            <Stat label="모임" value={c.groupCount} />
            <Stat label="모임 참여" value={c.activeMembershipCount} />
            <Stat label="공유 글" value={c.sharedPostCount} />
            <Stat label="응원 한마디" value={c.cheerCount} />
            <Stat label="오늘 재촉" value={c.todayNudgeCount} />
            <Stat label="모임당 평균 인원" value={c.groupCount ? +(c.activeMembershipCount / c.groupCount).toFixed(1) : 0} />
          </div>
        </div>
      </div>

      <div className="card">
        <div className="card-head">
          <h2>최근 운동 세션</h2>
          <Link to="/sessions" className="btn-link">전체 보기 →</Link>
        </div>
        <div className="table-wrap" style={{ marginTop: 12 }}>
          <table className="data">
            <thead><tr><th>회원</th><th>종목</th><th>상태</th><th>시작</th><th className="num">횟수</th><th>싱크로율</th></tr></thead>
            <tbody>
              {recent.content.map((s) => (
                <tr key={s.id}>
                  <td className="cell-main">{s.username}</td>
                  <td>{s.exerciseName}</td>
                  <td><Badge tone={SESSION_STATUS[s.status].tone}>{SESSION_STATUS[s.status].label}</Badge></td>
                  <td className="date">{dt(s.startTime)}</td>
                  <td className="num">{s.totalReps ?? '—'}</td>
                  <td><Sync value={s.avgSyncRate} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <div className="cell-sub">{label}</div>
      <div style={{ fontSize: 22, fontWeight: 800 }} className="num">{value.toLocaleString()}</div>
    </div>
  );
}

const STATUS_COLOR: Record<SessionStatus, string> = {
  COMPLETED: 'var(--ok)',
  IN_PROGRESS: 'var(--info)',
  CANCELLED: '#b9bfae',
  FAILED: 'var(--danger)',
};

function StatusBars({ counts, total }: { counts: Partial<Record<SessionStatus, number>>; total: number }) {
  const order: SessionStatus[] = ['COMPLETED', 'IN_PROGRESS', 'FAILED', 'CANCELLED'];
  return (
    <div className="status-rows">
      {order.map((s) => {
        const n = counts[s] ?? 0;
        const pct = total ? (n / total) * 100 : 0;
        return (
          <div key={s} className="status-row">
            <span>{SESSION_STATUS[s].label}</span>
            <span className="status-track"><i style={{ width: `${pct}%`, background: STATUS_COLOR[s] }} /></span>
            <span className="num" style={{ textAlign: 'right' }}>{n}</span>
          </div>
        );
      })}
    </div>
  );
}

/** 막대 = 그날 세션(완료 위에 미완료를 쌓음), 선 = 완료 평균 싱크로율(오른쪽 축 0~100%). 한 축척으로 그린다. */
function DailyChart({ data }: { data: DailyStat[] }) {
  const W = 640, H = 240, L = 34, R = 40, T = 16, B = 30;
  const iw = W - L - R, ih = H - T - B;
  const maxCount = Math.max(4, ...data.map((d) => d.sessionCount));
  const step = Math.ceil(maxCount / 4);
  const top = step * 4;
  const bw = (iw / data.length) * 0.58;
  const x = (i: number) => L + (iw / data.length) * (i + 0.5);
  const y = (v: number) => T + ih - (v / top) * ih;
  const ySync = (v: number) => T + ih - (v / 100) * ih;
  const line = data
    .map((d, i) => (d.averageSyncRate == null ? null : `${x(i)},${ySync(d.averageSyncRate)}`))
    .filter(Boolean)
    .join(' ');

  return (
    <svg className="chart" viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`최근 ${data.length}일 세션 수와 평균 싱크로율`}>
      {[0, 1, 2, 3, 4].map((k) => (
        <g key={k}>
          <line x1={L} x2={W - R} y1={y(k * step)} y2={y(k * step)} stroke="#e8ebe1" />
          <text x={L - 8} y={y(k * step) + 4} textAnchor="end">{k * step}</text>
          <text x={W - R + 8} y={ySync(k * 25) + 4}>{k * 25}%</text>
        </g>
      ))}
      {data.map((d, i) => {
        const done = d.completedCount, rest = d.sessionCount - d.completedCount;
        const dd = toDate(d.date);
        const label = dd ? `${dd.getMonth() + 1}.${dd.getDate()}` : '';
        return (
          <g key={String(d.date)}>
            <rect x={x(i) - bw / 2} y={y(done)} width={bw} height={Math.max(0, y(0) - y(done))} rx="3" fill="#14170f" />
            {rest > 0 && <rect x={x(i) - bw / 2} y={y(done + rest)} width={bw} height={y(done) - y(done + rest) - 1} rx="3" fill="#d9dece" />}
            {(i % 2 === data.length % 2 || i === data.length - 1) && <text x={x(i)} y={H - 10} textAnchor="middle">{label}</text>}
            <title>{`${day(d.date)} · 세션 ${d.sessionCount} (완료 ${d.completedCount})${d.averageSyncRate != null ? ` · 싱크로율 ${d.averageSyncRate}%` : ''} · 가입 ${d.newMemberCount}`}</title>
          </g>
        );
      })}
      {line && <polyline points={line} fill="none" stroke="#6f8f00" strokeWidth="2.4" strokeLinejoin="round" />}
      {data.map((d, i) => d.averageSyncRate != null && (
        <circle key={`p${String(d.date)}`} cx={x(i)} cy={ySync(d.averageSyncRate)} r={i === data.length - 1 ? 4.5 : 3} fill="#caff00" stroke="#6f8f00" strokeWidth="1.6" />
      ))}
    </svg>
  );
}
