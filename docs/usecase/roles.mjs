// 2026-09-21 3자 회의 — 역할별 유스케이스 1장씩 + 통합본 1장
//   node docs/usecase/roles.mjs   → svg/10~13-*.svg  (PNG 는 README «갱신 방법» 의 Edge 명령)
//
// 출처: 프론트 = catalog.mjs(코드 실사) · 백엔드 = 담당자 그림 3장(전체도·B-01 관계도·시연 경로)
//       AI = 담당자 PDF «유스케이스 명세서»(UC-01~09). 역할별 장은 **각자 가져온 내용 그대로** 옮겼고,
//       통합본만 ID 를 하나로 맞추고 역할별 상태를 F·B·A 칸으로 나란히 놓았다.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { UC as FE_UC, GROUPS as FE_GROUPS } from './catalog.mjs';

const DIR = path.dirname(fileURLToPath(import.meta.url));
const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const FONT = "'Segoe UI','Malgun Gothic','Apple SD Gothic Neo','Noto Sans KR',sans-serif";
const INK = '#1F231B', MUTED = '#5C6355', LINE = '#C9CEC0', PANEL = '#FAFAF7', EDGE = '#9AA08F', ALERT = '#A83232';

const ST = {
  done:     { fill: '#E8F1DC', stroke: '#3D6B12', text: '#2F5410' },
  verify:   { fill: '#E1F0F5', stroke: '#1F6F8B', text: '#15566D' },
  partial:  { fill: '#FAECD8', stroke: '#8A5A12', text: '#7A4A08' },
  noscreen: { fill: '#FBE9E9', stroke: '#A83232', text: '#9B2424' },
  planned:  { fill: '#FFFFFF', stroke: '#8C8C82', text: '#5F5F57', dash: '6 4' },
  backend:  { fill: '#F0EEE8', stroke: '#66645C', text: '#4A4943' },
};
const RANK = { planned: 0, noscreen: 1, partial: 2, verify: 3, done: 4, backend: 4 };

/* ───────────── 공통 그리기 ───────────── */
const open = (W, H, label, n) => `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}" role="img" aria-label="${esc(label)}" font-family="${FONT}">
<defs><marker id="o${n}" viewBox="0 0 12 12" refX="11" refY="6" markerWidth="13" markerHeight="13" orient="auto-start-reverse"><path d="M1,1 L11,6 L1,11" fill="none" stroke="${EDGE}" stroke-width="1.6"/></marker>
<marker id="s${n}" viewBox="0 0 12 12" refX="10" refY="6" markerWidth="11" markerHeight="11" orient="auto-start-reverse"><path d="M1,1 L11,6 L1,11 Z" fill="${EDGE}"/></marker></defs>
<rect width="${W}" height="${H}" fill="#FFFFFF"/>
`;

function stick(cx, cy, label, faded) {
  return `<g${faded ? ' opacity="0.36"' : ''} stroke="#4A4F44" stroke-width="2" fill="none"><circle cx="${cx}" cy="${cy - 44}" r="19" fill="#FFFFFF"/><line x1="${cx}" y1="${cy - 25}" x2="${cx}" y2="${cy + 16}"/><line x1="${cx - 30}" y1="${cy - 6}" x2="${cx + 30}" y2="${cy - 6}"/><line x1="${cx}" y1="${cy + 16}" x2="${cx - 24}" y2="${cy + 54}"/><line x1="${cx}" y1="${cy + 16}" x2="${cx + 24}" y2="${cy + 54}"/>
<text x="${cx}" y="${cy + 84}" font-size="19" font-weight="700" fill="${INK}" stroke="none" text-anchor="middle">${esc(label)}</text></g>`;
}
const sysActor = (x, y, w, label, faded) => `<g${faded ? ' opacity="0.36"' : ''}><rect x="${x}" y="${y}" width="${w}" height="64" rx="10" fill="#F0EEE8" stroke="#5F5D55" stroke-width="1.3"/><text x="${x + w / 2}" y="${y + 39}" font-size="19" font-weight="600" fill="#33322D" text-anchor="middle">${esc(label)}</text></g>`;

function indicator(x, y, letter, status, star) {
  const s = status ? ST[status] : null;
  let g = s
    ? `<rect x="${x}" y="${y}" width="22" height="22" rx="5" fill="${s.fill === '#FFFFFF' ? '#FFFFFF' : s.stroke}" stroke="${s.stroke}" stroke-width="1.2"${s.dash ? ` stroke-dasharray="3 2"` : ''}/><text x="${x + 11}" y="${y + 16}" font-size="12.5" font-weight="700" fill="${s.fill === '#FFFFFF' ? s.text : '#FFFFFF'}" text-anchor="middle">${letter}</text>`
    : `<rect x="${x}" y="${y}" width="22" height="22" rx="5" fill="#F6F6F2" stroke="#DADAD2" stroke-width="1"/><text x="${x + 11}" y="${y + 15}" font-size="12" fill="#B0B0A6" text-anchor="middle">–</text>`;
  if (star) g += `<circle cx="${x + 22}" cy="${y}" r="8" fill="#FFFFFF" stroke="${ALERT}" stroke-width="1.4"/><text x="${x + 22}" y="${y + 5.5}" font-size="15" font-weight="700" fill="${ALERT}" text-anchor="middle">*</text>`;
  return g;
}

function box(x, y, w, h, u, o = {}) {
  const s = ST[o.status ?? u.status];
  const thick = !!o.thick, fs = o.fs ?? 17;
  const dash = s.dash && !thick ? ` stroke-dasharray="${s.dash}"` : '';
  let g = `<g${o.faded ? ' opacity="0.36"' : ''}><rect x="${x}" y="${y}" width="${w}" height="${h}" rx="10" fill="${s.fill}" stroke="${o.alert ? ALERT : s.stroke}" stroke-width="${thick ? 3.6 : 1.3}"${dash}/>`;
  g += `<text x="${x + 15}" y="${y + h / 2 + fs * 0.35}" font-size="${fs}" fill="${s.text}"><tspan font-weight="400">${esc(u.id)}</tspan><tspan dx="8" font-weight="${thick ? 700 : 600}">${esc(u.name)}</tspan></text>`;
  if (o.tag) g += `<text x="${x + w - 14}" y="${y + h / 2 + 5}" font-size="14" fill="${s.text}" text-anchor="end" opacity="0.85">${esc(o.tag)}</text>`;
  if (o.ind) ['F', 'B', 'A'].forEach((r, i) => { g += indicator(x + w - 12 - (3 - i) * 26 + 4, y + h / 2 - 11, r, u[r], u.star?.includes(r)); });
  if (typeof o.badge === 'number' && o.badge > 0) g += `<circle cx="${x + w - 6}" cy="${y + 4}" r="14" fill="${INK}"/><text x="${x + w - 6}" y="${y + 9}" font-size="14" font-weight="700" fill="#FFFFFF" text-anchor="middle">${o.badge}</text>`;
  else if (o.badge) g += `<rect x="${x + w - 50}" y="${y - 11}" width="52" height="23" rx="11.5" fill="${INK}"/><text x="${x + w - 24}" y="${y + 5}" font-size="12.5" font-weight="700" fill="#FFFFFF" text-anchor="middle">${esc(o.badge)}</text>`;
  return g + '</g>';
}

function swatches(x, y, list) {
  let g = '', cx = x;
  for (const [k, label] of list) {
    const s = ST[k];
    g += `<rect x="${cx}" y="${y}" width="22" height="22" rx="5" fill="${s.fill}" stroke="${s.stroke}" stroke-width="1.3"${s.dash ? ` stroke-dasharray="${s.dash}"` : ''}/><text x="${cx + 32}" y="${y + 17}" font-size="17" fill="${MUTED}">${esc(label)}</text>`;
    cx += 32 + [...label].reduce((w, ch) => w + (ch.charCodeAt(0) > 255 ? 16.5 : 9.5), 0) + 30;
  }
  return g;
}

/**
 * 역할 한 장. cfg.groups 의 박스 격자 + 좌우 액터 + 범례 + (선택) 아래 패널.
 */
function sheet(cfg) {
  const { n, W = 1360, cols = 3, BW = 264, BH = 56 } = cfg;
  const GAPX = 16, GAPY = 16, PADL = 12, GX = 256;
  const GW = cols * BW + (cols - 1) * GAPX + PADL * 2;
  const RX = GX + GW + 32, RW = W - RX - 24;

  let body = '', y = 150;
  const mids = {};
  for (const grp of cfg.groups) {
    const rows = Math.ceil(grp.ids.length / cols);
    const gh = 56 + rows * BH + (rows - 1) * GAPY + 22;
    body += `<rect x="${GX}" y="${y}" width="${GW}" height="${gh}" rx="18" fill="${PANEL}" stroke="${LINE}" stroke-width="1.2"/><text x="${GX + 24}" y="${y + 37}" font-size="21" font-weight="700" fill="${INK}">${esc(grp.title)}</text>`;
    grp.ids.forEach((id, i) => {
      const u = cfg.items[id];
      body += box(GX + PADL + (i % cols) * (BW + GAPX), y + 56 + Math.floor(i / cols) * (BH + GAPY), BW, BH, u, cfg.boxOpts(u));
    });
    mids[grp.key] = { top: y, mid: y + gh / 2, h: gh };
    y += gh + 28;
  }
  const bottom = y - 28 + 24;

  let head = `<rect x="${GX - 20}" y="36" width="${GW + 40}" height="${bottom - 36}" rx="26" fill="none" stroke="${LINE}" stroke-width="1.4" stroke-dasharray="9 7"/>`;
  const cxT = GX + GW / 2;
  const tagW = [...cfg.roleTag].length * 11 + 28;
  head += `<rect x="${cxT - tagW / 2}" y="54" width="${tagW}" height="26" rx="13" fill="${cfg.roleColor}"/><text x="${cxT}" y="72" font-size="13.5" font-weight="700" fill="#FFFFFF" text-anchor="middle" letter-spacing="1.5">${esc(cfg.roleTag)}</text>`;
  head += `<text x="${cxT}" y="112" font-size="25" font-weight="700" fill="${INK}" text-anchor="middle">${esc(cfg.title)}</text>`;
  head += `<text x="${cxT}" y="137" font-size="15.5" fill="${MUTED}" text-anchor="middle">${esc(cfg.subtitle)}</text>`;

  let actors = '';
  const keys = cfg.left.keys;
  const ly0 = (mids[keys[0]].top + mids[keys[keys.length - 1]].top + mids[keys[keys.length - 1]].h) / 2;
  for (const k of keys) actors += `<line x1="156" y1="${ly0}" x2="${GX}" y2="${mids[k].mid}" stroke="${LINE}" stroke-width="1.5"/>`;
  actors += stick(112, ly0, cfg.left.name);
  for (const [k, list] of Object.entries(cfg.right)) {
    list.forEach((a, i) => {
      const ay = list.length === 1 ? mids[k].mid : mids[k].top + (i === 0 ? 130 : mids[k].h - 110);
      actors += `<line x1="${GX + GW}" y1="${ay}" x2="${a.person ? RX + RW / 2 - 38 : RX}" y2="${ay}" stroke="${LINE}" stroke-width="1.5"${a.faded ? ' opacity="0.4"' : ''}/>`;
      actors += a.person ? stick(RX + RW / 2, ay + 6, a.name, a.faded) : sysActor(RX, ay - 32, RW, a.name, a.faded);
    });
  }

  let yy = bottom + 34;
  let lg = swatches(150, yy, cfg.legend);
  for (const note of cfg.notes ?? []) { yy += 40; lg += note(150, yy); }
  yy += 50;
  let extra = '';
  if (cfg.extra) { const e = cfg.extra(yy + 10, W); extra = e.svg; yy += 10 + e.h + 20; }
  return open(W, yy + 10, cfg.title, n) + head + actors + body + lg + extra + '</svg>';
}

const thickNote = (color, text) => (x, y) => `<rect x="${x}" y="${y}" width="22" height="22" rx="5" fill="#FFFFFF" stroke="${color}" stroke-width="3.6"/><text x="${x + 32}" y="${y + 17}" font-size="17" fill="${MUTED}">${esc(text)}</text>`;
const textNote = (text) => (x, y) => `<text x="${x}" y="${y + 17}" font-size="16" fill="${MUTED}">${esc(text)}</text>`;
const badgeOf = (d) => (typeof d === 'number' && d > 0 ? d : d === 'pre' ? '사전' : d === 'bg' ? '배경' : null);

/* ───────────── ① 프론트 (catalog.mjs 그대로) ───────────── */
const feItems = Object.fromEntries(FE_UC.map((u) => [u.id, u]));
const feDemo = FE_UC.filter((u) => u.demo).length;
const frontend = () => sheet({
  n: 10, roleTag: 'FRONTEND', roleColor: '#4D7C0F',
  title: 'ShadowFit 유스케이스 — 프론트엔드', subtitle: `상태 = 앱 화면 + API 연동 여부 · 코드 실사 · 총 ${FE_UC.length}개`,
  groups: FE_GROUPS, items: feItems,
  left: { name: '회원', keys: ['A', 'B', 'C', 'E'] },
  right: { B: [{ name: 'AI 서버' }], C: [{ name: 'LLM (Gemini)' }], E: [{ name: '모임 친구', person: true }, { name: 'Expo Push' }], G: [{ name: '관리자', person: true }], S: [{ name: '스케줄러' }] },
  boxOpts: (u) => ({ thick: !!u.demo, alert: !!u.blocker, badge: badgeOf(u.demo) }),
  legend: [['done', '완료'], ['partial', '부분'], ['noscreen', '화면 없음'], ['planned', '계획 · 목업'], ['backend', '앱 밖 (백엔드)']],
  notes: [thickNote(INK, `굵은 테두리 = 시연 필수 (${feDemo}개) · 숫자 = 누르는 순서 · «사전» 은 미리 준비, «배경» 은 뒤에서 돈다`), thickNote(ALERT, '붉은 굵은 테두리 = 시연 필수인데 아직 미완 (B-04 음성 미연결 · C-01 본문 목업)')],
});

/* ───────────── ② 백엔드 (담당자 그림 3장을 한 장으로) ───────────── */
const beRaw = [
  ['A-01', '회원가입', 'done', 1], ['A-02', '로그인', 'done', 1], ['A-03', '온보딩', 'done', 1], ['A-05', 'TTS 설정', 'done'], ['A-06', '푸시 토큰', 'noscreen'],
  ['B-01', '운동 세션', 'partial', 1], ['B-04', 'TTS 피드백', 'noscreen', 1, 1], ['B-02', '이어하기', 'done'], ['B-03', '타임아웃', 'done'],
  ['C-01', '세션 리포트', 'noscreen', 1, 1], ['C-02', '캘린더', 'done'], ['C-03', '주간 요약', 'done'], ['C-04', '주간 AI 총평', 'noscreen'], ['C-05', '일일 메모', 'done'], ['C-06', '스트릭 카드', 'noscreen'],
  ['D-01', '목표', 'noscreen'], ['D-02', '패턴 분석', 'noscreen'], ['D-03', '추천', 'noscreen'],
  ['E-01', '모임 만들기', 'done'], ['E-02', '코드로 참여', 'done', 1], ['E-04', '모임 상세', 'done', 1], ['E-05', '친구 현황', 'done', 1], ['E-06', '재촉', 'partial', 1], ['E-07', '피드 자동 글', 'done', 1],
  ['E-08', '리액션', 'done'], ['E-09', '모임 캘린더', 'done'], ['F-01', '알림함', 'done', 1], ['F-02', '푸시 발송', 'noscreen'],
  ['G-01/02', '카탈로그', 'backend'], ['G-03', '기준 영상', 'backend', 1], ['G-05', '임계값', 'backend'],
  ['S-01', '아웃박스', 'done', 1], ['S-04', '장애 복구', 'done'],
];
const beItems = Object.fromEntries(beRaw.map(([id, name, status, demo, blocker]) => [id, { id, name, status, demo: !!demo, blocker: !!blocker }]));
const beGroups = [
  { key: 'A', title: '계정', ids: ['A-01', 'A-02', 'A-03', 'A-05', 'A-06'] },
  { key: 'B', title: '① 교정, 실시간 운동', ids: ['B-01', 'B-04', 'B-02', 'B-03'] },
  { key: 'C', title: '② 기록, 리포트', ids: ['C-01', 'C-02', 'C-03', 'C-04', 'C-05', 'C-06', 'D-01', 'D-02', 'D-03'] },
  { key: 'E', title: '③ 지속, 모임과 알림', ids: ['E-01', 'E-02', 'E-04', 'E-05', 'E-06', 'E-07', 'E-08', 'E-09', 'F-01', 'F-02'] },
  { key: 'G', title: '운영, Swagger 증빙', ids: ['G-01/02', 'G-03', 'G-05'] },
  { key: 'S', title: '시스템', ids: ['S-01', 'S-04'] },
];

function relPanel(y0, W, n, title, nodes, edges, items, H) {
  const N = Object.fromEntries(nodes.map((d) => [d.id, { rx: 132, ry: 52, ...d }]));
  const pt = (c, tx, ty) => { const dx = tx - c.x, dy = ty - c.y; const t = 1 / Math.sqrt((dx / (c.rx + 5)) ** 2 + (dy / (c.ry + 5)) ** 2); return [c.x + dx * t, c.y + dy * t]; };
  let g = `<rect x="40" y="${y0}" width="${W - 80}" height="${H}" rx="18" fill="${PANEL}" stroke="${LINE}" stroke-width="1.2"/><text x="64" y="${y0 + 40}" font-size="21" font-weight="700" fill="${INK}">${esc(title)}</text>`;
  g += `<g transform="translate(0 ${y0 + 30})">`;
  for (const e of edges) {
    const a = N[e.a], b = N[e.b];
    const [x1, y1] = pt(a, b.x, b.y), [x2, y2] = pt(b, a.x, a.y);
    g += `<line x1="${x1.toFixed(1)}" y1="${y1.toFixed(1)}" x2="${x2.toFixed(1)}" y2="${y2.toFixed(1)}" stroke="${EDGE}" stroke-width="1.7" stroke-dasharray="9 6" marker-end="url(#o${n})"/>`;
    if (e.label) g += `<text x="${(x1 + (x2 - x1) * (e.t ?? 0.5) + (e.dx ?? 0)).toFixed(1)}" y="${(y1 + (y2 - y1) * (e.t ?? 0.5) + (e.dy ?? -8)).toFixed(1)}" font-size="15" fill="${MUTED}" text-anchor="middle" paint-order="stroke" stroke="${PANEL}" stroke-width="6">${esc(e.label)}</text>`;
  }
  for (const d of Object.values(N)) {
    const u = items[d.id], s = ST[u.status];
    g += `<ellipse cx="${d.x}" cy="${d.y}" rx="${d.rx}" ry="${d.ry}" fill="${s.fill}" stroke="${u.blocker ? ALERT : s.stroke}" stroke-width="${u.blocker ? 4 : 1.4}"/><text x="${d.x}" y="${d.y - 7}" font-size="15" fill="${s.text}" text-anchor="middle">${esc(u.id)}</text><text x="${d.x}" y="${d.y + 18}" font-size="19.5" font-weight="700" fill="${s.text}" text-anchor="middle">${esc(d.name ?? u.name)}</text>`;
  }
  return { svg: g + '</g>', h: H };
}

const backend = () => sheet({
  n: 11, roleTag: 'BACKEND', roleColor: '#3B5B92',
  title: 'ShadowFit 유스케이스 — 백엔드', subtitle: `담당자 자료(전체도 · B-01 관계도 · 시연 경로)를 한 장으로 · 총 ${beRaw.length}개`,
  groups: beGroups, items: beItems,
  left: { name: '회원', keys: ['A', 'B', 'C', 'E'] },
  right: { B: [{ name: 'AI 서버' }], C: [{ name: 'LLM (Gemini)' }], E: [{ name: 'Expo Push' }], G: [{ name: '관리자', person: true }], S: [{ name: '스케줄러' }] },
  boxOpts: (u) => ({ thick: u.demo, alert: u.blocker }),
  legend: [['done', '완료'], ['partial', '부분'], ['noscreen', '화면 없음'], ['backend', '백엔드 증빙']],
  notes: [thickNote(INK, `굵은 테두리 = 시연 필수 (${beRaw.filter((r) => r[3]).length}개)`), thickNote(ALERT, '붉은 굵은 테두리 = 없으면 시연 끊김 (B-04 · C-01)')],
  extra: (y0, W) => relPanel(y0, W, 11, 'B-01 운동 세션 관계', [
    { id: 'B-04', x: 210, y: 130 }, { id: 'B-02', x: 520, y: 130 }, { id: 'B-03', x: 830, y: 130 }, { id: 'S-04', x: 1140, y: 130, name: '워커 재부착' },
    { id: 'A-03', x: 200, y: 330 }, { id: 'G-03', x: 200, y: 470 }, { id: 'B-01', x: 680, y: 400, rx: 190, ry: 70 }, { id: 'S-01', x: 1150, y: 400 },
    { id: 'C-01', x: 430, y: 660 }, { id: 'E-07', x: 920, y: 660 }, { id: 'E-08', x: 920, y: 810 },
  ], [
    { a: 'B-04', b: 'B-01' }, { a: 'B-02', b: 'B-01', label: '«extend»', t: 0.5, dx: 60 }, { a: 'B-03', b: 'B-01' }, { a: 'S-04', b: 'B-01' },
    { a: 'A-03', b: 'B-01', label: '사전조건', t: 0.5, dy: 30 }, { a: 'G-03', b: 'B-01' },
    { a: 'B-01', b: 'S-01', label: '«include»', dy: -12 },
    { a: 'B-01', b: 'C-01', label: '사전조건', dx: -56 }, { a: 'B-01', b: 'E-07', label: 'trigger, 비동기', dx: 76 }, { a: 'E-08', b: 'E-07', label: '«extend»', dx: 50, dy: 5 },
  ], beItems, 910),
});

/* ───────────── ③ AI (담당자 PDF UC-01~09) ───────────── */
const aiRaw = [
  ['UC-01', '회원가입 및 로그인', 'done', '필수 · 구현'], ['UC-02', '운동 종목 선택', 'done', '필수 · 구현'], ['UC-03', '촬영 환경 점검', 'partial', '필수 · 부분 구현'],
  ['UC-04', '스쿼트 횟수 측정', 'verify', '핵심 · 구현/검증 중'], ['UC-05', '전방 런지 횟수 측정', 'verify', '핵심 · 구현/검증 중'], ['UC-06', '실시간 자세 피드백', 'partial', '핵심 · 깊이 피드백 구현'],
  ['UC-07', '운동 종료 및 리포트', 'done', '필수 · 구현'], ['UC-08', '목표와 연속 기록 관리', 'planned', '확장 단계'], ['UC-09', '친구에게 기록 공유', 'planned', '확장 단계'],
  ['—', '분석 기준 관리', 'planned', '전체 구조도에만 있음'],
];
const aiItems = Object.fromEntries(aiRaw.map(([id, name, status, tag]) => [id === '—' ? 'X' : id, { id, name, status, tag }]));
function aiFlow(y0, W) {
  const H = 500;
  let g = `<rect x="40" y="${y0}" width="${W - 80}" height="${H}" rx="18" fill="${PANEL}" stroke="${LINE}" stroke-width="1.2"/><text x="64" y="${y0 + 40}" font-size="21" font-weight="700" fill="${INK}">핵심 시나리오 — 한 번의 반복이 횟수가 되기까지</text>`;
  const steps = ['운동 선택', '촬영 환경 점검', '선 자세 보정 (2초)', '프레임별 관절 추적'];
  const bw = 250, gap = 50, x0 = (W - (4 * bw + 3 * gap)) / 2, by = y0 + 76;
  steps.forEach((t, i) => {
    const x = x0 + i * (bw + gap);
    g += `<rect x="${x}" y="${by}" width="${bw}" height="58" rx="12" fill="#E1F0F5" stroke="#1F6F8B" stroke-width="1.4"/><text x="${x + bw / 2}" y="${by + 36}" font-size="18" font-weight="600" fill="#15566D" text-anchor="middle">${t}</text>`;
    if (i < 3) g += `<line x1="${x + bw + 4}" y1="${by + 29}" x2="${x + bw + gap - 4}" y2="${by + 29}" stroke="${EDGE}" stroke-width="1.8" marker-end="url(#s12)"/>`;
  });
  const dx = W / 2, dy = by + 150;
  g += `<line x1="${x0 + 3 * (bw + gap) + bw / 2}" y1="${by + 60}" x2="${dx + 150}" y2="${dy - 34}" stroke="${EDGE}" stroke-width="1.8" marker-end="url(#s12)"/>`;
  g += `<rect x="${dx - 160}" y="${dy - 32}" width="320" height="64" rx="14" fill="${INK}"/><text x="${dx}" y="${dy + 7}" font-size="19" font-weight="700" fill="#FFFFFF" text-anchor="middle">완전한 운동 주기인가?</text>`;
  const outs = [['done', '정상 완료', '횟수 +1 · 완료 안내 1회', '예'], ['partial', '깊이 미확인', '횟수 제외 · 관찰 결과 안내', '깊이 부족'], ['verify', '관절 가림', '판정 보류 · 촬영 위치 안내', '추적 불가']];
  outs.forEach(([k, t, sub, cond], i) => {
    const s = ST[k], ox = dx + (i - 1) * 400, oy = dy + 130;
    g += `<line x1="${dx + (i - 1) * 90}" y1="${dy + 34}" x2="${ox}" y2="${oy - 4}" stroke="${EDGE}" stroke-width="1.8" marker-end="url(#s12)"/>`;
    g += `<text x="${(dx + (i - 1) * 90 + ox) / 2 + (i === 1 ? 58 : (i - 1) * 46)}" y="${dy + 84}" font-size="15" font-weight="600" fill="${s.text}" text-anchor="middle" paint-order="stroke" stroke="${PANEL}" stroke-width="6">${cond}</text>`;
    g += `<rect x="${ox - 160}" y="${oy}" width="320" height="76" rx="14" fill="${s.fill}" stroke="${s.stroke}" stroke-width="1.5"/><text x="${ox}" y="${oy + 32}" font-size="19" font-weight="700" fill="${s.text}" text-anchor="middle">${t}</text><text x="${ox}" y="${oy + 57}" font-size="15" fill="${s.text}" text-anchor="middle">${sub}</text>`;
  });
  g += `<text x="${W / 2}" y="${y0 + H - 26}" font-size="15.5" fill="${MUTED}" text-anchor="middle">제한 촬영 조건 — 세로 고정 · 정면 기준 약 45° · 1080p 30fps 권장(AI 분석 3fps) · 머리~발목 전신 · 시작/종료 선 자세 2초</text>`;
  return { svg: g, h: H };
}
const ai = () => sheet({
  n: 12, cols: 2, BW: 404, roleTag: 'AI', roleColor: '#0E7490',
  title: 'ShadowFit 유스케이스 — AI', subtitle: '담당자 «유스케이스 명세서» (UC-01~09) · 오른쪽 글씨 = 우선순위 · 현재 단계',
  groups: [
    { key: 'P', title: '기본 사용 준비', ids: ['UC-01', 'UC-02', 'UC-03'] },
    { key: 'K', title: '핵심 운동 분석', ids: ['UC-04', 'UC-05', 'UC-06'] },
    { key: 'R', title: '기록과 지속 동기', ids: ['UC-07', 'UC-08', 'UC-09'] },
    { key: 'O', title: '운영 (확장)', ids: ['X'] },
  ],
  items: aiItems,
  left: { name: '운동 사용자', keys: ['P', 'K', 'R'] },
  right: { K: [{ name: '카메라 / AI' }], R: [{ name: '친구 사용자', person: true }], O: [{ name: '관리자', person: true }] },
  boxOpts: (u) => ({ tag: u.tag }),
  legend: [['done', '구현'], ['verify', '구현 · 검증 중'], ['partial', '부분 구현'], ['planned', '확장 단계']],
  notes: [textNote('핵심 가치 — 정확한 횟수 기록 + 즉시 이해할 수 있는 피드백 + 운동 지속 동기 · 안전 범위에 들어온 완전한 반복만 센다')],
  extra: aiFlow,
});

/* ───────────── ④ 통합 ───────────── */
// [id, 이름, F, B, A, 시연, blocker, AI 열 ＊(main 미반영)]  — F·B 는 코드 실사, A 는 담당자 보고
const N_ = null;
const intRaw = [
  ['A-01', '회원가입', 'done', 'done', N_, 1], ['A-02', '로그인 · 로그아웃', 'done', 'done', N_, 2], ['A-03', '온보딩', 'done', 'done', N_, 3],
  ['A-04', '비밀번호 재설정', 'planned', 'noscreen', N_], ['A-05', 'TTS 설정', 'done', 'done', N_], ['A-06', '푸시 토큰 등록', 'noscreen', 'done', N_], ['A-07', '회원 탈퇴', 'noscreen', 'done', N_],

  ['B-07', '운동 종목 선택', 'noscreen', 'noscreen', 'done', 0, 0, 'A'], ['B-08', '촬영 환경 점검', 'partial', N_, 'partial'], ['B-01', '운동 세션', 'partial', 'done', 'done', 4],
  ['B-09', '스쿼트 횟수 측정', 'done', 'done', 'verify', 5], ['B-05', '런지 횟수 측정', 'planned', 'partial', 'verify', 0, 0, 'A'], ['B-04', '피드백 · TTS', 'partial', 'done', 'partial', 6, 1],
  ['B-02', '이어하기', 'done', 'done', 'done'], ['B-03', '세션 타임아웃', 'done', 'done', N_], ['B-06', '운동 세트', 'planned', 'planned', 'planned'],

  ['C-01', '세션 리포트', 'partial', 'done', 'done', 7, 1], ['C-02', '캘린더 · 일별 기록', 'done', 'done', N_], ['C-03', '주간 요약', 'done', 'done', N_],
  ['C-04', '주간 AI 총평', 'noscreen', 'done', N_], ['C-05', '일일 메모', 'noscreen', 'done', N_], ['C-06', '스트릭 카드', 'noscreen', 'done', N_],
  ['D-01', '운동 목표', 'noscreen', 'done', N_], ['D-02', '패턴 분석', 'noscreen', 'done', N_], ['D-03', '다음 세션 추천', 'noscreen', 'done', N_],

  ['E-01', '모임 만들기', 'done', 'done', N_], ['E-02', '코드로 참여', 'done', 'done', N_, 8], ['E-03', '초대 (회원 지정)', 'partial', 'done', N_],
  ['E-04', '모임 상세', 'done', 'done', N_, 9], ['E-05', '친구 현황', 'done', 'done', N_, 11], ['E-06', '재촉하기', 'done', 'done', N_, 12],
  ['E-10', '응원 보내기', 'done', 'done', N_, 13], ['E-07', '피드 자동 글', 'done', 'done', N_, 10], ['E-08', '리액션', 'done', 'done', N_],
  ['E-09', '모임 출석 캘린더', 'done', 'done', N_], ['E-11', '코드 공유 · 재발급', 'done', 'done', N_], ['E-12', '모임 탈퇴', 'done', 'done', N_],
  ['E-13', '그룹장 양도', 'noscreen', 'done', N_], ['F-01', '알림함', 'done', 'done', N_, 14], ['F-02', '푸시 수신', 'noscreen', 'done', N_], ['F-03', '실시간 수신 (소켓)', 'noscreen', 'done', N_],

  ['G-01', '종목 · 카테고리 관리', N_, 'done', N_], ['G-03', '기준 영상 등록', N_, 'done', 'done', 'pre'], ['G-04', '분석 지원 토글', N_, 'done', N_],
  ['G-05', '임계값 (분석 기준)', N_, 'done', N_], ['G-06', '회원 · 세션 · 통계', N_, 'done', N_],
  ['S-01', '아웃박스 발행', N_, 'done', N_, 'bg'], ['S-02', '주간 리포트 생성', N_, 'done', N_], ['S-04', '장애 복구', N_, 'done', 'done'],
];
const intItems = Object.fromEntries(intRaw.map(([id, name, F, B, A, demo, blocker, star]) => {
  const roles = [F, B, A].filter(Boolean);
  const worst = roles.reduce((w, r) => (RANK[r] < RANK[w] ? r : w), roles[0]);
  return [id, { id, name, F, B, A, status: worst, demo: demo || 0, blocker: !!blocker, star: star ?? '' }];
}));
const intGroups = [
  { key: 'A', title: '계정', ids: ['A-01', 'A-02', 'A-03', 'A-04', 'A-05', 'A-06', 'A-07'] },
  { key: 'B', title: '① 교정 · 실시간 운동', ids: ['B-07', 'B-08', 'B-01', 'B-09', 'B-05', 'B-04', 'B-02', 'B-03', 'B-06'] },
  { key: 'C', title: '② 기록 · 리포트', ids: ['C-01', 'C-02', 'C-03', 'C-04', 'C-05', 'C-06', 'D-01', 'D-02', 'D-03'] },
  { key: 'E', title: '③ 지속 · 모임과 알림', ids: ['E-01', 'E-02', 'E-03', 'E-04', 'E-05', 'E-06', 'E-10', 'E-07', 'E-08', 'E-09', 'E-11', 'E-12', 'E-13', 'F-01', 'F-02', 'F-03'] },
  { key: 'G', title: '운영 (앱 화면 없음 · Swagger)', ids: ['G-01', 'G-03', 'G-04', 'G-05', 'G-06'] },
  { key: 'S', title: '시스템 (서버 내부)', ids: ['S-01', 'S-02', 'S-04'] },
];
function intPanel(y0, W) {
  const H = 392, mid = 600;
  let g = `<rect x="40" y="${y0}" width="${W - 80}" height="${H}" rx="18" fill="${PANEL}" stroke="${LINE}" stroke-width="1.2"/>`;
  g += `<text x="64" y="${y0 + 40}" font-size="20" font-weight="700" fill="${INK}">AI 명세서 번호 → 통합 ID</text>`;
  const map = [['UC-01 회원가입 및 로그인', 'A-01 · A-02'], ['UC-02 운동 종목 선택', 'B-07'], ['UC-03 촬영 환경 점검', 'B-08'], ['UC-04 스쿼트 횟수 측정', 'B-09 (B-01 세션 안에서)'], ['UC-05 전방 런지 횟수 측정', 'B-05'],
    ['UC-06 실시간 자세 피드백', 'B-04'], ['UC-07 운동 종료 및 리포트', 'B-01 종료 + C-01'], ['UC-08 목표와 연속 기록', 'D-01 · C-06'], ['UC-09 친구에게 기록 공유', 'E-07 · E-08'], ['분석 기준 관리', 'G-05']];
  map.forEach(([a, b], i) => { const yy = y0 + 74 + i * 30; g += `<text x="64" y="${yy}" font-size="15.5" fill="${MUTED}">${esc(a)}</text><text x="330" y="${yy}" font-size="15.5" font-weight="600" fill="${INK}">→  ${esc(b)}</text>`; });
  g += `<line x1="${mid}" y1="${y0 + 24}" x2="${mid}" y2="${y0 + H - 24}" stroke="${LINE}" stroke-width="1.2"/>`;
  g += `<text x="${mid + 28}" y="${y0 + 40}" font-size="20" font-weight="700" fill="${ALERT}">역할 사이에 어긋난 곳 4건 — 다음 회의에서 맞출 것</text>`;
  const diffs = [
    ['B-05 런지', 'AI «구현/검증 중» ↔ main 에는 스쿼트 분석기뿐 · 백엔드 분석 지원 꺼짐 · 앱에 고를 화면 없음'],
    ['B-07 종목 선택', 'AI «구현» ↔ 앱은 exerciseId=1(스쿼트) 고정 · 회원용 종목 목록 API 없음(관리자용만)'],
    ['B-08 촬영 점검', 'AI 는 «선 자세 2초 보정 · 가림 안내» 전제 ↔ 앱은 카메라 권한 + 가이드 문구뿐'],
    ['E-07 기록 공유', 'AI 안은 «골라서 게시 · 횟수/시간 공개» ↔ 구현은 «완료 시 자동 글 · 출석/연속일수만 공개»'],
  ];
  diffs.forEach(([k, t], i) => { const yy = y0 + 84 + i * 66; g += `<text x="${mid + 28}" y="${yy}" font-size="16.5" font-weight="700" fill="${INK}">${esc(k)}</text><text x="${mid + 28}" y="${yy + 25}" font-size="14.5" fill="${MUTED}">${esc(t)}</text>`; });
  g += `<circle cx="${mid + 37}" cy="${y0 + H - 27}" r="8" fill="#FFFFFF" stroke="${ALERT}" stroke-width="1.4"/><text x="${mid + 37}" y="${y0 + H - 21.5}" font-size="15" font-weight="700" fill="${ALERT}" text-anchor="middle">*</text><text x="${mid + 54}" y="${y0 + H - 22}" font-size="14.5" fill="${ALERT}">A 칸의 붉은 * = AI 담당자 보고로는 구현이지만 main 브랜치에 아직 없음</text>`;
  return { svg: g, h: H };
}
const indNote = (x, y) => {
  let g = '';
  [['F', 'done'], ['B', 'done'], ['A', 'verify']].forEach(([r, s], i) => { g += indicator(x + i * 26, y, r, s); });
  g += `<text x="${x + 90}" y="${y + 17}" font-size="17" fill="${MUTED}">칸 색 = 그 역할의 상태 (F 프론트 · B 백엔드 · A AI)</text>`;
  g += indicator(x + 560, y, '', null) + `<text x="${x + 592}" y="${y + 17}" font-size="17" fill="${MUTED}">그 역할이 할 일 없음</text>`;
  g += `<text x="${x + 800}" y="${y + 17}" font-size="17" fill="${MUTED}">박스 색 = 셋 중 가장 뒤처진 상태</text>`;
  return g;
};
const intDemo = intRaw.filter((r) => r[5]).length;
const integrated = () => sheet({
  n: 13, W: 1480, BW: 300, roleTag: 'INTEGRATED · FE + BE + AI', roleColor: INK,
  title: 'ShadowFit 유스케이스 — 통합', subtitle: `2026-09-21 3자 회의 · 세 역할의 유스케이스를 한 ID 체계로 · 총 ${intRaw.length}개`,
  groups: intGroups, items: intItems,
  left: { name: '회원', keys: ['A', 'B', 'C', 'E'] },
  right: { B: [{ name: '카메라 / AI 서버' }], C: [{ name: 'LLM (Gemini)' }], E: [{ name: '모임 친구', person: true }, { name: 'Expo Push' }], G: [{ name: '관리자', person: true }], S: [{ name: '스케줄러' }] },
  boxOpts: (u) => ({ ind: true, fs: 16, thick: !!u.demo, alert: u.blocker, badge: badgeOf(u.demo) }),
  legend: [['done', '완료'], ['verify', '구현 · 검증 중'], ['partial', '부분'], ['noscreen', '없음'], ['planned', '계획 · 확장']],
  notes: [indNote, thickNote(INK, `굵은 테두리 = 시연 필수 (${intDemo}개) · 숫자 = 누르는 순서`), thickNote(ALERT, '붉은 굵은 테두리 = 시연 필수인데 미완 — B-04 (앱 음성 미연결 · AI 는 깊이 피드백만) · C-01 (앱 본문 목업)')],
  extra: intPanel,
});

/* ───────────── 실행 ───────────── */
fs.mkdirSync(path.join(DIR, 'svg'), { recursive: true });
for (const [name, fn] of [['10-role-frontend', frontend], ['11-role-backend', backend], ['12-role-ai', ai], ['13-integrated', integrated]]) {
  const svg = fn();
  fs.writeFileSync(path.join(DIR, 'svg', `${name}.svg`), svg);
  console.log(name, svg.match(/viewBox="0 0 (\d+) (\d+)"/).slice(1).join('x'));
}
