# 근육 부위 마스크: 정점의 뼈 가중치(어느 뼈에 붙은 피부인가) + 표면 방향(앞/뒤/안쪽) + 높이로 계산하고,
# 메시 이웃을 따라 흐려 경계를 부드럽게 만든다. 결과를 정점 속성 _MASKA(RGBA)·_MASKB(RGBA)로 굽는다.
#   A = (대퇴사두근, 대둔근, 햄스트링, 내전근)   B = (종아리, 척추기립근, 복근, 어깨)
import bpy, math
import os
WORK = os.environ.get('MANNEQUIN_WORK', os.path.dirname(os.path.abspath(__file__)))
bpy.ops.wm.open_mainfile(filepath=os.path.join(WORK, 'human.blend'))
rig = bpy.data.objects['Human.rig']; h = bpy.data.objects['Human']
bpy.context.view_layer.update()
off = rig.matrix_world.translation.copy() - h.matrix_world.translation
def J(b, tail=False):
    bb = rig.data.bones[b]; return (bb.tail_local if tail else bb.head_local) + off
me = h.data
names = {g.index: g.name for g in h.vertex_groups}
N = len(me.vertices)
def ss(e0, e1, x):
    t = max(0.0, min(1.0, (x - e0) / (e1 - e0))); return t * t * (3 - 2 * t)
def band(x, a, b, f=0.06):  # a..b 안쪽이면 1, 바깥으로 f 만큼 부드럽게 줄어든다
    return ss(a - f, a + f, x) * (1 - ss(b - f, b + f, x))

hipZ = {s: J('thigh_' + s).z for s in 'lr'}; kneeZ = {s: J('calf_' + s).z for s in 'lr'}; ankZ = {s: J('foot_' + s).z for s in 'lr'}
ribZ = J('spine_03').z + 0.1            # 갈비뼈 아래 끝 근처
shZ = J('upperarm_l').z; shX = J('upperarm_l').x
def ellipse(dx, dz, rx, rz):            # 타원 안쪽 1, 가장자리로 부드럽게 0
    d = math.sqrt((dx / rx) ** 2 + (dz / rz) ** 2); return 1 - ss(0.7, 1.15, d)
A = [[0.0] * 4 for _ in range(N)]; B = [[0.0] * 4 for _ in range(N)]
for v in me.vertices:
    w = {}
    for g in v.groups:
        n = names.get(g.group)
        if n: w[n] = w.get(n, 0) + g.weight
    c, n = v.co, v.normal
    s = 'l' if c.x > 0 else 'r'; ax = abs(c.x)
    arm = min(1.0, sum(w.get(k + s, 0) for k in ('upperarm_', 'lowerarm_', 'hand_')) + sum(x for k, x in w.items() if k.startswith(('thumb', 'index', 'middle', 'ring', 'pinky'))))
    body = 1 - ss(0.2, 0.6, arm)        # 팔·손은 다리·몸통 부위에서 뺀다 (손이 허벅지 옆에 있어도 칠해지지 않게)
    front = ss(-0.1, 0.5, -n.y); back = ss(-0.1, 0.5, n.y)
    medial = ss(0.1, 0.6, -math.copysign(1, c.x) * n.x); lateral = ss(0.1, 0.6, math.copysign(1, c.x) * n.x)
    hz, kz, az = hipZ[s], kneeZ[s], ankZ[s]
    leg = body * ss(0.02, 0.045, ax)
    groin_top = hz - 0.07 + 0.7 * max(0.0, ax - 0.07)          # 사타구니 주름: 바깥쪽일수록 높다
    quads = leg * front * (1 - 0.75 * medial) * band(c.z, kz + 0.05, groin_top, 0.035)
    ham = leg * back * band(c.z, kz + 0.06, hz - 0.1, 0.035)
    add = leg * medial * (1 - 0.6 * back) * band(c.z, kz + 0.14, hz - 0.06, 0.04)
    glute = body * back * ellipse(ax - 0.088, c.z - (hz - 0.02), 0.085, 0.105)
    calves = leg * back * (1 - 0.3 * front) * band(c.z, kz - 0.62 * (kz - az), kz - 0.03, 0.035)
    erector = body * back * (1 - ss(0.045, 0.075, ax)) * band(c.z, hz + 0.03, ribZ + 0.04, 0.04)
    core = body * (1 - back) * band(c.z, hz + 0.04, ribZ, 0.035) * (front * (1 - ss(0.085, 0.12, ax)) + 0.7 * lateral * (1 - ss(0.15, 0.19, ax)))
    sh = (w.get('upperarm_' + s, 0) + w.get('clavicle_' + s, 0)) * band(c.z, shZ - 0.16, shZ + 0.1, 0.04) * ss(shX - 0.06, shX - 0.01, ax)
    A[v.index] = [quads, glute, ham, add]; B[v.index] = [calves, erector, core, sh]

# 이웃 평균으로 흐리기 (경계를 부드럽게)
adj = [[] for _ in range(N)]
for e in me.edges:
    a, b = e.vertices; adj[a].append(b); adj[b].append(a)
for it in range(6):
    for arr in (A, B):
        new = []
        for i in range(N):
            nb = adj[i]
            if not nb: new.append(arr[i]); continue
            acc = [0.0] * 4
            for j in nb:
                for k in range(4): acc[k] += arr[j][k]
            new.append([0.5 * arr[i][k] + 0.5 * acc[k] / len(nb) for k in range(4)])
        arr[:] = new
# 대비를 살짝 올리고 0..1 로 자른다
for arr in (A, B):
    for i in range(N):
        arr[i] = [min(1.0, max(0.0, ss(0.08, 0.6, x))) for x in arr[i]]

for nm, arr in (('_MASKA', A), ('_MASKB', B)):
    if nm in me.attributes: me.attributes.remove(me.attributes[nm])
    at = me.attributes.new(nm, 'FLOAT_COLOR', 'POINT')
    for i, val in enumerate(arr): at.data[i].color = val
print('coverage', [round(sum(1 for i in range(N) if arr[i][k] > 0.5) / N * 100, 1) for arr in (A, B) for k in range(4)])

# 재질: 무채색 하나
mat = bpy.data.materials.new('Body'); mat.use_nodes = True
bsdf = mat.node_tree.nodes.get('Principled BSDF'); bsdf.inputs['Base Color'].default_value = (0.3, 0.3, 0.3, 1); bsdf.inputs['Roughness'].default_value = 0.7
me.materials.clear(); me.materials.append(mat)
# 손가락 뼈는 쓰지 않지만 가중치를 위해 둔다. 내보내기
for o in bpy.context.selected_objects: o.select_set(False)
h.select_set(True); rig.select_set(True)
bpy.ops.export_scene.gltf(filepath=os.path.join(WORK, 'body.glb'), export_format='GLB', use_selection=True,
    export_attributes=True, export_skins=True, export_animations=False, export_morph=False,
    export_texcoords=False, export_normals=True, export_materials='EXPORT', export_yup=True, export_apply=False)
import os; print('glb bytes', os.path.getsize(os.path.join(WORK, 'body.glb')))
