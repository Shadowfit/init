// catalog.mjs → 그림 5장(svg/) + README.md (+ OUT_HTML 을 주면 한 장짜리 HTML)
//   node docs/usecase/build.mjs
// PNG 는 README «갱신 방법» 의 Edge 헤드리스 명령으로 svg 에서 뽑는다.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { UC, GROUPS, STATUS, ACTORS, SCREENS, UPDATED, BACKEND, BE_STATUS } from './catalog.mjs';

const DIR = path.dirname(fileURLToPath(import.meta.url));
const byId = Object.fromEntries(UC.map((u) => [u.id, u]));
const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

const FONT = "'Segoe UI','Malgun Gothic','Apple SD Gothic Neo','Noto Sans KR',sans-serif";
const MONO = "Consolas,'Cascadia Mono','D2Coding',monospace";
const INK = '#1F231B', MUTED = '#5C6355', LINE = '#C9CEC0', PANEL = '#FAFAF7', EDGE = '#9AA08F', ALERT = '#A83232';

const counts = Object.fromEntries(Object.keys(STATUS).map((k) => [k, UC.filter((u) => u.status === k).length]));
const demoSteps = UC.filter((u) => typeof u.demo === 'number').sort((a, b) => a.demo - b.demo);
const demoAll = UC.filter((u) => u.demo);
const blockers = UC.filter((u) => u.blocker);
const beOf = (u) => BACKEND[u.id] ?? ['na'];
const beCounts = Object.fromEntries(Object.keys(BE_STATUS).map((k) => [k, UC.filter((u) => beOf(u)[0] === k).length]));

/* ───────────── SVG 기본기 ───────────── */
function svgOpen(W, H, label, n) {
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${W} ${H}" width="${W}" height="${H}" role="img" aria-label="${esc(label)}" font-family="${FONT}">
<defs>
<marker id="open${n}" viewBox="0 0 12 12" refX="11" refY="6" markerWidth="13" markerHeight="13" orient="auto-start-reverse"><path d="M1,1 L11,6 L1,11" fill="none" stroke="${EDGE}" stroke-width="1.6"/></marker>
<marker id="solid${n}" viewBox="0 0 12 12" refX="10" refY="6" markerWidth="11" markerHeight="11" orient="auto-start-reverse"><path d="M1,1 L11,6 L1,11 Z" fill="${EDGE}"/></marker>
</defs>
<rect width="${W}" height="${H}" fill="#FFFFFF"/>
`;
}

function ucBox(x, y, w, h, u, { thick = false, faded = false, badge = null, fs = 17 } = {}) {
  const s = STATUS[u.status];
  const dash = s.dash && !thick ? ` stroke-dasharray="${s.dash}"` : '';
  let g = `<g${faded ? ' opacity="0.36"' : ''}>`;
  g += `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="10" fill="${s.fill}" stroke="${thick && u.blocker ? ALERT : s.stroke}" stroke-width="${thick ? 3.6 : 1.3}"${dash}/>`;
  g += `<text x="${x + 15}" y="${y + h / 2 + fs * 0.35}" font-size="${fs}" fill="${s.text}"><tspan font-weight="400">${u.id}</tspan><tspan dx="8" font-weight="${thick ? 700 : 600}">${esc(u.name)}</tspan></text>`;
  if (typeof badge === 'number') {
    g += `<circle cx="${x + w - 6}" cy="${y + 4}" r="14" fill="${INK}"/><text x="${x + w - 6}" y="${y + 9}" font-size="14" font-weight="700" fill="#FFFFFF" text-anchor="middle">${badge}</text>`;
  } else if (badge) {
    g += `<rect x="${x + w - 50}" y="${y - 11}" width="52" height="23" rx="11.5" fill="${INK}"/><text x="${x + w - 24}" y="${y + 5}" font-size="12.5" font-weight="700" fill="#FFFFFF" text-anchor="middle">${esc(badge)}</text>`;
  }
  return g + '</g>';
}

function stick(cx, cy, label, { faded = false, labelSize = 19 } = {}) {
  return `<g${faded ? ' opacity="0.36"' : ''} stroke="#4A4F44" stroke-width="2" fill="none">
<circle cx="${cx}" cy="${cy - 44}" r="19" fill="#FFFFFF"/><line x1="${cx}" y1="${cy - 25}" x2="${cx}" y2="${cy + 16}"/><line x1="${cx - 30}" y1="${cy - 6}" x2="${cx + 30}" y2="${cy - 6}"/><line x1="${cx}" y1="${cy + 16}" x2="${cx - 24}" y2="${cy + 54}"/><line x1="${cx}" y1="${cy + 16}" x2="${cx + 24}" y2="${cy + 54}"/>
<text x="${cx}" y="${cy + 84}" font-size="${labelSize}" font-weight="700" fill="${INK}" stroke="none" text-anchor="middle">${esc(label)}</text></g>`;
}

function sysActor(x, y, w, h, label, faded) {
  return `<g${faded ? ' opacity="0.36"' : ''}><rect x="${x}" y="${y}" width="${w}" height="${h}" rx="10" fill="#F0EEE8" stroke="#5F5D55" stroke-width="1.3"/><text x="${x + w / 2}" y="${y + h / 2 + 7}" font-size="19" font-weight="600" fill="#33322D" text-anchor="middle">${esc(label)}</text></g>`;
}

function legend(x, y, keys, n) {
  let g = '', cx = x;
  for (const k of keys) {
    const s = STATUS[k];
    const label = `${s.label} ${counts[k]}`;
    g += `<rect x="${cx}" y="${y}" width="22" height="22" rx="5" fill="${s.fill}" stroke="${s.stroke}" stroke-width="1.3"${s.dash ? ` stroke-dasharray="${s.dash}"` : ''}/><text x="${cx + 32}" y="${y + 17}" font-size="17" fill="${MUTED}">${esc(label)}</text>`;
    cx += 32 + label.length * 14.5 + 34;
  }
  return g;
}

/* ───────────── 그림 1 · 5 — 전체도 / 시연 경로 ───────────── */
function overview(mode, n) {
  const demo = mode === 'demo';
  const W = 1360, GX = 256, GW = 848, BW = 264, BH = 56, GAPX = 16, GAPY = 16, PADL = 12;
  const RIGHT = { B: ['AI 서버'], C: ['LLM (Gemini)'], E: ['모임 친구', 'Expo Push'], G: ['관리자'], H: ['트레이너'], S: ['스케줄러'] };
  const fadedActor = { 'LLM (Gemini)': true, 'Expo Push': true };

  let body = '', y = 132;
  const mids = {};
  for (const grp of GROUPS) {
    const rows = Math.ceil(grp.ids.length / 3);
    const gh = 56 + rows * BH + (rows - 1) * GAPY + 22;
    body += `<rect x="${GX}" y="${y}" width="${GW}" height="${gh}" rx="18" fill="${PANEL}" stroke="${LINE}" stroke-width="1.2"/>`;
    body += `<text x="${GX + 24}" y="${y + 37}" font-size="21" font-weight="700" fill="${INK}">${esc(grp.title)}</text>`;
    grp.ids.forEach((id, i) => {
      const u = byId[id];
      const bx = GX + PADL + (i % 3) * (BW + GAPX), by = y + 56 + Math.floor(i / 3) * (BH + GAPY);
      body += demo
        ? ucBox(bx, by, BW, BH, u, { thick: !!u.demo, faded: !u.demo, badge: typeof u.demo === 'number' ? u.demo : u.demo === 'pre' ? '사전' : u.demo === 'bg' ? '배경' : null })
        : ucBox(bx, by, BW, BH, u, { thick: !!u.blocker });
    });
    mids[grp.key] = { top: y, mid: y + gh / 2, h: gh };
    y += gh + 28;
  }
  const bottom = y - 28 + 24;

  // 시스템 경계 + 제목
  let head = `<rect x="236" y="36" width="888" height="${bottom - 36}" rx="26" fill="none" stroke="${LINE}" stroke-width="1.4" stroke-dasharray="9 7"/>`;
  head += `<text x="680" y="82" font-size="25" font-weight="700" fill="${INK}" text-anchor="middle">${demo ? 'ShadowFit 시연 경로' : 'ShadowFit 유스케이스 — 프론트 구현 상태'}</text>`;
  head += `<text x="680" y="110" font-size="15.5" fill="${MUTED}" text-anchor="middle">${demo
    ? `시연 필수 ${demoAll.length}개 · 숫자는 누르는 순서 · ${UPDATED} 코드 기준`
    : `상태 = 앱 화면 + API 연동 여부 · ${UPDATED} 코드 기준 · 총 ${UC.length}개`}</text>`;

  // 왼쪽 액터 — 회원
  const memberY = (mids.A.top + mids.E.top + mids.E.h) / 2;
  let actors = '';
  for (const k of ['A', 'B', 'C', 'E']) actors += `<line x1="156" y1="${memberY}" x2="${GX}" y2="${mids[k].mid}" stroke="${LINE}" stroke-width="1.5"/>`;
  actors += stick(112, memberY, '회원');

  // 오른쪽 액터
  for (const [k, list] of Object.entries(RIGHT)) {
    list.forEach((name, i) => {
      const ay = list.length === 1 ? mids[k].mid : mids[k].top + (i === 0 ? 130 : mids[k].h - 110);
      const faded = demo && fadedActor[name];
      const person = name === '관리자' || name === '모임 친구' || name === '트레이너';
      actors += `<line x1="${GX + GW}" y1="${ay}" x2="${person ? 1208 : 1156}" y2="${ay}" stroke="${LINE}" stroke-width="1.5"${faded ? ' opacity="0.4"' : ''}/>`;
      actors += person ? stick(1246, ay + 6, name, { faded }) : sysActor(1156, ay - 32, 184, 64, name, faded);
    });
  }

  // 범례
  let lg = legend(150, bottom + 34, ['done', 'partial', 'noscreen', 'planned', 'backend'], n);
  const ly = bottom + 78;
  if (demo) {
    lg += `<rect x="150" y="${ly}" width="22" height="22" rx="5" fill="#FFFFFF" stroke="${INK}" stroke-width="3.6"/><text x="182" y="${ly + 17}" font-size="17" fill="${MUTED}">굵은 테두리 = 시연 필수 (${demoAll.length}개)</text>`;
    lg += `<circle cx="521" cy="${ly + 11}" r="12" fill="${INK}"/><text x="521" y="${ly + 16}" font-size="13" font-weight="700" fill="#FFFFFF" text-anchor="middle">1</text><text x="542" y="${ly + 17}" font-size="17" fill="${MUTED}">누르는 순서 · «사전» 은 미리 준비, «배경» 은 뒤에서 돈다</text>`;
    lg += `<text x="1010" y="${ly + 17}" font-size="17" fill="${MUTED}" opacity="0.6">흐린 칸은 없어도 시연 성립</text>`;
  } else {
    lg += `<rect x="150" y="${ly}" width="22" height="22" rx="5" fill="#FFFFFF" stroke="#A83232" stroke-width="3.6"/><text x="182" y="${ly + 17}" font-size="17" fill="${MUTED}">굵은 테두리 = 시연 필수인데 아직 미완 — 없으면 시연이 끊긴다 (${blockers.map((b) => b.id).join(' · ')})</text>`;
  }
  const H = ly + 60;
  return svgOpen(W, H, demo ? 'ShadowFit 시연 경로' : 'ShadowFit 유스케이스 전체도', n) + head + actors + body + lg + '</svg>';
}

/* ───────────── 그림 2 — 화면 ↔ 유스케이스 맵 ───────────── */
function screenMap(n) {
  const W = 1360;
  const CH = 34, CG = 8;
  function panel(x, y, w, scr, cols = 1) {
    const rows = Math.max(1, Math.ceil((scr.ids.length || 1) / cols));
    const h = 70 + rows * CH + (rows - 1) * CG + 16;
    let g = `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="14" fill="${PANEL}" stroke="${LINE}" stroke-width="1.3"/>`;
    g += `<text x="${x + 16}" y="${y + 31}" font-size="19" font-weight="700" fill="${INK}">${esc(scr.title)}</text>`;
    g += `<text x="${x + 16}" y="${y + 53}" font-size="12.5" fill="${MUTED}" font-family="${MONO}">${esc(scr.file)}</text>`;
    const cw = (w - 24 - (cols - 1) * CG) / cols;
    if (!scr.ids.length) g += `<text x="${x + 16}" y="${y + 70 + 22}" font-size="14.5" fill="${MUTED}">${esc(scr.memo ?? '')}</text>`;
    scr.ids.forEach((id, i) => {
      g += ucBox(x + 12 + (i % cols) * (cw + CG), y + 70 + Math.floor(i / cols) * (CH + CG), cw, CH, byId[id], { fs: 14.5 });
    });
    return { g, h, x, y, w };
  }
  const arrow = (x1, y1, x2, y2, label, lx, ly, anchor = 'start') =>
    `<line x1="${x1}" y1="${y1}" x2="${x2}" y2="${y2}" stroke="${EDGE}" stroke-width="1.6" marker-end="url(#solid${n})"/><text x="${lx}" y="${ly}" font-size="14.5" fill="${MUTED}" text-anchor="${anchor}" paint-order="stroke" stroke="#FFFFFF" stroke-width="5">${esc(label)}</text>`;

  let out = `<text x="40" y="62" font-size="25" font-weight="700" fill="${INK}">화면 – 유스케이스 맵</text>`;
  out += `<text x="40" y="90" font-size="15.5" fill="${MUTED}">어느 화면 파일이 어떤 유스케이스를 담는가 · 붉은 칩 = 그 화면에 들어갈 자리(아직 없음) · frontend/app/ 기준</text>`;

  // 진입 흐름
  const e = SCREENS.entry, ey = 150;
  out += `<text x="40" y="${ey - 18}" font-size="15" font-weight="700" fill="${MUTED}" letter-spacing="1">진입</text>`;
  const login = panel(40, ey, 290, e[0]), onboard = panel(430, ey, 290, e[2]), reset = panel(750, ey, 290, e[1]), board = panel(1070, ey, 250, e[3]);
  const entryBottom = Math.max(login.h, onboard.h, reset.h, board.h) + ey;

  // 탭
  const ty = entryBottom + 100;
  out += `<text x="40" y="${ty - 18}" font-size="15" font-weight="700" fill="${MUTED}" letter-spacing="1">하단 탭 5개</text>`;
  const tabs = SCREENS.tabs.map((s, i) => panel(40 + i * 260, ty, 240, s));
  const tabsBottom = Math.max(...tabs.map((t) => t.h)) + ty;

  // 스택
  const sy = tabsBottom + 100;
  out += `<text x="40" y="${sy - 18}" font-size="15" font-weight="700" fill="${MUTED}" letter-spacing="1">탭 위에 쌓이는 화면</text>`;
  const noti = panel(40, sy, 240, SCREENS.stack[0]), report = panel(300, sy, 240, SCREENS.stack[1]), group = panel(560, sy, 760, SCREENS.stack[2], 3);
  const stackBottom = Math.max(noti.h, report.h, group.h) + sy;

  // 화살표 (패널 아래에 깔리도록 먼저 그린다)
  let arrows = '';
  arrows += arrow(332, ey + 50, 427, ey + 50, '미완료 시', 380, ey + 38, 'middle');
  arrows += arrow(575, ey + onboard.h, 575, ty - 2, '완료 → 메인 탭', 587, (ey + onboard.h + ty) / 2 + 5);
  arrows += arrow(246, ty + tabs[0].h, 246, sy - 2, '종 아이콘', 234, (ty + tabs[0].h + sy) / 2 - 8, 'end');
  arrows += arrow(272, ty + tabs[0].h, 420, sy - 2, '날짜 → 세션 카드', 362, (ty + tabs[0].h + sy) / 2 - 6);
  arrows += arrow(940, ty + tabs[3].h, 940, sy - 2, '모임 카드 · 만들기/참여 직후', 952, (ty + tabs[3].h + sy) / 2 + 5);

  const panels = [login, onboard, reset, board, ...tabs, noti, report, group].map((p) => p.g).join('');
  const lg = legend(40, stackBottom + 44, ['done', 'partial', 'noscreen', 'planned'], n);
  const H = stackBottom + 100;
  return svgOpen(W, H, '화면과 유스케이스 맵', n) + out + arrows + panels + lg + '</svg>';
}

/* ───────────── 그림 3 · 4 — 관계도 ───────────── */
function relFigure({ W, H, title, subtitle, nodes, edges, n }) {
  const N = Object.fromEntries(nodes.map((d) => [d.id, { rx: 132, ry: 52, ...d }]));
  const edgePoint = (c, tx, ty, pad = 5) => {
    const dx = tx - c.x, dy = ty - c.y;
    const t = 1 / Math.sqrt((dx / (c.rx + pad)) ** 2 + (dy / (c.ry + pad)) ** 2);
    return [c.x + dx * t, c.y + dy * t];
  };
  let g = `<text x="40" y="62" font-size="25" font-weight="700" fill="${INK}">${esc(title)}</text><text x="40" y="90" font-size="15.5" fill="${MUTED}">${esc(subtitle)}</text>`;
  for (const e of edges) {
    const a = N[e.a], b = N[e.b];
    const [x1, y1] = edgePoint(a, b.x, b.y), [x2, y2] = edgePoint(b, a.x, a.y);
    g += `<line x1="${x1.toFixed(1)}" y1="${y1.toFixed(1)}" x2="${x2.toFixed(1)}" y2="${y2.toFixed(1)}" stroke="${EDGE}" stroke-width="1.7" stroke-dasharray="9 6" marker-end="url(#open${n})"/>`;
    if (e.label) {
      const t = e.t ?? 0.5;
      const lx = x1 + (x2 - x1) * t + (e.dx ?? 0), ly = y1 + (y2 - y1) * t + (e.dy ?? -8);
      g += `<text x="${lx.toFixed(1)}" y="${ly.toFixed(1)}" font-size="15" fill="${MUTED}" text-anchor="middle" paint-order="stroke" stroke="#FFFFFF" stroke-width="6">${esc(e.label)}</text>`;
    }
  }
  for (const d of Object.values(N)) {
    const u = byId[d.id], s = STATUS[u.status];
    const thick = !!u.blocker;
    g += `<ellipse cx="${d.x}" cy="${d.y}" rx="${d.rx}" ry="${d.ry}" fill="${s.fill}" stroke="${thick ? ALERT : s.stroke}" stroke-width="${thick ? 4 : 1.4}"${s.dash ? ` stroke-dasharray="${s.dash}"` : ''}/>`;
    g += `<text x="${d.x}" y="${d.y - 7}" font-size="15" fill="${s.text}" text-anchor="middle">${u.id}</text>`;
    g += `<text x="${d.x}" y="${d.y + 18}" font-size="${u.name.length > 10 ? 17 : 19.5}" font-weight="700" fill="${s.text}" text-anchor="middle">${esc(u.name)}</text>`;
  }
  g += legend(40, H - 52, ['done', 'partial', 'noscreen', 'planned', 'backend'], n);
  g += `<text x="${W - 40}" y="${H - 35}" font-size="15" fill="${MUTED}" text-anchor="end">점선 화살표 = 의존 방향 · 굵은 테두리 = 시연 필수인데 미완</text>`;
  return svgOpen(W, H, title, n) + g + '</svg>';
}

const sessionRel = (n) => relFigure({
  n, W: 1360, H: 1130,
  title: '운동 세션 관계도 — B-01 중심',
  subtitle: '세션 하나가 무엇을 전제로 하고, 무엇으로 확장되고, 끝나면 무엇을 일으키는가',
  nodes: [
    { id: 'B-04', x: 190, y: 200 }, { id: 'B-02', x: 510, y: 200 }, { id: 'B-03', x: 830, y: 200 }, { id: 'S-04', x: 1150, y: 200, rx: 150 },
    { id: 'A-03', x: 180, y: 430 }, { id: 'G-03', x: 180, y: 570 },
    { id: 'B-01', x: 670, y: 500, rx: 200, ry: 74 },
    { id: 'S-01', x: 1180, y: 430 }, { id: 'B-05', x: 1180, y: 590 }, { id: 'B-06', x: 1180, y: 730 },
    { id: 'C-01', x: 220, y: 830 }, { id: 'C-02', x: 550, y: 860, rx: 150 }, { id: 'E-07', x: 890, y: 830 },
    { id: 'E-08', x: 890, y: 990 },
  ],
  edges: [
    { a: 'B-04', b: 'B-01', label: '«extend» 결함이 오면', t: 0.62, dx: -96, dy: 8 },
    { a: 'B-02', b: 'B-01', label: '«extend» 끊겼다 돌아오면', t: 0.22, dx: -104, dy: 4 },
    { a: 'B-03', b: 'B-01', label: '«extend» 방치되면', t: 0.4, dx: 72 },
    { a: 'S-04', b: 'B-01', label: '«extend» AI 워커가 죽으면', t: 0.4, dx: 96 },
    { a: 'A-03', b: 'B-01', label: '사전조건', t: 0.45 },
    { a: 'G-03', b: 'B-01', label: '사전조건', t: 0.45, dy: 22 },
    { a: 'B-01', b: 'S-01', label: '«include» 종료 신호', t: 0.5, dy: -12 },
    { a: 'B-05', b: 'B-01', label: '«extend» 계획', t: 0.5, dy: -12 },
    { a: 'B-06', b: 'B-01', label: '«extend» 계획', t: 0.22, dx: -6, dy: 32 },
    { a: 'B-01', b: 'C-01', label: '완료가 사전조건', t: 0.5, dx: -64 },
    { a: 'B-01', b: 'C-02', label: '집계에 반영', t: 0.55, dx: -52 },
    { a: 'B-01', b: 'E-07', label: 'trigger · 비동기', t: 0.55, dx: 66 },
    { a: 'E-08', b: 'E-07', label: '«extend»', t: 0.5, dx: 46, dy: 5 },
  ],
});

const socialRel = (n) => relFigure({
  n, W: 1360, H: 1250,
  title: '모임 · 알림 관계도 — E · F',
  subtitle: '들어오는 길 셋 → 모임 상세가 품는 것 → 재촉 · 응원이 알림으로 닿는 세 갈래',
  nodes: [
    { id: 'E-01', x: 300, y: 190 }, { id: 'E-02', x: 680, y: 190 }, { id: 'E-03', x: 1060, y: 190 },
    { id: 'E-12', x: 190, y: 340 }, { id: 'E-13', x: 190, y: 470 },
    { id: 'E-04', x: 680, y: 400, rx: 190, ry: 72 },
    { id: 'E-11', x: 1170, y: 400, rx: 150 },
    { id: 'E-05', x: 300, y: 620 }, { id: 'E-09', x: 680, y: 640 }, { id: 'E-07', x: 1060, y: 620 },
    { id: 'E-06', x: 160, y: 810, rx: 125 }, { id: 'E-10', x: 440, y: 810, rx: 125 },
    { id: 'E-08', x: 870, y: 810, rx: 116 }, { id: 'B-01', x: 1232, y: 810, rx: 108 },
    { id: 'F-01', x: 270, y: 1010 },
    { id: 'F-03', x: 745, y: 945, rx: 140 }, { id: 'F-02', x: 745, y: 1100 },
    { id: 'S-01', x: 1090, y: 975 }, { id: 'A-06', x: 1110, y: 1118 },
  ],
  edges: [
    { a: 'E-01', b: 'E-04', label: '만든 직후 이동', t: 0.45, dx: -66 },
    { a: 'E-02', b: 'E-04', label: '사전조건 (멤버여야 열림)', t: 0.45, dx: 96 },
    { a: 'E-03', b: 'E-04', label: '수락하면 멤버', t: 0.45, dx: 62 },
    { a: 'E-12', b: 'E-04', label: '«extend»', t: 0.5, dy: -10 },
    { a: 'E-13', b: 'E-04', label: '«extend»', t: 0.5, dy: 22 },
    { a: 'E-13', b: 'E-12', label: '그룹장은 양도 먼저', t: 0.5, dx: -84, dy: 5 },
    { a: 'E-11', b: 'E-04', label: '«extend»', t: 0.5, dy: -10 },
    { a: 'E-04', b: 'E-05', label: '«include»', t: 0.55, dx: -46 },
    { a: 'E-04', b: 'E-09', label: '«include»', t: 0.5, dx: 46, dy: 5 },
    { a: 'E-04', b: 'E-07', label: '«include»', t: 0.55, dx: 46 },
    { a: 'E-06', b: 'E-05', label: '«extend» 오늘 안 한 친구', t: 0.45, dx: -92 },
    { a: 'E-10', b: 'E-05', label: '«extend» 누구에게나', t: 0.45, dx: 80 },
    { a: 'E-08', b: 'E-07', label: '«extend»', t: 0.45, dx: -44 },
    { a: 'B-01', b: 'E-07', label: 'trigger · 비동기', t: 0.45, dx: 66 },
    { a: 'E-06', b: 'F-01', label: 'trigger · 항상 저장', t: 0.5, dx: -80 },
    { a: 'E-10', b: 'F-01', label: 'trigger', t: 0.5, dx: 38 },
    { a: 'F-03', b: 'F-01', label: '«extend» 접속 중이면', t: 0.5, dy: -12 },
    { a: 'F-02', b: 'F-01', label: '«extend» 기기 등록 시', t: 0.5, dy: 26 },
    { a: 'F-02', b: 'S-01', label: '«include»', t: 0.5, dx: -34, dy: -8 },
    { a: 'A-06', b: 'F-02', label: '사전조건', t: 0.5, dy: 24 },
    { a: 'E-07', b: 'S-01', label: '«include» 팬아웃', t: 0.93, dx: 78, dy: 2 },
  ],
});

/* ───────────── README.md ───────────── */
const md = (s) => String(s);
function readme() {
  const L = [];
  L.push(`# ShadowFit 유스케이스`, '');
  L.push(`기준: ${UPDATED} 코드 · 총 ${UC.length}개. 이 저장소의 **유스케이스 정본**이다 — 2026-09-23 에 백엔드 관점 문서([\`../USE-CASES.md\`](../USE-CASES.md))를 여기로 합쳤다.`, '');
  L.push(`상태는 두 축이다. **앱** 축은 «앱 화면 + API 연동» 기준이라 백엔드 API 가 있어도 화면이 없으면 «화면 없음» 으로 센다 — 그림 색과 시연 판정은 이 축이다. **백엔드** 축은 서버 API · 내부 동작이 있는지다. 두 축이 다른 행(백엔드 완료 · 앱 화면 없음)이 남은 프론트 일이다.`, '');
  L.push(`백엔드가 **왜** 이렇게 생겼는지(흐름 상세 · 설계 판단 W-01~W-41 · 알려진 결함)는 [\`design-rationale.md\`](./design-rationale.md) 에 있다. 명세의 «백엔드» 줄에 붙은 W-nn 이 그 번호다.`, '');
  L.push(`> 이 문서와 그림은 [\`catalog.mjs\`](./catalog.mjs) 에서 생성된다. 손으로 고치지 말고 카탈로그를 고친 뒤 아래 «갱신 방법» 을 돌릴 것.`, '');
  L.push(`## 한눈에`, '');
  L.push(`| 상태 | 뜻 | 개수 |`, `|---|---|:--:|`);
  const mean = { done: '화면이 있고 API 까지 붙어 동작', partial: '화면은 있으나 일부가 목업 · 미연결', noscreen: '백엔드 API 는 있는데 앱 화면이 없음', planned: '백엔드 · AI 도 없거나 화면이 목업뿐', backend: '앱 화면 대상이 아님 (Swagger · 서버 내부)' };
  for (const k of Object.keys(STATUS)) L.push(`| ${STATUS[k].label} | ${mean[k]} | ${counts[k]} |`);
  const beMean = { done: '서버 API · 내부 동작이 있다', partial: '서버 쪽 일부만 있다', none: '서버 API 가 없다', na: '서버가 할 일이 없다 (앱 · AI 만의 일)' };
  L.push('', `| 백엔드 | 뜻 | 개수 |`, `|---|---|:--:|`);
  for (const k of Object.keys(BE_STATUS)) L.push(`| ${BE_STATUS[k].label} | ${beMean[k]} | ${beCounts[k]} |`);
  L.push('', `**시연을 끊는 것 ${blockers.length}개** — 시연 필수인데 아직 미완:`, '');
  for (const b of blockers) L.push(`- **${b.id} ${b.name}** — ${md(b.note)}`);
  L.push('', `## 그림`, '');
  const figs = [
    ['01-overview', '그림 1. 유스케이스 전체도', `액터와 ${UC.length}개 유스케이스, 색 = 프론트 구현 상태`],
    ['02-screen-map', '그림 2. 화면 – 유스케이스 맵', '어느 화면 파일이 어떤 유스케이스를 담는가. 붉은 칩은 «여기 들어갈 자리»'],
    ['03-session-relations', '그림 3. 운동 세션 관계도', 'B-01 의 사전조건 · 확장 · 종료 후 파급'],
    ['04-social-relations', '그림 4. 모임 · 알림 관계도', '모임에 들어오는 길 → 모임 상세 → 재촉 · 응원이 닿는 세 갈래'],
    ['05-demo-path', '그림 5. 시연 경로', `시연 필수 ${demoAll.length}개와 누르는 순서`],
  ];
  for (const [f, t, c] of figs) L.push(`### ${t}`, '', `${c}`, '', `![${t}](./png/${f}.png)`, '');
  L.push(`## 3자 회의 자료 (2026-09-21) — 역할별 1장 + 통합 1장`, '');
  L.push(`[\`roles.mjs\`](./roles.mjs) 가 만든다. 역할별 장은 **각자 가져온 내용 그대로**(프론트 = 이 카탈로그 · 백엔드 = 담당자 그림 3장 · AI = 담당자 PDF UC-01~09), 통합본만 ID 를 하나로 맞추고 유스케이스마다 F · B · A 세 역할의 상태를 나란히 놓았다.`, '');
  for (const [f, t] of [['10-role-frontend', '프론트엔드'], ['11-role-backend', '백엔드'], ['12-role-ai', 'AI'], ['13-integrated', '통합']]) L.push(`### ${t}`, '', `![${t}](./png/${f}.png)`, '');
  L.push(`이 네 장은 **09-21 회의 시점 스냅샷**이라 \`build.mjs\` 가 다시 만들지 않는다. 통합본에서 새로 생긴 번호(B-07 종목 선택 · B-08 촬영 점검 · B-09 스쿼트 횟수)는 09-23 에 카탈로그로 옮겼고, 그 뒤 바뀐 상태(세트 B-06 백엔드 완료 등)는 아래 목록이 정본이다.`, '');
  L.push(`통합본의 A 열은 AI 담당자 **보고 기준**이다 — main 브랜치에는 스쿼트 분석기뿐이라(\`ai-server/app/core/analyzer_registry.py\`), 보고로는 구현이지만 main 에 없는 칸은 붉은 \\* 로 표시했다.`, '');
  L.push(`## 시연 순서`, '', `| # | 유스케이스 | 화면 | 상태 |`, `|:--:|---|---|---|`);
  for (const u of UC.filter((x) => x.demo === 'pre')) L.push(`| 사전 | ${u.id} ${u.name} | ${u.screen} | ${STATUS[u.status].label} |`);
  for (const u of demoSteps) L.push(`| ${u.demo} | ${u.id} ${u.name}${u.blocker ? ' 🔴' : ''} | ${u.screen} | ${STATUS[u.status].label} |`);
  for (const u of UC.filter((x) => x.demo === 'bg')) L.push(`| 배경 | ${u.id} ${u.name} | ${u.screen} | ${STATUS[u.status].label} |`);
  L.push('', `## 목록`, '', `| ID | 이름 | 액터 | 앱 | 백엔드 | 시연 |`, `|---|---|---|---|---|:--:|`);
  for (const grp of GROUPS) for (const id of grp.ids) {
    const u = byId[id];
    const demo = typeof u.demo === 'number' ? `${u.demo}` : u.demo === 'pre' ? '사전' : u.demo === 'bg' ? '배경' : '';
    L.push(`| ${u.id} | ${u.name}${u.blocker ? ' 🔴' : ''} | ${u.actors.join(', ')} | ${STATUS[u.status].label} | ${BE_STATUS[beOf(u)[0]].label} | ${demo} |`);
  }
  L.push('', `## 액터`, '', `| 액터 | 유형 | 설명 |`, `|---|---|---|`);
  for (const a of ACTORS) L.push(`| ${a.name} | ${a.kind} | ${a.desc} |`);
  L.push('', `## 유스케이스 명세`, '');
  for (const grp of GROUPS) {
    L.push(`### ${grp.title}`, '');
    for (const id of grp.ids) {
      const u = byId[id];
      const demo = typeof u.demo === 'number' ? ` · 시연 ${u.demo}번` : u.demo === 'pre' ? ' · 시연 사전 준비' : u.demo === 'bg' ? ' · 시연 배경' : '';
      L.push(`#### ${u.id} ${u.name} — ${STATUS[u.status].label}${demo}`, '');
      L.push(`- **액터**: ${u.actors.join(', ')}`);
      L.push(`- **화면**: ${u.screen}`);
      L.push(`- **API**: ${u.api.map((a) => '`' + a + '`').join(' · ')}`);
      L.push(`- **사전조건**: ${u.pre}`);
      L.push(`- **기본 흐름**`);
      u.main.forEach((m, i) => L.push(`  ${i + 1}. ${m}`));
      if (u.alt.length) { L.push(`- **대안 · 예외**`); u.alt.forEach((m) => L.push(`  - ${m}`)); }
      L.push(`- **사후조건**: ${u.post}`);
      if (u.note) L.push(`- **프론트 메모**: ${u.note}`);
      const [bs, bn] = beOf(u);
      L.push(`- **백엔드**: ${BE_STATUS[bs].label}${bn ? ` — ${bn}` : ''}`);
      L.push('');
    }
  }
  L.push(`## 갱신 방법`, '', '```bash', 'node docs/usecase/build.mjs          # svg/ 01~05 + 이 README', 'node docs/usecase/roles.mjs          # svg/ 10~13 (역할별 3장 + 통합)', '# PNG (Windows, Edge 헤드리스) — svg 하나당 한 번', '"/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe" --headless=new --disable-gpu --hide-scrollbars \\', '  --force-device-scale-factor=2 --window-size=1360,<그림 높이> --screenshot=docs/usecase/png/<이름>.png docs/usecase/svg/<이름>.svg', '```', '');
  return L.join('\n');
}

/* ───────────── 한 장짜리 HTML (OUT_HTML) ───────────── */
function html(svgs) {
  const rich = (s) => esc(s).replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
  const pill = (u) => `<span class="pill st-${u.status}">${STATUS[u.status].label}</span>`;
  const demoChip = (u) => (typeof u.demo === 'number' ? `<span class="demo">시연 ${u.demo}</span>` : u.demo === 'pre' ? '<span class="demo">시연 사전</span>' : u.demo === 'bg' ? '<span class="demo">시연 배경</span>' : '');
  const figs = [
    ['f1', '그림 1', '유스케이스 전체도', `액터와 ${UC.length}개 유스케이스. 색은 백엔드가 아니라 <strong>앱 화면 + API 연동</strong> 상태다. 굵은 붉은 테두리 둘이 시연을 끊는다.`, svgs[0]],
    ['f2', '그림 2', '화면 – 유스케이스 맵', '어느 화면 파일이 어떤 유스케이스를 담는가. 붉은 칩은 «이 화면에 들어갈 자리» — 다음에 무엇을 어디에 만들지 바로 읽힌다.', svgs[1]],
    ['f3', '그림 3', '운동 세션 관계도', 'B-01 하나가 온보딩과 기준 영상을 전제로 하고, 끝나는 순간 리포트 · 캘린더 · 모임 피드로 번진다.', svgs[2]],
    ['f4', '그림 4', '모임 · 알림 관계도', '재촉 · 응원은 알림함에 항상 저장되고, 소켓과 푸시는 그 위에 얹히는 전달 수단이다. 앱에는 지금 알림함 갈래만 있다.', svgs[3]],
    ['f5', '그림 5', '시연 경로', `시연 필수 ${demoAll.length}개와 누르는 순서. 흐린 칸은 없어도 시연이 성립한다.`, svgs[4]],
  ];
  const spec = GROUPS.map((grp) => `
<section class="grp" id="g-${grp.key}"><h2>${esc(grp.title)}</h2>
${grp.ids.map((id) => { const u = byId[id]; return `<article class="uc${u.blocker ? ' is-blocker' : ''}" id="${u.id}">
<header><span class="uid">${u.id}</span><h3>${esc(u.name)}</h3>${pill(u)}${demoChip(u)}</header>
<dl>
<dt>액터</dt><dd>${u.actors.map(esc).join(' · ')}</dd>
<dt>화면</dt><dd>${rich(u.screen)}</dd>
<dt>API</dt><dd class="api">${u.api.map((a) => `<code>${esc(a)}</code>`).join('')}</dd>
<dt>사전조건</dt><dd>${rich(u.pre)}</dd>
<dt>기본 흐름</dt><dd><ol>${u.main.map((m) => `<li>${rich(m)}</li>`).join('')}</ol></dd>
${u.alt.length ? `<dt>대안 · 예외</dt><dd><ul>${u.alt.map((m) => `<li>${rich(m)}</li>`).join('')}</ul></dd>` : ''}
<dt>사후조건</dt><dd>${rich(u.post)}</dd>
${u.note ? `<dt>프론트 메모</dt><dd class="note">${rich(u.note)}</dd>` : ''}
</dl></article>`; }).join('')}
</section>`).join('');

  return `<title>ShadowFit 유스케이스</title>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans+KR:wght@400;500;600;700&family=IBM+Plex+Mono:wght@400;500&display=swap">
<style>
:root{--bg:#F5F7F1;--surface:#FFFFFF;--ink:#1B1F17;--muted:#5A6152;--line:#DADFD1;--accent:#47750B;--accent-soft:#E8F2D8;--code:#ECEFE5;
--done-bg:#E8F1DC;--done-fg:#2F5410;--partial-bg:#FAECD8;--partial-fg:#7A4A08;--noscreen-bg:#FBE9E9;--noscreen-fg:#9B2424;--planned-fg:#5F5F57;--backend-bg:#ECEAE3;--backend-fg:#4A4943;--alert:#A83232}
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){--bg:#11140E;--surface:#191D14;--ink:#E8ECDF;--muted:#9CA592;--line:#2C3325;--accent:#B4DD4C;--accent-soft:#222A13;--code:#20261A;
--done-bg:#22301A;--done-fg:#BADF8C;--partial-bg:#33290F;--partial-fg:#EAC47E;--noscreen-bg:#3A1B1B;--noscreen-fg:#F2A6A6;--planned-fg:#A8AE9E;--backend-bg:#27281F;--backend-fg:#BFBDB0;--alert:#F08C8C}}
:root[data-theme="dark"]{--bg:#11140E;--surface:#191D14;--ink:#E8ECDF;--muted:#9CA592;--line:#2C3325;--accent:#B4DD4C;--accent-soft:#222A13;--code:#20261A;
--done-bg:#22301A;--done-fg:#BADF8C;--partial-bg:#33290F;--partial-fg:#EAC47E;--noscreen-bg:#3A1B1B;--noscreen-fg:#F2A6A6;--planned-fg:#A8AE9E;--backend-bg:#27281F;--backend-fg:#BFBDB0;--alert:#F08C8C}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--ink);font:400 16px/1.7 "IBM Plex Sans KR","Malgun Gothic","Apple SD Gothic Neo",system-ui,sans-serif;padding-inline:20px;padding-block:48px 96px}
.wrap{max-width:1120px;margin-inline:auto;display:flex;flex-direction:column;gap:56px}
.col{max-width:760px}
.eyebrow{font:500 13px/1 "IBM Plex Mono",Consolas,monospace;letter-spacing:.08em;text-transform:uppercase;color:var(--accent)}
h1{font-size:clamp(30px,5vw,44px);line-height:1.15;margin:14px 0 18px;font-weight:700;letter-spacing:-.02em;text-wrap:balance}
h2{font-size:24px;line-height:1.3;margin:0 0 6px;font-weight:700;letter-spacing:-.01em;text-wrap:balance}
h3{font-size:18px;margin:0;font-weight:600}
p{margin:0}
.lede{font-size:18px;color:var(--muted)}
.lede strong,.cap strong{color:var(--ink);font-weight:600}
.tally{display:flex;flex-wrap:wrap;gap:10px 28px;margin-top:28px;padding-top:20px;border-top:1px solid var(--line)}
.tally div{display:flex;align-items:baseline;gap:8px}
.tally b{font:500 28px/1 "IBM Plex Mono",Consolas,monospace;font-variant-numeric:tabular-nums}
.block{border-left:3px solid var(--alert);padding:4px 0 4px 20px;display:flex;flex-direction:column;gap:14px}
.block h2{font-size:20px}
.block li{margin-top:10px}
.block ul{margin:0;padding-left:20px}
nav{display:flex;flex-wrap:wrap;gap:8px}
nav a{font-size:14px;padding:5px 12px;border:1px solid var(--line);border-radius:999px;color:var(--ink);text-decoration:none;background:var(--surface)}
nav a:hover,nav a:focus-visible{border-color:var(--accent);color:var(--accent);outline:none}
figure{margin:0;display:flex;flex-direction:column;gap:14px}
.fignum{font:500 13px/1 "IBM Plex Mono",Consolas,monospace;color:var(--accent);letter-spacing:.06em}
.cap{color:var(--muted);max-width:760px}
.sheet{background:#FFFFFF;border:1px solid var(--line);border-radius:6px;overflow-x:auto}
.sheet svg{display:block;width:100%;min-width:780px;height:auto}
table{border-collapse:collapse;width:100%;font-size:15px}
th,td{text-align:left;padding:10px 14px 10px 0;border-bottom:1px solid var(--line);vertical-align:top}
th{font-weight:600;color:var(--muted);font-size:13px;letter-spacing:.04em}
.tablewrap{overflow-x:auto}
.grp{display:flex;flex-direction:column;gap:18px}
.grp>h2{padding-bottom:10px;border-bottom:2px solid var(--ink)}
.uc{background:var(--surface);border:1px solid var(--line);border-radius:6px;padding:20px 22px;display:flex;flex-direction:column;gap:14px}
.uc.is-blocker{border-color:var(--alert)}
.uc header{display:flex;flex-wrap:wrap;align-items:center;gap:8px 12px}
.uid{font:500 14px/1 "IBM Plex Mono",Consolas,monospace;color:var(--muted)}
.pill{font-size:12.5px;font-weight:600;padding:3px 10px;border-radius:999px}
.st-done{background:var(--done-bg);color:var(--done-fg)}.st-partial{background:var(--partial-bg);color:var(--partial-fg)}.st-noscreen{background:var(--noscreen-bg);color:var(--noscreen-fg)}
.st-planned{color:var(--planned-fg);border:1px dashed var(--planned-fg)}.st-backend{background:var(--backend-bg);color:var(--backend-fg)}
.demo{font:500 12px/1 "IBM Plex Mono",Consolas,monospace;padding:5px 9px;border-radius:999px;background:var(--ink);color:var(--bg)}
dl{display:grid;grid-template-columns:96px 1fr;gap:10px 16px;margin:0;font-size:15px}
dt{color:var(--muted);font-size:13px;font-weight:600;padding-top:3px}
dd{margin:0;min-width:0}
dd ol,dd ul{margin:0;padding-left:20px}
dd li+li{margin-top:4px}
.api{display:flex;flex-wrap:wrap;gap:6px}
code{font:400 13px/1.5 "IBM Plex Mono",Consolas,monospace;background:var(--code);padding:2px 7px;border-radius:4px;overflow-wrap:anywhere}
.note{color:var(--muted)}
.note strong{color:var(--ink)}
footer{color:var(--muted);font-size:14px;border-top:1px solid var(--line);padding-top:20px}
@media (max-width:560px){dl{grid-template-columns:1fr;gap:2px 0}dt{padding-top:10px}.uc{padding:16px}}
</style>
<div class="wrap">
<header class="col">
<div class="eyebrow">ShadowFit · Frontend · ${UPDATED}</div>
<h1>유스케이스 명세 — 앱 화면에서 본 구현 상태</h1>
<p class="lede">백엔드 API 가 있어도 <strong>앱에 화면이 없으면 «화면 없음»</strong> 으로 셌다. 유스케이스마다 어느 화면 파일이 어떤 API 를 부르고 회원에게 무엇이 보이는지를 ${UPDATED} 코드에서 직접 확인해 적었다.</p>
<div class="tally">${Object.keys(STATUS).map((k) => `<div><b>${counts[k]}</b><span class="pill st-${k}">${STATUS[k].label}</span></div>`).join('')}</div>
</header>

<section class="block col"><h2>시연을 끊는 것 ${blockers.length}개</h2><p>시연 경로 위에 있는데 아직 미완이다. 나머지 붉은 칸은 없어도 시연이 성립한다.</p>
<ul>${blockers.map((b) => `<li><strong><a href="#${b.id}" style="color:inherit">${b.id} ${esc(b.name)}</a></strong> — ${rich(b.note.replace(/^🔴 시연 필수인데 미완: /, ''))}</li>`).join('')}</ul></section>

<nav aria-label="바로가기">${figs.map((f) => `<a href="#${f[0]}">${f[1]} ${esc(f[2])}</a>`).join('')}<a href="#actors">액터</a>${GROUPS.map((g) => `<a href="#g-${g.key}">명세 · ${esc(g.title)}</a>`).join('')}</nav>

${figs.map((f) => `<figure id="${f[0]}"><div><div class="fignum">${f[1].toUpperCase().replace('그림', 'FIG.')}</div><h2>${esc(f[2])}</h2></div><div class="sheet">${f[4]}</div><figcaption class="cap">${f[3]}</figcaption></figure>`).join('\n')}

<section id="actors" class="grp"><h2>액터</h2><div class="tablewrap"><table><thead><tr><th>액터</th><th>유형</th><th>설명</th></tr></thead><tbody>
${ACTORS.map((a) => `<tr><td><strong>${esc(a.name)}</strong></td><td>${esc(a.kind)}</td><td>${rich(a.desc)}</td></tr>`).join('')}
</tbody></table></div></section>

${spec}

<footer>카탈로그 한 파일(docs/usecase/catalog.mjs)에서 그림 5장과 이 명세가 같이 생성된다. 상태가 바뀌면 그 파일의 status 만 고치고 <code>node docs/usecase/build.mjs</code> 를 다시 돌린다.</footer>
</div>`;
}

/* ───────────── 실행 ───────────── */
const out = [['01-overview', overview('status', 1)], ['02-screen-map', screenMap(2)], ['03-session-relations', sessionRel(3)], ['04-social-relations', socialRel(4)], ['05-demo-path', overview('demo', 5)]];
fs.mkdirSync(path.join(DIR, 'svg'), { recursive: true });
fs.mkdirSync(path.join(DIR, 'png'), { recursive: true });
for (const [name, svg] of out) {
  fs.writeFileSync(path.join(DIR, 'svg', `${name}.svg`), svg);
  const h = svg.match(/viewBox="0 0 (\d+) (\d+)"/);
  console.log(`${name}.svg  ${h[1]}x${h[2]}`);
}
fs.writeFileSync(path.join(DIR, 'README.md'), readme());
console.log('README.md');
if (process.env.OUT_HTML) { fs.writeFileSync(process.env.OUT_HTML, html(out.map((o) => o[1]))); console.log('html →', process.env.OUT_HTML); }
console.log(`UC ${UC.length} · ` + Object.keys(STATUS).map((k) => `${STATUS[k].label} ${counts[k]}`).join(' · ') + ` · 시연 ${demoAll.length} · blocker ${blockers.map((b) => b.id).join(',')}`);
const missing = [...GROUPS.flatMap((g) => g.ids), ...Object.values(SCREENS).flat().flatMap((s) => s.ids)].filter((id) => !byId[id]);
if (missing.length) { console.error('카탈로그에 없는 id:', missing); process.exit(1); }
