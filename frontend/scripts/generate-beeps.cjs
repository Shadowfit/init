/* 운동 중 비프음 3종을 만든다 — 저장소에는 결과 WAV 가 들어 있고, 소리를 바꾸고 싶을 때만 다시 돌린다:
 *   node scripts/generate-beeps.cjs
 *
 * 같은 입력이면 같은 바이트가 나온다(난수 없음). 외부 소리 파일·라이선스에 기대지 않으려고 사인파로 직접 합성한다.
 *
 *   success  rep 완료   — 상승 2음(솔→레). «됐다» 는 느낌, 밝고 짧게
 *   warning  자세 결함  — 낮고 길게 한 번(솔4 + 배음). 폰 스피커는 400Hz 아래가 약해서 배음을 섞어 들리게 한다
 *   notice   전신 안 보임 — 짧은 2연속(레#5). 급하지 않은 «확인해 보세요»
 *
 * 규격: 16bit PCM · 모노 · 44.1kHz. 시작·끝 6ms 페이드로 «딱» 소리(클릭)를 막고, 최대 진폭 0.55 로 클리핑을 피한다. */
const fs = require('fs');
const path = require('path');

const RATE = 44100;
const PEAK = 0.55;
const FADE_MS = 6;

/** 한 음: freq(Hz), ms, 배음 [[배수, 상대진폭], ...] */
function tone(freq, ms, harmonics = []) {
  const n = Math.round((RATE * ms) / 1000);
  const fade = Math.round((RATE * FADE_MS) / 1000);
  const out = new Float64Array(n);
  const norm = 1 + harmonics.reduce((s, [, a]) => s + a, 0);
  for (let i = 0; i < n; i++) {
    const t = i / RATE;
    let v = Math.sin(2 * Math.PI * freq * t);
    for (const [mult, amp] of harmonics) v += amp * Math.sin(2 * Math.PI * freq * mult * t);
    v /= norm;
    const env = Math.min(1, i / fade, (n - 1 - i) / fade);
    out[i] = v * env;
  }
  return out;
}

function silence(ms) {
  return new Float64Array(Math.round((RATE * ms) / 1000));
}

function concat(parts) {
  const total = parts.reduce((s, p) => s + p.length, 0);
  const out = new Float64Array(total);
  let o = 0;
  for (const p of parts) { out.set(p, o); o += p.length; }
  return out;
}

function toWav(samples) {
  const dataLen = samples.length * 2;
  const buf = Buffer.alloc(44 + dataLen);
  buf.write('RIFF', 0);
  buf.writeUInt32LE(36 + dataLen, 4);
  buf.write('WAVE', 8);
  buf.write('fmt ', 12);
  buf.writeUInt32LE(16, 16);          // fmt 청크 크기
  buf.writeUInt16LE(1, 20);           // PCM
  buf.writeUInt16LE(1, 22);           // 모노
  buf.writeUInt32LE(RATE, 24);
  buf.writeUInt32LE(RATE * 2, 28);    // 바이트/초
  buf.writeUInt16LE(2, 32);           // 블록 정렬
  buf.writeUInt16LE(16, 34);          // 비트
  buf.write('data', 36);
  buf.writeUInt32LE(dataLen, 40);
  // 소리마다 최대 진폭을 PEAK 로 맞춘다. 배음을 섞은 경고음은 합을 1.75 로 나눠 만들면 최대가 0.8 쯤에 머물러
  // 다른 소리보다 작게 들리는데, 가장 중요한 소리가 제일 작으면 안 된다.
  let max = 0;
  for (let i = 0; i < samples.length; i++) max = Math.max(max, Math.abs(samples[i]));
  const gain = max > 0 ? PEAK / max : 0;
  for (let i = 0; i < samples.length; i++) {
    buf.writeInt16LE(Math.round(Math.max(-1, Math.min(1, samples[i] * gain)) * 32767), 44 + i * 2);
  }
  return buf;
}

const SOUNDS = {
  'beep-success.wav': concat([tone(784, 90), silence(20), tone(1175, 150)]),
  'beep-warning.wav': tone(392, 240, [[2, 0.5], [3, 0.25]]),
  'beep-notice.wav': concat([tone(622, 70), silence(80), tone(622, 70)]),
};

if (require.main === module) {
  const dir = path.join(__dirname, '..', 'assets', 'sounds');
  fs.mkdirSync(dir, { recursive: true });
  for (const [name, samples] of Object.entries(SOUNDS)) {
    const wav = toWav(samples);
    fs.writeFileSync(path.join(dir, name), wav);
    console.log(`${name}  ${(samples.length / RATE * 1000).toFixed(0)}ms  ${wav.length}B`);
  }
}

module.exports = { SOUNDS, toWav, RATE };
