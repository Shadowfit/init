// 관리자 웹 화면 전체 캡처 — 발표·보고서용.
//   백엔드(8080)와 `npm run dev`(5173)를 띄운 상태에서:  npm run capture
// 결과: screenshots/NN-이름.png — 1440px 폭 · 2배 해상도. 목록 화면은 페이지 전체, 상세 패널·확인창은 보이는 화면만.
// 브라우저는 설치된 Edge/Chrome 을 화면 없이 띄운다(BROWSER=경로 로 바꿀 수 있다). 데이터는 건드리지 않는다 — 삭제 확인창은 열기만 한다.
import { existsSync } from 'node:fs';
import { mkdir, rm } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import puppeteer from 'puppeteer-core';

const BASE = process.env.BASE ?? 'http://localhost:5173';
const OUT = fileURLToPath(new URL('../screenshots/', import.meta.url));
const PASSWORD = process.env.PASSWORD ?? 'Shadow1234!';
const ADMIN = process.env.ADMIN_EMAIL ?? 'admin@shadowfit.kr';
const NON_ADMIN = process.env.USER_EMAIL ?? 'jiho@shadowfit.kr';

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
const browser = await puppeteer.launch({
  executablePath,
  headless: true,
  defaultViewport: { width: 1440, height: 900, deviceScaleFactor: 2 },
});
const page = await browser.newPage();
let n = 0;

// 요청이 잦아들고 스피너가 사라지고 글꼴까지 그려진 뒤에 찍는다
async function settle() {
  await page.waitForNetworkIdle({ idleTime: 400, timeout: 15000 }).catch(() => {});
  await page.waitForFunction(() => !document.querySelector('.spinner'), { timeout: 15000 }).catch(() => {});
  await page.evaluate(() => document.fonts.ready);
  await new Promise((r) => setTimeout(r, 300));
}

async function shot(name, { full = true } = {}) {
  await settle();
  const file = `${String(++n).padStart(2, '0')}-${name}.png`;
  await page.screenshot({ path: OUT + file, fullPage: full });
  console.log('  ✓', file);
}

const go = (path) => page.goto(BASE + path, { waitUntil: 'networkidle0' });
async function click(selector) {
  await page.waitForSelector(selector, { visible: true });
  await page.click(selector);
}
async function openRow(text) {
  await page.waitForSelector('tr.clickable', { visible: true });
  const row = await page.$(`tr.clickable ::-p-text(${text})`);
  await (row ?? (await page.$('tr.clickable'))).click();
}
// React 입력칸은 DOM 값을 직접 지우면 상태와 어긋난다 — 사람처럼 전체 선택 후 지우고 친다
async function fill(selector, text) {
  await page.click(selector, { count: 3 });
  await page.keyboard.press('Backspace');
  await page.type(selector, text);
}
async function login(email) {
  await page.waitForSelector('#login-email');
  await fill('#login-email', email);
  await fill('#login-password', PASSWORD);
  await click('.auth-card button.btn-primary');
}

try {
  console.log(`캡처 → ${OUT}`);
  await go('/');
  await page.evaluate(() => localStorage.clear());
  await go('/');
  await shot('login', { full: false });

  await login(NON_ADMIN);
  await page.waitForSelector('.alert-danger');
  await shot('login-not-admin', { full: false });

  await login(ADMIN);
  await page.waitForSelector('.sidebar');
  await shot('dashboard');

  await go('/members');
  await shot('members');
  // 상세 패널은 기록이 많은 행으로 연다(시드 데이터 기준 — 없으면 첫 행)
  await openRow(NON_ADMIN);
  await page.waitForSelector('.drawer .mini-stats');
  await shot('member-detail', { full: false });

  await go('/sessions');
  await shot('sessions');

  await go('/exercises');
  await shot('exercises');
  await openRow('SQUAT');
  await page.waitForSelector('.drawer button::-p-text(정보 수정)');
  await shot('exercise-detail', { full: false });
  await click('.drawer button::-p-text(정보 수정)');
  await page.waitForSelector('.modal');
  await shot('exercise-edit', { full: false });

  await go('/categories');
  await shot('categories');

  await go('/groups');
  await shot('groups');
  await openRow('아침 7시 스쿼트단');
  await page.waitForSelector('.drawer button::-p-text(모임 삭제)');
  await shot('group-detail', { full: false });

  await go('/feed-posts');
  await shot('feed-posts');
  await click('button::-p-text(내리기)');
  await page.waitForSelector('.modal');
  await shot('feed-post-takedown', { full: false });

  console.log(`완료 — ${n}장`);
} catch (e) {
  await page.screenshot({ path: OUT + 'error.png' }).catch(() => {});
  console.error('실패 — 그 순간 화면을 screenshots/error.png 에 남겼다');
  throw e;
} finally {
  await browser.close();
}
