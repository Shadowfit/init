// 앱(사용자) 화면 캡처 — 발표·보고서용. 웹 버전(Expo)을 휴대폰 크기로 띄워 찍는다.
//   백엔드(8080)와 `npx expo start`를 띄운 상태에서:  npm run capture
//   expo 가 8081 이 아닌 포트로 떴으면(터미널에 나온 주소):  BASE=http://localhost:8083 npm run capture
// 결과: screenshots/NN-이름.png — 390×844(휴대폰) · 3배 해상도. 스크롤 화면은 끝까지 길게, 모달은 보이는 화면만.
//
// 🔒 읽기 전용으로 돈다 — 조회(GET)와 로그인 말고 서버로 가는 요청(재촉·응원·공유·읽음 처리 등)은
//    브라우저에서 가로채 «성공한 척» 돌려준다. 그래서 몇 번을 돌려도 데이터가 바뀌지 않는다.
// 카메라 촬영 화면은 웹에선 실제처럼 안 나와서 뺐다 — 휴대폰에서 직접 찍는다.
import { existsSync } from 'node:fs';
import { mkdir, rm } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer-core';

const BASE = process.env.BASE ?? 'http://localhost:8081';
const OUT = fileURLToPath(new URL('../screenshots/', import.meta.url));
const PASSWORD = process.env.PASSWORD ?? 'Shadow1234!';
const USER = process.env.USER_EMAIL ?? 'jiho@shadowfit.kr'; // 메인 시연 계정 — 모임장, 알림·재촉 기록이 있다
const NEW_USER = process.env.NEW_USER_EMAIL ?? 'minseo@shadowfit.kr'; // 온보딩 안 끝난 계정
const GROUP = process.env.GROUP_NAME ?? '아침 7시 스쿼트단';
const VIEWPORT = { width: 390, height: 844, deviceScaleFactor: 3 };
const MAX_HEIGHT = 5200; // 긴 화면 상한(css px)

const executablePath = [
  process.env.BROWSER,
  'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/usr/bin/google-chrome',
].find((p) => p && existsSync(p));
if (!executablePath) throw new Error('Edge/Chrome 을 못 찾았다 — BROWSER=브라우저 실행 파일 경로 로 지정');

await rm(OUT, { recursive: true, force: true });
await mkdir(OUT, { recursive: true });
const browser = await puppeteer.launch({ executablePath, headless: true, defaultViewport: VIEWPORT });
const page = await browser.newPage();
let n = 0;

// 읽기 전용 — API(8080)로 가는 쓰기 요청은 서버에 안 보내고 200 으로 답한다
const faked = [];
await page.setRequestInterception(true);
page.on('request', (req) => {
  const url = new URL(req.url());
  const write = !['GET', 'HEAD', 'OPTIONS'].includes(req.method());
  const allowed = /^\/member\/(login|reissue)$/.test(url.pathname);
  if (url.port === '8080' && write && !allowed) {
    faked.push(`${req.method()} ${url.pathname}`);
    return req.respond({
      status: 200,
      contentType: 'application/json',
      headers: { 'Access-Control-Allow-Origin': req.headers().origin ?? '*', 'Access-Control-Allow-Credentials': 'true' },
      body: '{}',
    });
  }
  return req.continue();
});
// 웹에선 앱의 Alert 가 브라우저 팝업이 된다 — 확인창은 «취소», 알림은 «확인» 으로 닫는다
page.on('dialog', (d) => (d.type() === 'confirm' ? d.dismiss() : d.accept()));

/* ───────── 도우미 ───────── */

async function settle() {
  await page.evaluate(() => document.activeElement?.blur?.()); // 입력칸 커서·포커스 테두리 없이
  await page.waitForNetworkIdle({ idleTime: 500, timeout: 20000 }).catch(() => {});
  await page.evaluate(() => document.fonts.ready);
  await new Promise((r) => setTimeout(r, 600)); // RN 모달·전환 애니메이션
}

const go = (path) => page.goto(BASE + path, { waitUntil: 'networkidle2', timeout: 60000 });

// 글자로 요소 찾기 — RN 웹은 클래스·테스트 id 가 없어서 화면 글자가 유일한 손잡이다
// last: 모달·시트는 문서 맨 끝에 그려진다 — 뒤 화면에 같은 글자가 있으면 마지막 것을 고른다
async function byText(text, { exact = false, last = false, timeout = 20000 } = {}) {
  const handle = await page.waitForFunction(
    (t, ex, lst) => {
      const norm = (s) => s.replace(/\s+/g, ' ').trim();
      const want = norm(t);
      const hits = [...document.querySelectorAll('body *')].filter((el) => {
        const r = el.getBoundingClientRect();
        if (!r.width || !r.height || getComputedStyle(el).visibility === 'hidden') return false;
        const txt = norm(el.textContent ?? '');
        return ex ? txt === want : txt.includes(want);
      });
      // 글자를 품은 요소 중 가장 안쪽(=글자 자체)
      const inner = hits.filter((el) => !hits.some((o) => o !== el && el.contains(o)));
      return (lst ? inner.at(-1) : inner[0]) ?? null;
    },
    { timeout, polling: 250 },
    text,
    exact,
    last,
  );
  return handle.asElement();
}
const waitText = (text, opts) => byText(text, opts);
async function clickText(text, opts) {
  const el = await byText(text, opts);
  await el.click();
}
async function fill(selector, text) {
  await page.waitForSelector(selector, { visible: true });
  await page.click(selector, { count: 3 });
  await page.keyboard.press('Backspace');
  await page.type(selector, text);
}
async function scrollTo(text) {
  const el = await byText(text);
  await el.evaluate((e) => e.scrollIntoView({ block: 'start' }));
}

async function shot(name) {
  await settle();
  const file = `${String(++n).padStart(2, '0')}-${name}.png`;
  await page.screenshot({ path: OUT + file });
  console.log('  ✓', file);
}

// 앱은 문서가 아니라 안쪽 ScrollView 가 스크롤된다 — 그만큼 화면을 늘려 끝까지 한 장에 담는다
async function tallShot(name) {
  await settle();
  let height = VIEWPORT.height;
  for (let i = 0; i < 3; i++) {
    const extra = await page.evaluate(() => {
      let max = 0;
      for (const el of document.querySelectorAll('div')) {
        const over = el.scrollHeight - el.clientHeight;
        if (over < 2 || el.clientHeight < 200) continue;
        const s = getComputedStyle(el);
        const r = el.getBoundingClientRect();
        if (!/(auto|scroll)/.test(s.overflowY) || !r.width || s.visibility === 'hidden') continue;
        max = Math.max(max, over);
      }
      return max;
    });
    if (extra < 2) break;
    height = Math.min(MAX_HEIGHT, height + extra);
    await page.setViewport({ ...VIEWPORT, height });
    await settle();
    if (height === MAX_HEIGHT) break;
  }
  const file = `${String(++n).padStart(2, '0')}-${name}.png`;
  await page.screenshot({ path: OUT + file });
  await page.setViewport(VIEWPORT);
  console.log('  ✓', file, height > VIEWPORT.height ? `(길게 ${height}px)` : '');
}

async function logout() {
  await page.evaluate(() => localStorage.clear());
}
async function openLogin() {
  await go('/login');
  await waitText('비밀번호를 잊으셨나요?');
  // 개발 모드에서만 보이는 «DEV 바로가기» 칸은 캡처에서 숨긴다(앱 코드는 그대로)
  await page.evaluate(() => {
    for (const el of document.querySelectorAll('body *')) {
      if (el.children.length === 0 && el.textContent?.trim() === 'DEV 바로가기') {
        el.parentElement.style.display = 'none';
        break;
      }
    }
  });
}
async function login(email) {
  await logout();
  await openLogin();
  await fill('input[placeholder="example@email.com"]', email);
  await fill('input[placeholder="6자 이상 입력"]', PASSWORD);
  await clickText('로그인 →', { exact: true });
}

/* ───────── 캡처 ───────── */

try {
  console.log(`캡처 → ${OUT}  (앱: ${BASE})`);
  await go('/login');
  // 8081 은 다른 프로그램이 쓰고 있으면 expo 가 다른 포트로 뜬다 — 엉뚱한 사이트를 찍지 않게 확인
  if (!(await page.content()).includes('ShadowFit')) {
    throw new Error(`${BASE} 는 ShadowFit 앱이 아니다 — expo 가 뜬 주소로 지정: BASE=http://localhost:포트 npm run capture`);
  }
  await logout();

  // 로그인 전
  await openLogin();
  await shot('login');
  await clickText('회원가입', { exact: true });
  await page.waitForSelector('input[placeholder="3자 이상"]', { visible: true });
  await shot('signup');
  // 비밀번호 재설정 3단계 — 서버 호출 없는 화면 흐름. 마지막 «비밀번호 변경» 은 누르지 않는다
  await go('/reset-password');
  await waitText('인증 코드 발송');
  await fill('input[placeholder="example@email.com"]', USER);
  await shot('reset-password-1-email');
  await clickText('인증 코드 발송', { exact: true });
  await waitText('인증 코드 입력');
  await fill('input[placeholder="6자리 숫자 입력"]', '482913');
  await shot('reset-password-2-code');
  await clickText('확인', { exact: true });
  await waitText('새 비밀번호 설정');
  await fill('input[placeholder="6자 이상 입력"]', PASSWORD);
  await fill('input[placeholder="비밀번호를 다시 입력"]', PASSWORD);
  await shot('reset-password-3-new');

  // 처음 가입한 회원 — 온보딩 5단계. 마지막 «완료» 는 누르지 않아 계정은 그대로다
  await login(NEW_USER);
  await waitText('운동 수준이 어떻게 되시나요?');
  await clickText('초급', { exact: true });
  await shot('onboarding-1-level');
  await clickText('다음 >', { exact: true });
  await waitText('키가 어떻게 되시나요?');
  await shot('onboarding-2-height');
  await clickText('다음 >', { exact: true });
  await waitText('목표 몸무게가 어떻게 되시나요?');
  await shot('onboarding-3-weight');
  await clickText('다음 >', { exact: true });
  await waitText('어떤 트레이너 스타일을 원하시나요?');
  await clickText('헬린이', { exact: true });
  await shot('onboarding-4-persona');
  await clickText('다음 >', { exact: true });
  await waitText('기준 영상을 선택해주세요');
  await clickText('기본 바벨 스쿼트', { exact: true });
  await tallShot('onboarding-5-video');

  // 메인 계정
  await login(USER);
  await waitText('최신 모임 운동 현황');
  await waitText('운동 완료');
  await tallShot('home');

  await go('/notifications');
  await waitText('님의 재촉').catch(() => waitText('님의 응원'));
  await tallShot('notifications');

  await go('/activity');
  await waitText('운동 상세');
  await tallShot('activity');

  // 보고서 — 활동 탭의 오늘 운동 카드에서 연다
  await clickText('싱크로율', { exact: true });
  await waitText('종목별 싱크로율', { timeout: 40000 });
  await tallShot('report');
  await clickText('모임 피드에 공유하기', { exact: true });
  await waitText('공유할 모임');
  await clickText(GROUP, { exact: true, last: true }); // 체크만 — «공유하기» 는 누르지 않는다
  await shot('report-share');

  await go('/groups');
  await waitText(GROUP);
  await tallShot('groups');
  await clickText('모임 만들기', { exact: true });
  await fill('input[placeholder="예: 우리 진짜 거북목 되지 말자"]', '주말 아침 등산 + 스쿼트');
  await fill('input[placeholder="모임을 소개해주세요"]', '토요일 7시 북한산 입구에서 만나요');
  await shot('group-create');
  await go('/groups');
  await clickText('초대 코드로 참여', { exact: true });
  await fill('input[placeholder="8자리 코드"]', 'LEGDAY9P');
  await shot('group-join');

  // 모임 상세 — 구성원 현황(재촉하기) · 피드(응원 보내기)
  await go('/groups');
  await clickText(GROUP, { exact: true });
  await waitText('구성원 운동 현황');
  await waitText('재촉하기');
  await tallShot('group-detail');
  await scrollTo('구성원 운동 현황');
  await shot('group-nudge');
  await scrollTo('모임 피드');
  await shot('group-feed');
  // 이미 응원한 글은 «응원 바꾸기» — 어느 쪽이든 같은 응원 창이 열린다
  const cheerBtn = await byText('응원보내기', { exact: true, timeout: 3000 }).catch(() => byText('응원 바꾸기', { exact: true }));
  await cheerBtn.click();
  await waitText('님에게 한마디');
  await clickText('🔥 응원해요', { exact: true, last: true });
  await shot('cheer-modal');

  await go('/mypage');
  await waitText('cm');
  await tallShot('mypage');
  await go('/board/notice');
  await waitText('서비스 점검 안내');
  await shot('notice');
  await go('/board/qna');
  await waitText('기준 영상 변경이 안 돼요');
  await shot('qna');

  console.log(`완료 — ${n}장`);
  if (faked.length) console.log(`  (서버로 안 보내고 막은 쓰기 요청 ${faked.length}건: ${[...new Set(faked)].join(', ')})`);
} catch (e) {
  await page.screenshot({ path: OUT + 'error.png' }).catch(() => {});
  console.error(`실패 — 그 순간 화면을 screenshots/error.png 에 남겼다 (${n}장까지 찍음)`);
  throw e;
} finally {
  await browser.close();
}
