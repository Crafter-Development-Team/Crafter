"""Spawn short-lived visual bursts for ParticleStore (EEVEE-friendly instances)."""
from __future__ import annotations

import math
import random
from pathlib import Path

# How long (ms) a burst stays visible
LIFE_MS = {
    "explosion": 600,
    "explosion_emitter": 800,
    "destroy_block": 500,
    "block": 400,
    "falling_dust": 500,
    "break_anim": 150,
    "poof": 400,
    "cloud": 500,
    "smoke": 500,
    "large_smoke": 600,
    "white_smoke": 500,
    "flame": 400,
}
DEFAULT_LIFE = 350

# Colors (RGB) by kind
COLORS = {
    "explosion": (1.0, 0.55, 0.15),
    "explosion_emitter": (1.0, 0.7, 0.2),
    "destroy_block": (0.55, 0.45, 0.35),
    "block": (0.5, 0.4, 0.3),
    "falling_dust": (0.55, 0.45, 0.35),
    "break_anim": (0.6, 0.55, 0.45),
    "poof": (0.85, 0.85, 0.85),
    "cloud": (0.9, 0.9, 0.9),
    "smoke": (0.35, 0.35, 0.35),
    "large_smoke": (0.3, 0.3, 0.3),
    "white_smoke": (0.85, 0.85, 0.85),
    "flame": (1.0, 0.4, 0.05),
    "crit": (1.0, 1.0, 0.6),
}


def build_particles(store, frame_times, *, box=None, mc_space=None, log=print, seed=1):
    """frame_times: list[(frame, replay_ms)]. Creates empties+meshes under MC_Space."""
    import bpy
    rng = random.Random(seed)
    if mc_space is None:
        mc_space = bpy.data.objects.get("MC_Space_Entities") or bpy.data.objects.get("MC_Space")
        if mc_space is None:
            mc_space = bpy.data.objects.new("MC_Space_Particles", None)
            bpy.context.scene.collection.objects.link(mc_space)
            mc_space.rotation_euler = (math.pi / 2, 0, 0)
    bursts = store.in_box(box) if hasattr(store, "in_box") else list(store.bursts)
    if not bursts:
        log("particles: none")
        return []
    root = bpy.data.objects.new("ReplayParticles", None)
    bpy.context.scene.collection.objects.link(root)
    root.parent = mc_space
    mats = {}
    created = 0
    # Cap instance count for performance
    MAX_INST = 800
    inst_left = MAX_INST

    def mat_for(kind, tint=None):
        key = (kind, tint)
        if key in mats:
            return mats[key]
        col = tint or COLORS.get(kind, (0.7, 0.7, 0.7))
        m = bpy.data.materials.new(f"P_{kind}_{len(mats)}")
        m.use_nodes = True
        nt = m.node_tree
        bsdf = nt.nodes.get("Principled BSDF")
        bsdf.inputs["Base Color"].default_value = (*col, 1.0)
        try:
            bsdf.inputs["Emission Color"].default_value = (*col, 1.0)
            bsdf.inputs["Emission Strength"].default_value = 0.4 if kind.startswith("explo") or kind == "flame" else 0.05
        except Exception:
            pass
        bsdf.inputs["Roughness"].default_value = 1.0
        try:
            bsdf.inputs["Alpha"].default_value = 0.85
            m.blend_method = "BLEND"
        except Exception:
            pass
        mats[key] = m
        return m

    def mesh_cube(name, size, mat):
        me = bpy.data.meshes.new(name)
        s = size * 0.5
        verts = [(-s, -s, -s), (s, -s, -s), (s, s, -s), (-s, s, -s),
                 (-s, -s, s), (s, -s, s), (s, s, s), (-s, s, s)]
        faces = [(0, 1, 2, 3), (4, 5, 6, 7), (0, 1, 5, 4), (2, 3, 7, 6), (0, 3, 7, 4), (1, 2, 6, 5)]
        me.from_pydata(verts, [], faces)
        me.materials.append(mat)
        return me

    from .entity_blender import bake_visibility

    for bi, burst in enumerate(bursts):
        if inst_left <= 0:
            break
        # skip break_anim stages that aren't "active crack" clutter — show stage>=5 as chips
        if burst.kind == "break_anim" and (burst.stage is None or burst.stage < 5 or burst.stage >= 10):
            continue
        life = LIFE_MS.get(burst.kind, DEFAULT_LIFE)
        n = min(burst.count, 32 if burst.kind.startswith("explo") else 16, inst_left)
        if n <= 0:
            continue
        tint = None
        if burst.block_state is not None and burst.kind in ("block", "destroy_block", "falling_dust"):
            # crude dirt-ish / stone tint from state id hash
            h = (burst.block_state * 2654435761) & 0xFFFFFF
            tint = (((h >> 16) & 255) / 255 * 0.5 + 0.25,
                    ((h >> 8) & 255) / 255 * 0.4 + 0.2,
                    (h & 255) / 255 * 0.3 + 0.15)
        mat = mat_for(burst.kind, tint)
        size = 0.35 if burst.kind.startswith("explo") else 0.18
        if burst.kind == "break_anim":
            size = 0.14
        group = bpy.data.objects.new(f"PB{bi}_{burst.kind}", None)
        bpy.context.scene.collection.objects.link(group)
        group.parent = root
        # MC Y-up under mc_space Rx(90): place in MC coords on parent
        group.location = (burst.x, burst.y, burst.z)
        fr_on = _frame_at(frame_times, burst.t)
        fr_off = _frame_at(frame_times, burst.t + life)
        objs = []
        for i in range(n):
            me = mesh_cube(f"pb{bi}_{i}", size * (0.6 + rng.random() * 0.8), mat)
            ob = bpy.data.objects.new(f"PB{bi}_{i}", me)
            bpy.context.scene.collection.objects.link(ob)
            ob.parent = group
            dx = (rng.random() - 0.5) * 2 * (burst.ox or 0.3)
            dy = (rng.random() - 0.5) * 2 * (burst.oy or 0.3)
            dz = (rng.random() - 0.5) * 2 * (burst.oz or 0.3)
            if burst.kind.startswith("explo"):
                dy = abs(dy) + rng.random() * 0.4
            ob.location = (dx, dy, dz)
            objs.append(ob)
            inst_left -= 1
        # visibility window
        f0 = frame_times[0][0]
        seq = [(f0, False)]
        if fr_on > f0:
            seq.append((fr_on, True))
        else:
            seq = [(f0, True)]
        seq.append((fr_off, False))
        for ob in [group] + objs:
            bake_visibility(ob, seq)
        created += n
    log(f"particles: {created} instances from {len(bursts)} bursts")
    return [root]


def _frame_at(frame_times, t):
    for f, tt in frame_times:
        if tt >= t:
            return f
    return frame_times[-1][0] + 1
