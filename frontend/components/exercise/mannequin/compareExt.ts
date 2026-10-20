// 3D 운동 비교 확장 — sceneHtml.ts 의 `/*__EXT__*/` 자리에 들어가는 JS (같은 IIFE 안이라 scene·camera·apply·
// poseSquat·poseLunge·wpos·dist·elev·camX 등을 그대로 쓴다). window.__COMPARE__ 일 때만 켜진다.
//
// 하는 일
//   1) 기준 시범 모션 뽑기 — 장면이 «자기 자세 계산 그대로»(키프레임 + 2-링크 IK + 뼈 회전) 움직인 결과에서
//      어깨·엉덩이·무릎·발목 관절 위치를 읽어 앱에 보낸다 (type:'reference'). DTW 비교의 기준 데이터다.
//   2) 사용자 모션 그리기 — 앱이 보낸 사용자 3D 골격(구체 + 막대)을 같은 장면에 그린다.
//   3) 재생 — DTW 정렬로 사용자 프레임마다 대응하는 시범 시각을 알아 두고, 마네킹을 그 시각으로 움직인다.
//   4) 오차가 큰 관절 강조 — 사용자 관절 색(초록→빨강) + 시범 마네킹 그 관절 위치에 붉은 후광.
//   5) 나란히/겹쳐서 보기, 회전·확대/축소.
// ⚠️ 이 문자열 안에는 백틱(`)과 ${ 를 쓰지 않는다 (TS 템플릿 문자열 안에 들어간다).
export const COMPARE_EXT = `
if (window.__COMPARE__) {
  document.body.className = 'cmp';
  var hud = document.createElement('div');
  hud.id = 'cmpHud'; hud.className = 'hud';
  document.body.appendChild(hud);

  var JN = ['lShoulder', 'rShoulder', 'lHip', 'rHip', 'lKnee', 'rKnee', 'lAnkle', 'rAnkle'];
  var BONE_OF = { lShoulder: 'upperarm_l', rShoulder: 'upperarm_r', lHip: 'thigh_l', rHip: 'thigh_r',
                  lKnee: 'calf_l', rKnee: 'calf_r', lAnkle: 'foot_l', rAnkle: 'foot_r' };
  function send(msg) {
    var s = JSON.stringify(msg);
    if (window.ReactNativeWebView) window.ReactNativeWebView.postMessage(s);
    if (window.__OUT__) window.__OUT__(msg);
  }
  function clamp(x, a, b) { return Math.max(a, Math.min(b, x)); }

  // ── 1) 기준 시범 모션 뽑기 ────────────────────────────────
  function readJoints() {
    var o = {};
    JN.forEach(function (n) { var p = wpos(BONE_OF[n]); o[n] = [p.x, p.y, p.z]; });
    return o;
  }
  function sampleCycle(t0, t1, dt) {
    var samples = [];
    for (var t = t0; t <= t1 + 1e-9; t += dt) {
      var p = EXERCISE === 'lunge' ? poseLunge(t) : poseSquat(t);
      apply(p);
      scene.updateMatrixWorld(true);
      samples.push({
        t: Math.round((t - t0) * 1000) / 1000,
        phase: String(p.phase).replace(/^(오른발|왼발) · /, ''),
        joints: readJoints(),
      });
    }
    return samples;
  }
  function cycleOf(lead, samples, dt, t0) {
    var j = samples[0].joints;
    var sm = [(j.lShoulder[0] + j.rShoulder[0]) / 2, (j.lShoulder[1] + j.rShoulder[1]) / 2, (j.lShoulder[2] + j.rShoulder[2]) / 2];
    var hm = [(j.lHip[0] + j.rHip[0]) / 2, (j.lHip[1] + j.rHip[1]) / 2, (j.lHip[2] + j.rHip[2]) / 2];
    return {
      exercise: EXERCISE, lead: lead, dt: dt, t0: t0, samples: samples,
      torso: Math.hypot(sm[0] - hm[0], sm[1] - hm[1], sm[2] - hm[2]),
      ankleY: (j.lAnkle[1] + j.rAnkle[1]) / 2,
    };
  }
  window.__ON_MODEL__ = function () {
    var DT = 0.1, cycles;
    if (EXERCISE === 'lunge') {
      var half = LUNGE.period / 2;
      // poseLunge 는 앞 절반을 오른발, 뒤 절반을 왼발로 간다
      cycles = [cycleOf('right', sampleCycle(0, half - 1e-6, DT), DT, 0), cycleOf('left', sampleCycle(half, LUNGE.period - 1e-6, DT), DT, half)];
    } else {
      cycles = [cycleOf(null, sampleCycle(0, SQUAT.period, DT), DT, 0)];
    }
    window.__REFERENCE__ = { type: 'reference', exercise: EXERCISE, cycles: cycles };
    send(window.__REFERENCE__);
    hud.textContent = '기준 시범을 불러왔어요';
  };

  // ── 2) 사용자 3D 골격 ─────────────────────────────────────
  var userGroup = new THREE.Group();
  userGroup.visible = false;      // 사용자 모션이 들어오기 전에는 숨긴다 (setMotion 이 켠다)
  scene.add(userGroup);
  var sphereGeo = new THREE.SphereGeometry(1, 18, 14);
  var cylGeo = new THREE.CylinderGeometry(1, 1, 1, 10);
  var USER_COLOR = new THREE.Color(0x4dabf7);
  var jointMeshes = JN.map(function (n) {
    var m = new THREE.Mesh(sphereGeo, new THREE.MeshStandardMaterial({ color: USER_COLOR, roughness: 0.5, metalness: 0 }));
    m.scale.setScalar(n.indexOf('Hip') >= 0 || n.indexOf('Shoulder') >= 0 ? 0.045 : 0.04);
    m.castShadow = true;
    userGroup.add(m);
    return m;
  });
  var BONES = [[0, 1], [2, 3], [0, 2], [1, 3], [2, 4], [4, 6], [3, 5], [5, 7]];
  var boneMeshes = BONES.map(function () {
    var m = new THREE.Mesh(cylGeo, new THREE.MeshStandardMaterial({ color: 0x74c0fc, roughness: 0.6, metalness: 0 }));
    m.castShadow = true;
    userGroup.add(m);
    return m;
  });
  // 시범 마네킹 쪽 후광 (오차가 큰 관절 위치)
  var halos = JN.map(function () {
    var m = new THREE.Mesh(sphereGeo, new THREE.MeshBasicMaterial({ color: 0xff4d4d, transparent: true, opacity: 0.38, depthWrite: false }));
    m.scale.setScalar(0.085);
    m.visible = false;
    scene.add(m);
    return m;
  });
  var C_OK = new THREE.Color(0x51cf66), C_MID = new THREE.Color(0xffd43b), C_BAD = new THREE.Color(0xff4d4d);
  function errColor(e, out) {
    if (e < 0.5) return out.copy(C_OK).lerp(C_MID, e / 0.5);
    return out.copy(C_MID).lerp(C_BAD, Math.min(1, (e - 0.5) / 0.5));
  }

  // ── 3) 재생 ───────────────────────────────────────────────
  var motion = null;               // 앱이 보낸 비교 데이터
  var pb = { playing: false, time: 0, speed: 1, i: 0, u: 0, lastSent: 0 };
  var mode = 'side';               // 'side' | 'overlay'
  var SIDE_D = 0.95;               // 나란히 볼 때 사용자 골격을 카메라 오른쪽으로 옮기는 거리(m)
  var sideOn = true;               // 나란히(true) / 겹쳐서(false)
  var userX = 0, userZ = 0;        // 사용자 골격을 옮기는 양 — 카메라가 돌면 같이 돈다
  var pinching = false;
  var xP = new V(), xD = new V(), xUp = new V(0, 1, 0);   // 템플릿의 임시 변수와 이름이 겹치지 않게 x 접두사를 쓴다

  window.__setMotion = function (m) {
    motion = m;
    pb.time = 0; pb.i = 0; pb.u = 0;
    if (!m || !m.frames || !m.frames.length) { userGroup.visible = false; hud.textContent = ''; return; }
    userGroup.visible = true;
    pb.duration = Math.max(0.001, m.frames[m.frames.length - 1].t - m.frames[0].t);
    send({ type: 'loaded', frames: m.frames.length, duration: pb.duration });
  };
  window.__play = function (on) { pb.playing = !!on; };
  window.__seek = function (frac) { if (motion) { pb.time = clamp(frac, 0, 1) * pb.duration; } };
  window.__speed = function (s) { pb.speed = clamp(s, 0.1, 3); };
  window.__setMode = function (m) {
    mode = m === 'overlay' ? 'overlay' : 'side';
    sideOn = mode !== 'overlay';
    bodyMat.transparent = mode === 'overlay';
    bodyMat.opacity = mode === 'overlay' ? 0.5 : 1;
    bodyMat.depthWrite = mode !== 'overlay';
    bodyMat.needsUpdate = true;
    dist = mode === 'overlay' ? 3.6 : 4.9;
  };
  window.__setMode('side');
  targetAz = azimuth = 1.05;       // 약 60° — 앞뒤로 굽히는 동작이 잘 보이는 3/4 옆모습
  elev = 0.12;

  function lerpArr(a, b, u) { var o = new Array(a.length); for (var k = 0; k < a.length; k++) o[k] = a[k] + (b[k] - a[k]) * u; return o; }

  // 모델이 확장보다 먼저 준비됐다면(동기 파싱) 지금 한 번 알린다 — 보통은 모델이 나중에 준비돼 onModel 이 부른다
  if (B && M.root) window.__ON_MODEL__();

  window.__EXT_TICK__ = function (now, dt) {
    // 카메라의 오른쪽 방향 = (cos az, 0, −sin az). 나란히 볼 때는 어느 각도에서 봐도 기준이 왼쪽, 내 동작이 오른쪽이다.
    var rx = Math.cos(azimuth), rz = -Math.sin(azimuth), amt = sideOn ? SIDE_D : 0;
    userX += (rx * amt - userX) * Math.min(1, dt * 8);
    userZ += (rz * amt - userZ) * Math.min(1, dt * 8);
    camX = userX / 2; camZ = userZ / 2;
    if (!motion || !motion.frames.length) return;
    var F = motion.frames;
    if (pb.playing) {
      pb.time += dt * pb.speed;
      if (pb.time > pb.duration) pb.time = 0;   // 끝나면 처음부터 반복
    }
    var tAbs = F[0].t + pb.time, i = 0;
    while (i < F.length - 2 && F[i + 1].t <= tAbs) i++;
    var span = F[Math.min(i + 1, F.length - 1)].t - F[i].t;
    pb.i = i; pb.u = span > 0 ? clamp((tAbs - F[i].t) / span, 0, 1) : 0;
    var j = Math.min(i + 1, F.length - 1);
    // 마네킹은 DTW 정렬이 알려 준 시범 시각을 따라간다
    window.__FIXED_T__ = motion.refOffset + motion.refTime[i] + (motion.refTime[j] - motion.refTime[i]) * pb.u;
    if (now - pb.lastSent > 100) {
      pb.lastSent = now;
      send({ type: 'pos', frac: pb.time / pb.duration, index: i, playing: pb.playing, segment: motion.segment ? motion.segment[i] : null });
    }
  };

  window.__EXT_FRAME__ = function () {
    if (!motion || !motion.frames.length) return;
    var F = motion.frames, i = pb.i, j = Math.min(i + 1, F.length - 1), u = pb.u;
    var P = lerpArr(F[i].p, F[j].p, u), E = lerpArr(motion.err[i], motion.err[j], u);
    var pts = [];
    for (var k = 0; k < 8; k++) {
      pts.push(new V(P[k * 3] + userX, P[k * 3 + 1], P[k * 3 + 2] + userZ));
      jointMeshes[k].position.copy(pts[k]);
      errColor(E[k], jointMeshes[k].material.color);
    }
    BONES.forEach(function (b, n) {
      var a = pts[b[0]], c = pts[b[1]], m = boneMeshes[n];
      xD.subVectors(c, a);
      var len = xD.length();
      m.position.copy(a).addScaledVector(xD, 0.5);
      m.scale.set(0.014, Math.max(len, 1e-4), 0.014);
      m.quaternion.setFromUnitVectors(xUp, xD.multiplyScalar(1 / Math.max(len, 1e-6)));
    });
    for (var h = 0; h < 8; h++) {
      var hot = E[h] >= 0.6;
      halos[h].visible = hot;
      if (hot) { xP.copy(wpos(BONE_OF[JN[h]])); halos[h].position.copy(xP); }
    }
    hud.textContent = mode === 'overlay' ? '회색 마네킹 = 기준 시범 · 파란 골격 = 내 동작' : '왼쪽 = 기준 시범 · 오른쪽 = 내 동작';
  };

  // ── 5) 회전(기존 드래그) + 위아래 각도 + 확대/축소 ───────────
  var lastY = 0;
  renderer.domElement.addEventListener('pointerdown', function (e) { lastY = e.clientY; });
  window.addEventListener('pointermove', function (e) {
    if (dragging && !pinching) elev = clamp(elev + (e.clientY - lastY) * 0.004, -0.05, 0.9);
    lastY = e.clientY;
  });
  renderer.domElement.addEventListener('wheel', function (e) {
    dist = clamp(dist * (1 + e.deltaY * 0.001), 1.8, 9);
    e.preventDefault();
  }, { passive: false });
  var pinchD = 0;
  function touchDist(t) { return Math.hypot(t[0].clientX - t[1].clientX, t[0].clientY - t[1].clientY); }
  renderer.domElement.addEventListener('touchstart', function (e) {
    if (e.touches.length === 2) { pinching = true; dragging = false; pinchD = touchDist(e.touches); }
  }, { passive: true });
  window.addEventListener('touchmove', function (e) {
    if (e.touches.length === 2 && pinchD > 0) {
      var d = touchDist(e.touches);
      dist = clamp(dist * (pinchD / d), 1.8, 9);
      pinchD = d;
    }
  }, { passive: true });
  window.addEventListener('touchend', function (e) { if (e.touches.length < 2) { pinching = false; pinchD = 0; } });
}
`;
