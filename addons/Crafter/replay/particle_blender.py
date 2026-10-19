"""Vanilla-ish particle bursts: textured billboards + dig crack overlays (EEVEE)."""
from __future__ import annotations

import os

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
    "break_anim": 280,  # 阶段重叠更像原版
    "poof": 480,
    "cloud": 450,
    "smoke": 650,
    "large_smoke": 800,
    "white_smoke": 650,
    "flame": 450,
    "small_flame": 400,
    "crit": 320,
    "enchanted_hit": 300,
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
    "small_flame": "flame",
    "crit": "spark",
    "enchanted_hit": "spark",
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
                    tex_root: str | Path | None = None, jar: str | None = None,
                    resourcepacks: list[str] | None = None):
    """Spawn billboard sprites + dig cracks for ParticleStore bursts."""
    import bpy

    rng = random.Random(seed)
    if mc_space is None:
        mc_space = bpy.data.objects.get("MC_Space_Entities") or bpy.data.objects.get("MC_Space")
        if mc_space is None:
            mc_space = bpy.data.objects.new("MC_Space_Particles", None)
            bpy.context.scene.collection.objects.link(mc_space)
            mc_space.rotation_euler = (math.pi / 2, 0, 0)

    packs = [p for p in (resourcepacks or []) if p]
    root_tex = Path(tex_root) if tex_root else _default_tex_root()
    if jar or packs:
        dest = Path(bpy.app.tempdir or "/tmp") / "crafter_particle_tex"
        if root_tex is None or packs:
            # Writable cache: jar fills gaps, packs override (highest priority wins).
            if root_tex and root_tex.is_dir() and packs:
                import shutil
                dest.mkdir(parents=True, exist_ok=True)
                for sub in ("particle", "block", "seq", "debris"):
                    src = root_tex / sub
                    if src.is_dir():
                        shutil.copytree(src, dest / sub, dirs_exist_ok=True)
            _ensure_from_jar(jar or "", dest, packs)
            root_tex = dest

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

    def _finish_mat(m, alpha_clip):
        try:
            m.blend_method = "CLIP" if alpha_clip else "BLEND"
            if hasattr(m, "shadow_method"):
                m.shadow_method = "NONE"
            if hasattr(m, "use_backface_culling"):
                m.use_backface_culling = False
        except Exception:
            pass

    def mat_tex(key: str, path: Path | None, *, emit=0.0, alpha_clip=False, tint=None, warm=0.0, alpha_mul=1.0, soft_edge=False):
        full_key = key if alpha_mul >= 0.999 and not soft_edge else f"{key}_a{alpha_mul:.2f}{'s' if soft_edge else ''}"
        if full_key in mats:
            return mats[full_key]
        m = bpy.data.materials.new(f"PTex_{full_key}")
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
            tex.interpolation = "Linear" if soft_edge else "Closest"
            color_out = tex.outputs["Color"]
            if warm > 0:
                mix = nodes.new("ShaderNodeMixRGB")
                mix.blend_type = "MIX"
                mix.inputs["Fac"].default_value = warm
                mix.inputs["Color2"].default_value = (1.0, 0.35, 0.02, 1)
                links.new(color_out, mix.inputs["Color1"])
                color_out = mix.outputs["Color"]
            links.new(color_out, bsdf.inputs["Base Color"])
            if "Alpha" in bsdf.inputs:
                a_out = tex.outputs["Alpha"]
                if soft_edge:
                    # UV 径向衰减，软化硬白边
                    tc = nodes.new("ShaderNodeTexCoord")
                    sep = nodes.new("ShaderNodeSeparateXYZ")
                    links.new(tc.outputs["UV"], sep.inputs["Vector"])
                    subx = nodes.new("ShaderNodeMath"); subx.operation = "SUBTRACT"
                    subx.inputs[1].default_value = 0.5
                    links.new(sep.outputs["X"], subx.inputs[0])
                    suby = nodes.new("ShaderNodeMath"); suby.operation = "SUBTRACT"
                    suby.inputs[1].default_value = 0.5
                    links.new(sep.outputs["Y"], suby.inputs[0])
                    sqx = nodes.new("ShaderNodeMath"); sqx.operation = "MULTIPLY"
                    links.new(subx.outputs[0], sqx.inputs[0]); links.new(subx.outputs[0], sqx.inputs[1])
                    sqy = nodes.new("ShaderNodeMath"); sqy.operation = "MULTIPLY"
                    links.new(suby.outputs[0], sqy.inputs[0]); links.new(suby.outputs[0], sqy.inputs[1])
                    add = nodes.new("ShaderNodeMath"); add.operation = "ADD"
                    links.new(sqx.outputs[0], add.inputs[0]); links.new(sqy.outputs[0], add.inputs[1])
                    # falloff = saturate(1 - 4*r^2)
                    mul4 = nodes.new("ShaderNodeMath"); mul4.operation = "MULTIPLY"
                    mul4.inputs[1].default_value = 4.0
                    links.new(add.outputs[0], mul4.inputs[0])
                    one_m = nodes.new("ShaderNodeMath"); one_m.operation = "SUBTRACT"
                    one_m.inputs[0].default_value = 1.0
                    links.new(mul4.outputs[0], one_m.inputs[1])
                    clamp = nodes.new("ShaderNodeClamp")
                    clamp.inputs["Min"].default_value = 0.0
                    clamp.inputs["Max"].default_value = 1.0
                    links.new(one_m.outputs[0], clamp.inputs["Value"])
                    am = nodes.new("ShaderNodeMath"); am.operation = "MULTIPLY"
                    links.new(a_out, am.inputs[0]); links.new(clamp.outputs[0], am.inputs[1])
                    a_out = am.outputs[0]
                if alpha_mul < 0.999:
                    mathn = nodes.new("ShaderNodeMath")
                    mathn.operation = "MULTIPLY"
                    mathn.inputs[1].default_value = float(alpha_mul)
                    links.new(a_out, mathn.inputs[0])
                    a_out = mathn.outputs[0]
                links.new(a_out, bsdf.inputs["Alpha"])
            _finish_mat(m, alpha_clip if not soft_edge else False)
            try:
                bsdf.inputs["Emission Color"].default_value = (*col, 1)
                bsdf.inputs["Emission Strength"].default_value = emit
                # also feed warmed color into emission when punchy
                if warm > 0 and emit > 0:
                    links.new(color_out, bsdf.inputs["Emission Color"])
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
        mats[full_key] = m
        return m

    def mat_seq(key: str, seq_dir: Path, *, emit=0.0, alpha_clip=False, tint=None,
                warm=0.0, frame_start=1, frame_offset=0, fps_div=1, alpha_mul=1.0, soft_edge=False):
        """序列帧：用 offset 选静态图（Image SEQUENCE 在 EEVEE 易粉红丢贴图）。"""
        full_key = f"{key}_o{frame_offset}_s{frame_start}"
        frames = sorted(seq_dir.glob("*.png"))
        if not frames:
            return mat_tex(key, None, emit=emit, alpha_clip=alpha_clip, tint=tint, warm=warm,
                           alpha_mul=alpha_mul, soft_edge=soft_edge)
        fp = frames[int(frame_offset) % len(frames)]
        return mat_tex(full_key, fp, emit=emit, alpha_clip=alpha_clip, tint=tint, warm=warm,
                       alpha_mul=alpha_mul, soft_edge=soft_edge)

    def seq_dir_for(kind: str) -> Path | None:
        if not root_tex:
            return None
        mapping = {
            "explosion": "explosion",
            "explosion_emitter": "explosion",
            "smoke": "big_smoke",
            "large_smoke": "big_smoke",
            "white_smoke": "generic",
            "poof": "generic",
            "cloud": "generic",
            "flame": "flame",
            "small_flame": "flame",
            "crit": "spark",
            "enchanted_hit": "spark",
        }
        name = mapping.get(kind)
        if not name:
            return None
        d = root_tex / "seq" / name
        return d if d.is_dir() and any(d.glob("*.png")) else None

    def flip_book(name_prefix, seq_dir, mat_factory, parent, loc, size, fr_on, life_frames, *, n_show=None):
        """Fallback flip: one billboard per sprite frame, visibility-stepped."""
        frames = sorted(seq_dir.glob("*.png"))
        if not frames:
            return []
        if n_show:
            frames = frames[:n_show]
        out_objs = []
        step = max(1, life_frames // max(1, len(frames)))
        for i, fp in enumerate(frames):
            mat = mat_factory(fp, i)
            ob = billboard(f"{name_prefix}_{i}", size, mat, parent, loc)
            f_a = fr_on + i * step
            f_b = fr_on + (i + 1) * step
            f0 = frame_times[0][0]
            bake_visibility(ob, [(f0, False), (f_a, True), (f_b, False)])
            out_objs.append(ob)
        return out_objs

    def mat_fire(key: str, *, color=(1.0, 0.35, 0.02), strength=20.0, soft=0.08):
        """Emissive fire core; soft~0 → solid glow, soft~0.3 → airier bloom source."""
        full = f"{key}_s{strength:.0f}_{soft:.2f}"
        if full in mats:
            return mats[full]
        m = bpy.data.materials.new(f"PFire_{full}")
        m.use_nodes = True
        nt = m.node_tree
        nodes, links = nt.nodes, nt.links
        nodes.clear()
        out = nodes.new("ShaderNodeOutputMaterial")
        emit = nodes.new("ShaderNodeEmission")
        emit.inputs["Color"].default_value = (*color, 1)
        emit.inputs["Strength"].default_value = strength
        trans = nodes.new("ShaderNodeBsdfTransparent")
        mix = nodes.new("ShaderNodeMixShader")
        mix.inputs["Fac"].default_value = soft
        links.new(trans.outputs["BSDF"], mix.inputs[1])
        links.new(emit.outputs["Emission"], mix.inputs[2])
        links.new(mix.outputs["Shader"], out.inputs["Surface"])
        try:
            m.blend_method = "BLEND"
            if hasattr(m, "shadow_method"):
                m.shadow_method = "NONE"
        except Exception:
            pass
        mats[full] = m
        return m

    def enable_bloom(scene=None, *, threshold=0.95, size=6, mix=0.22):
        """EEVEE Next has no use_bloom — add compositor Glare (Fog Glow) for flame bloom."""
        import bpy
        scene = scene or bpy.context.scene
        scene.use_nodes = True
        nt = scene.node_tree
        nodes, links = nt.nodes, nt.links
        # idempotent: reuse if present
        glare = nodes.get("ReplayParticleGlare")
        if glare is None:
            glare = nodes.new("CompositorNodeGlare")
            glare.name = "ReplayParticleGlare"
            glare.glare_type = "FOG_GLOW"
            try:
                glare.quality = "HIGH"
            except Exception:
                pass
            # wire: find Render Layers → Composite (or insert)
            rl = next((n for n in nodes if n.type == "R_LAYERS"), None)
            comp = next((n for n in nodes if n.type == "COMPOSITE"), None)
            if rl is None:
                rl = nodes.new("CompositorNodeRLayers")
            if comp is None:
                comp = nodes.new("CompositorNodeComposite")
            # disconnect rl→comp if direct
            for l in list(links):
                if l.to_node == comp and l.from_node == rl:
                    links.remove(l)
            links.new(rl.outputs["Image"], glare.inputs["Image"])
            links.new(glare.outputs["Image"], comp.inputs["Image"])
        glare.threshold = threshold
        glare.size = size
        try:
            glare.mix = mix
        except Exception:
            pass
        return glare

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
        try:
            ob.visible_shadow = False  # 粒子不投阴影
        except Exception:
            pass
        if track and cam is not None:
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
        try:
            ob.visible_shadow = False
        except Exception:
            pass
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

    def drift(ob, loc0, vel, t0, life, *, g=8.0):
        """简单弹道：起点→终点（重力下坠）。"""
        fr_a = _frame_at(frame_times, t0)
        fr_b = _frame_at(frame_times, t0 + life)
        if fr_b <= fr_a:
            fr_b = fr_a + 1
        dt = max(0.05, life / 1000.0)
        vx, vy, vz = vel
        end = (
            loc0[0] + vx * dt,
            max(0.02, loc0[1] + vy * dt - 0.5 * g * dt * dt),
            loc0[2] + vz * dt,
        )
        ob.location = loc0
        ob.keyframe_insert(data_path="location", frame=fr_a)
        ob.location = end
        ob.keyframe_insert(data_path="location", frame=fr_b)
        if ob.animation_data and ob.animation_data.action:
            for fc in ob.animation_data.action.fcurves:
                if fc.data_path == "location":
                    for kp in fc.keyframe_points:
                        kp.interpolation = "LINEAR"

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
            # body textured from block_state when known, else dirt
            body_path = pick_debris(burst.block_state) if burst.block_state is not None else None
            if body_path is None and root_tex:
                body_path = root_tex / "block" / "dirt.png"
            # prefer full block tex over tiny debris crop for the dig body
            if root_tex:
                bare = _block_tex_name(burst.block_state, states)
                full = root_tex / "block" / f"{bare}.png"
                if full.is_file():
                    body_path = full
                elif (root_tex / "block" / "dirt.png").is_file():
                    body_path = root_tex / "block" / "dirt.png"
            body = mat_tex(f"dig_body_{body_path.stem if body_path else 'x'}", body_path, alpha_clip=False)
            ob_body = crack_cube(f"PB{bi}_body", body, group, (0, 0, 0), scale=1.0)
            objs.append(ob_body)
            path = (root_tex / "block" / f"destroy_stage_{stage}.png") if root_tex else None
            # destroy_stage 已有裂纹 alpha，勿再压暗
            mat = mat_tex(f"crack_{stage}", path, alpha_clip=False, emit=0.0)
            try:
                mat.blend_method = "BLEND"
            except Exception:
                pass
            ob = crack_cube(f"PB{bi}_crack", mat, group, (0, 0, 0), scale=1.04)
            objs.append(ob)
            # 各破坏阶段都喷碎屑；晚期更多
            if root_tex and left > 2:
                n_chip = min(5 + stage * 2, 14, left)
                for ci in range(n_chip):
                    dpath = pick_debris(burst.block_state)
                    dmat = mat_tex(f"digchip_{dpath.name if dpath else ci}", dpath, alpha_clip=True)
                    size = 0.09 + rng.random() * (0.12 + stage * 0.01)
                    dx = (rng.random() - 0.5) * 1.1
                    dy = 0.45 + rng.random() * 0.55
                    dz = (rng.random() - 0.5) * 1.1
                    cob = billboard(f"PB{bi}_chip{ci}", size, dmat, group, (dx, dy, dz))
                    drift(cob, (dx, dy, dz),
                          ((rng.random()-0.5)*0.5, 0.3+rng.random()*0.5, (rng.random()-0.5)*0.5),
                          burst.t, life, g=5.0)
                    objs.append(cob)
                    left -= 1
                    created += 1
            left -= 2
            created += 2
            vis(objs, burst.t, life)
            continue

        if kind in ("destroy_block", "block", "falling_dust"):
            # sprint dust stays low; destroy_block sprays higher
            is_destroy = kind == "destroy_block"
            n = min(burst.count + (12 if is_destroy else 0), 36, left)
            for i in range(n):
                path = pick_debris(burst.block_state)
                mat = mat_tex(f"deb_{path.name if path else 'x'}_{i%4}", path, alpha_clip=True)
                size = (0.12 + rng.random() * 0.1) if not is_destroy else (0.14 + rng.random() * 0.16)
                dx = (rng.random() - 0.5) * 2 * (burst.ox or 0.35)
                if is_destroy:
                    dy = abs(rng.random()) * (burst.oy or 0.45) + 0.08
                else:
                    dy = rng.random() * 0.12 + 0.02  # sprint: hug ground
                dz = (rng.random() - 0.5) * 2 * (burst.oz or 0.35)
                ob = billboard(f"PB{bi}_{i}", size, mat, group, (dx, dy, dz))
                if is_destroy:
                    drift(ob, (dx, dy, dz),
                          ((rng.random()-0.5)*2.0, 1.0+rng.random()*1.5, (rng.random()-0.5)*2.0),
                          burst.t, life)
                else:
                    drift(ob, (dx, dy, dz),
                          ((rng.random()-0.5)*0.8, 0.15+rng.random()*0.35, (rng.random()-0.5)*0.8),
                          burst.t, life, g=6.0)
                objs.append(ob)
                left -= 1
                created += 1
            # soft dust puff for sprint/block
            if not is_destroy and root_tex and left > 0:
                sd = seq_dir_for("poof") or seq_dir_for("smoke")
                fr_on = _frame_at(frame_times, burst.t)
                for i in range(min(6, left)):
                    if sd:
                        mat = mat_seq(f"sprintdust", sd, emit=0.0, alpha_clip=False,
                                      frame_start=fr_on, frame_offset=rng.randint(0, 5),
                                      alpha_mul=0.28, soft_edge=True)
                    else:
                        mat = mat_tex(f"sprintdust_{i}", pick_sprite("poof"), alpha_clip=False,
                                      alpha_mul=0.28, soft_edge=True)
                    ob = billboard(f"PB{bi}_puff{i}", 0.26 + rng.random() * 0.14, mat, group,
                                   ((rng.random()-0.5)*0.45, 0.04 + rng.random()*0.1, (rng.random()-0.5)*0.45))
                    objs.append(ob)
                    left -= 1
                    created += 1
            vis(objs, burst.t, life)
            continue

        if kind in ("smoke", "large_smoke", "white_smoke", "poof", "cloud"):
            fr_on = _frame_at(frame_times, burst.t)
            sd = seq_dir_for(kind)
            # 短烟/冲刺尘：更小更贴地
            trail = kind in ("smoke", "white_smoke", "poof") and (
                burst.count <= 3 or (burst.ox or 0) <= 0.15 or (burst.oy or 0) <= 0.1
            )
            n = min(max(burst.count, 6 if trail else 3), 18 if trail else 16, left)
            for i in range(n):
                if kind == "large_smoke":
                    size = 0.45 * (0.7 + rng.random() * 0.6)
                elif trail:
                    size = 0.16 + rng.random() * 0.1
                else:
                    size = 0.28 * (0.7 + rng.random() * 0.6)
                dx = (rng.random() - 0.5) * 2 * (burst.ox or 0.2)
                if trail:
                    dy = rng.random() * 0.1 + 0.02
                else:
                    dy = rng.random() * (burst.oy or 0.15) + 0.02
                dz = (rng.random() - 0.5) * 2 * (burst.oz or 0.2)
                emit = 0.0 if trail else 0.02
                amul = 0.28 if trail else (0.35 if kind == "large_smoke" else 0.30)
                se = bool(trail or kind in ("poof", "cloud", "white_smoke"))
                if sd is not None:
                    mat = mat_seq(
                        f"sm_{kind}", sd, emit=emit, alpha_clip=False,
                        frame_start=fr_on, frame_offset=rng.randint(0, 7),
                        alpha_mul=amul, soft_edge=se,
                    )
                else:
                    path = pick_sprite(kind)
                    mat = mat_tex(f"sm_{kind}_{path.stem if path else i}", path, alpha_clip=False,
                                  emit=emit, alpha_mul=amul, soft_edge=se)
                ob = billboard(f"PB{bi}_{i}", size, mat, group, (dx, dy, dz))
                if not trail:
                    drift(ob, (dx, dy, dz),
                          ((rng.random()-0.5)*0.3, 0.4+rng.random()*0.6, (rng.random()-0.5)*0.3),
                          burst.t, life, g=1.5)
                objs.append(ob)
                left -= 1
                created += 1
            vis(objs, burst.t, life)
            continue

        if kind in ("explosion", "explosion_emitter", "flame", "small_flame"):
            is_flame = kind in ("flame", "small_flame")
            n = min(max(burst.count, 6), 18 if not is_flame else 10, left)
            radius = burst.radius or (burst.ox or 1.0)
            fr_on = _frame_at(frame_times, burst.t)
            sd = seq_dir_for("flame" if is_flame else kind)
            for i in range(n):
                size = (0.75 if kind == "explosion_emitter" else 0.45) * (0.55 + rng.random() * 0.7)
                if is_flame:
                    size *= 0.5 if kind == "small_flame" else 0.55
                theta = rng.random() * math.tau
                phi = rng.random() * math.pi
                r = radius * (0.12 + rng.random() * 0.9)
                dx = r * math.sin(phi) * math.cos(theta)
                dy = r * math.cos(phi) * 0.7 + abs(rng.random()) * 0.5
                dz = r * math.sin(phi) * math.sin(theta)
                emit = 1.8 if kind.startswith("explo") else 2.8
                warm = 0.75 if kind.startswith("explo") else 0.45
                clip = False
                se = kind.startswith("explo")
                if sd is not None:
                    mat = mat_seq(
                        f"exseq_{kind}",
                        sd,
                        emit=emit,
                        alpha_clip=clip,
                        tint=(1.0, 0.55, 0.12),
                        warm=warm * 0.6 if se else warm,
                        frame_start=fr_on,
                        frame_offset=rng.randint(0, 12),
                        alpha_mul=0.55 if se else 1.0,
                        soft_edge=se,
                    )
                else:
                    path = pick_sprite("flame" if is_flame else kind)
                    mat = mat_tex(
                        f"ex_{kind}_{path.stem if path else i}_{i}",
                        path,
                        emit=emit,
                        alpha_clip=clip,
                        tint=(1.0, 0.5, 0.08),
                        warm=warm * 0.6 if se else warm,
                        alpha_mul=0.55 if se else 1.0,
                        soft_edge=se,
                    )
                ob = billboard(f"PB{bi}_{i}", size, mat, group, (dx, dy, dz))
                if is_flame:
                    ob.scale = (0.7, 0.7, 1.5)  # 火焰竖长
                objs.append(ob)
                left -= 1
                created += 1
            # Punch: dedicated flame + spark cores
            if kind.startswith("explo") and root_tex and left > 0:
                flame_sd = seq_dir_for("flame")
                spark_dir = root_tex / "seq" / "spark"
                for i in range(min(4, left)):
                    theta = rng.random() * math.tau
                    r = radius * (0.08 + rng.random() * 0.4)
                    dx, dy, dz = math.cos(theta) * r, 0.55 + rng.random() * 0.75, math.sin(theta) * r
                    if flame_sd and i % 2 == 0:
                        mat = mat_seq(
                            "flame_punch", flame_sd, emit=2.2, alpha_clip=False,
                            tint=(1.0, 0.4, 0.05), warm=0.45,
                            frame_start=fr_on, frame_offset=rng.randint(0, 3),
                            alpha_mul=0.75,
                        )
                        size = 0.16 + rng.random() * 0.14
                    elif spark_dir.is_dir():
                        sp = sorted(spark_dir.glob("*.png"))
                        path = rng.choice(sp) if sp else None
                        mat = mat_tex(
                            f"spark_{path.stem if path else i}", path,
                            emit=2.4, alpha_clip=True, tint=(1.0, 0.85, 0.3), warm=0.2,
                            alpha_mul=0.55,
                        )
                        size = 0.12 + rng.random() * 0.1
                    else:
                        continue
                    ob = billboard(f"PB{bi}_fire{i}", size, mat, group, (dx, dy, dz))
                    objs.append(ob)
                    left -= 1
                    created += 1
                # 无固体白核：只用 glow 贴图 bloom（多片、抬高）
                glow = None
                if root_tex:
                    gp = root_tex / "particle" / "generic_0.png"
                    if gp.is_file():
                        glow = gp
                for bi_g in range(min(2, left)):
                    theta = rng.random() * math.tau
                    r = radius * (0.05 + rng.random() * 0.25)
                    dx, dz = math.cos(theta) * r, math.sin(theta) * r
                    dy = 1.15 + rng.random() * 0.55
                    if glow:
                        mat = mat_tex(
                            f"bloom_glow_{bi_g}", glow, emit=3.0 + bi_g * 0.3, alpha_clip=False,
                            tint=(1.0, 0.5, 0.1), warm=0.4, alpha_mul=0.35, soft_edge=True,
                        )
                    else:
                        # 无贴图时也用高 soft，绝不硬白方
                        mat = mat_fire(f"bloom_soft_{bi_g}", color=(1.0, 0.45, 0.08),
                                       strength=5.0, soft=0.88)
                    size = 1.0 + rng.random() * 0.5
                    ob = billboard(f"PB{bi}_bloom{bi_g}", size, mat, group, (dx, dy, dz))
                    objs.append(ob)
                    left -= 1
                    created += 1
                # Explicit flip-book billboards (visibility-stepped) so stills differ across frames
                explo_dir = root_tex / "seq" / "explosion"
                if explo_dir.is_dir() and left > 4:
                    life_fr = max(8, _frame_at(frame_times, burst.t + life) - fr_on)
                    for k in range(0):  # polish9: skip flip crescents
                        theta = rng.random() * math.tau
                        r = radius * (0.2 + rng.random() * 0.4)
                        loc = (math.cos(theta) * r, 0.4 + rng.random() * 0.5, math.sin(theta) * r)
                        size = 0.9 + rng.random() * 0.4

                        def _mf(fp, i, _k=k):
                            return mat_tex(
                                f"flipbook_{_k}_{fp.stem}", fp,
                                emit=2.5, alpha_clip=False, tint=(1.0, 0.45, 0.05), warm=0.4, alpha_mul=0.65,
                            )
                        fobjs = flip_book(f"PB{bi}_flip{k}", explo_dir, _mf, group, loc, size, fr_on, life_fr)
                        # flip 已 bake 可见性，勿再进 vis()
                        left -= len(fobjs)
                        created += len(fobjs)
                # smoke ring (animated)
                smoke_sd = seq_dir_for("large_smoke")
                for i in range(min(10, left)):
                    ang = i / 10 * math.tau
                    dx = math.cos(ang) * radius * 0.55
                    dz = math.sin(ang) * radius * 0.55
                    if smoke_sd:
                        mat = mat_seq(
                            "exsmoke", smoke_sd, emit=0.02, alpha_clip=False,
                            frame_start=fr_on, frame_offset=rng.randint(0, 8),
                            alpha_mul=0.45,
                        )
                    else:
                        path = pick_sprite("large_smoke")
                        mat = mat_tex(f"exsmoke_{path.stem if path else i}", path, alpha_clip=False, alpha_mul=0.45)
                    size = 0.75 * (0.7 + rng.random())
                    ob = billboard(f"PB{bi}_sm{i}", size, mat, group, (dx, 0.35 + rng.random() * 0.5, dz))
                    objs.append(ob)
                    left -= 1
                    created += 1
            vis(objs, burst.t, life + 150)
            continue

        if kind in ("crit", "enchanted_hit"):
            n = min(max(burst.count, 3), 10, left)
            sd = seq_dir_for(kind)
            fr_on = _frame_at(frame_times, burst.t)
            for i in range(n):
                if sd:
                    mat = mat_seq(f"crit_{kind}", sd, emit=2.2, alpha_clip=True,
                                  tint=(1.0, 0.95, 0.6), warm=0.1,
                                  frame_start=fr_on, frame_offset=rng.randint(0, 7))
                else:
                    path = pick_sprite(kind) or pick_sprite("crit")
                    mat = mat_tex(f"crit_{kind}_{i}", path, emit=2.2, alpha_clip=True, tint=(1.0, 0.95, 0.6), alpha_mul=0.75)
                size = 0.12 + rng.random() * 0.1
                dx = (rng.random() - 0.5) * 2 * (burst.ox or 0.35)
                dy = abs(rng.random()) * (burst.oy or 0.35) + 0.25
                dz = (rng.random() - 0.5) * 2 * (burst.oz or 0.35)
                ob = billboard(f"PB{bi}_crit{i}", size, mat, group, (dx, dy, dz))
                drift(ob, (dx, dy, dz),
                      ((rng.random()-0.5)*2.5, 1.2+rng.random()*1.5, (rng.random()-0.5)*2.5),
                      burst.t, life, g=10.0)
                objs.append(ob)
                left -= 1
                created += 1
            vis(objs, burst.t, life)
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

    if any(b.kind.startswith("explo") or b.kind in ("flame", "small_flame", "crit") for b in bursts):
        try:
            enable_bloom(bpy.context.scene)
            log("particles: compositor Fog Glow bloom enabled")
        except Exception as e:
            log(f"particles: bloom skip ({e})")
    log(f"particles: {created} sprites from {len(bursts)} bursts (tex={bool(root_tex)})")
    return [root]


def _particle_asset_wanted(name: str, want_pref) -> bool:
    if not name.endswith(".png"):
        return False
    if any(name.startswith(p) or name == p.rstrip("_") for p in want_pref):
        return True
    if "/textures/particle/" in name:
        return True
    if "destroy_stage_" in name or name.endswith("/dirt.png") or name.endswith("/stone.png"):
        return True
    return False


def _write_particle_asset(dest: Path, name: str, data: bytes, *, overwrite: bool = False):
    bn = name.rsplit("/", 1)[-1]
    sub = "block" if ("destroy_stage" in bn or bn in ("dirt.png", "stone.png")) else "particle"
    out = dest / sub / bn
    if overwrite or not out.exists():
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_bytes(data)


def _ensure_from_jar(jar: str, dest: Path, resourcepacks: list[str] | None = None):
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
    if jar and os.path.isfile(jar):
        with zipfile.ZipFile(jar) as zf:
            for name in zf.namelist():
                if not _particle_asset_wanted(name, want_pref):
                    continue
                _write_particle_asset(dest, name, zf.read(name), overwrite=False)
    # Packs override jar: low priority first so highest-priority pack wins.
    packs = [p for p in (resourcepacks or []) if p]
    for pack in reversed(packs):
        if os.path.isdir(pack):
            base = Path(pack)
            for pref in (
                "assets/minecraft/textures/particle",
                "assets/minecraft/textures/block",
            ):
                folder = base.joinpath(*pref.split("/"))
                if not folder.is_dir():
                    continue
                for fp in folder.glob("*.png"):
                    name = f"{pref}/{fp.name}"
                    if not _particle_asset_wanted(name, want_pref):
                        continue
                    _write_particle_asset(dest, name, fp.read_bytes(), overwrite=True)
        elif os.path.isfile(pack) and zipfile.is_zipfile(pack):
            with zipfile.ZipFile(pack) as zf:
                for name in zf.namelist():
                    if not _particle_asset_wanted(name, want_pref):
                        continue
                    _write_particle_asset(dest, name, zf.read(name), overwrite=True)


def _frame_at(frame_times, t):
    for f, tt in frame_times:
        if tt >= t:
            return f
    return frame_times[-1][0] + 1
