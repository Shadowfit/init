import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api';
import { ago, day, dt } from '../format';
import type { GroupDetail, GroupListItem, Page } from '../types';
import { Badge, Confirm, Drawer, ErrorBox, PageHead, Pager, Spinner, useLoad, useToast } from '../ui';

export default function Groups() {
  const [keyword, setKeyword] = useState('');
  const [q, setQ] = useState({ keyword: '', page: 0 });
  const [openId, setOpenId] = useState<number | null>(null);
  const { data, error, loading, reload } = useLoad(
    () => api<Page<GroupListItem>>('/admin/groups', { query: { ...q, size: 20 } }),
    [JSON.stringify(q)],
  );

  return (
    <>
      <PageHead title="모임" sub="회원이 만든 운동 모임. 멤버 수 · 공유 글 · 마지막 활동으로 살아 있는 모임을 확인해요." />
      <div className="card">
        <form className="filters" onSubmit={(e) => { e.preventDefault(); setQ({ keyword: keyword.trim(), page: 0 }); }}>
          <label className="field"><span>검색</span>
            <input id="group-keyword" className="input search" value={keyword} onChange={(e) => setKeyword(e.target.value)} placeholder="모임 이름" />
          </label>
          <button className="btn btn-primary">검색</button>
        </form>
        {error ? <ErrorBox message={error} /> : loading && !data ? <Spinner /> : data && (
          <>
            <div className="table-wrap">
              <table className="data">
                <thead><tr><th>모임</th><th>그룹장</th><th className="num">멤버</th><th className="num">공유 글</th><th>마지막 활동</th><th>초대 코드</th><th>만든 날</th></tr></thead>
                <tbody>
                  {data.content.length === 0 && <tr><td colSpan={7} className="empty">모임이 없어요.</td></tr>}
                  {data.content.map((g) => (
                    <tr key={g.id} className="clickable" onClick={() => setOpenId(g.id)}>
                      <td><div className="cell-main">{g.name}</div>{g.description && <div className="cell-sub">{g.description}</div>}</td>
                      <td>{g.ownerUsername ?? <span className="cell-sub">없음</span>}</td>
                      <td className="num">{g.activeMemberCount}</td>
                      <td className="num">{g.sharedPostCount}</td>
                      <td>{ago(g.lastActivityAt)}</td>
                      <td className="mono">{g.inviteCode}</td>
                      <td className="date">{day(g.createdAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pager page={data.page} totalPages={data.totalPages} total={data.totalElements} onPage={(p) => setQ((s) => ({ ...s, page: p }))} />
          </>
        )}
      </div>
      {openId != null && <GroupDrawer id={openId} onClose={() => setOpenId(null)} onDeleted={() => { setOpenId(null); reload(); }} />}
    </>
  );
}

function GroupDrawer({ id, onClose, onDeleted }: { id: number; onClose: () => void; onDeleted: () => void }) {
  const toast = useToast();
  const navigate = useNavigate();
  const { data, error } = useLoad(() => api<GroupDetail>(`/admin/groups/${id}`), [id]);
  const [deleting, setDeleting] = useState(false);
  if (error) return <Drawer title="모임" onClose={onClose}><div className="alert alert-danger">{error}</div></Drawer>;
  if (!data) return <Drawer title="모임" onClose={onClose}><Spinner /></Drawer>;
  const g = data.summary;
  const active = data.members.filter((m) => m.status === 'ACTIVE');
  const left = data.members.filter((m) => m.status === 'LEFT');

  return (
    <Drawer title={g.name} sub={g.description ?? undefined} onClose={onClose}>
      <div className="mini-stats">
        <div className="card"><b>{g.activeMemberCount}</b><span>멤버</span></div>
        <div className="card"><b>{g.sharedPostCount}</b><span>공유 글</span></div>
        <div className="card"><b style={{ fontSize: 15 }}>{ago(g.lastActivityAt)}</b><span>마지막 활동</span></div>
      </div>
      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <button className="btn btn-outline btn-sm" onClick={() => navigate(`/feed-posts?groupId=${g.id}`)}>이 모임 공유 글 보기</button>
        <button className="btn btn-danger btn-sm" onClick={() => setDeleting(true)}>모임 삭제</button>
      </div>
      <dl className="dl">
        <dt>초대 코드</dt><dd className="mono">{g.inviteCode}</dd>
        <dt>만든 날</dt><dd>{dt(g.createdAt)}</dd>
      </dl>
      <div>
        <h4 className="section-title">멤버 {active.length}</h4>
        <ul className="list-plain">
          {active.map((m) => (
            <li key={m.memberId}>
              <span><b>{m.username}</b> <span className="cell-sub">· {day(m.joinedAt)} 가입</span></span>
              {m.role === 'OWNER' ? <Badge tone="b-ink" plain>그룹장</Badge> : <Badge tone="b-neutral" plain>멤버</Badge>}
            </li>
          ))}
        </ul>
      </div>
      {left.length > 0 && (
        <div>
          <h4 className="section-title">탈퇴한 멤버 {left.length}</h4>
          <div className="cell-sub">{left.map((m) => m.username).join(', ')}</div>
        </div>
      )}
      {deleting && <Confirm title="모임 삭제" danger confirmLabel="삭제"
        message={<>«{g.name}» 을(를) 지우면 멤버십 · 피드 · 응원 · 공유 사진이 모두 사라지고 되돌릴 수 없어요.</>}
        onClose={() => setDeleting(false)}
        onConfirm={async () => {
          try { await api(`/admin/groups/${id}`, { method: 'DELETE' }); toast('모임을 삭제했어요.'); onDeleted(); }
          catch (e: any) { toast(e.message, true); }
        }} />}
    </Drawer>
  );
}
