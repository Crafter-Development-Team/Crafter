"""After Crafter imported the replay's base world: camera path, animated entities, and
time-varying blocks (each interval imported through the normal Crafter/WI path)."""
from __future__ import annotations

import os

_state: dict = {}


def frame_times(info: dict, fps: int):
    from .timelines import map_replay_time
    tls = info.get("timelines") or []
    meta = info.get("meta") or {}
    if tls and tls[0].camera_keys:
        tl = tls[0]
        start, end = tl.camera_keys[0].time_ms, tl.camera_keys[-1].time_ms
        n = int(round((end - start) / 1000.0 * fps)) + 1
        return [(1 + i, map_replay_time(tl, start + i * 1000.0 / fps)) for i in range(n)]
    dur = int(meta.get("duration_ms") or 10000)
    n = int(dur / 1000.0 * fps) + 1
    return [(1 + i, int(i * 1000.0 / fps)) for i in range(n)]


def _frame_at(ft, t):
    for f, tt in ft:
        if tt >= t:
            return f
    return ft[-1][0] + 1


def on_world_imported(info: dict, prefs, *, fps: int = 30, log=print):
    import bpy
    from .import_replay import apply_replay_camera
    from .entity_blender import TextureCache, build_entities
    scene = bpy.context.scene
    ft = frame_times(info, fps)
    scene.render.fps = fps
    scene.frame_start, scene.frame_end = ft[0][0], ft[-1][0]
    if info.get("timelines"):
        cam = apply_replay_camera(info, fps=fps)
        log(f"replay camera: {cam.name if cam else None}")
    jar = info.get("suggest_jar") or getattr(prefs, "Custom_Jar_Path", "")
    ents = info.get("entities")
    if ents and jar and os.path.isfile(jar):
        tex = TextureCache(jar, os.path.join(info.get("work_root") or info["world"], "_textures"))
        roots = build_entities(ents, ft, tex, box=info.get("box"), log=log)
        log(f"replay entities: {len(roots)}")
    groups = info.get("block_groups") or []
    if groups:
        _state.update(queue=list(groups), ft=ft, prefs=prefs, log=log, busy=False,
                      saved=(tuple(prefs.XYZ_1), tuple(prefs.XYZ_2)), before=None, done=0)
        bpy.app.timers.register(_tick_groups, first_interval=0.5)


def groups_pending() -> bool:
    return bool(_state.get("queue")) or bool(_state.get("busy"))


def _tick_groups():
    import bpy
    from importlib import import_module
    st = _state
    iw = import_module(__package__.rsplit(".", 1)[0] + ".operators.ImportWorld")
    if st.get("busy"):
        if getattr(iw, "import_running", False):
            return 0.5
        g = st["current"]
        new = [o for o in bpy.data.objects if o.name not in st["before"]]
        coll = bpy.data.collections.new(f"ReplayBlocks_{st['done']:02d}")
        bpy.context.scene.collection.children.link(coll)
        on = _frame_at(st["ft"], g["t_from"]) if g["t_from"] > 0 else st["ft"][0][0]
        off = _frame_at(st["ft"], g["t_to"]) if g["t_to"] is not None else None
        from .entity_blender import bake_visibility
        for o in new:
            for c in list(o.users_collection):
                c.objects.unlink(o)
            coll.objects.link(o)
            seq = [(st["ft"][0][0], on <= st["ft"][0][0])]
            if on > st["ft"][0][0]:
                seq.append((on, True))
            if off is not None:
                seq.append((off, False))
            bake_visibility(o, seq)
        st["log"](f"replay block group {st['done']}: {len(new)} objs t={g['t_from']}..{g['t_to']}")
        st["busy"] = False
        st["done"] += 1
    if not st["queue"]:
        p = st["prefs"]
        p.XYZ_1, p.XYZ_2 = st["saved"]
        st["log"]("replay block groups done")
        return None
    g = st["queue"].pop(0)
    p = st["prefs"]
    x1, y1, z1, x2, y2, z2 = g["box"]
    p.XYZ_1, p.XYZ_2 = (x1, y1, z1), (x2, y2, z2)
    st["before"] = {o.name for o in bpy.data.objects}
    st["current"] = g
    st["busy"] = True
    win = bpy.context.window_manager.windows[0]
    area = next((a for a in win.screen.areas if a.type == "VIEW_3D"), win.screen.areas[0])
    with bpy.context.temp_override(window=win, screen=win.screen, area=area):
        bpy.ops.crafter.import_surface_world("EXEC_DEFAULT", worldPath=g["world"],
                                             jarPath=p.Custom_Jar_Path, modsPath="")
    return 1.0
