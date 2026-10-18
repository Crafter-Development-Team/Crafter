"""Orchestrate .mcpr → temp Anvil world → existing WI import → camera animation."""
from __future__ import annotations

import os
import shutil
import tempfile
from pathlib import Path

from .mcpr import open_mcpr, read_meta, read_timelines_raw, iter_packets
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
        for pkt in iter_packets(zf):
            info["packets"] += 1
            store.handle_packet(pkt.packet_id, pkt.payload, pkt.timestamp_ms)

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
