import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api, photoSrc } from '../api';
import { dt } from '../format';
import type { FeedPost, GroupListItem, Page } from '../types';
import { Confirm, ErrorBox, PageHead, Pager, Spinner, useLoad, useToast } from '../ui';

/** 회원이 피드에 올린 운동 공유 글과 사진 — 부적절한 사진을 내린다. */
export default function FeedPosts() {
  const toast = useToast();
  const [params, setParams] = useSearchParams();
  const groupId = params.get('groupId') ?? '';
  const [page, setPage] = useState(0);
  const [deleting, setDeleting] = useState<FeedPost | null>(null);

  const groups = useLoad(() => api<Page<GroupListItem>>('/admin/groups', { query: { size: 100 } }), []);
  const { data, error, loading, reload } = useLoad(
    () => api<Page<FeedPost>>('/admin/feed-posts', { query: { groupId, page, size: 12 } }),
    [groupId, page],
  );

  return (
    <>
      <PageHead title="피드 사진 검수" sub="회원이 모임 피드에 공유한 운동 기록과 사진. 지우면 응원과 사진 파일도 함께 사라져요." />
      <div className="card">
        <div className="filters">
          <label className="field"><span>모임</span>
            <select id="feed-group" className="select" value={groupId} onChange={(e) => { setPage(0); setParams(e.target.value ? { groupId: e.target.value } : {}); }}>
              <option value="">전체 모임</option>
              {groups.data?.content.map((g) => <option key={g.id} value={g.id}>{g.name}</option>)}
            </select>
          </label>
        </div>
        {error ? <ErrorBox message={error} /> : loading && !data ? <Spinner /> : data && (
          <>
            {data.content.length === 0 ? <div className="empty">공유 글이 없어요.</div> : (
              <div className="post-grid">
                {data.content.map((p) => {
                  const src = photoSrc(p.photoUrl);
                  return (
                    <article key={p.postId} className="card post">
                      <div className="post-photo">
                        {src ? <img src={src} alt={`${p.authorUsername ?? '탈퇴한 회원'}의 공유 사진`} loading="lazy" /> : <span>사진 없음</span>}
                      </div>
                      <div className="post-body">
                        <div className="cell-main">{p.authorUsername ?? '탈퇴한 회원'}</div>
                        <div>{p.caption || <span className="cell-sub">(한마디 없음)</span>}</div>
                        <div className="post-meta">{p.groupName} · {p.exerciseName ?? '운동'} {p.totalReps ?? 0}회 · 응원 {p.cheerCount}</div>
                      </div>
                      <div className="post-foot">
                        <span className="post-meta num">{dt(p.createdAt)}</span>
                        <button className="btn btn-danger btn-sm" onClick={() => setDeleting(p)}>내리기</button>
                      </div>
                    </article>
                  );
                })}
              </div>
            )}
            <Pager page={data.page} totalPages={data.totalPages} total={data.totalElements} onPage={setPage} />
          </>
        )}
      </div>
      {deleting && <Confirm title="공유 글 내리기" danger confirmLabel="내리기"
        message={<>{deleting.authorUsername ?? '탈퇴한 회원'}님의 공유 글을 내릴까요? 응원 {deleting.cheerCount}개와 사진 파일도 함께 지워지고 되돌릴 수 없어요.</>}
        onClose={() => setDeleting(null)}
        onConfirm={async () => {
          try { await api(`/admin/feed-posts/${deleting.postId}`, { method: 'DELETE' }); toast('공유 글을 내렸어요.'); reload(); }
          catch (e: any) { toast(e.message, true); }
        }} />}
    </>
  );
}
