require('./register.cjs');
const fs = require('fs'), path = require('path');
const { prepareReference } = require('./reference');
const { compareRecording } = require('./analysis');
const { feedbackForSession } = require('./feedback');
const FIX = path.join(__dirname, 'fixtures');
const load = (n) => JSON.parse(fs.readFileSync(path.join(FIX, n), 'utf8'));
const sq = prepareReference(load('reference_squat.json').cycles[0]);
const lg = load('reference_lunge.json').cycles.reduce((o, c) => ({ ...o, [c.lead]: prepareReference(c) }), {});
for (const n of ['squat_normal_01', 'squat_normal_02', 'squat_side_01', 'squat_shallow_01', 'lunge_normal_01', 'lunge_sallow_01', 'lunge_walking_01']) {
  const rec = load(n + '.json');
  const c = compareRecording(rec, { squat: sq, lunge: lg });
  console.log(`\n${n}: 유사도 ${c.similarity}, 분석 ${c.analyzed}/${c.reps.length}회, 프레임 ${c.quality.usableFrames}/${c.quality.totalFrames}`);
  for (const r of c.reps) {
    if (r.status === 'skipped') { console.log(`  ${r.repNumber}회 건너뜀: ${r.reason}`); continue; }
    console.log(`  ${r.repNumber}회 [${r.lead ?? '-'}] 유사도 ${r.similarity} (d̄ ${r.meanDistance.toFixed(2)}) 프레임 ${r.frames} 최저무릎 ${r.kneeMin.user.toFixed(0)}° vs 기준 ${r.kneeMin.ref.toFixed(0)}° / 상체 ${r.trunkMax.user.toFixed(0)}° vs ${r.trunkMax.ref.toFixed(0)}° / 시간 ${r.timing.userSec.toFixed(1)}s vs ${r.timing.refSec.toFixed(1)}s`);
  }
  if (process.argv[2] === 'fb') feedbackForSession(c).forEach((f) => console.log('   -', f.severity, f.text));
}
