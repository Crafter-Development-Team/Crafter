"""Build animated vanilla entity models (players + mobs) in Blender from EntityStore tracks.

Geometry: entity_models-1.21.1.json, extracted (read-only) from the entities line's
EntityModelData.inc (vanilla LayerDefinitions). Transform chain mirrors
LivingEntityRenderer: T(pos) * RY(180 - bodyYaw) * S(-1,-1,1) * T(0,-1.501,0) * parts,
ModelPart.translateAndRotate = T(pose/16) * Rz * Ry * Rx (Blender Euler 'XYZ').
Everything lives under an empty that maps MC (Y-up) to Blender (Z-up): Rx(+90deg).
"""
from __future__ import annotations

import io
import json
import math
import os
import urllib.request
import zipfile
from pathlib import Path

_MODELS = None

MOB_MODELS = {
    "player": ("zombie#main", "entity/player/wide/steve.png"),
    "villager": ("villager#main", "entity/villager/villager.png"),
    "cow": ("cow#main", "entity/cow/cow.png"),
    "pig": ("pig#main", "entity/pig/pig.png"),
    "sheep": ("sheep#main", "entity/sheep/sheep.png"),
    "chicken": ("chicken#main", "entity/chicken.png"),
    "cat": ("cat#main", "entity/cat/tabby.png"),
    "wolf": ("wolf#main", "entity/wolf/wolf.png"),
    "creeper": ("creeper#main", "entity/creeper/creeper.png"),
    "skeleton": ("skeleton#main", "entity/skeleton/skeleton.png"),
    "zombie": ("zombie#main", "entity/zombie/zombie.png"),
}
# extra overlay layers drawn with the same pose
MOB_EXTRA = {"sheep": [("sheep#fur", "entity/sheep/sheep_fur.png")],
             "villager": [("villager#main", "entity/villager/type/plains.png")]}
# Duplicate of the base layer (villager profession). Exact coplanar shells whose
# texels are transparent (head/hat/nose) make EEVEE's dithered depth test write
# black over the face. A fraction of a model pixel keeps the shell in front.
_OVERLAY_INFLATE = 0.25
ARMOR_MAT = {"iron": "iron", "diamond": "diamond", "golden": "gold", "leather": "leather",
             "chainmail": "chainmail", "netherite": "netherite", "turtle": "turtle"}
ARMOR_PARTS = {"head": ("outer", ["head", "hat"]), "chest": ("outer", ["body", "right_arm", "left_arm"]),
               "legs": ("inner", ["body", "right_leg", "left_leg"]), "feet": ("outer", ["right_leg", "left_leg"])}
HUMANOID = {"player", "zombie", "skeleton"}


def models():
    global _MODELS
    if _MODELS is None:
        _MODELS = json.loads((Path(__file__).parent / "entity_models-1.21.1.json").read_text())
    return _MODELS


class TextureCache:
    def __init__(self, jar: str, cache_dir: str):
        self.jar = zipfile.ZipFile(jar)
        self.dir = Path(cache_dir)
        self.dir.mkdir(parents=True, exist_ok=True)
        self.images = {}

    def path(self, rel: str) -> str | None:
        out = self.dir / rel.replace("/", "_")
        if not out.exists():
            try:
                out.write_bytes(self.jar.read("assets/minecraft/textures/" + rel))
            except KeyError:
                return None
        return str(out)

    def skin(self, url: str | None) -> str | None:
        if not url:
            return None
        out = self.dir / ("skin_" + url.rsplit("/", 1)[-1] + ".png")
        if not out.exists():
            try:
                with urllib.request.urlopen(url.replace("http://", "https://", 1), timeout=10) as r:
                    out.write_bytes(r.read())
            except Exception:
                return None
        return str(out)


def _material(name: str, img_path: str, tint=None):
    import bpy
    mat = bpy.data.materials.get(name)
    if mat:
        return mat
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    nt = mat.node_tree
    bsdf = nt.nodes.get("Principled BSDF")
    tex = nt.nodes.new("ShaderNodeTexImage")
    tex.interpolation = "Closest"
    tex.image = bpy.data.images.load(img_path, check_existing=True)
    uvn = nt.nodes.new("ShaderNodeUVMap")
    uvn.uv_map = "UVMap"
    nt.links.new(uvn.outputs["UV"], tex.inputs["Vector"])
    col = tex.outputs["Color"]
    if tint is not None:
        mix = nt.nodes.new("ShaderNodeMix")
        mix.data_type = "RGBA"
        mix.blend_type = "MULTIPLY"
        mix.inputs[0].default_value = 1.0
        nt.links.new(col, mix.inputs[6])
        mix.inputs[7].default_value = (*tint, 1.0)
        col = mix.outputs[2]
    nt.links.new(col, bsdf.inputs["Base Color"])
    nt.links.new(tex.outputs["Alpha"], bsdf.inputs["Alpha"])
    bsdf.inputs["Roughness"].default_value = 1.0
    try:
        bsdf.inputs["Specular IOR Level"].default_value = 0.0
    except Exception:
        pass
    mat.blend_method = "CLIP" if hasattr(mat, "blend_method") else None
    try:
        mat.surface_render_method = "DITHERED"
    except Exception:
        pass
    mat.use_backface_culling = False
    # Overlay shells (hat / villager profession / armor) enclose the base cube: without
    # transparent shadows their invisible texels shadow the face black in EEVEE.
    try:
        mat.use_transparent_shadow = True
    except Exception:
        pass
    try:
        mat.shadow_method = "CLIP"
    except Exception:
        pass
    # Textures live in the temp replay work dir; pack so the .blend stays self-contained.
    try:
        if tex.image and not tex.image.packed_file:
            tex.image.pack()
    except Exception:
        pass
    return mat


def _quad_normal_offset(corners, inflate: float):
    """Shift quad corners along their normal by `inflate` (same units as `corners`)."""
    if not inflate:
        return corners
    (x0, y0, z0), (x1, y1, z1), (x2, y2, z2) = corners[0], corners[1], corners[2]
    ax, ay, az = x1 - x0, y1 - y0, z1 - z0
    bx, by, bz = x2 - x0, y2 - y0, z2 - z0
    nx, ny, nz = ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx
    length = math.sqrt(nx * nx + ny * ny + nz * nz)
    if length < 1e-8:
        return corners
    s = inflate / length
    nx, ny, nz = nx * s, ny * s, nz * s
    return [(x + nx, y + ny, z + nz) for x, y, z in corners]


def offset_mesh_along_normals(me, inflate: float):
    """Push each quad of an already-built `_part_mesh` out by `inflate` model pixels.

    Mesh coordinates are model pixels / 16. Quads do not share vertices.
    """
    if me is None or not inflate:
        return
    co = [tuple(v.co) for v in me.vertices]
    step = inflate / 16.0
    for p in me.polygons:
        ids = list(p.vertices)
        moved = _quad_normal_offset([co[i] for i in ids], step)
        for i, c in zip(ids, moved):
            co[i] = c
    for v, c in zip(me.vertices, co):
        v.co = c
    me.update()


def _part_mesh(name: str, polys, mat, inflate: float = 0.0):
    import bpy
    verts, faces, uvs = [], [], []
    for poly in polys:
        corners, face_uvs = [], []
        for i in range(4):
            x, y, z, u, v = poly[i * 5:i * 5 + 5]
            corners.append((x, y, z))
            face_uvs.append((u, 1.0 - v))
        # `inflate` is in model pixels, before the /16 world scale.
        corners = _quad_normal_offset(corners, inflate)
        idx = []
        for (x, y, z), uv in zip(corners, face_uvs):
            verts.append((x / 16.0, y / 16.0, z / 16.0))
            uvs.append(uv)
            idx.append(len(verts) - 1)
        faces.append(idx)
    me = bpy.data.meshes.new(name)
    me.from_pydata(verts, [], faces)
    uvl = me.uv_layers.new(name="UVMap")
    for li, loop in enumerate(me.loops):
        uvl.data[li].uv = uvs[loop.vertex_index]
    me.materials.append(mat)
    return me


def build_layer(layer_key: str, mat, parent, prefix: str, only=None, attach_to=None, inflate: float = 0.0):
    """Instantiate a model layer. Returns {part_name: object}.
    attach_to: {part_name: obj} -> parent geometry to existing posed parts (armor/overlays).
    inflate: model pixels to push each quad out along its normal (overlay shells)."""
    import bpy
    L = models()[layer_key]
    objs = {}
    for i, p in enumerate(L["parts"]):
        name = p["name"]
        top = name
        if only is not None and name not in only and name != "root":
            continue
        me = _part_mesh(f"{prefix}_{name}", p["polys"], mat, inflate) if p["polys"] else None
        ob = bpy.data.objects.new(f"{prefix}_{name}", me)
        bpy.context.scene.collection.objects.link(ob)
        if attach_to is not None and name in attach_to:
            ob.parent = attach_to[name]
        else:
            par = p["parent"]
            ob.parent = objs.get(L["parts"][par]["name"]) if par >= 0 else parent
            pose = p["pose"]
            ob.location = (pose[0] / 16.0, pose[1] / 16.0, pose[2] / 16.0)
            ob.rotation_mode = "XYZ"
            ob.rotation_euler = (pose[3], pose[4], pose[5])
            ob.scale = (pose[6], pose[7], pose[8])
        if not p["visible"]:
            ob.hide_render = True
            ob.hide_viewport = True
        objs[name] = ob
    return objs


def _lerp_angle(a, b, f):
    d = (b - a + 180.0) % 360.0 - 180.0
    return a + d * f


def sample(track, t):
    ks = track.keys
    if t <= ks[0].t:
        return ks[0]
    if t >= ks[-1].t:
        return ks[-1]
    lo, hi = 0, len(ks) - 1
    while hi - lo > 1:
        m = (lo + hi) // 2
        if ks[m].t <= t:
            lo = m
        else:
            hi = m
    a, b = ks[lo], ks[hi]
    # client interpolates entity moves over ~3 ticks
    f = min(1.0, (t - a.t) / max(1, min(b.t - a.t, 150)))
    from .entities import Key
    return Key(t, a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.z + (b.z - a.z) * f,
               _lerp_angle(a.yaw, b.yaw, f), a.pitch + (b.pitch - a.pitch) * f,
               _lerp_angle(a.head_yaw, b.head_yaw, f), b.on_ground)


def _fcurves(ob, path, n):
    ob.animation_data_create()
    if ob.animation_data.action is None:
        import bpy
        ob.animation_data.action = bpy.data.actions.new(ob.name + "_act")
    act = ob.animation_data.action
    out = []
    for i in range(n):
        fc = act.fcurves.find(path, index=i) or act.fcurves.new(path, index=i)
        out.append(fc)
    return out


def bake(ob, path, frames, values, interp="LINEAR"):
    """values: list of tuples (one per frame)."""
    n = len(values[0])
    fcs = _fcurves(ob, path, n)
    for i, fc in enumerate(fcs):
        fc.keyframe_points.add(len(frames))
        co = []
        for f, v in zip(frames, values):
            co += [f, v[i]]
        fc.keyframe_points.foreach_set("co", co)
        fc.keyframe_points.foreach_set("interpolation", [{"LINEAR": 1, "CONSTANT": 0}[interp]] * len(frames))
        fc.update()


def bake_visibility(ob, frames_on_off):
    """frames_on_off: list of (frame, visible)."""
    for path in ("hide_render", "hide_viewport"):
        fcs = _fcurves(ob, path, 1)
        fc = fcs[0]
        fc.keyframe_points.add(len(frames_on_off))
        co = []
        for f, vis in frames_on_off:
            co += [f, 0.0 if vis else 1.0]
        fc.keyframe_points.foreach_set("co", co)
        fc.keyframe_points.foreach_set("interpolation", [0] * len(frames_on_off))
        fc.update()


def build_entities(store, frame_times, tex: TextureCache, *, box=None, mc_space=None, log=print):
    """frame_times: list of (frame, replay_ms). Creates animated entities. Returns created roots."""
    import bpy
    if mc_space is None:
        mc_space = bpy.data.objects.new("MC_Space_Entities", None)
        bpy.context.scene.collection.objects.link(mc_space)
        mc_space.rotation_euler = (math.pi / 2, 0, 0)
    frames = [f for f, _ in frame_times]
    roots = []
    for tr in store.visible_tracks():
        spec = MOB_MODELS.get(tr.type_name)
        if not spec or not tr.keys:
            continue
        k0 = tr.keys[0]
        if box and not (box[0] - 8 <= k0.x <= box[3] + 8 and box[2] - 8 <= k0.z <= box[5] + 8):
            continue
        layer, tex_rel = spec
        img = None
        slim = False
        from .entities import CAT_VARIANTS, VILLAGER_TYPES, VILLAGER_PROFESSIONS, DYE_TEXTURE_DIFFUSE
        t0 = tr.spawn_t
        if tr.type_name == "player":
            prof = store.profiles.get(tr.uuid)
            img = tex.skin(prof.skin_url if prof else None)
            if img:
                img = upgrade_legacy_skin(img)
        elif tr.type_name == "cat":
            vid = tr.cat_variant_id(t0)
            if vid is not None and 0 <= vid < len(CAT_VARIANTS):
                tex_rel = f"entity/cat/{CAT_VARIANTS[vid]}.png"
        img = img or tex.path(tex_rel)
        if not img:
            continue
        prefix = f"E{tr.eid}_{tr.type_name}"
        root = bpy.data.objects.new(prefix, None)
        bpy.context.scene.collection.objects.link(root)
        root.parent = mc_space
        flip = bpy.data.objects.new(prefix + "_flip", None)
        bpy.context.scene.collection.objects.link(flip)
        flip.parent = root
        flip.scale = (-1, -1, 1)
        model = bpy.data.objects.new(prefix + "_model", None)
        bpy.context.scene.collection.objects.link(model)
        model.parent = flip
        model.location = (0, -1.501, 0)
        # AgeableListModel baby: body scale 1/2, bodyYOffset 24px (Quadruped/Sheep defaults)
        if tr.baby(t0) and tr.type_name in ("cat", "sheep", "cow", "pig", "chicken", "wolf"):
            model.scale = (0.5, 0.5, 0.5)
            model.location = (0, -1.501 + 24.0 / 16.0 * 0.5, 0)
        mat = _material(f"ent_{Path(img).stem}_{tr.eid}", img)
        parts = build_layer(layer, mat, model, prefix)
        extras = list(MOB_EXTRA.get(tr.type_name, []))
        if tr.type_name == "villager":
            vd = tr.villager_data(t0)
            vtype = VILLAGER_TYPES[vd[0]] if vd and 0 <= vd[0] < len(VILLAGER_TYPES) else "plains"
            vprof = VILLAGER_PROFESSIONS[vd[1]] if vd and 0 <= vd[1] < len(VILLAGER_PROFESSIONS) else "none"
            extras = [("villager#main", f"entity/villager/type/{vtype}.png")]
            if vprof != "none":
                extras.append(("villager#main", f"entity/villager/profession/{vprof}.png"))
        fur_objs = []
        for extra_layer, extra_tex in extras:
            p2 = tex.path(extra_tex)
            if not p2:
                continue
            shell = _OVERLAY_INFLATE if extra_layer == layer else 0.0
            tint = None
            mat_name = f"ent_{Path(p2).stem}_{tr.eid}"
            if tr.type_name == "sheep" and "sheep_fur" in extra_tex:
                color = tr.sheep_color(t0)
                if 0 <= color < len(DYE_TEXTURE_DIFFUSE):
                    tint = DYE_TEXTURE_DIFFUSE[color]
                mat_name = f"ent_sheep_fur_{tr.eid}_{color}"
            built = build_layer(extra_layer, _material(mat_name, p2, tint), model, prefix + "_x",
                                attach_to=parts, inflate=shell)
            if tr.type_name == "sheep" and "sheep_fur" in extra_tex:
                fur_objs = list(built.values())
                if tr.sheep_sheared(t0):
                    for ob in fur_objs:
                        ob.hide_render = True
                        ob.hide_viewport = True
        # Player skin-layer hat (zombie#main only has hat among overlay parts)
        if tr.type_name == "player" and "hat" in parts:
            if not (tr.player_skin_parts(t0) & 0x40):
                parts["hat"].hide_render = True
                parts["hat"].hide_viewport = True
        # --- per-frame animation ---
        locs, rots, head_r, limb = [], [], [], []
        walk_pos, last = 0.0, None
        vis = []
        for f, t in frame_times:
            k = sample(tr, t)
            locs.append((k.x, k.y, k.z))
            rots.append((0.0, math.radians(180.0 - k.yaw), 0.0))
            hy = (k.head_yaw - k.yaw + 180.0) % 360.0 - 180.0
            head_r.append((math.radians(k.pitch), math.radians(hy)))
            if last is not None:
                dist = math.hypot(k.x - last.x, k.z - last.z)
                dt_ticks = max(1e-3, (t - last.t) / 50.0)
                speed = min(1.0, dist / dt_ticks * 4.0)
                walk_pos += speed * dt_ticks
            else:
                speed = 0.0
            last = k
            limb.append((walk_pos, speed))
            alive = t >= tr.spawn_t and (tr.remove_t is None or t < tr.remove_t)
            alive = alive and not tr.invisible(t)
            vis.append((f, alive))
        bake(root, "location", frames, locs)
        bake(root, "rotation_euler", frames, rots)
        if "head" in parts:
            h = parts["head"]
            bake(h, "rotation_euler", frames, [(hp, hyw, h.rotation_euler[2]) for hp, hyw in head_r])
        swing = {"right_arm": math.pi, "left_arm": 0.0, "right_leg": 0.0, "left_leg": math.pi,
                 "right_hind_leg": 0.0, "left_hind_leg": math.pi, "right_front_leg": math.pi, "left_front_leg": 0.0}
        amp = {"right_arm": 1.0, "left_arm": 1.0}
        for pn, phase in swing.items():
            ob = parts.get(pn)
            if not ob:
                continue
            base = tuple(ob.rotation_euler)
            a = amp.get(pn, 1.4)
            if tr.type_name == "villager" and pn.endswith("arm"):
                continue
            vals = []
            for (wp, sp) in limb:
                x = math.cos(wp * 0.6662 + phase) * a * sp
                if pn == "right_arm" and tr.equipment.get("mainhand"):
                    x = x * 0.5 - math.pi / 10
                vals.append((base[0] + x, base[1], base[2]))
            bake(ob, "rotation_euler", frames, vals)
        # sparse visibility (spawn/remove)
        changes = [vis[0]] + [vis[i] for i in range(1, len(vis)) if vis[i][1] != vis[i - 1][1]]
        for ob in [root] + list(root.children_recursive):
            bake_visibility(ob, changes)
        if tr.type_name == "player":
            _build_equipment(tr, parts, tex, frame_times)
        roots.append(root)
        log(f"entity {prefix}: {len(tr.keys)} keys")
    return roots


def _armor_texture(item: str, tex: TextureCache, layer: int):
    mat = item.split("_")[0]
    mat = ARMOR_MAT.get(mat, mat)
    return tex.path(f"models/armor/{mat}_layer_{layer}.png"), mat


def _build_equipment(tr, parts, tex: TextureCache, frame_times):
    import bpy
    from mathutils import Euler, Matrix
    for slot, hist in tr.equipment.items():
        for i, (t_on, item) in enumerate(hist):
            if not item:
                continue
            t_off = hist[i + 1][0] if i + 1 < len(hist) else None
            objs = []
            if slot in ARMOR_PARTS:
                which, pnames = ARMOR_PARTS[slot]
                layer = 2 if which == "inner" else 1
                img, matname = _armor_texture(item, tex, layer)
                if not img:
                    continue
                tint = (0xA0 / 255, 0x65 / 255, 0x40 / 255) if matname == "leather" else None
                mat = _material(f"armor_{matname}_{layer}", img, tint)
                key = "zombie#inner_armor" if which == "inner" else "zombie#outer_armor"
                built = build_layer(key, mat, None, f"E{tr.eid}_{slot}", only=pnames, attach_to=parts)
                objs = [o for n, o in built.items() if n != "root"]
                if "root" in built:
                    bpy.data.objects.remove(built["root"])
            elif slot == "mainhand" and "right_arm" in parts:
                img = tex.path(f"item/{item}.png")
                if not img:
                    continue
                mat = _material(f"item_{item}", img)
                me = bpy.data.meshes.new(f"E{tr.eid}_{item}")
                z = 0.5
                me.from_pydata([(0, 0, z), (1, 0, z), (1, 1, z), (0, 1, z)], [], [[0, 1, 2, 3]])
                uvl = me.uv_layers.new(name="UVMap")
                for li, uv in enumerate([(0, 0), (1, 0), (1, 1), (0, 1)]):
                    uvl.data[li].uv = uv
                me.materials.append(mat)
                ob = bpy.data.objects.new(f"E{tr.eid}_{item}", me)
                bpy.context.scene.collection.objects.link(ob)
                ob.parent = parts["right_arm"]
                # ItemInHandLayer: Rx(-90) Ry(180) T(1/16, .125, -.625); handheld thirdperson_righthand:
                # T(0, 4/16, .5/16) R(0,-90,55) S(.85); item model offset T(-.5,-.5,-.5)
                m = (Matrix.Rotation(math.radians(-90), 4, "X") @ Matrix.Rotation(math.radians(180), 4, "Y")
                     @ Matrix.Translation((1 / 16, 0.125, -0.625))
                     @ Matrix.Translation((0, 4 / 16, 0.5 / 16))
                     @ Euler((0, math.radians(-90), math.radians(55)), "XYZ").to_matrix().to_4x4()
                     @ Matrix.Scale(0.85, 4) @ Matrix.Translation((-0.5, -0.5, -0.5)))
                ob.matrix_basis = m
                objs = [ob]
            fr_on = _frame_at(frame_times, t_on)
            fr_off = _frame_at(frame_times, t_off) if t_off else None
            for ob in objs:
                seq = [(frame_times[0][0], frame_times[0][0] >= fr_on)]
                if fr_on > frame_times[0][0]:
                    seq.append((fr_on, True))
                if fr_off:
                    seq.append((fr_off, False))
                bake_visibility(ob, seq)


def _frame_at(frame_times, t):
    for f, tt in frame_times:
        if tt >= t:
            return f
    return frame_times[-1][0] + 1


_LEGACY_COPIES = [(4, 16, 16, 32, 4, 4), (8, 16, 16, 32, 4, 4), (0, 20, 24, 32, 4, 12), (4, 20, 16, 32, 4, 12),
                  (8, 20, 8, 32, 4, 12), (12, 20, 16, 32, 4, 12), (44, 16, -8, 32, 4, 4), (48, 16, -8, 32, 4, 4),
                  (40, 20, 0, 32, 4, 12), (44, 20, -8, 32, 4, 12), (48, 20, -16, 32, 4, 12), (52, 20, -8, 32, 4, 12)]


def upgrade_legacy_skin(path: str) -> str:
    """64x32 legacy skin -> 64x64 (vanilla SkinTextureDownloader.processLegacySkin)."""
    import bpy
    src = bpy.data.images.load(path, check_existing=True)
    w, h = src.size
    if (w, h) != (64, 32):
        return path
    out_path = path[:-4] + "_64.png"
    if os.path.exists(out_path):
        return out_path
    px = list(src.pixels)  # bottom-up RGBA floats
    def get(x, y):
        i = ((31 - y) * 64 + x) * 4
        return px[i:i + 4]
    img = [[(0.0, 0.0, 0.0, 0.0)] * 64 for _ in range(64)]
    for y in range(32):
        for x in range(64):
            img[y][x] = tuple(get(x, y))
    # notch transparency hack: hat region fully opaque -> transparent
    if all(img[y][x][3] >= 1.0 for y in range(0, 16) for x in range(32, 64)):
        for y in range(0, 16):
            for x in range(32, 64):
                img[y][x] = (0.0, 0.0, 0.0, 0.0)
    for sx, sy, dx, dy, rw, rh in _LEGACY_COPIES:
        for yy in range(rh):
            for xx in range(rw):
                img[sy + dy + yy][sx + dx + (rw - 1 - xx)] = img[sy + yy][sx + xx]
    new = bpy.data.images.new(os.path.basename(out_path), 64, 64, alpha=True)
    flat = []
    for y in range(63, -1, -1):
        for x in range(64):
            flat.extend(img[y][x])
    new.pixels = flat
    new.filepath_raw = out_path
    new.file_format = "PNG"
    new.save()
    return out_path
