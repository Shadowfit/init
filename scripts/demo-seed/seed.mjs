// ShadowFit 데모 데이터 SQL 생성기 — 사용자 데이터를 전부 지우고 «보기 좋은» 데모 데이터로 다시 채운다.
//   PASSWORD_HASH='$2a$10$...' node scripts/demo-seed/seed.mjs > seed.sql
// 실행은 scripts/demo-seed/run.sh 가 한다(백업 → 해시 얻기 → 사진 → SQL 적용).
//
// 지우는 것: 회원과 회원이 만든 모든 것(세션·리포트·모임·피드·알림·토큰 등).
// 남기는 것: 운동 종목·카테고리·기준 좌표·피드백 문구(마스터 데이터). 종목 카탈로그는 몇 개 더한다.
// 시각은 이 스크립트를 돌린 «지금» 기준(로컬 시간) — 오늘·어제가 늘 살아 있게.
import { USERS, GROUPS, PHOTOS, CHEERS, MOODS, MEMOS } from './data.mjs';

const HASH = process.env.PASSWORD_HASH;
if (!HASH || !HASH.startsWith('$2')) {
  console.error('PASSWORD_HASH (BCrypt) 가 필요하다');
  process.exit(1);
}

/* ───────── 유틸 ───────── */
let seed = 20261009;
const rnd = () => ((seed = (seed + 0x6d2b79f5) | 0), (() => { let t = seed; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; })());
const between = (a, b) => a + rnd() * (b - a);
const int = (a, b) => Math.floor(between(a, b + 1));
const pick = (arr) => arr[Math.floor(rnd() * arr.length)];
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
const r1 = (v) => Math.round(v * 10) / 10;

const NOW = new Date();
NOW.setSeconds(0, 0);
const startOfDay = (d) => new Date(d.getFullYear(), d.getMonth(), d.getDate());
const TODAY = startOfDay(NOW);
const daysAgo = (k, h = 0, m = 0) => new Date(TODAY.getFullYear(), TODAY.getMonth(), TODAY.getDate() - k, h, m);
const pad = (n) => String(n).padStart(2, '0');
const fmt = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
const fmtDate = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const addMin = (d, m) => new Date(d.getTime() + m * 60000);
const addSec = (d, s) => new Date(d.getTime() + s * 1000);
const q = (v) => (v === null || v === undefined ? 'NULL' : typeof v === 'number' ? String(v) : `'${String(v).replace(/\\/g, '\\\\').replace(/'/g, "''")}'`);
const mmss = (sec) => `${pad(Math.floor(sec / 60))}:${pad(Math.floor(sec % 60))}`;

const out = [];
const sql = (s) => out.push(s);
function insert(table, cols, rows) {
  for (let i = 0; i < rows.length; i += 400) {
    sql(`INSERT INTO ${table} (${cols.join(', ')}) VALUES\n${rows.slice(i, i + 400).map((r) => `(${r.map(q).join(', ')})`).join(',\n')};`);
  }
}

const HISTORY_DAYS = 45;
const SQUAT = 1;
const SQUAT_URL = 'https://www.youtube.com/watch?v=ultWZbUMPL8';
const byId = Object.fromEntries(USERS.map((u) => [u.id, u]));

/* ───────── 0. 지우기 ───────── */
sql('SET NAMES utf8mb4;');
sql('SET FOREIGN_KEY_CHECKS = 0;');
for (const t of ['event_cheers', 'event_reactions', 'group_events', 'group_invitations', 'group_members', 'workout_groups',
  'notifications', 'push_tokens', 'refresh_token', 'outbox_events', 'trainer_assignments',
  'session_feedback_logs', 'exercise_session_sets', 'session_reports', 'pose_data', 'exercise_sessions',
  'daily_logs', 'goals', 'body_records', 'weekly_reports', 'users']) {
  sql(`TRUNCATE TABLE ${t};`);
}
sql('SET FOREIGN_KEY_CHECKS = 1;');

/* ───────── 1. 종목 카탈로그 보강 (관리자 화면용, 분석은 스쿼트만) ───────── */
sql(`UPDATE exercises SET description = '엉덩이를 뒤로 빼며 허벅지가 바닥과 평행해질 때까지 앉았다 일어나는 하체 기본 운동. ShadowFit 이 실시간으로 깊이와 무릎 방향을 분석합니다.', target_joints = '["hip","knee","ankle"]' WHERE id = 1 AND description IS NULL;`);
sql(`UPDATE exercises SET description = '한 발을 앞으로 내딛고 양 무릎이 90도가 될 때까지 내려가는 운동. 좌우 반복 측정은 AI 분석기 준비 중입니다.', target_joints = '["hip","knee","ankle"]' WHERE id = 2 AND description IS NULL;`);
sql(`UPDATE exercises SET description = '팔꿈치와 발끝으로 몸을 일직선으로 버티는 코어 운동. 자세 유지 시간 측정은 준비 중입니다.', target_joints = '["shoulder","hip","ankle"]' WHERE id = 3 AND description IS NULL;`);
sql(`DELETE FROM exercises WHERE code IN ('PUSH_UP','SHOULDER_PRESS','DEADLIFT','BURPEE','GLUTE_BRIDGE','SIDE_PLANK') AND id NOT IN (SELECT exercise_id FROM exercise_sessions);`);
const cat = (name) => `(SELECT id FROM categories WHERE name = ${q(name)})`;
for (const [name, code, c, desc, joints, mins] of [
  ['푸시업', 'PUSH_UP', 'UPPER', '손을 어깨너비로 짚고 가슴이 바닥에 닿을 듯 내려갔다 밀어 올리는 상체 운동.', '["shoulder","elbow","wrist"]', 10],
  ['숄더 프레스', 'SHOULDER_PRESS', 'UPPER', '덤벨을 어깨 높이에서 머리 위로 밀어 올리는 어깨 운동.', '["shoulder","elbow"]', 12],
  ['데드리프트', 'DEADLIFT', 'BACK', '허리를 곧게 편 채 엉덩이를 접어 바닥의 무게를 들어 올리는 후면 사슬 운동.', '["hip","knee","shoulder"]', 15],
  ['버피', 'BURPEE', 'FULL', '스쿼트·플랭크·점프를 잇는 전신 유산소 운동.', '["hip","knee","shoulder"]', 10],
  ['글루트 브릿지', 'GLUTE_BRIDGE', 'LOWER', '누워서 무릎을 세우고 엉덩이를 들어 올리는 둔근 운동. 재활 루틴에 자주 쓰입니다.', '["hip","knee"]', 10],
  ['사이드 플랭크', 'SIDE_PLANK', 'CORE', '옆으로 누워 한 팔로 몸을 일직선으로 버티는 옆구리 운동.', '["shoulder","hip"]', 8],
]) {
  sql(`INSERT INTO exercises (name, code, category_id, description, target_joints, expected_duration_minutes, analysis_supported) SELECT ${q(name)}, ${q(code)}, ${cat(c)}, ${q(desc)}, ${q(joints)}, ${mins}, 0 FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM exercises WHERE code = ${q(code)});`);
}

/* ───────── 2. 회원 ───────── */
const joinedAt = {};
const userRows = USERS.map((u) => {
  const ago = u.joinedDaysAgo ?? (u.id === 1 ? 60 : 46 + (u.id % 9));
  const created = u.joinedDaysAgo === 0 ? addMin(NOW, -95) : daysAgo(ago, int(9, 22), int(0, 59));
  joinedAt[u.id] = created;
  return [u.id, u.email, HASH, u.username, u.sex, u.role ?? 'USER', null, u.h, u.w, u.level, u.persona,
    u.noOnboarding ? null : SQUAT_URL, u.noOnboarding ? 0 : 1, 1, u.persona === 'REHAB' ? 0.8 : 1.0, fmt(created), fmt(created)];
});
insert('users', ['id', 'email', 'password', 'username', 'sex', 'role', 'profile_image_url', 'height', 'weight', 'workout_level',
  'selected_persona', 'preferred_url', 'onboarding_completed', 'tts_enabled', 'tts_speed', 'created_at', 'updated_at'], userRows);

/* ───────── 3. 세션 · 세트 · 리포트 · 피드백 ───────── */
// 공유 글이 걸릴 날 — 그날은 운동했어야 한다
const SHARES = [
  { g: 1, m: 3, k: 0, photo: 0, caption: '오늘 스쿼트 30개 3세트 완료! 무릎 안 모이게 신경 썼어요 🙌', cheers: [[4, 5], [5, 8], [2, 10]] },
  { g: 1, m: 2, k: 1, photo: 1, caption: '퇴근하고 거실에서 3세트. 11일째 이어가는 중!', cheers: [[3, 2], [5, 6], [4, 7]] },
  { g: 1, m: 5, k: 2, photo: 10, caption: '연속 7일 달성 🏅 아침 루틴이 완전히 자리 잡았어요', cheers: [[2, 9], [3, 1], [4, 11]] },
  { g: 1, m: 4, k: 4, photo: 11, caption: '승연이랑 영상통화하면서 같이 했어요 ㅎㅎ 혼자보다 훨씬 덜 힘들다', cheers: [[3, 8], [2, 0]] },
  { g: 1, m: 2, k: 6, photo: 3, caption: '스트레칭까지 마무리. 등이 펴지는 느낌!', cheers: [[5, 3]] },
  { g: 2, m: 8, k: 0, photo: 4, caption: '하체 데이 인증. 깊이 확인 다 받았다 💪', cheers: [[3, 2], [9, 6]] },
  { g: 2, m: 9, k: 2, photo: 6, caption: '점심시간에 산책하고 스쿼트 20개 추가', cheers: [[2, 1], [10, 0]] },
  { g: 3, m: 12, k: 0, photo: 5, caption: '퇴근하고 바로! 오늘은 25개 성공', cheers: [[8, 4], [13, 9], [11, 11]] },
  { g: 3, m: 13, k: 1, photo: 2, caption: '한강 5km 뛰고 와서 스쿼트로 마무리', cheers: [[12, 6], [8, 7]] },
  { g: 3, m: 11, k: 5, photo: 7, caption: '비 와서 집에서… 그래도 했다!', cheers: [[12, 3], [14, 1]] },
  { g: 4, m: 10, k: 0, photo: 9, caption: '재활 3주차, 무릎 통증 없이 15개 완료했어요', cheers: [[15, 8], [16, 0], [17, 1]] },
  { g: 4, m: 15, k: 3, photo: 3, caption: '천천히 매트 위에서. 숨 고르면서 했어요', cheers: [[10, 3]] },
  { g: 5, m: 18, k: 6, photo: 8, caption: '토요일 아침 등산 + 스쿼트 마무리 ⛰️', cheers: [[19, 6], [14, 2]] },
  { g: 5, m: 19, k: 6, photo: 11, caption: '토요일 한강 같이 뛴 사람들 최고 👯', cheers: [[18, 8], [21, 5]] },
];
const forced = new Set(SHARES.map((s) => `${s.m}:${s.k}`));

function activeDays(u) {
  const days = new Set();
  const created = startOfDay(joinedAt[u.id]);
  for (let k = HISTORY_DAYS; k >= 0; k--) {
    if (daysAgo(k) < created) continue;
    const s = u.streak ?? 0;
    let on;
    if (u.today && s > 0) on = k < s ? true : k === s ? false : null;
    else if (!u.today && s > 0) on = k >= 1 && k <= s ? true : k === 0 || k === s + 1 ? false : null;
    else on = k <= 1 ? false : null;
    if (forced.has(`${u.id}:${k}`)) on = true;
    if (on === null) on = rnd() < u.rate;
    if (on) days.add(k);
  }
  return days;
}

const FEEDBACK = [['KNEE_IN', 5], ['BACK_BENT', 4], ['HIP_LOW', 3], ['KNEE_OUT', 2], ['HEAD_DOWN', 2], ['HIP_HIGH', 1]];
const weighted = () => { const tot = FEEDBACK.reduce((s, f) => s + f[1], 0); let x = rnd() * tot; for (const [t, w] of FEEDBACK) { if ((x -= w) < 0) return t; } return 'KNEE_IN'; };
const FB_LABEL = { KNEE_IN: '무릎이 안쪽으로 모이는', BACK_BENT: '등이 굽는', HIP_LOW: '엉덩이가 처지는', KNEE_OUT: '무릎이 발끝보다 나가는', HEAD_DOWN: '고개가 숙여지는', HIP_HIGH: '엉덩이가 들리는' };

let sid = 0;
const sessions = []; // 이후 피드·알림이 쓴다
const rows = { s: [], set: [], rep: [], fb: [] };

for (const u of USERS) {
  if (!u.rate && !u.streak) continue;
  const days = activeDays(u);
  for (let k = HISTORY_DAYS; k >= 0; k--) {
    if (!days.has(k)) continue;
    const progress = 1 - k / HISTORY_DAYS;
    const count = u.main && rnd() < 0.12 ? 2 : 1;
    for (let c = 0; c < count; c++) {
      let hour = clamp(u.hour + between(-1.2, 1.2) + (c ? -9 : 0), 5.5, 23.3);
      let start = addMin(daysAgo(k), Math.round(hour * 60));
      if (k === 0 && start > addMin(NOW, -40)) start = addMin(NOW, -int(45, 200));
      if (start < joinedAt[u.id]) start = addMin(joinedAt[u.id], 30);

      // 가끔 실패·취소 세션이 먼저 있다(네트워크 끊김 · 중간에 그만둠)
      if (rnd() < 0.07) {
        const failed = addMin(start, -int(35, 90));
        if (failed > joinedAt[u.id]) {
          sid++;
          const cancelled = rnd() < 0.35;
          rows.s.push([sid, u.id, SQUAT, SQUAT_URL, fmt(failed), cancelled ? fmt(addMin(failed, int(2, 5))) : null, fmt(addMin(failed, int(1, 4))),
            cancelled ? int(2, 8) : int(0, 6), null, null, null, null, 1, 12, 3, cancelled ? 'CANCELLED' : 'FAILED', 1, fmt(failed)]);
        }
      }

      sid++;
      const sets = pick([3, 3, 3, 2, 4]);
      const target = pick([10, 12, 12, 15]);
      const reps = Math.max(6, sets * target - (rnd() < 0.3 ? int(1, 4) : 0));
      const avg = r1(clamp(u.skill - 5 + progress * 6 + between(-4, 4), 52, 96.5));
      const minutes = Math.round(clamp(6 + sets * 3.2 + between(-2, 4), 8, 24));
      const end = addMin(start, minutes);
      const maxS = r1(clamp(avg + between(4, 9), 0, 99.5));
      const minS = r1(clamp(avg - between(9, 19), 30, avg));
      const cal = r1(reps * 0.42 + minutes * 2.7);
      rows.s.push([sid, u.id, SQUAT, SQUAT_URL, fmt(start), fmt(end), fmt(end), reps, avg, maxS, minS, cal, avg > 85 ? 3 : avg > 75 ? 2 : 1,
        target, sets, 'COMPLETED', 2, fmt(start)]);
      sessions.push({ id: sid, member: u.id, start, end, reps, avg, minutes, k });

      // 세트
      let t = between(4, 9);
      const perSet = [];
      for (let n = 1; n <= sets; n++) {
        const r = n === sets ? reps - target * (sets - 1) : target;
        const dur = r * between(3.2, 4.4);
        const sa = r1(clamp(avg + between(-3.5, 3.5) - (n === sets ? 1.5 : 0), 40, 99));
        rows.set.push([sid, n, Math.max(1, r), sa, r1(t), r1(t + dur)]);
        perSet.push([t, t + dur, Math.max(1, r)]);
        t += dur + between(35, 60);
      }

      // 회차별 추이 + 최악 회차 (리포트가 detailed_analysis 를 그대로 읽는다)
      const trend = [];
      let rep = 0;
      for (const [s0, s1, r] of perSet) {
        for (let i = 0; i < r; i++) {
          rep++;
          const fatigue = (i / Math.max(1, r - 1)) * between(2, 7);
          const sr = r1(clamp(avg + 3 - fatigue + between(-4.5, 4.5), Math.max(30, minS), maxS));
          trend.push({ repNumber: rep, syncRate: sr, timeStamp: mmss(s0 + ((s1 - s0) * (i + 0.5)) / r) });
        }
      }
      const worst = trend.reduce((a, b) => (b.syncRate < a.syncRate ? b : a), trend[0]);
      const fbCount = clamp(Math.round((92 - avg) / 7 + between(-1, 1)), 0, 6);
      const fbTypes = [];
      const fbSeen = new Set(); // UNIQUE(session_id, rep_number, feedback_type) — 같은 회차·같은 결함은 한 번
      for (let i = 0; i < fbCount; i++) {
        const type = i === 0 ? weighted() : rnd() < 0.5 ? fbTypes[0] : weighted();
        let repNo = int(1, reps);
        for (let tries = 0; fbSeen.has(`${repNo}:${type}`) && tries < 20; tries++) repNo = int(1, reps);
        if (fbSeen.has(`${repNo}:${type}`)) continue;
        fbSeen.add(`${repNo}:${type}`);
        fbTypes.push(type);
        const at = addSec(start, Math.min(minutes * 60 - 5, (repNo / reps) * minutes * 55 + int(5, 20)));
        rows.fb.push([sid, repNo, type, r1(clamp(avg - between(6, 16), 30, 99)), fmt(at), fmt(at)]);
      }
      const top = fbTypes.length ? fbTypes.sort((a, b) => fbTypes.filter((x) => x === b).length - fbTypes.filter((x) => x === a).length)[0] : null;
      const tips = avg >= 85
        ? `깊이와 무릎 방향이 안정적이에요. 마지막 세트에서만 속도가 빨라졌으니 내려갈 때 2초를 세어 보세요. 지금 강도를 유지하면 충분합니다.`
        : avg >= 75
          ? `${top ? `${FB_LABEL[top]} 순간이 ${fbTypes.filter((x) => x === top).length}번 있었어요. ` : ''}전반적으로 좋은 자세였어요. ${worst.repNumber}회차처럼 피로가 쌓일 때는 세트 사이 휴식을 10초 늘려 보세요.`
          : `${top ? `${FB_LABEL[top]} 자세가 반복됐어요. ` : ''}무리하게 횟수를 채우기보다 거울을 보며 천천히 앉는 연습부터 해 보세요. 통증이 느껴지면 바로 멈추세요.`;
      const summary = `스쿼트 ${sets}세트 ${reps}회 · 평균 싱크로율 ${avg}% · ${minutes}분`;
      const analysis = JSON.stringify({
        worstSection: { repNumber: worst.repNumber, exerciseName: '스쿼트', timeStamp: worst.timeStamp, reason: `${worst.repNumber}회차 · 싱크로율 ${Math.round(worst.syncRate)}%`, poseDataId: null },
        repTrend: trend,
      });
      rows.rep.push([u.id, sid, summary, analysis, tips, fmt(addSec(end, 4)), fmt(addSec(end, 4))]);
    }
  }
}

insert('exercise_sessions', ['id', 'member_id', 'exercise_id', 'reference_source', 'start_time', 'end_time', 'last_active_at', 'total_reps',
  'avg_sync_rate', 'max_sync_rate', 'min_sync_rate', 'calories_burned', 'difficulty_level', 'target_reps_per_set', 'target_sets', 'status',
  'version', 'created_at'], rows.s);
insert('exercise_session_sets', ['session_id', 'set_no', 'reps', 'avg_sync_rate', 'started_sec', 'ended_sec'], rows.set);
insert('session_reports', ['member_id', 'session_id', 'summary', 'detailed_analysis', 'improvement_tips', 'created_at', 'updated_at'], rows.rep);
insert('session_feedback_logs', ['session_id', 'rep_number', 'feedback_type', 'sync_rate_at_trigger', 'occurred_at', 'created_at'], rows.fb);

/* ───────── 4. 일지 (완료 세션 합계 + 메모·기분) ───────── */
const daily = new Map();
for (const s of sessions) {
  const key = `${s.member}|${fmtDate(s.start)}`;
  const d = daily.get(key) ?? { member: s.member, date: fmtDate(s.start), min: 0, cal: 0, at: s.end };
  d.min += s.minutes;
  d.cal += s.reps * 0.42 + s.minutes * 2.7;
  d.at = s.end;
  daily.set(key, d);
}
insert('daily_logs', ['member_id', 'log_date', 'memo', 'total_exercise_time', 'total_calories', 'mood', 'created_at', 'updated_at'],
  [...daily.values()].map((d) => {
    const writes = byId[d.member].main ? rnd() < 0.55 : rnd() < 0.12;
    return [d.member, d.date, writes ? pick(MEMOS) : null, d.min, r1(d.cal), writes ? pick(MOODS) : null, fmt(d.at), fmt(d.at)];
  }));

/* ───────── 5. 모임 · 멤버 · 초대 ───────── */
const membership = {}; // groupId → [{member, joined, left}]
const groupRows = [];
const memberRows = [];
for (const g of GROUPS) {
  const created = daysAgo(g.createdDaysAgo, int(19, 22), int(0, 59));
  groupRows.push([g.id, g.name, g.description, g.code, g.owner, 0, fmt(created)]);
  const list = [{ member: g.owner, joined: created, role: 'OWNER' }];
  [...g.members, ...(g.left ?? [])].forEach((m, i) => {
    let joined = addMin(created, int(30, 60 * 24 * 5) + i * 300);
    if (joined < joinedAt[m]) joined = addMin(joinedAt[m], int(10, 40));
    if (joined > NOW) joined = addMin(NOW, -20);
    const left = g.left?.includes(m) ? addMin(joined, 60 * 24 * int(4, 9)) : null;
    list.push({ member: m, joined, role: 'MEMBER', left });
  });
  membership[g.id] = list;
  list.forEach((x) => memberRows.push([g.id, x.member, x.role, x.left ? 'LEFT' : 'ACTIVE', fmt(x.joined)]));
}
insert('workout_groups', ['id', 'name', 'description', 'invite_code', 'created_by', 'next_seq', 'created_at'], groupRows);
insert('group_members', ['group_id', 'member_id', 'role', 'status', 'joined_at'], memberRows);
insert('group_invitations', ['group_id', 'inviter_id', 'invitee_id', 'status', 'created_at', 'responded_at'], [
  [3, 8, 2, 'PENDING', fmt(daysAgo(2, 20, 14)), null],
  [4, 10, 6, 'PENDING', fmt(daysAgo(1, 11, 3)), null],
  [5, 18, 9, 'DECLINED', fmt(daysAgo(9, 9, 40)), fmt(daysAgo(8, 22, 10))],
]);

/* ───────── 6. 피드 (가입 · 자동 완료 글 · 공유 글) + 응원 ───────── */
const events = []; // {g, type, sender, payload, source, at, cheers?}
for (const g of GROUPS) {
  for (const x of membership[g.id]) {
    if (x.role !== 'OWNER') {
      events.push({ g: g.id, type: 'MEMBER_JOINED', sender: null, at: x.joined,
        payload: JSON.stringify({ memberId: x.member, username: byId[x.member].username }) });
    }
    for (const s of sessions.filter((s) => s.member === x.member && s.end >= x.joined && (!x.left || s.end < x.left))) {
      events.push({ g: g.id, type: 'SESSION_COMPLETED', sender: x.member, source: s.id, at: addSec(s.end, 3),
        payload: JSON.stringify({ sessionId: s.id, memberId: x.member, username: byId[x.member].username, exerciseName: '스쿼트' }) });
    }
  }
}
for (const sh of SHARES) {
  const s = sessions.filter((x) => x.member === sh.m && x.k === sh.k).pop();
  if (!s) throw new Error(`공유할 세션 없음: member ${sh.m} day -${sh.k}`);
  let at = addMin(s.end, int(4, 35));
  if (at > NOW) at = addMin(NOW, -3);
  const p = PHOTOS[sh.photo];
  events.push({ g: sh.g, type: 'SESSION_SHARED', sender: sh.m, source: s.id, at, cheers: sh.cheers,
    payload: JSON.stringify({ sessionId: s.id, memberId: sh.m, username: byId[sh.m].username, exerciseName: '스쿼트',
      totalReps: s.reps, workoutMinutes: s.minutes, caption: sh.caption, photoUrl: `/feed-photos/${p.file}.png` }) });
}
events.sort((a, b) => a.g - b.g || a.at - b.at);
const nextSeq = {};
const eventRows = [];
const cheerRows = [];
events.forEach((e, i) => {
  const id = i + 1;
  nextSeq[e.g] = (nextSeq[e.g] ?? 0) + 1;
  eventRows.push([id, e.g, nextSeq[e.g], e.type, e.sender, e.payload, e.source ?? null, fmt(e.at)]);
  (e.cheers ?? []).forEach(([m, msg], j) => {
    let at = addMin(e.at, int(6, 90) + j * 17);
    if (at > NOW) at = addMin(NOW, -1);
    cheerRows.push([id, m, CHEERS[msg], fmt(at), fmt(at)]);
  });
});
insert('group_events', ['id', 'group_id', 'seq', 'event_type', 'sender_id', 'payload', 'source_id', 'created_at'], eventRows);
insert('event_cheers', ['event_id', 'member_id', 'message', 'created_at', 'updated_at'], cheerRows);
for (const g of GROUPS) sql(`UPDATE workout_groups SET next_seq = ${nextSeq[g.id] ?? 0} WHERE id = ${g.id};`);

/* ───────── 7. 알림 (재촉 · 응원) ───────── */
const nt = (sender, recipient, type, k, h, m, message, read) => {
  let at = daysAgo(k, h, m);
  if (at > NOW) at = addMin(NOW, -int(5, 50));
  return [sender, recipient, type, fmtDate(daysAgo(k)), message, read ? fmt(addMin(at, int(10, 180))) : null, fmt(at)];
};
insert('notifications', ['sender_id', 'recipient_id', 'type', 'target_date', 'message', 'read_at', 'created_at'], [
  nt(3, 2, 'CHEER', 0, NOW.getHours(), 0, '👏 칭찬해요! 💪 할 수 있다!', false),
  nt(5, 2, 'CHEER', 0, 9, 12, '😎 진정한 운동인', false),
  nt(4, 2, 'NUDGE', 1, 18, 5, null, true),
  nt(8, 2, 'CHEER', 3, 21, 40, '🔥 응원해요', true),
  nt(3, 2, 'NUDGE', 5, 19, 20, null, true),
  nt(9, 2, 'CHEER', 8, 22, 15, '😮 대단해!', true),
  nt(2, 6, 'NUDGE', 0, 20, 30, null, false),
  nt(3, 7, 'NUDGE', 0, 8, 15, null, false),
  nt(8, 11, 'NUDGE', 0, 19, 50, null, false),
  nt(2, 3, 'CHEER', 0, 8, 40, '💯 100점 드립니다', true),
  nt(12, 8, 'CHEER', 1, 22, 5, '😆 다음에 같이 운동하자', true),
  nt(10, 16, 'NUDGE', 1, 15, 0, null, true),
  nt(18, 19, 'NUDGE', 2, 9, 30, null, true),
]);

/* ───────── 8. 목표 · 체성분 · 주간 리포트 ───────── */
insert('goals', ['member_id', 'goal_type', 'target_value', 'created_at', 'updated_at'], [
  [2, 'WEEKLY_SESSIONS', 5, fmt(daysAgo(30, 21)), fmt(daysAgo(30, 21))],
  [2, 'WEEKLY_MINUTES', 100, fmt(daysAgo(30, 21)), fmt(daysAgo(30, 21))],
  [3, 'WEEKLY_SESSIONS', 4, fmt(daysAgo(25, 7)), fmt(daysAgo(25, 7))],
  [10, 'WEEKLY_MINUTES', 60, fmt(daysAgo(20, 10)), fmt(daysAgo(20, 10))],
]);
insert('body_records', ['member_id', 'record_date', 'weight', 'body_fat_percentage', 'muscle_mass', 'created_at'],
  [0, 1, 2, 3, 4, 5].map((i) => {
    const k = 35 - i * 7;
    return [2, fmtDate(daysAgo(k)), r1(70.4 - i * 0.4), r1(21.8 - i * 0.35), r1(30.1 + i * 0.12), fmt(daysAgo(k, 8))];
  }));
const monday = (d) => { const x = startOfDay(d); x.setDate(x.getDate() - ((x.getDay() + 6) % 7)); return x; };
const lastMon = monday(addMin(monday(NOW), -60 * 24 * 3));
const lastSun = addMin(lastMon, 60 * 24 * 6);
const lastWeek = sessions.filter((s) => s.member === 2 && s.start >= lastMon && s.start < addMin(lastSun, 60 * 24));
const lwMin = lastWeek.reduce((a, s) => a + s.minutes, 0);
const lwAvg = lastWeek.length ? r1(lastWeek.reduce((a, s) => a + s.avg, 0) / lastWeek.length) : 0;
insert('weekly_reports', ['member_id', 'period_start', 'period_end', 'summary_source', 'summary', 'cited_metrics', 'generation_model', 'prompt_version',
  'fallback_reason', 'generated_at', 'created_at'], [[2, fmtDate(lastMon), fmtDate(lastSun), 'LLM',
  `지난주에는 ${lastWeek.length}번, 모두 ${lwMin}분 동안 스쿼트를 했어요. 평균 싱크로율은 ${lwAvg}%로 꾸준히 안정적이었고, 저녁 9시 전후 운동이 습관으로 자리 잡았어요. 무릎이 안쪽으로 모이는 순간이 세트 후반에 몰려 있으니, 이번 주에는 마지막 세트에서 내려가는 속도를 조금 늦춰 보세요. 목표(주 5회)까지 거의 다 왔어요!`,
  null, 'demo-seed', 'seed-v1', null, fmt(addMin(lastSun, 60 * 24 + 60 * 3)), fmt(addMin(lastSun, 60 * 24 + 60 * 3))]]);

/* ───────── 끝 ───────── */
const done = sessions.length;
sql(`-- summary: users=${USERS.length} completed_sessions=${done} all_sessions=${rows.s.length} events=${eventRows.length} shared=${SHARES.length} cheers=${cheerRows.length} daily_logs=${daily.size}`);
process.stdout.write(out.join('\n') + '\n');
console.error(`users ${USERS.length} · sessions ${rows.s.length} (completed ${done}) · sets ${rows.set.length} · feedback ${rows.fb.length} · events ${eventRows.length} · cheers ${cheerRows.length} · daily ${daily.size}`);
