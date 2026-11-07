# ShadowFit 3D 시범용 인체 모델 생성 (MPFB 2, CC0 에셋)
import os
WORK = os.environ.get('MANNEQUIN_WORK', os.path.dirname(os.path.abspath(__file__)))
exec(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), 'common.py')).read())
import bmesh, math
from mathutils import Vector
HumanService = M('services.humanservice', 'HumanService')
TargetService = M('services.targetservice', 'TargetService')
HOP = M('entities.objectproperties', 'HumanObjectProperties')

h = HumanService.create_human(mask_helpers=False, detailed_helpers=False, extra_vertex_groups=True, feet_on_ground=False, scale=0.1)
for k, v in {'gender': 1.0, 'age': 0.5, 'muscle': 0.9, 'weight': 0.42, 'height': 0.55, 'proportions': 1.0,
             'african': 0.34, 'asian': 0.33, 'caucasian': 0.33}.items():
    HOP.set_value(k, v, entity_reference=h)
TargetService.reapply_macro_details(h)

# 모양 키(체형 타깃)를 메시에 굳힌다
bpy.context.view_layer.objects.active = h
for o in bpy.context.selected_objects: o.select_set(False)
h.select_set(True)
if h.data.shape_keys:
    bpy.ops.object.shape_key_remove(all=True, apply_mix=True)
# 체형을 굳힌 뒤 뼈대를 맞춘다 (관절 큐브 기준). 그래야 뼈 위치가 실제 피부와 일치한다
rig = HumanService.add_builtin_rig(h, 'game_engine')
print('rig', rig.name, 'bones', len(rig.data.bones))

# 몸 피부와 눈만 남기고 보조 도형(관절 큐브·옷 도우미 등)을 지운다
names = {g.index: g.name for g in h.vertex_groups}
keep_groups = {'body', 'helper-l-eye', 'helper-r-eye'}
bm = bmesh.new(); bm.from_mesh(h.data)
deform = bm.verts.layers.deform.active
kill = []
for v in bm.verts:
    gs = {names[i] for i, w in v[deform].items() if w > 0} if deform else set()
    if not (gs & keep_groups):
        kill.append(v)
bmesh.ops.delete(bm, geom=kill, context='VERTS')
bm.to_mesh(h.data); bm.free()
for p in h.data.polygons: p.use_smooth = True
# 모디파이어 정리 (마스크 등) — 아마추어만 남긴다
for mod in list(h.modifiers):
    if mod.type != 'ARMATURE':
        h.modifiers.remove(mod)
# 발바닥을 바닥(z=0)에 맞춘다: 뼈대 오브젝트를 올리면 자식인 메시도 같이 올라간다. 그 뒤 변환을 굳힌다
low = min(v.co.z for v in h.data.vertices)
bpy.context.view_layer.update()
low = min((h.matrix_world @ v.co).z for v in h.data.vertices)
rig.location.z -= low
bpy.context.view_layer.update()
for o in bpy.context.selected_objects: o.select_set(False)
rig.select_set(True); h.select_set(True); bpy.context.view_layer.objects.active = rig
bpy.ops.object.transform_apply(location=True, rotation=False, scale=False)
print('verts', len(h.data.vertices), 'faces', len(h.data.polygons))
bpy.ops.wm.save_as_mainfile(filepath=os.path.join(WORK, 'human.blend'))
