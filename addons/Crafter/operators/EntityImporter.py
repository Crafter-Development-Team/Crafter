"""Minecraft 实体导入（参考 jMc2Obj 的配置驱动架构）。

原理（与 jMc2Obj 的 entities.conf + 预制模型一致）：
  entities.json 注册表：实体 id -> 原型模型文件 + 贴图路径。
  实例化时按实体 NBT 的 Pos/Rotation 摆放原型，并附上真实皮肤贴图。

原型模型：OptiFine CEM 模板（原版 Java 生物的准确几何 + 每面 UV），
由本模块构建为纯 Python 几何快照 (verts/faces/face_uvs/uvs)，
每个实体实例用 from_pydata 独立重建网格 —— 绝无共享 mesh data、
绝无 Blender data.copy()（避免 5.x 的 "internal error setting the array"）。

安全约定：
  - 本模块只创建对象到 "Crafter Entities" 集合，绝不删除/移动场景其他对象。
  - 单个实体失败只记日志，不影响其余实体与主导入流程。
"""
import bpy
import os
import json
import math
import zipfile
import shutil

_MESH_CACHE = {}      # model_path -> (verts, faces, face_uvs, uvs)
_MATERIAL_CACHE = {}  # (entity_id, texture) -> material
_ARCHIVE_NAMES = {}   # archive -> set(namelist)


# ==================== CEM 几何构建（纯 Python 快照） ====================

def _box_corners(cx, cy, cz, sx, sy, sz):
    return [
        (cx - sx/2, cy - sy/2, cz - sz/2), (cx + sx/2, cy - sy/2, cz - sz/2),
        (cx + sx/2, cy + sy/2, cz - sz/2), (cx - sx/2, cy + sy/2, cz - sz/2),
        (cx - sx/2, cy - sy/2, cz + sz/2), (cx + sx/2, cy - sy/2, cz + sz/2),
        (cx + sx/2, cy + sy/2, cz + sz/2), (cx - sx/2, cy + sy/2, cz + sz/2),
    ]


def _apply_part_pose(corners, translate, rotate):
    """CEM 实体模型部件变换（经 Minecraft 源码 QuadrupedModel 校准）。

    - box coordinates 是世界绝对坐标（相对模型原点，像素，Y-up）
    - pivot = CEM translate 翻转：Minecraft offset = (tx, -ty, -tz)
    - rotate 绕 pivot 旋转（CEM 原始角度）
    - world = T(pivot) * R * T(-pivot) * box
    - 输出方块单位(像素/16)
    """
    if translate:
        pivot = (translate[0], -translate[1], -translate[2])
    else:
        pivot = (0.0, 0.0, 0.0)
    out = []
    for (x, y, z) in corners:
        qx, qy, qz = x - pivot[0], y - pivot[1], z - pivot[2]
        if rotate:
            rx, ry, rz = (math.radians(v) for v in map(float, rotate))
            cx, sx = math.cos(rx), math.sin(rx)
            cy, sy = math.cos(ry), math.sin(ry)
            cz, sz = math.cos(rz), math.sin(rz)
            y1, z1 = qy*cx - qz*sx, qy*sx + qz*cx
            x2, z2 = qx*cy + z1*sy, -qx*sy + z1*cy
            x3, y3 = x2*cz - y1*sz, x2*sz + y1*cz
            qx, qy, qz = x3, y3, z2
        out.append(((qx + pivot[0]) / 16.0,
                    (qy + pivot[1]) / 16.0,
                    (qz + pivot[2]) / 16.0))
    return out


# Minecraft ModelPart.Cube 顶点定义（z- 为北/前）：
#   t0=(-x,-y,-z) t1=(+x,-y,-z) t2=(+x,+y,-z) t3=(-x,+y,-z)
#   l0=(-x,-y,+z) l1=(+x,-y,+z) l2=(+x,+y,+z) l3=(-x,+y,+z)
# 六面顶点（按 Minecraft 顺序，保证 UV 与顶点一一对应）：
#   DOWN = l1,l0,t0,t1   UP = t2,t3,l3,l2
#   WEST = t0,l0,l3,t3   NORTH = t1,t0,t3,t2
#   EAST = l1,t1,t2,l2   SOUTH = l0,l1,l2,l3
# UV 矩形（像素）：
#   DOWN = (u1,v0)-(u2,v1)   UP = (u2,v1)-(u22,v0)
#   WEST = (u0,v1)-(u1,v2)   NORTH = (u1,v1)-(u2,v2)
#   EAST = (u2,v1)-(u3,v2)   SOUTH = (u3,v1)-(u4,v2)
#   u0=texX, u1=texX+d, u2=texX+d+w, u22=texX+d+2w, u3=texX+d+w+d, u4=texX+d+w+d+w
#   v0=texY, v1=texY+d, v2=texY+d+h
_CUBE_FACES = [
    ("DOWN",  (5, 4, 0, 1)),
    ("UP",    (2, 3, 7, 6)),
    ("WEST",  (0, 4, 7, 3)),
    ("NORTH", (1, 0, 3, 2)),
    ("EAST",  (5, 1, 2, 6)),
    ("SOUTH", (4, 5, 6, 7)),
]


def _cube_uv_rects(uvx, uvy, w, h, d):
    """按 Minecraft ModelPart.Cube 官方布局返回每面的 UV 角点(u0,u1,v0,v1)。

    Minecraft Polygon 每面 remap:
      DOWN:  (u1,v0)-(u2,v1)   UP: (u2,v1)-(u22,v0)   WEST: (u0,v1)-(u1,v2)
      NORTH: (u1,v1)-(u2,v2)   EAST:(u2,v1)-(u3,v2)   SOUTH:(u3,v1)-(u4,v2)
    注意 UP 的 v 是反向的（v1>v0）。
    """
    u0, u1 = uvx, uvx + d
    u2, u22 = uvx + d + w, uvx + d + w + w
    u3, u4 = uvx + d + w + d, uvx + d + w + d + w
    v0, v1, v2 = uvy, uvy + d, uvy + d + h
    return {
        "DOWN":  (u1, u2, v0, v1),
        "UP":    (u2, u22, v1, v0),
        "WEST":  (u0, u1, v1, v2),
        "NORTH": (u1, u2, v1, v2),
        "EAST":  (u2, u3, v1, v2),
        "SOUTH": (u3, u4, v1, v2),
    }


def _build_cem_snapshot(jem):
    """CEM .jem -> (verts, faces, face_uvs, uvs)，纯数据，不触碰 Blender。"""
    tw, th = jem.get("textureSize", [64, 64])
    verts, faces, uvs, uv_faces = [], [], [], []

    for model in jem.get("models", []):
        parent = {"translate": model.get("translate"), "rotate": model.get("rotate")}
        for box in model.get("boxes", []):
            coords = box.get("coordinates")
            if not coords or len(coords) != 6:
                continue
            x, y, z, w, h, d = map(float, coords)
            # UV 始终按原始尺寸布局（Minecraft 的 texOffs 以未 grow 尺寸计算）
            uvw, uvh, uvd = w, h, d
            size_add = float(box.get("sizeAdd", 0))
            w += size_add*2; h += size_add*2; d += size_add*2
            # 中心坐标(像素,绝对)
            cx, cy, cz = x + w/2, y + h/2, z + d/2
            uvx, uvy = map(float, box.get("textureOffset", [0, 0]))
            base = len(verts)
            # Minecraft 角点: 0=t0 1=t1 2=t2 3=t3 4=l0 5=l1 6=l2 7=l3
            # _box_corners 输出顺序: (-x,-y,-z)(+x,-y,-z)(+x,+y,-z)(-x,+y,-z)(-x,-y,+z)(+x,-y,+z)(+x,+y,+z)(-x,+y,+z)
            for c in _apply_part_pose(_box_corners(cx, cy, cz, w, h, d),
                                      parent["translate"], parent["rotate"]):
                # c 已是方块单位（Minecraft Y-up）；转 Blender (x, -z, y)
                verts.append((c[0], -c[2], c[1]))
            rects = _cube_uv_rects(uvx, uvy, uvw, uvh, uvd)
            for face_name, quad in _CUBE_FACES:
                ua, ub, va, vb = rects[face_name]
                # Minecraft Polygon: 顶点0=(ub,va) 顶点1=(ua,va) 顶点2=(ua,vb) 顶点3=(ub,vb)
                # 像素 -> 0..1 UV，翻转 V（Minecraft V 向下）
                quads = [
                    (ub/tw, 1 - va/th),
                    (ua/tw, 1 - va/th),
                    (ua/tw, 1 - vb/th),
                    (ub/tw, 1 - vb/th),
                ]
                start = len(uvs); uvs.extend(quads)
                faces.append(tuple(base + i for i in quad))
                uv_faces.append((start, start+1, start+2, start+3))
    return verts, faces, uv_faces, uvs


def _load_prototype(model_path):
    cached = _MESH_CACHE.get(model_path)
    if cached:
        return cached
    if model_path.endswith(".jem"):
        with open(model_path, "r", encoding="utf-8") as f:
            snapshot = _build_cem_snapshot(json.load(f))
    else:  # .obj 兜底（jmc2obj 风格）
        snapshot = _load_obj_snapshot(model_path)
    _MESH_CACHE[model_path] = snapshot
    return snapshot


def _load_obj_snapshot(path):
    verts, texcoords, faces, face_uvs = [], [], [], []
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        for raw in f:
            line = raw.strip()
            if line.startswith("v "):
                x, y, z = map(float, line.split()[1:4])
                verts.append((x, -z, y))
            elif line.startswith("vt "):
                u, v = map(float, line.split()[1:3])
                texcoords.append((u, v))
            elif line.startswith("f "):
                vi, ti = [], []
                for token in line.split()[1:]:
                    fields = token.split("/")
                    a = int(fields[0]); vi.append(a - 1 if a > 0 else len(verts) + a)
                    if len(fields) > 1 and fields[1]:
                        b = int(fields[1]); ti.append(b - 1 if b > 0 else len(texcoords) + b)
                    else:
                        ti.append(-1)
                faces.append(tuple(vi)); face_uvs.append(tuple(ti))
    return verts, faces, face_uvs, texcoords


# ==================== 注册表 ====================

def _addon_dir():
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _registry(addon_dir):
    path = os.path.join(addon_dir, "entity_models", "entities.json")
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except Exception:
        return {}


# ==================== 贴图 ====================

def _archive_candidates(config):
    for p in config.get("resourcepacksPaths", []):
        if os.path.isfile(p):
            yield p
    mods = config.get("modsPath", "")
    if os.path.isdir(mods):
        for f in sorted(os.listdir(mods)):
            if f.endswith(".jar"):
                yield os.path.join(mods, f)
    jar = config.get("jarPath", "")
    if os.path.isfile(jar):
        yield jar


def _variant_texture(entity_id, configured, data):
    """根据 NBT 变体选贴图（猫/马/羊驼）。"""
    name = entity_id.split(":", 1)[-1]
    if name == "cat" and isinstance(data.get("variant"), str):
        return "entity/cat/%s.png" % data["variant"].split(":")[-1]
    if name == "horse":
        colors = ["white", "creamy", "chestnut", "brown", "black", "gray", "darkbrown"]
        color = int(data.get("variant", 0)) & 255
        return "entity/horse/horse_%s.png" % (colors[color] if color < len(colors) else "white")
    if name in {"llama", "trader_llama"}:
        colors = ["creamy", "white", "brown", "gray"]
        variant = int(data.get("variant", 0))
        return "entity/llama/%s.png" % (colors[variant] if 0 <= variant < len(colors) else "creamy")
    return configured


def _extract_texture(importer_dir, config, entity_id, rel):
    ns = entity_id.split(":", 1)[0] if ":" in entity_id else "minecraft"
    rel = rel.replace("\\", "/").lstrip("/")
    asset = "assets/%s/textures/%s" % (ns, rel)
    key = (ns + "_" + rel.replace("/", "_")).replace(":", "_")
    out = os.path.join(importer_dir, "entity_textures", key)
    if os.path.isfile(out):
        return out
    for archive in _archive_candidates(config):
        try:
            names = _ARCHIVE_NAMES.get(archive)
            with zipfile.ZipFile(archive) as z:
                if names is None:
                    names = set(z.namelist())
                    _ARCHIVE_NAMES[archive] = names
                hit = asset if asset in names else None
                if not hit:
                    suffix = "/textures/" + rel
                    hit = next((p for p in names if p.endswith(suffix)), None)
                if hit:
                    os.makedirs(os.path.dirname(out), exist_ok=True)
                    with z.open(hit) as src, open(out, "wb") as dst:
                        shutil.copyfileobj(src, dst)
                    return out
        except (OSError, zipfile.BadZipFile, KeyError):
            continue
    return None


# ==================== Blender 对象创建 ====================

def _material(entity_id, texture_path):
    key = entity_id + "|" + (texture_path or "missing")
    cached = _MATERIAL_CACHE.get(key)
    if cached and cached.name in bpy.data.materials:
        return cached
    name = "CrafterEntity_" + entity_id.replace(":", "_")
    mat = bpy.data.materials.get(name) or bpy.data.materials.new(name)
    mat.use_nodes = True
    nodes = mat.node_tree.nodes
    bsdf = nodes.get("Principled BSDF")
    if texture_path and bsdf:
        try:
            image = bpy.data.images.load(texture_path, check_existing=True)
        except Exception:
            image = None
        if image:
            tex = nodes.new("ShaderNodeTexImage")
            tex.image = image
            tex.interpolation = "Closest"
            mat.node_tree.links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
            mat.node_tree.links.new(tex.outputs["Alpha"], bsdf.inputs["Alpha"])
            try:
                mat.surface_render_method = "DITHERED"
            except Exception:
                pass
    _MATERIAL_CACHE[key] = mat
    return mat


def _mesh_from_snapshot(verts, faces, face_uvs, uvs, mesh_name):
    """独立构建网格。

    Blender 5.1 的 from_pydata 传空 edges=[] 会触发 "internal error setting
    the array"（回归 bug），因此这里必须先从 faces 计算去重边再传入。
    """
    # 从 faces 计算去重边（Blender 5.1 必需）
    seen = set()
    edges = []
    for face in faces:
        n = len(face)
        for i in range(n):
            a, b = face[i], face[(i + 1) % n]
            if a > b:
                a, b = b, a
            if (a, b) not in seen:
                seen.add((a, b))
                edges.append((a, b))
    mesh = bpy.data.meshes.new(mesh_name)
    mesh.from_pydata(verts, edges, faces)
    mesh.update()
    if uvs and face_uvs and len(faces) == len(face_uvs):
        try:
            uv_layer = mesh.uv_layers.new(name="UVMap")
            for poly, indices in zip(mesh.polygons, face_uvs):
                for loop_index, uv_index in zip(poly.loop_indices, indices):
                    if 0 <= uv_index < len(uvs):
                        uv_layer.data[loop_index].uv = uvs[uv_index]
        except Exception:
            pass
    return mesh


def _create_registered_entity(collection, data, importer_dir, addon_dir, config, definition):
    entity_id = data["id"]
    model_path = os.path.join(addon_dir, "entity_models", definition["model"])
    if not os.path.isfile(model_path):
        raise FileNotFoundError(model_path)
    rel_texture = _variant_texture(entity_id, definition.get("texture", ""), data)
    texture = _extract_texture(importer_dir, config, entity_id, rel_texture) if rel_texture else None
    verts, faces, face_uvs, uvs = _load_prototype(model_path)
    mesh = _mesh_from_snapshot(verts, faces, face_uvs, uvs,
                               "Entity_" + entity_id.split(":", 1)[-1])
    try:
        mat = _material(entity_id, texture)
        if len(mesh.materials):
            mesh.materials.clear()
        mesh.materials.append(mat)
    except Exception:
        # 材质失败不阻止对象创建（至少几何可见）
        pass
    obj = bpy.data.objects.new(entity_id.split(":", 1)[-1], mesh)
    collection.objects.link(obj)
    pos = data.get("pos", [0, 0, 0])
    obj.location = (float(pos[0]), -float(pos[2]), float(pos[1]))
    rotation = data.get("rotation", [0, 0])
    obj.rotation_euler[2] = math.radians(-float(rotation[0] if rotation else 0))
    if float(data.get("age", 0)) < 0:
        obj.scale = (.5, .5, .5)
    obj["minecraft_id"] = entity_id
    for k, value in data.items():
        if k in {"id", "pos", "rotation"}:
            continue
        try:
            obj["minecraft_" + k] = value if isinstance(value, (str, int, float, bool)) else json.dumps(value, ensure_ascii=False)
        except Exception:
            pass
    return obj


# ==================== 主入口 ====================

def import_entities(importer_dir, config, log_fn=None):
    import traceback as _tb
    # 文件级调试日志：每个实体失败原因写入 importer/entity_import.log
    _dbg_path = os.path.join(importer_dir, "entity_import.log")
    try:
        _dbg = open(_dbg_path, "w", encoding="utf-8")
    except Exception:
        _dbg = None

    def _dbg_log(msg):
        try:
            if _dbg:
                _dbg.write(msg + "\n")
                _dbg.flush()
        except Exception:
            pass
        if log_fn:
            log_fn(msg)

    entities_path = os.path.join(importer_dir, "entities.json")
    if not os.path.isfile(entities_path):
        _dbg_log("未找到 entities.json（WorldImporter 未导出实体或 Import Entities 未勾选）")
        if _dbg: _dbg.close()
        return 0
    try:
        payload = json.load(open(entities_path, "r", encoding="utf-8"))
    except Exception as ex:
        _dbg_log("实体 JSON 读取失败: %s" % ex)
        if _dbg: _dbg.close()
        return 0

    addon_dir = _addon_dir()
    models_dir = os.path.join(addon_dir, "entity_models")
    if not os.path.isdir(models_dir):
        _dbg_log("缺少 entity_models 目录: %s（插件可能未完整安装）" % models_dir)
        if _dbg: _dbg.close()
        return 0
    registry = _registry(addon_dir)
    if not registry:
        _dbg_log("entity_models/entities.json 为空或读取失败")
        if _dbg: _dbg.close()
        return 0

    _dbg_log("注册表 %d 条；实体 %d 个" % (len(registry), len(payload.get("entities", []))))

    # 只清理旧实体，绝不触碰场景其他对象
    old = bpy.data.collections.get("Crafter Entities")
    if old:
        for obj in list(old.objects):
            bpy.data.objects.remove(obj, do_unlink=True)
    collection = old or bpy.data.collections.new("Crafter Entities")
    try:
        scene = bpy.context.scene or bpy.data.scenes[0]
    except Exception:
        scene = bpy.data.scenes[0]
    if collection.name not in scene.collection.children:
        scene.collection.children.link(collection)
    _dbg_log("集合 %s 就绪（objects 属于 scene.collection）" % collection.name)

    imported, unsupported, failed = 0, {}, 0
    for data in payload.get("entities", []):
        entity_id = data.get("id", "")
        definition = registry.get(entity_id)
        if not definition:
            unsupported[entity_id] = unsupported.get(entity_id, 0) + 1
            continue
        try:
            _create_registered_entity(collection, data, importer_dir, addon_dir, config, definition)
            imported += 1
            _dbg_log("导入 OK: %s" % entity_id)
        except Exception as ex:
            failed += 1
            _dbg_log("失败 %s: %s\n%s" % (entity_id, ex, _tb.format_exc()))
    _dbg_log("已准确导入 %d 个实体；失败 %d 个" % (imported, failed))
    if unsupported:
        _dbg_log("未注册准确模型，已跳过: " + ", ".join("%s×%d" % x for x in sorted(unsupported.items())))
    if _dbg:
        _dbg.close()
    return imported
