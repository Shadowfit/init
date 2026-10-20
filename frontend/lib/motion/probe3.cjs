require('./register.cjs');
const fs = require('fs'), path = require('path');
const { cleanRecording } = require('./preprocess');
const { featuresFromLandmarks } = require('./features');
const { splitReps } = require('./analysis');
for (const name of ['lunge_normal_01', 'lunge_sallow_01', 'lunge_walking_01']) {
  const rec = JSON.parse(fs.readFileSync(path.join(__dirname, 'fixtures', name + '.json'), 'utf8'));
  const clean = cleanRecording(rec);
  const out = [];
  for (const w of splitReps(clean)) {
    let best = null, aiLead = {left: 0, right: 0};
    for (let i = w.start; i <= w.end; i++) {
      if (clean[i].lead) aiLead[clean[i].lead]++;
      if (!clean[i].ok) continue;
      const f = featuresFromLandmarks(clean[i].lm, rec.aspect, clean[i].t);
      if (!f) continue;
      const m = (f.kneeL + f.kneeR) / 2;
      if (!best || m < best.m) best = { m, f, i };
    }
    if (!best) continue;
    const geo = best.f.hipL < best.f.hipR ? 'left' : 'right';
    const ai = aiLead.left >= aiLead.right ? 'left' : 'right';
    out.push(`${w.repNumber}:AI=${ai}/기하=${geo}(hip ${best.f.hipL.toFixed(0)}/${best.f.hipR.toFixed(0)})`);
  }
  console.log(name, out.join('  '));
}
