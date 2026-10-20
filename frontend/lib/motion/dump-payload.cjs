/* 실촬영 기록 → 3D 장면용 데이터(JSON) 내보내기 (시각 점검용):  node lib/motion/dump-payload.cjs squat_normal_02 3 out.json */
require('./register.cjs');
const fs = require('fs'), path = require('path');
const { prepareReference } = require('./reference');
const { compareRecording } = require('./analysis');
const { buildPayloads } = require('./viz');
const FIX = path.join(__dirname, 'fixtures');
const load = (n) => JSON.parse(fs.readFileSync(path.join(FIX, n), 'utf8'));
const [name, repArg, out] = process.argv.slice(2);
const REAL = {
  squat: prepareReference(load('reference_squat.json').cycles[0]),
  lunge: Object.fromEntries(load('reference_lunge.json').cycles.map((c) => [c.lead, prepareReference(c)])),
};
const rec = load(name + '.json');
const cmp = compareRecording(rec, REAL);
const payloads = buildPayloads(rec, cmp.reps, REAL);
const pick = payloads.find((p) => p.repNumber === Number(repArg)) || payloads[0];
fs.writeFileSync(out, JSON.stringify(pick));
console.log(name, '반복', pick.repNumber, '프레임', pick.frames.length, 'refOffset', pick.refOffset);
