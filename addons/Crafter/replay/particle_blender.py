"""Vanilla-ish particle bursts: textured billboards + dig crack overlays (EEVEE)."""
from __future__ import annotations

import json
import math
import random
from pathlib import Path

LIFE_MS = {
    "explosion": 700,
    "explosion_emitter": 900,
    "destroy_block": 550,
    "block": 450,
    "falling_dust": 500,
    "break_anim": 120,
    "poof": 400,
    "cloud": 450,
    "smoke": 500,
    "large_smoke": 650,
    "white_smoke": 500,
    "flame": 350,
}
DEFAULT_LIFE = 350

# kind → particle texture basename pattern under textures/particle/
SPRITE = {
    "explosion": "explosion",
    "explosion_emitter": "explosion",
    "smoke": "big_smoke",
    "large_smoke": "big_smoke",
    "white_smoke": "generic",
    "poof": "generic",
    "cloud": "generic",
    "flame": "flame",
}

_BLOCK_TEX_FALLBACK = {
    "minecraft:dirt": "dirt",
    "minecraft:coarse_dirt": "dirt",
    "minecraft:grass_block": "grass_block_side",
    "minecraft:stone": "stone",
    "minecraft:cobblestone": "cobblestone",
    "minecraft:sand": "sand",
    "minecraft:oak_planks": "oak_planks",
}


def _default_tex_root() -> Path | None:
    here = Path(__file__).resolve().parent
    for cand in (
        here / "particle_tex",
        here.parents[2] / "cache" / "particle_tex",  # bughunt/replay/cache when running from tree
    ):
        if cand.is_dir():
            return cand
    return None


def _load_blockstates():
    p = Path(__file__).with_name("blockstates-1.21.1.json")
    if not p.is_file():
        return []
    try:
        return json.loads(p.read_text()).get("blockStates") or []
    except Exception:
        return []


def _block_tex_name(state_id: int | None, states: list) -> str:
    if state_id is None or not states or state_id < 0 or state_id >= len(states):
        return "dirt"
    name = (states[state_id] or {}).get("Name") or "minecraft:dirt"
    if name in _BLOCK_TEX_FALLBACK:
        return _BLOCK_TEX_FALLBACK[name]
    bare = name.split(":")[-1]
    # strip _slab / _stairs etc. — best effort
    for suf in ("_slab", "_stairs", "_wall", "_fence"):
        if bare.endswith(suf):
            bare = bare[: -len(suf)]
    return bare


def build_particles(store, frame_times, *, box=None, mc_space=None, log=print, seed=1,
                    tex_root: str | Path | None = None, jar: str | None = None):
    """Spawn billboard sprites + dig cracks for ParticleStore bursts."""
    import bpy

    rng = random.Random(seed)
    if mc_space is None:
        mc_space = bpy.data.objects.get("MC_Space_Entities") or bpy.data.objects.get("MC_Space")
        if mc_space is None:
            mc_space = bpy.data.objects.new("MC_Space_Particles", None)
            bpy.context.scene.collection.objects.link(mc_space)
            mc_space.rotation_euler = (math.pi / 2, 0, 0)

    root_tex = Path(tex_root) if tex_root else _default_tex_root()
    if root_tex is None and jar:
        # extract on demand via zip
        root_tex = Path(bpy.app.tempdir or "/tmp") / "crafter_particle_tex"
        _ensure_from_jar(jar, root_tex)

    bursts = store.in_box(box) if hasattr(store, "in_box") else list(store.bursts)
    if not bursts:
        log("particles: none")
        return []

    from .entity_blender import bake_visibility

    coll = bpy.context.scene.collection
    root = bpy.data.objects.new("ReplayParticles", None)
    coll.objects.link(root)
    root.parent = mc_space

    cam = bpy.context.scene.camera
    states = _load_blockstates()
    mats: dict = {}
    created = 0
    MAX_INST = 900
    left = MAX_INST

    def mat_tex(key: str, path: Path | None, *, emit=0.0, alpha_clip=False, tint=None):
        if key in mats:
            return mats[key]
        m = bpy.data.materials.new(f"PTex_{key}")
        m.use_nodes = True
        nt = m.node_tree
        nodes, links = nt.nodes, nt.links
        nodes.clear()
        out = nodes.new("ShaderNodeOutputMaterial")
        bsdf = nodes.new("ShaderNodeBsdfPrincipled")
        links.new(bsdf.outputs["BSDF"], out.inputs["Surface"])
        col = tint or (1, 1, 1)
        if path and path.is_file():
            tex = nodes.new("ShaderNodeTexImage")
            img = bpy.data.images.load(str(path), check_existing=True)
            img.alpha_mode = "STRAIGHT"
            tex.image = img
            tex.interpolation = "Closest"
            links.new(tex.outputs["Color"], bsdf.inputs["Base Color"])
            if "Alpha" in bsdf.inputs:
                links.new(tex.outputs["Alpha"], bsdf.inputs["Alpha"])
            try:
                m.blend_method = "CLIP" if alpha_clip else "BLEND"
                if hasattr(m, "shadow_method"):
                    m.shadow_method = "NONE"
                if hasattr(m, "use_backface_culling"):
                    m.use_backface_culling = False
            except Exception:
                pass
            try:
                bsdf.inputs["Emission Color"].default_value = (*col, 1)
                bsdf.inputs["Emission Strength"].default_value = emit
            except Exception:
                pass
        else:
            bsdf.inputs["Base Color"].default_value = (*col, 1)
            try:
                bsdf.inputs["Emission Color"].default_value = (*col, 1)
                bsdf.inputs["Emission Strength"].default_value = max(emit, 0.2)
                bsdf.inputs["Alpha"].default_value = 0.85
                m.blend_method = "BLEND"
            except Exception:
                pass
        bsdf.inputs["Roughness"].default_value = 1.0
        mats[key] = m
        return m

    def billboard(name, size, mat, parent, loc, *, track=True):
        me = bpy.data.meshes.new(name)
        s = size * 0.5
        me.from_pydata(
            [(-s, -s, 0), (s, -s, 0), (s, s, 0), (-s, s, 0)],
            [],
            [(0, 1, 2, 3)],
        )
        me.materials.append(mat)
        uv = me.uv_layers.new(name="UVMap")
        for li, uvco in enumerate([(0, 0), (1, 0), (1, 1), (0, 1)]):
            uv.data[li].uv = uvco
        ob = bpy.data.objects.new(name, me)
        coll.objects.link(ob)
        ob.parent = parent
        ob.location = loc
        if track and cam is not None:
            # Damped track: local +Z toward camera (works with parenting)
            c = ob.constraints.new("DAMPED_TRACK")
            c.target = cam
            c.track_axis = "TRACK_Z"
        return ob

    def crack_cube(name, mat, parent, loc, *, scale=1.02):
        me = bpy.data.meshes.new(name)
        s = 0.5 * scale
        verts = [(-s, -s, -s), (s, -s, -s), (s, s, -s), (-s, s, -s),
                 (-s, -s, s), (s, -s, s), (s, s, s), (-s, s, s)]
        faces = [(0, 1, 2, 3), (4, 5, 6, 7), (0, 1, 5, 4), (2, 3, 7, 6), (0, 3, 7, 4), (1, 2, 6, 5)]
        me.from_pydata(verts, [], faces)
        uv = me.uv_layers.new(name="UVMap")
        # per-face full UV (each face shows whole destroy_stage sprite)
        for fi, _ in enumerate(faces):
            for j, uvco in enumerate([(0, 0), (1, 0), (1, 1), (0, 1)]):
                uv.data[fi * 4 + j].uv = uvco
        me.materials.append(mat)
        ob = bpy.data.objects.new(name, me)
        coll.objects.link(ob)
        ob.parent = parent
        ob.location = loc
        return ob

    def pick_sprite(kind: str) -> Path | None:
        if not root_tex:
            return None
        base = SPRITE.get(kind)
        if not base:
            return None
        if base == "flame":
            p = root_tex / "particle" / "flame.png"
            return p if p.is_file() else None
        # numbered sequences
        folder = root_tex / "particle"
        cands = sorted(folder.glob(f"{base}_*.png"))
        if not cands:
            cands = sorted(folder.glob(f"{base}.png"))
        return rng.choice(cands) if cands else None

    def pick_debris(state_id) -> Path | None:
        if not root_tex:
            return None
        bare = _block_tex_name(state_id, states)
        debris = root_tex / "debris"
        cands = list(debris.glob(f"{bare}_*.png")) if debris.is_dir() else []
        if not cands:
            # fall back to full block tex
            p = root_tex / "block" / f"{bare}.png"
            if p.is_file():
                return p
            p = root_tex / "block" / "dirt.png"
            return p if p.is_file() else None
        return rng.choice(cands)

    def vis(objs, t0, life):
        fr_on = _frame_at(frame_times, t0)
        fr_off = _frame_at(frame_times, t0 + life)
        f0 = frame_times[0][0]
        seq = [(f0, False)]
        if fr_on > f0:
            seq.append((fr_on, True))
        else:
            seq = [(f0, True)]
        seq.append((fr_off, False))
        for ob in objs:
            bake_visibility(ob, seq)

    for bi, burst in enumerate(bursts):
        if left <= 0:
            break
        kind = burst.kind
        life = LIFE_MS.get(kind, DEFAULT_LIFE)
        group = bpy.data.objects.new(f"PB{bi}_{kind}", None)
        coll.objects.link(group)
        group.parent = root
        group.location = (burst.x, burst.y, burst.z)
        objs = [group]

        if kind == "break_anim":
            stage = burst.stage if burst.stage is not None else 0
            if stage < 0 or stage > 9:
                continue
            # solid dirt body so the crack reads (overlay alone is sparse black pixels)
            dirt_path = (root_tex / "block" / "dirt.png") if root_tex else None
            body = mat_tex("dig_body_dirt", dirt_path, alpha_clip=False, emit=0.0)
            ob_body = crack_cube(f"PB{bi}_body", body, group, (0, 0, 0), scale=1.0)
            objs.append(ob_body)
            path = (root_tex / "block" / f"destroy_stage_{stage}.png") if root_tex else None
            mat = mat_tex(f"crack_{stage}", path, alpha_clip=False, emit=0.0)
            # force BLEND for crack
            try:
                mat.blend_method = "BLEND"
            except Exception:
                pass
            ob = crack_cube(f"PB{bi}_crack", mat, group, (0, 0, 0), scale=1.03)
            objs.append(ob)
            left -= 2
            created += 2
            vis(objs, burst.t, life)
            continue

        if kind in ("destroy_block", "block", "falling_dust"):
            n = min(burst.count, 28, left)
            for i in range(n):
                path = pick_debris(burst.block_state)
                mat = mat_tex(f"deb_{path.name if path else 'x'}_{i%4}", path, alpha_clip=True)
                size = 0.16 + rng.random() * 0.14
                dx = (rng.random() - 0.5) * 2 * (burst.ox or 0.35)
                dy = abs(rng.random()) * (burst.oy or 0.35) + 0.05
                dz = (rng.random() - 0.5) * 2 * (burst.oz or 0.35)
                ob = billboard(f"PB{bi}_{i}", size, mat, group, (dx, dy, dz))
                objs.append(ob)
                left -= 1
                created += 1
            vis(objs, burst.t, life)
            continue

        if kind in ("smoke", "large_smoke", "white_smoke", "poof", "cloud"):
            n = min(max(burst.count, 3), 16, left)
            for i in range(n):
                path = pick_sprite(kind)
                mat = mat_tex(f"sm_{kind}_{path.stem if path else i}", path, alpha_clip=False, emit=0.02)
                size = (0.45 if kind == "large_smoke" else 0.28) * (0.7 + rng.random() * 0.6)
                dx = (rng.random() - 0.5) * 2 * (burst.ox or 0.2)
                dy = rng.random() * (burst.oy or 0.15) + 0.02
                dz = (rng.random() - 0.5) * 2 * (burst.oz or 0.2)
                ob = billboard(f"PB{bi}_{i}", size, mat, group, (dx, dy, dz))
                objs.append(ob)
                left -= 1
                created += 1
            vis(objs, burst.t, life)
            continue

        if kind in ("explosion", "explosion_emitter", "flame"):
            n = min(max(burst.count, 10), 36 if kind != "flame" else 12, left)
            radius = burst.radius or (burst.ox or 1.0)
            for i in range(n):
                path = pick_sprite(kind if kind != "flame" else "flame")
                emit = 2.5 if kind.startswith("explo") else 1.5
                mat = mat_tex(
                    f"ex_{kind}_{path.stem if path else i}",
                    path,
                    emit=emit,
                    alpha_clip=True,
                    tint=(1.0, 0.7, 0.3),
                )
                size = (0.9 if kind == "explosion_emitter" else 0.55) * (0.6 + rng.random() * 0.8)
                if kind == "flame":
                    size *= 0.45
                # spherical scatter
                theta = rng.random() * math.tau
                phi = rng.random() * math.pi
                r = radius * (0.15 + rng.random() * 0.85)
                dx = r * math.sin(phi) * math.cos(theta)
                dy = r * math.cos(phi) * 0.7 + abs(rng.random()) * 0.4
                dz = r * math.sin(phi) * math.sin(theta)
                ob = billboard(f"PB{bi}_{i}", size, mat, group, (dx, dy, dz))
                objs.append(ob)
                left -= 1
                created += 1
            # extra smoke ring for TNT
            if kind.startswith("explo") and root_tex and left > 0:
                for i in range(min(10, left)):
                    path = pick_sprite("large_smoke")
                    mat = mat_tex(f"exsmoke_{path.stem if path else i}", path, alpha_clip=True)
                    size = 0.7 * (0.7 + rng.random())
                    ang = i / 10 * math.tau
                    dx = math.cos(ang) * radius * 0.5
                    dz = math.sin(ang) * radius * 0.5
                    ob = billboard(f"PB{bi}_sm{i}", size, mat, group, (dx, 0.3 + rng.random() * 0.5, dz))
                    objs.append(ob)
                    left -= 1
                    created += 1
            vis(objs, burst.t, life + 100)
            continue

        # generic fallback: billboard with generic sprite or tinted plane
        n = min(max(burst.count, 2), 12, left)
        for i in range(n):
            path = pick_sprite(kind) or (pick_sprite("poof") if root_tex else None)
            mat = mat_tex(f"gen_{kind}_{i}", path, alpha_clip=True, tint=(0.8, 0.8, 0.8))
            size = 0.15
            dx = (rng.random() - 0.5) * 2 * (burst.ox or 0.2)
            dy = (rng.random() - 0.5) * 2 * (burst.oy or 0.2)
            dz = (rng.random() - 0.5) * 2 * (burst.oz or 0.2)
            ob = billboard(f"PB{bi}_{i}", size, mat, group, (dx, dy, dz))
            objs.append(ob)
            left -= 1
            created += 1
        vis(objs, burst.t, life)

    log(f"particles: {created} sprites from {len(bursts)} bursts (tex={bool(root_tex)})")
    return [root]


def _ensure_from_jar(jar: str, dest: Path):
    import zipfile
    dest.mkdir(parents=True, exist_ok=True)
    (dest / "particle").mkdir(exist_ok=True)
    (dest / "block").mkdir(exist_ok=True)
    want_pref = (
        "assets/minecraft/textures/particle/explosion_",
        "assets/minecraft/textures/particle/big_smoke_",
        "assets/minecraft/textures/particle/generic_",
        "assets/minecraft/textures/particle/flame.png",
        "assets/minecraft/textures/block/destroy_stage_",
        "assets/minecraft/textures/block/dirt.png",
        "assets/minecraft/textures/block/stone.png",
    )
    with zipfile.ZipFile(jar) as zf:
        for name in zf.namelist():
            if not any(name.startswith(p) or name == p.rstrip("_") for p in want_pref):
                # simpler
                if "/textures/particle/" not in name and "destroy_stage_" not in name and not name.endswith("/dirt.png") and not name.endswith("/stone.png"):
                    continue
            if not name.endswith(".png"):
                continue
            bn = name.rsplit("/", 1)[-1]
            sub = "block" if ("destroy_stage" in bn or bn in ("dirt.png", "stone.png")) else "particle"
            out = dest / sub / bn
            if not out.exists():
                out.write_bytes(zf.read(name))


def _frame_at(frame_times, t):
    for f, tt in frame_times:
        if tt >= t:
            return f
    return frame_times[-1][0] + 1
