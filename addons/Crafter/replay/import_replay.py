"""Orchestrate .mcpr → temp Anvil world → existing WI import → camera animation."""
from __future__ import annotations

import os
import shutil
import tempfile
from pathlib import Path

from .mcpr import open_mcpr, read_meta, read_timelines_raw, iter_play_packets
from .particles import ParticleStore
from .timelines import parse_timelines
from .anvil import write_level_dat


def is_mcpr_path(path: str) -> bool:
    p = os.path.normpath(path)
    return p.lower().endswith(".mcpr") and os.path.isfile(p)


def materialize_world_from_mcpr(mcpr_path: str, dest_dir: str | None = None) -> tuple[str, dict]:
    """Parse .mcpr and write a temp Anvil world. Returns (world_dir, info).

    Parses map_chunk (0x27) via ChunkStore into Anvil; falls back to sibling
    world_from_regions / CRAFTER_REPLAY_FALLBACK_WORLD only when no columns decoded.
    Always loads timelines.json when present for camera.
    """
    mcpr_path = os.path.normpath(mcpr_path)
    info = {"mcpr": mcpr_path, "chunks": 0, "packets": 0, "timelines": []}
    from .chunks import ChunkStore

    store = ChunkStore()
    with open_mcpr(mcpr_path) as zf:
        meta = read_meta(zf)
        info["meta"] = {
            "mcversion": meta.mcversion,
            "protocol": meta.protocol,
            "duration_ms": meta.duration_ms,
        }
        raw_tl = read_timelines_raw(zf)
        if raw_tl:
            info["timelines"] = parse_timelines(raw_tl)
        from .entities import EntityStore
        ents = EntityStore()
        parts = ParticleStore()
        for pkt in iter_play_packets(zf):
            info["packets"] += 1
            store.handle_packet(pkt.packet_id, pkt.payload, pkt.timestamp_ms)
            ents.handle(pkt.packet_id, pkt.payload, pkt.timestamp_ms)
            parts.handle(pkt.packet_id, pkt.payload, pkt.timestamp_ms)
        info["particles"] = parts
        info["entities"] = ents
        info["block_segments"] = store.block_segments()
        info["chunk_store"] = store

    if dest_dir is None:
        dest_dir = tempfile.mkdtemp(prefix="crafter_replay_")
    else:
        os.makedirs(dest_dir, exist_ok=True)

    data_version = 3955
    info["world"] = dest_dir
    ver = (info.get("meta") or {}).get("mcversion") or ""
    for cand in (
        Path(f"/workspace/crafter-pipeline/mc/versions/{ver}/{ver}.jar"),
        Path(f"/workspace/crafter-pipeline/mc/versions/{ver}/client.jar"),
    ):
        if cand.is_file():
            info["suggest_jar"] = str(cand)
            break

    if store.map_chunks:
        n = store.write_anvil_world(Path(dest_dir))
        info["chunks"] = n
        info["map_chunks"] = store.map_chunks
    else:
        write_level_dat(Path(dest_dir), data_version=data_version, name="replay_import")
        fallback = os.environ.get("CRAFTER_REPLAY_FALLBACK_WORLD")
        if not fallback:
            cand = Path(mcpr_path).parent / "world_from_regions"
            if cand.is_dir() and (cand / "level.dat").exists():
                fallback = str(cand)
        if fallback and os.path.isdir(fallback):
            src_region = Path(fallback) / "region"
            if src_region.is_dir():
                dst_region = Path(dest_dir) / "region"
                if dst_region.exists():
                    shutil.rmtree(dst_region)
                shutil.copytree(src_region, dst_region)
                info["chunks"] = len(list(dst_region.glob("*.mca")))
                info["fallback_world"] = fallback
    return dest_dir, info


def apply_replay_camera(info: dict, *, fps: int = 30, fov_degrees: float = 70.0):
    """After world import, apply first timeline's camera path in the current Blender scene."""
    tls = info.get("timelines") or []
    if not tls:
        return None
    from .camera_blender import apply_camera_timeline
    return apply_camera_timeline(tls[0], fps=fps, fov_degrees=fov_degrees)


def write_time_varying_worlds(info: dict, base_dir: str, box=None, min_t: int = 0, quantum_ms: int = 500,
                              quantize=None):
    """Split the reconstructed world into a static base + small per-interval block worlds.

    Changed positions (block_change / multi_block_change after load) are cleared to air in
    the base world; each (t_from, t_to) interval gets its own mini Anvil world containing just
    the blocks that are present during that interval, so WorldImporter meshes them with real
    models/textures and Blender toggles their visibility over time.
    box = (x1, y1, z1, x2, y2, z2) inclusive, optional filter.
    quantize(t) -> key time (or None = never / until the end); default rounds to quantum_ms.
    Returns (base_world_dir, [ {world, t_from, t_to, box} ... ]).
    """
    from .chunks import ChunkStore, NUM_SECTIONS, BLOCK_VOLUME, BIOME_VOLUME

    store = info["chunk_store"]
    segs = info.get("block_segments") or {}
    if box:
        x1, y1, z1, x2, y2, z2 = box
        segs = {p: v for p, v in segs.items()
                if min(x1, x2) <= p[0] <= max(x1, x2) and min(y1, y2) <= p[1] <= max(y1, y2)
                and min(z1, z2) <= p[2] <= max(z1, z2)}
    segs = {p: [s for s in v] for p, v in segs.items() if any(t >= min_t for _s, t, _e in v[1:])}
    base = os.path.join(base_dir, "base")
    # base = initial snapshot with all time-varying positions cleared
    final_backup = {p: store.get_block(*p) for p in segs}
    for p in segs:
        store.set_block(*p, 0)
    store.write_anvil_world(Path(base))
    for p, st in final_backup.items():
        store.set_block(*p, st)
    groups: dict = {}
    q = quantize or (lambda t: None if t is None else (0 if t == 0 else max(1, int(round(t / quantum_ms)) * quantum_ms)))
    for p, v in segs.items():
        for state, t0, t1 in v:
            if state == 0:
                continue
            k0, k1 = q(t0), q(t1)
            if k0 is None or (k1 is not None and k1 <= k0):
                continue  # never visible in the rendered range
            groups.setdefault((k0, k1), []).append((p, state))
    out = []
    for gi, ((t0, t1), blocks) in enumerate(sorted(groups.items(), key=lambda kv: kv[0][0])):
        mini = ChunkStore()
        for (x, y, z), st in blocks:
            key = (x >> 4, z >> 4)
            if key not in mini.columns:
                mini.columns[key] = [([0] * BLOCK_VOLUME, list(store.columns[key][i][1]))
                                     for i in range(NUM_SECTIONS)]
            mini.set_block(x, y, z, st)
        d = os.path.join(base_dir, f"blocks_{gi:02d}")
        mini.write_anvil_world(Path(d))
        xs = [b[0][0] for b in blocks]; ys = [b[0][1] for b in blocks]; zs = [b[0][2] for b in blocks]
        out.append({"world": d, "t_from": t0, "t_to": t1, "count": len(blocks),
                    "box": (min(xs), min(ys), min(zs), max(xs), max(ys), max(zs))})
    return base, out


def frame_quantizer(info: dict, fps: int = 30, max_groups: int = 96):
    """Snap block-change times to the video frame where they first become visible
    (first frame whose replay time >= t, like ReplayMod's renderer), so an edit never shows
    early/late by up to half a quantum. Returns None (use the coarse default) when that would
    create more than max_groups block worlds."""
    from bisect import bisect_left
    from .scene_builder import frame_times
    ft = frame_times(info, fps)
    times = [t for _f, t in ft]
    if any(b < a for a, b in zip(times, times[1:])):
        return None  # time path goes backwards: frame snapping is ambiguous

    def q(t):
        if t is None:
            return None
        if t <= times[0]:
            return 0
        i = bisect_left(times, t)
        return times[i] if i < len(times) else None

    keys = {(q(t0), q(t1)) for v in (info.get("block_segments") or {}).values() for s, t0, t1 in v if s}
    return q if len(keys) <= max_groups else None


def prepare_replay_world(mcpr_path: str, box, work_root: str | None = None, focus=None, focus_radius: float = 0,
                         fps: int = 30):
    """Full replay preparation used by Crafter: parse, split static/time-varying, return info.
    info['world'] = base Anvil world; info['block_groups'] = interval mini worlds."""
    if work_root is None:
        work_root = tempfile.mkdtemp(prefix="crafter_replay_")
    world, info = materialize_world_from_mcpr(mcpr_path, os.path.join(work_root, "final"))
    segs = info.get("block_segments") or {}
    ents = info.get("entities")
    if focus is None and ents is not None:
        # default: only animate edits near players (skips fluid ticks / crop growth noise)
        pts = [(k.x, k.y, k.z) for tr in ents.visible_tracks() if tr.type_name == "player"
               for k in tr.keys[::4]]
        if pts:
            r2 = 6.0 ** 2
            info["block_segments"] = {p: v for p, v in segs.items()
                                      if any((p[0] - a) ** 2 + (p[1] - b) ** 2 + (p[2] - c) ** 2 <= r2
                                             for a, b, c in pts)}
    if focus is not None and focus_radius > 0:
        fx, fy, fz = focus
        info["block_segments"] = {p: v for p, v in segs.items()
                                  if (p[0] - fx) ** 2 + (p[1] - fy) ** 2 + (p[2] - fz) ** 2 <= focus_radius ** 2}
    base, groups = write_time_varying_worlds(info, work_root, box=box, quantize=frame_quantizer(info, fps))
    info["final_world"] = world
    info["world"] = base
    info["block_groups"] = groups
    info["box"] = box
    info["work_root"] = work_root
    return base, info
