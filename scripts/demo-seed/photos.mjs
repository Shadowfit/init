// 데모 피드 사진 12장 — 실사 대신 «운동 장면 카드» 일러스트를 HTML 로 그려 Edge 헤드리스로 PNG 를 찍는다.
//   node scripts/demo-seed/photos.mjs <html 출력 폴더>
// 파일 이름(UUID)은 seed.mjs 의 PHOTOS 와 같아야 한다 — 시드 SQL 의 photoUrl 이 이 이름을 가리킨다.
import fs from 'node:fs';
import path from 'node:path';
import { PHOTOS } from './data.mjs';

const out = process.argv[2];
if (!out) {
  console.error('usage: node photos.mjs <outDir>');
  process.exit(1);
}
fs.mkdirSync(out, { recursive: true });

const page = (p) => `<!doctype html><html><head><meta charset="utf-8"><style>
html,body{margin:0;width:800px;height:600px;overflow:hidden}
body{background:${p.bg};font-family:'Segoe UI','Malgun Gothic',sans-serif;position:relative}
.sun{position:absolute;border-radius:50%;filter:blur(${p.blur ?? 0}px)}
.hill{position:absolute;left:-10%;right:-10%;border-radius:50% 50% 0 0}
.floor{position:absolute;left:0;right:0;bottom:0;height:${p.floorH ?? 150}px;background:${p.floor}}
.hero{position:absolute;left:0;right:0;top:${p.heroTop ?? 150}px;text-align:center;font-size:${p.heroSize ?? 210}px;line-height:1;
  font-family:'Segoe UI Emoji','Apple Color Emoji','Noto Color Emoji',sans-serif;filter:drop-shadow(0 18px 24px rgba(0,0,0,.28))}
.side{position:absolute;font-size:74px;font-family:'Segoe UI Emoji','Apple Color Emoji',sans-serif;opacity:.95}
.tag{position:absolute;left:34px;bottom:30px;color:${p.ink ?? '#fff'};font-weight:800;font-size:30px;letter-spacing:-.5px;
  text-shadow:0 2px 12px rgba(0,0,0,.25)}
.tag small{display:block;font-size:17px;font-weight:600;opacity:.82;letter-spacing:.5px;margin-top:4px}
.stamp{position:absolute;right:30px;top:28px;background:${p.stampBg ?? 'rgba(255,255,255,.88)'};color:${p.stampInk ?? '#14170f'};
  font-weight:800;font-size:18px;padding:8px 14px;border-radius:999px;letter-spacing:.5px}
.grain{position:absolute;inset:0;background:radial-gradient(rgba(255,255,255,.06) 1px,transparent 1px) 0 0/6px 6px;mix-blend-mode:overlay}
</style></head><body>
${(p.suns ?? []).map((s) => `<div class="sun" style="left:${s[0]}px;top:${s[1]}px;width:${s[2]}px;height:${s[2]}px;background:${s[3]}"></div>`).join('')}
${(p.hills ?? []).map((h) => `<div class="hill" style="bottom:${h[0]}px;height:${h[1]}px;background:${h[2]}"></div>`).join('')}
${p.floor ? '<div class="floor"></div>' : ''}
${(p.sides ?? []).map((s) => `<div class="side" style="left:${s[0]}px;top:${s[1]}px;font-size:${s[3] ?? 74}px">${s[2]}</div>`).join('')}
<div class="hero">${p.hero}</div>
<div class="stamp">${p.stamp}</div>
<div class="tag">${p.title}<small>${p.sub}</small></div>
<div class="grain"></div>
</body></html>`;

for (const p of PHOTOS) {
  fs.writeFileSync(path.join(out, `${p.file}.html`), page(p));
}
console.log(`${PHOTOS.length} scenes → ${out}`);
