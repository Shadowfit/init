import { useState } from 'react';
import { api } from '../api';
import { day } from '../format';
import type { Category, ExerciseListItem, Page } from '../types';
import { Confirm, ErrorBox, Modal, PageHead, Spinner, useLoad, useToast } from '../ui';

export default function Categories() {
  const toast = useToast();
  const { data, error, loading, reload } = useLoad(async () => {
    const [cats, ex] = await Promise.all([
      api<Category[]>('/admin/categories'),
      api<Page<ExerciseListItem>>('/admin/exercises', { query: { size: 100 } }),
    ]);
    const count: Record<number, number> = {};
    ex.content.forEach((x) => (count[x.categoryId] = (count[x.categoryId] ?? 0) + 1));
    return { cats, count };
  }, []);
  const [editing, setEditing] = useState<Category | 'new' | null>(null);
  const [deleting, setDeleting] = useState<Category | null>(null);

  return (
    <>
      <PageHead title="카테고리" sub="운동 부위 분류. 종목이 남아 있는 카테고리는 지울 수 없어요."
        actions={<button className="btn btn-lime" onClick={() => setEditing('new')}>+ 새 카테고리</button>} />
      <div className="card">
        {error ? <ErrorBox message={error} /> : loading && !data ? <Spinner /> : data && (
          <div className="table-wrap">
            <table className="data">
              <thead><tr><th>이름</th><th className="num">종목 수</th><th>만든 날</th><th /></tr></thead>
              <tbody>
                {data.cats.map((c) => (
                  <tr key={c.id}>
                    <td className="cell-main mono">{c.name}</td>
                    <td className="num">{data.count[c.id] ?? 0}</td>
                    <td className="date">{day(c.createdAt)}</td>
                    <td className="num" style={{ whiteSpace: 'nowrap' }}>
                      <button className="btn btn-outline btn-sm" onClick={() => setEditing(c)}>이름 변경</button>{' '}
                      <button className="btn btn-danger btn-sm" onClick={() => setDeleting(c)}>삭제</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
      {editing && <CategoryForm initial={editing === 'new' ? undefined : editing} onClose={() => setEditing(null)}
        onSaved={() => { setEditing(null); reload(); toast('저장했어요.'); }} />}
      {deleting && <Confirm title="카테고리 삭제" danger confirmLabel="삭제" message={<>«{deleting.name}» 을(를) 지울까요?</>}
        onClose={() => setDeleting(null)}
        onConfirm={async () => {
          try { await api(`/admin/categories/${deleting.id}`, { method: 'DELETE' }); toast('삭제했어요.'); reload(); }
          catch (e: any) { toast(e.message, true); }
        }} />}
    </>
  );
}

function CategoryForm({ initial, onClose, onSaved }: { initial?: Category; onClose: () => void; onSaved: () => void }) {
  const [name, setName] = useState(initial?.name ?? '');
  const [error, setError] = useState<string | null>(null);
  const save = async () => {
    try {
      if (initial) await api(`/admin/categories/${initial.id}`, { method: 'PATCH', body: { name: name.trim() } });
      else await api('/admin/categories', { method: 'POST', body: { name: name.trim() } });
      onSaved();
    } catch (e: any) {
      setError(e.message);
    }
  };
  return (
    <Modal title={initial ? '카테고리 이름 변경' : '새 카테고리'} onClose={onClose} footer={
      <><button className="btn btn-outline" onClick={onClose}>취소</button>
        <button className="btn btn-primary" disabled={!name.trim()} onClick={save}>저장</button></>
    }>
      {error && <div className="alert alert-danger">{error}</div>}
      <label className="field"><span>이름 (50자)</span>
        <input id="category-name" className="input" maxLength={50} value={name} onChange={(e) => setName(e.target.value)} placeholder="예: UPPER" autoFocus />
      </label>
    </Modal>
  );
}
