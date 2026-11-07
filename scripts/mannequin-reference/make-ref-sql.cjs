// 3D 마네킹 시범(frontend/lib/motion/fixtures/reference_squat.json 의 3D 관절) → AI 싱크로율 기준 좌표(exercise_references) SQL.
//
//   node scripts/mannequin-reference/make-ref-sql.cjs > /tmp/ref.sql
//   docker exec -i shadowfit-mysql mysql --default-character-set=utf8mb4 -u shadowfit -pshadowfit shadowfit < /tmp/ref.sql
//
// 왜 영상 업로드(관리자 › 기준 영상)가 아니라 관절 좌표를 직접 넣나(2026-10-20):
//   마네킹을 영상으로 렌더링해 MediaPipe 로 다시 읽으면 무릎이 바깥으로 벌어진 자세 때문에 2D 무릎각이 실제(71°)보다
//   20° 넘게 얕게 읽히고(어느 카메라 각도에서도 93° 이상), 45° 에서는 «런지 자세» 로 오인돼 반복이 무효가 된다.
//   여기서는 장면이 계산한 3D 관절을 시상면(앞뒤·높이)에 투영한 «가상 랜드마크» 를 만든다 — 너비/높이 비율 1.0,
//   2D 각도 = 3D 각도. 3D 비교(lib/motion)가 쓰는 기준과 같은 숫자라 두 점수가 같은 잣대를 본다.
//   끝에 index=-1 표식(image_aspect_ratio=1.0, source)을 붙여 AI 가 비율 보정 없이 읽게 한다(ai-server _parse_reference_poses).
// 기준 샘플(reference_squat.json)을 다시 뽑는 법: 앱의 3D 비교 화면이 띄우는 장면이 type:'reference' 메시지로 보내는 cycles —
//   frontend/lib/motion/run-tests.cjs 상단 주석 참고.
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..');
const ref = JSON.parse(fs.readFileSync(path.join(ROOT, 'frontend/lib/motion/fixtures/reference_squat.json'), 'utf8')).cycles[0];
const IDX = { lShoulder: 11, rShoulder: 12, lHip: 23, rHip: 24, lKnee: 25, rKnee: 26, lAnkle: 27, rAnkle: 28 };
const rows = ref.samples.map((s) => {
  const items = Object.entries(IDX).map(([n, i]) => {
    const p = s.joints[n];   // [x 좌우, y 높이, z 앞뒤] (m)
    // 앞뒤·높이에 같은 배율(0.45) — 다르면 2D 각도가 비틀린다(2026-10-21 이전 0.35/0.45 → 무릎 71°가 77°로 읽혔다).
    // admin-web/src/mannequinReference.ts 의 PROJ_SCALE 과 같아야 한다.
    return { index: i, x: +(0.5 + p[2] * 0.45).toFixed(4), y: +(0.95 - p[1] * 0.45).toFixed(4), z: +p[0].toFixed(4), visibility: 1 };
  });
  items.push({ index: -1, x: 0, y: 0, z: 0, visibility: 0, image_aspect_ratio: 1.0, source: 'mannequin-3d-joints' });
  return { t: s.t, json: JSON.stringify(items) };
});
const esc = (s) => s.replace(/\\/g, '\\\\').replace(/'/g, "''");
process.stdout.write(
  'DELETE FROM exercise_references WHERE exercise_id=1;\n'
  + 'INSERT INTO exercise_references (exercise_id, timestamp_sec, joint_coordinates) VALUES\n'
  + rows.map((r) => `(1, ${r.t.toFixed(3)}, '${esc(r.json)}')`).join(',\n') + ';\n',
);
process.stderr.write(`rows ${rows.length} · ${rows[0].t}s ~ ${rows[rows.length - 1].t}s · dt ${ref.dt}\n`);
