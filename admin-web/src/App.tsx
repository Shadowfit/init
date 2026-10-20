import { Navigate, NavLink, Route, Routes } from 'react-router-dom';
import { useAuth } from './auth';
import { Icon } from './ui';
import Login from './pages/Login';
import Dashboard from './pages/Dashboard';
import Members from './pages/Members';
import Sessions from './pages/Sessions';
import Exercises from './pages/Exercises';
import Categories from './pages/Categories';
import Groups from './pages/Groups';
import FeedPosts from './pages/FeedPosts';

export default function App() {
  const { user, ready } = useAuth();
  if (!ready) return null;
  // 관리자 계정만 들어온다 — 일반 계정은 로그인 단계(auth.tsx)에서 거르고, 관리자 API 는 서버가 403 으로도 막는다
  if (!user || user.role !== 'ADMIN') {
    return (
      <Routes>
        <Route path="*" element={<Login />} />
      </Routes>
    );
  }
  return <Shell />;
}

const NAV = [
  { section: '운영', items: [
    { to: '/', label: '대시보드', icon: Icon.dashboard, end: true },
    { to: '/members', label: '회원', icon: Icon.users },
    { to: '/sessions', label: '운동 세션', icon: Icon.activity },
  ] },
  { section: '콘텐츠', items: [
    { to: '/exercises', label: '운동 종목', icon: Icon.dumbbell },
    { to: '/categories', label: '카테고리', icon: Icon.tag },
  ] },
  { section: '커뮤니티', items: [
    { to: '/groups', label: '모임', icon: Icon.group },
    { to: '/feed-posts', label: '피드 사진 검수', icon: Icon.image },
  ] },
];

function Shell() {
  const { user, logout } = useAuth();

  return (
    <div className="shell">
      <nav className="sidebar" aria-label="관리자 메뉴">
        <div className="brand">
          <div className="brand-mark">{Icon.mark()}</div>
          <div>
            <div className="brand-name">ShadowFit</div>
            <div className="brand-sub">Admin Console</div>
          </div>
        </div>
        {NAV.map((g) => (
          <div key={g.section}>
            <div className="nav-label">{g.section}</div>
            {g.items.map((it) => (
              <NavLink key={it.to} to={it.to} end={'end' in it ? it.end : false}
                className={({ isActive }) => `nav-link${isActive ? ' active' : ''}`}>
                {it.icon()}
                {it.label}
              </NavLink>
            ))}
          </div>
        ))}
        <div className="sidebar-foot">
          <div className="who">
            <b>{user?.username ?? '관리자'}</b>
            <span>{user?.email}</span>
          </div>
          <button className="ghost-dark" onClick={logout}>로그아웃</button>
        </div>
      </nav>
      <main className="main">
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/members" element={<Members />} />
          <Route path="/sessions" element={<Sessions />} />
          <Route path="/exercises" element={<Exercises />} />
          <Route path="/categories" element={<Categories />} />
          <Route path="/groups" element={<Groups />} />
          <Route path="/feed-posts" element={<FeedPosts />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
    </div>
  );
}
