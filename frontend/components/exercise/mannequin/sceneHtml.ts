// 3D 근육 인체 모형 시범 장면 (2026-10-17). MakeHuman(CC0) 기반 인체 GLB 하나를 뼈대(스켈레톤)로 움직인다.
// 피부는 이어진 메시 하나이고, 근육 부위는 정점 속성 마스크(_MASKA/_MASKB)를 따라 셰이더가 색만 섞는다 — 모양·크기는 그대로.
// 관절 목표 위치는 키프레임 + 2-링크 IK 로 계산하고, 결과를 뼈 회전으로 바꿔 넣는다.
// /*__THREE__*/, /*__GLTF__*/, /*__BODY__*/, /*__CONFIG__*/ 자리는 buildMannequinHtml 이 채운다.
export const SCENE_TEMPLATE = `<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no" />
<style>
  html, body { margin: 0; padding: 0; width: 100%; height: 100%; overflow: hidden; touch-action: none;
               background: radial-gradient(ellipse at 50% 42%, #6b6b6b 0%, #3a3a3a 45%, #121212 100%); }
  canvas { display: block; }
  .hud { position: absolute; pointer-events: none; font-family: -apple-system, 'Segoe UI', 'Malgun Gothic', sans-serif; color: #fff;
         text-shadow: 0 1px 3px rgba(0,0,0,.7); }
  #top { left: 8px; top: 8px; max-width: 46%; font-size: 11px; line-height: 1.4; padding: 4px 8px; border-radius: 8px; background: rgba(0,0,0,.45); text-shadow: none; }
  #phase { font-weight: 700; font-size: 12px; }
  #legend { left: 8px; bottom: 8px; right: 8px; display: flex; flex-wrap: wrap; gap: 4px 5px; }
  .chip { display: flex; align-items: center; gap: 4px; padding: 2px 7px 2px 5px; border-radius: 10px; font-size: 11px;
          background: rgba(0,0,0,.45); text-shadow: none; }
  .dot { width: 8px; height: 8px; border-radius: 4px; }
  #hint { right: 10px; top: 8px; font-size: 10px; color: rgba(255,255,255,.6); }
</style>
</head>
<body>
<div class="hud" id="top"><div id="phase"></div><div id="sub"></div></div>
<div class="hud" id="hint">드래그해서 돌려보기</div>
<div class="hud" id="legend"></div>
<script>/*__THREE__*/</script>
<script>/*__GLTF__*/</script>
<script>/*__BODY__*/</script>
<script>/*__CONFIG__*/</script>
<script>
(function () {
  var EXERCISE = window.__EXERCISE__ === 'lunge' ? 'lunge' : 'squat';
  var V = THREE.Vector3, Q = THREE.Quaternion, NL = String.fromCharCode(10);
  // 색 값을 화면에 보이는 그대로(sRGB) 해석한다
  if (THREE.ColorManagement) THREE.ColorManagement.legacyMode = false;
  var phaseEl = document.getElementById('phase'), subEl = document.getElementById('sub');

  // ── 장면 ────────────────────────────────────────────────
  var renderer;
  try {
    renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
  } catch (e) {
    phaseEl.textContent = '이 기기에서는 3D 시범을 표시할 수 없어요';
    document.getElementById('hint').style.display = 'none';
    return;
  }
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFSoftShadowMap;
  renderer.outputEncoding = THREE.sRGBEncoding;
  document.body.appendChild(renderer.domElement);

  var scene = new THREE.Scene();
  var camera = new THREE.PerspectiveCamera(32, 1, 0.05, 50);

  // 조각상 느낌: 위에서 부드러운 키 라이트 + 뒤에서 약한 림 라이트 (색 판단을 방해하지 않게 무채색)
  scene.add(new THREE.HemisphereLight(0xf4f4f4, 0x303030, 0.6));
  var key = new THREE.DirectionalLight(0xffffff, 0.95);
  key.position.set(1.2, 3.2, 2.6);
  key.castShadow = true;
  key.shadow.mapSize.set(1024, 1024);
  key.shadow.camera.left = -1.4; key.shadow.camera.right = 1.4;
  key.shadow.camera.top = 1.4; key.shadow.camera.bottom = -1.4;
  key.shadow.radius = 5;
  key.shadow.bias = -0.0005;
  scene.add(key);
  var fill = new THREE.DirectionalLight(0xffffff, 0.3); fill.position.set(-2.2, 1.4, 1.5); scene.add(fill);
  var rim = new THREE.DirectionalLight(0xffffff, 0.55); rim.position.set(0, 1.8, -2.6); scene.add(rim);

  var floor = new THREE.Mesh(new THREE.CircleGeometry(1.3, 48), new THREE.ShadowMaterial({ opacity: 0.35 }));
  floor.rotation.x = -Math.PI / 2;
  floor.receiveShadow = true;
  scene.add(floor);

  // ── 색 ──────────────────────────────────────────────────
  // 주동 = 부드러운 빨강, 보조 = 주황, 안정 = 노랑, 쉬는 근육 = 피부 회색 그대로
  var SKIN = new THREE.Color(0x9a9a9a);
  var C_MAIN = new THREE.Color(0xd9574a), C_SUP = new THREE.Color(0xe8914a), C_STAB = new THREE.Color(0xe6c35a);
  function tierColor(w) { return w >= 0.9 ? C_MAIN : (w >= 0.45 ? C_SUP : C_STAB); }

  // ── 운동 부위 ───────────────────────────────────────────
  // 마스크 순서: A = (대퇴사두근, 대둔근, 햄스트링, 내전근), B = (종아리, 척추기립근, 복근, 어깨)
  var REGIONS = ['quads', 'glutes', 'hamstrings', 'adductors', 'calves', 'erector', 'core', 'shoulders'];
  var NAMES = { quads: '대퇴사두근', glutes: '대둔근', adductors: '내전근', hamstrings: '햄스트링', erector: '척추기립근', core: '복근', calves: '종아리', shoulders: '어깨' };
  var LEGEND = EXERCISE === 'lunge'
    ? [['quads', 1], ['glutes', 1], ['hamstrings', 0.55], ['adductors', 0.5], ['calves', 0.4], ['core', 0.3]]
    : [['quads', 1], ['glutes', 1], ['adductors', 0.6], ['hamstrings', 0.5], ['erector', 0.5], ['core', 0.3], ['calves', 0.3]];
  // 부위의 비중(주동 1.0 / 보조 0.5 안팎 / 안정 0.3 안팎). side: 'L' | 'R'
  function weightOf(p, group, side) {
    if (EXERCISE === 'squat') return { quads: 1, glutes: 1, adductors: 0.6, hamstrings: 0.5, erector: 0.5, core: 0.3, calves: 0.3 }[group] || 0;
    if (group === 'core') return 0.3;
    if (group === 'erector') return 0.25;
    var table = side === p.lead
      ? { quads: 1, glutes: 1, hamstrings: 0.55, adductors: 0.5, calves: 0.3 }
      : { quads: 0.55, glutes: 0.35, hamstrings: 0.2, adductors: 0.3, calves: 0.45 };
    return table[group] || 0;
  }
  // 지금 자세에서 얼마나 힘이 들어가는가 0..1 (깊이 내려갈수록 진해진다)
  function effortOf(p) {
    if (EXERCISE === 'squat') return 0.05 + 0.95 * p.depth;
    return 0.05 + 0.95 * Math.max(p.depth, (p.step || 0) * 0.25);
  }

  var legendEl = document.getElementById('legend');
  [['주동', 0.9, 1], ['보조', 0.45, 0.6], ['안정', 0, 0.3]].forEach(function (tier, i, all) {
    var names = LEGEND.filter(function (it) { return it[1] >= tier[1] && (i === 0 || it[1] < all[i - 1][1]); })
                      .map(function (it) { return NAMES[it[0]]; });
    if (!names.length) return;
    var chip = document.createElement('div');
    chip.className = 'chip';
    chip.innerHTML = '<span class="dot" style="background:#' + tierColor(tier[2]).getHexString() + '"></span>'
      + '<b style="font-weight:700">' + tier[0] + '</b>&nbsp;' + names.join(' · ');
    legendEl.appendChild(chip);
  });

  // 셰이더에 넘길 값: 부위마다 (색, 세기). 왼쪽·오른쪽 따로 (런지는 앞다리·뒷다리가 다르다)
  var U = {
    uColL: { value: [] }, uColR: { value: [] }, uAmtL: { value: [] }, uAmtR: { value: [] },
  };
  for (var r = 0; r < 8; r++) {
    U.uColL.value.push(new THREE.Color()); U.uColR.value.push(new THREE.Color());
    U.uAmtL.value.push(0); U.uAmtR.value.push(0);
  }
  function paint(p) {
    var e = effortOf(p);
    for (var i = 0; i < 8; i++) {
      var g = REGIONS[i];
      var wl = weightOf(p, g, 'L'), wr = weightOf(p, g, 'R');
      U.uColL.value[i].copy(tierColor(wl)); U.uColR.value[i].copy(tierColor(wr));
      U.uAmtL.value[i] = wl > 0 ? e : 0; U.uAmtR.value[i] = wr > 0 ? e : 0;
      // 점검용: window.__SOLO__ = 부위 번호 → 그 부위만 빨갛게
      if (typeof window.__SOLO__ === 'number') {
        U.uColL.value[i].copy(C_MAIN); U.uColR.value[i].copy(C_MAIN);
        U.uAmtL.value[i] = U.uAmtR.value[i] = i === window.__SOLO__ ? 1 : 0;
      }
    }
  }

  // 피부 재질: 매끈한 무채색. 근육 부위 마스크(정점 속성)를 따라 색만 섞는다 — 모양·크기는 그대로
  var bodyMat = new THREE.MeshStandardMaterial({ color: SKIN, roughness: 0.62, metalness: 0.0 });
  bodyMat.onBeforeCompile = function (sh) {
    for (var k in U) sh.uniforms[k] = U[k];
    sh.vertexShader = sh.vertexShader
      .replace('#include <common>', [
        '#include <common>',
        'attribute vec4 _maska;', 'attribute vec4 _maskb;',
        'varying vec4 vMaskA;', 'varying vec4 vMaskB;', 'varying float vSideL;'].join(NL))
      .replace('#include <begin_vertex>', [
        '#include <begin_vertex>',
        'vMaskA = _maska; vMaskB = _maskb;',
        'vSideL = smoothstep(-0.01, 0.01, position.x);'].join(NL));
    sh.fragmentShader = sh.fragmentShader
      .replace('#include <common>', [
        '#include <common>',
        'uniform vec3 uColL[8];', 'uniform vec3 uColR[8];', 'uniform float uAmtL[8];', 'uniform float uAmtR[8];',
        'varying vec4 vMaskA;', 'varying vec4 vMaskB;', 'varying float vSideL;'].join(NL))
      .replace('vec4 diffuseColor = vec4( diffuse, opacity );', [
        'float m[8];',
        'm[0] = vMaskA.x; m[1] = vMaskA.y; m[2] = vMaskA.z; m[3] = vMaskA.w;',
        'm[4] = vMaskB.x; m[5] = vMaskB.y; m[6] = vMaskB.z; m[7] = vMaskB.w;',
        'vec3 acc = vec3(0.0); float wsum = 0.0; float top = 0.0;',
        'for (int i = 0; i < 8; i++) {',
        '  vec3 c = mix(uColR[i], uColL[i], vSideL);',
        '  float s = m[i] * mix(uAmtR[i], uAmtL[i], vSideL);',
        '  acc += c * s; wsum += s; top = max(top, s);',
        '}',
        'vec3 tint = wsum > 0.0001 ? acc / wsum : diffuse;',
        'vec4 diffuseColor = vec4(mix(diffuse, tint, clamp(top, 0.0, 1.0) * 0.85), opacity);'].join(NL));
  };

  // ── 모델 불러오기 (앱에 묶어 둔 GLB, 인터넷 필요 없음) ──────
  phaseEl.textContent = '모델 불러오는 중…';
  var B = null;       // 뼈 이름 → THREE.Bone
  var REST = {};      // 뼈 이름 → 처음 자세의 로컬 회전·위치
  var M = {};         // 모델 치수 (m)
  function b64ToBuffer(s) {
    var bin = atob(s), n = bin.length, u = new Uint8Array(n);
    for (var i = 0; i < n; i++) u[i] = bin.charCodeAt(i);
    return u.buffer;
  }
  function wpos(name) { return B[name].getWorldPosition(new V()); }
  try {
    new THREE.GLTFLoader().parse(b64ToBuffer(window.__BODY_GLB__), '', onModel, function (err) {
      phaseEl.textContent = '모델을 불러오지 못했어요';
      console.error(err);
    });
  } catch (e) {
    phaseEl.textContent = '모델을 불러오지 못했어요';
    console.error(e);
  }

  function onModel(gltf) {
    var root = gltf.scene;
    B = {};
    root.traverse(function (o) {
      if (o.isBone) B[o.name] = o;
      if (o.isSkinnedMesh || o.isMesh) {
        o.material = bodyMat;
        o.castShadow = true;
        o.frustumCulled = false;
      }
    });
    scene.add(root);
    root.updateMatrixWorld(true);
    for (var n in B) REST[n] = { q: B[n].quaternion.clone(), p: B[n].position.clone() };
    // 모델 자체의 뼈 길이를 쓴다 (늘어나거나 줄어들지 않게)
    M.thigh = wpos('thigh_l').distanceTo(wpos('calf_l'));
    M.shin = wpos('calf_l').distanceTo(wpos('foot_l'));
    M.uarm = wpos('upperarm_l').distanceTo(wpos('lowerarm_l'));
    M.farm = wpos('lowerarm_l').distanceTo(wpos('hand_l'));
    M.root = wpos('Root');
    M.pelvis = wpos('pelvis');
    M.ankle = { L: wpos('foot_l'), R: wpos('foot_r') };
    M.ball = { L: wpos('ball_l'), R: wpos('ball_r') };
    M.leg = M.thigh + M.shin;
    M.restWQ = {};
    for (n in B) M.restWQ[n] = B[n].getWorldQuaternion(new Q());
    requestAnimationFrame(frame);
  }

  // ── 뼈 조작 ─────────────────────────────────────────────
  var _q = new Q(), _q2 = new Q(), _a = new V(), _b = new V(), _c = new V();
  // 뼈의 세계 회전을 정한다 (부모 기준 로컬 회전으로 바꿔 넣는다)
  function setWorldQuat(bone, wq) {
    bone.parent.getWorldQuaternion(_q2);
    bone.quaternion.copy(_q2.invert().multiply(wq));
    bone.updateMatrixWorld(true);
  }
  // 뼈가 지금 child 쪽을 보는 방향을 target 쪽으로 돌린다 (가장 작은 회전 — 비틀림이 생기지 않는다)
  function aim(name, childName, target) {
    var bone = B[name];
    bone.getWorldPosition(_a);
    B[childName].getWorldPosition(_b);
    _b.sub(_a).normalize();
    _c.copy(target).sub(_a).normalize();
    _q.setFromUnitVectors(_b, _c);
    bone.getWorldQuaternion(_q2);
    setWorldQuat(bone, _q.multiply(_q2));
  }
  // 세계 X축(좌우축) 기준으로 앞(+)으로 숙인다
  var AX = new V(1, 0, 0);
  function tilt(name, ang) {
    var bone = B[name];
    bone.getWorldQuaternion(_q2);
    _q.setFromAxisAngle(AX, ang);
    setWorldQuat(bone, _q.multiply(_q2));
  }
  function resetPose() {
    for (var n in B) { B[n].quaternion.copy(REST[n].q); B[n].position.copy(REST[n].p); }
  }

  // 엉덩이·발목 위치에서 무릎을 구한다 (2-링크 IK). pole 쪽(앞, 살짝 바깥)으로 굽는다
  function solveKnee(hip, ankle, pole) {
    var d = new V().subVectors(ankle, hip);
    var dist = Math.min(d.length(), M.leg - 1e-4);
    d.normalize();
    var a = (M.thigh * M.thigh - M.shin * M.shin + dist * dist) / (2 * dist);
    var h = Math.sqrt(Math.max(0, M.thigh * M.thigh - a * a));
    var perp = pole.clone().addScaledVector(d, -pole.dot(d)).normalize();
    return hip.clone().addScaledVector(d, a).addScaledVector(perp, h);
  }
  function kneeAngle(hip, knee, ankle) {
    var a = new V().subVectors(hip, knee).normalize(), b = new V().subVectors(ankle, knee).normalize();
    return Math.acos(Math.max(-1, Math.min(1, a.dot(b)))) * 180 / Math.PI;
  }

  // ── 동작 (키프레임) ─────────────────────────────────────
  function ease(x) { x = Math.max(0, Math.min(1, x)); return x * x * (3 - 2 * x); }
  // 구간표 [길이(초), 시작값, 끝값] 를 따라 0..1 값을 돌려준다
  function track(segs, t) {
    var total = 0, i;
    for (i = 0; i < segs.length; i++) total += segs[i][0];
    t = t % total;
    for (i = 0; i < segs.length; i++) {
      if (t <= segs[i][0]) return segs[i][1] + (segs[i][2] - segs[i][1]) * ease(t / segs[i][0]);
      t -= segs[i][0];
    }
    return segs[segs.length - 1][2];
  }
  function phaseOf(t, table) { for (var i = 0; i < table.length; i++) if (t < table[i][0]) return table[i][1]; return table[table.length - 1][1]; }

  // 거리는 다리 길이(L) 비율로 둔다 — 모델 키가 바뀌어도 동작이 그대로 맞는다
  var SQUAT = { period: 4.2, depth: [[0.9, 0, 0], [1.3, 0, 1], [0.5, 1, 1], [1.2, 1, 0], [0.3, 0, 0]] };
  function poseSquat(t) {
    var dp = track(SQUAT.depth, t), L = M.leg;
    var ankles = {}, wide = 0.04;
    ['L', 'R'].forEach(function (k) {
      var a = M.ankle[k].clone(); a.x += (k === 'L' ? 1 : -1) * wide; ankles[k] = a;
    });
    return {
      pelvisDrop: 0.5 * L * dp, pelvisBack: -0.25 * L * dp,
      ankles: ankles, lean: 0.6 * dp, arm: 1.45 * dp, heelLift: { L: 0, R: 0 }, depth: dp, lead: null,
      phase: phaseOf(t % SQUAT.period, [[0.9, '선 자세'], [2.2, '천천히 내려가기'], [2.7, '바닥 자세 · 잠깐 멈춤'], [3.9, '올라오기'], [9, '선 자세']]),
    };
  }

  var LUNGE = {
    period: 9.6,
    // 한 다리당 4.8초: 서기 → 앞으로 딛기 → 내려가기 → 멈춤 → 올라오기 → 돌아오기
    step:  [[0.5, 0, 0], [0.7, 0, 1], [2.2, 1, 1], [0.7, 1, 0], [0.7, 0, 0]],
    depth: [[0.5, 0, 0], [0.7, 0, 0], [0.9, 0, 1], [0.5, 1, 1], [0.8, 1, 0], [1.4, 0, 0]],
  };
  function poseLunge(t) {
    var lead = (t % LUNGE.period) < LUNGE.period / 2 ? 'R' : 'L';
    var lt = t % (LUNGE.period / 2);
    var st = track(LUNGE.step, lt), dp = track(LUNGE.depth, lt), L = M.leg;
    var STEP = 0.72 * L;
    var ankles = { L: M.ankle.L.clone(), R: M.ankle.R.clone() };
    ankles[lead].z += STEP * st;
    ankles[lead].y += Math.sin(Math.PI * Math.max(0, Math.min(1, st))) * (dp < 0.02 ? 0.08 : 0);
    var back = lead === 'R' ? 'L' : 'R';
    var heel = { L: 0, R: 0 }; heel[back] = Math.min(1, st * 1.5);
    ankles[back].y += 0.08 * heel[back];
    return {
      pelvisDrop: 0.035 * L * st + 0.42 * L * dp, pelvisBack: STEP * 0.42 * st,
      ankles: ankles, lean: 0.06 * dp, arm: 0, heelLift: heel, depth: dp, lead: lead, step: st,
      phase: (lead === 'R' ? '오른발 · ' : '왼발 · ') + phaseOf(lt, [[0.5, '선 자세'], [1.2, '앞으로 딛기'], [2.1, '내려가기'], [2.6, '바닥 자세 · 잠깐 멈춤'], [3.4, '올라오기'], [4.1, '제자리로 돌아오기'], [9, '선 자세']]),
    };
  }

  // ── 매 프레임 자세 적용: 키프레임 → 관절 목표 위치 → 뼈 회전 ──
  function apply(p) {
    resetPose();
    // 골반: 내리고 뒤(스쿼트)·앞(런지)으로 옮긴다. 엉덩이를 뒤로 빼는 만큼 골반도 살짝 앞으로 기운다
    var rootT = M.root.clone().add(new V(0, -p.pelvisDrop, p.pelvisBack));
    B.Root.position.copy(B.Root.parent.worldToLocal(rootT));
    B.Root.updateMatrixWorld(true);
    tilt('pelvis', p.lean * 0.45);
    tilt('spine_01', p.lean * 0.2);
    tilt('spine_02', p.lean * 0.2);
    tilt('spine_03', p.lean * 0.15);
    tilt('neck_01', -p.lean * 0.45);   // 시선은 앞으로
    tilt('head', -p.lean * 0.3);

    var minKnee = 180;
    ['L', 'R'].forEach(function (k) {
      var s = k === 'L' ? 'l' : 'r', sign = k === 'L' ? 1 : -1;
      var hip = wpos('thigh_' + s), ankle = p.ankles[k];
      // 스쿼트는 무릎이 발끝 방향(앞·살짝 바깥)으로, 런지는 정면으로 굽는다
      var pole = EXERCISE === 'squat' ? new V(sign * 0.35, 0, 1) : new V(sign * 0.08, 0, 1);
      var knee = solveKnee(hip, ankle, pole.normalize());
      aim('thigh_' + s, 'calf_' + s, knee);
      aim('calf_' + s, 'foot_' + s, ankle);
      // 발: 앞꿈치는 바닥에 붙이고, 뒤꿈치만 든다
      var footNow = wpos('foot_' + s);
      var rv = new V().subVectors(M.ball[k], M.ankle[k]);
      var flat = Math.sqrt(rv.x * rv.x + rv.z * rv.z), len = rv.length();
      var dy = Math.min(len * 0.95, Math.max(0, footNow.y - M.ball[k].y));
      var reach = Math.sqrt(Math.max(0, len * len - dy * dy));
      var ball = new V(footNow.x + rv.x / flat * reach, footNow.y - dy, footNow.z + rv.z / flat * reach);
      if (p.heelLift[k] < 0.01 && Math.abs(footNow.y - M.ankle[k].y) < 0.01) ball = footNow.clone().add(rv);
      aim('foot_' + s, 'ball_' + s, ball);
      setWorldQuat(B['ball_' + s], M.restWQ['ball_' + s]);   // 발가락은 바닥과 나란히
      var front = EXERCISE === 'squat' || p.lead === k;
      if (front) minKnee = Math.min(minKnee, kneeAngle(hip, knee, footNow));

      // 팔: 스쿼트는 균형을 위해 앞으로 나란히, 런지는 몸 옆에 자연스럽게
      var sh = wpos('upperarm_' + s), armDir, foreDir;
      if (EXERCISE === 'squat') {
        armDir = new V(sign * 0.2, -Math.cos(p.arm), Math.sin(p.arm)).normalize();
        foreDir = armDir.clone();
      } else {
        armDir = new V(sign * 0.2, -1, -0.03).normalize();
        foreDir = new V(sign * 0.1, -1, 0.25).normalize();
      }
      aim('upperarm_' + s, 'lowerarm_' + s, sh.clone().addScaledVector(armDir, M.uarm));
      var el = wpos('lowerarm_' + s);
      aim('lowerarm_' + s, 'hand_' + s, el.clone().addScaledVector(foreDir, M.farm));
    });

    paint(p);
    phaseEl.textContent = p.phase;
    subEl.textContent = '무릎 ' + Math.round(minKnee) + '°' + (minKnee <= 115 ? ' ✓ 깊이 충족' : '');
  }

  // ── 카메라: 45도 앞에서 (앱이 권하는 촬영 각도와 같다) + 드래그 회전 ──
  var azimuth = Math.PI / 4, targetAz = azimuth, elev = 0.16, dist = 3.85, dragging = false, lastX = 0, idle = 0;
  function onDown(x) { dragging = true; lastX = x; idle = 0; }
  function onMove(x) { if (!dragging) return; targetAz -= (x - lastX) * 0.01; lastX = x; }
  function onUp() { dragging = false; }
  renderer.domElement.addEventListener('pointerdown', function (e) { onDown(e.clientX); });
  window.addEventListener('pointermove', function (e) { onMove(e.clientX); });
  window.addEventListener('pointerup', onUp);
  renderer.domElement.addEventListener('touchstart', function (e) { onDown(e.touches[0].clientX); }, { passive: true });
  window.addEventListener('touchmove', function (e) { onMove(e.touches[0].clientX); }, { passive: true });
  window.addEventListener('touchend', onUp);

  function resize() {
    var w = window.innerWidth || 300, h = window.innerHeight || 300;
    renderer.setSize(w, h, false);
    renderer.domElement.style.width = w + 'px';
    renderer.domElement.style.height = h + 'px';
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
  }
  window.addEventListener('resize', resize);
  resize();

  var start = performance.now(), last = start;
  function frame(now) {
    var dt = (now - last) / 1000; last = now;
    var t = typeof window.__FIXED_T__ === 'number' ? window.__FIXED_T__ : (now - start) / 1000;
    if (typeof window.__FIXED_AZ__ === 'number') { targetAz = azimuth = window.__FIXED_AZ__; }
    else if (!dragging) {
      idle += dt;
      // 손을 떼고 3초 지나면 45도 시점으로 천천히 되돌아온다
      if (idle > 3) targetAz += (Math.PI / 4 - targetAz) * Math.min(1, dt * 0.8);
    }
    azimuth += (targetAz - azimuth) * Math.min(1, dt * 8);
    camera.position.set(Math.sin(azimuth) * dist * Math.cos(elev), 0.8 + Math.sin(elev) * dist, Math.cos(azimuth) * dist * Math.cos(elev));
    camera.lookAt(0, 0.86, 0.12);
    apply(EXERCISE === 'lunge' ? poseLunge(t) : poseSquat(t));
    renderer.render(scene, camera);
    requestAnimationFrame(frame);
  }
})();
</script>
</body>
</html>
`;
