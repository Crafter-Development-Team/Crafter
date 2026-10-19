"""Build a synthetic .mcpr for pipeline tests (timelines + empty/minimal tmcpr).

World blocks are supplied separately by copying a real region into a temp Anvil
world (see scripts/make_sample_bundle.py) until map_chunk parsing lands.
"""
from __future__ import annotations

import json
import struct
import zipfile
from pathlib import Path


def build_timelines_json(
    *,
    duration_ms: int = 5000,
    origin=(0.5, 70.0, 0.5),
) -> dict:
    ox, oy, oz = origin
    # time path: video 0..duration maps 1:1 to replay time
    time_path = {
        "keyframes": [
            {"time": 0, "properties": {"timestamp": 0}},
            {"time": duration_ms, "properties": {"timestamp": duration_ms}},
        ],
        "segments": [0],
        "interpolators": [{"type": "linear", "properties": ["timestamp"]}],
    }
    # camera: orbit-ish linear path
    pos_path = {
        "keyframes": [
            {
                "time": 0,
                "properties": {
                    "camera:position": [ox + 8, oy + 4, oz + 8],
                    "camera:rotation": [135.0, 20.0, 0.0],
                },
            },
            {
                "time": duration_ms // 2,
                "properties": {
                    "camera:position": [ox - 8, oy + 6, oz + 2],
                    "camera:rotation": [200.0, 25.0, 5.0],
                },
            },
            {
                "time": duration_ms,
                "properties": {
                    "camera:position": [ox + 2, oy + 3, oz - 8],
                    "camera:rotation": [45.0, 15.0, 0.0],
                },
            },
        ],
        "segments": [0, 0],
        "interpolators": [
            {"type": "cubic-spline", "properties": ["camera:position", "camera:rotation"]}
        ],
    }
    return {"synthetic": [time_path, pos_path]}


def build_meta(*, mcversion="1.21.1", protocol=767, duration_ms=5000) -> dict:
    return {
        "singleplayer": False,
        "serverName": "crafter-replay-synthetic",
        "duration": duration_ms,
        "date": 0,
        "mcversion": mcversion,
        "fileFormat": "MCPR",
        "fileFormatVersion": 14,
        "protocol": protocol,
        "generator": "crafter-replay-synthetic",
        "selfId": 1,
        "players": [],
    }


def write_mcpr(path: Path, *, duration_ms: int = 5000, packets: list[tuple[int, bytes]] | None = None) -> Path:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    if packets is None:
        packets = []
    tmcpr = bytearray()
    for ts, raw in packets:
        tmcpr += struct.pack(">ii", ts, len(raw))
        tmcpr += raw
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("metaData.json", json.dumps(build_meta(duration_ms=duration_ms), indent=2))
        zf.writestr("timelines.json", json.dumps(build_timelines_json(duration_ms=duration_ms), indent=2))
        zf.writestr("recording.tmcpr", bytes(tmcpr))
        zf.writestr("markers.json", "[]")
    return path


if __name__ == "__main__":
    out = Path(__file__).resolve().parents[2] / "samples" / "synthetic_empty.mcpr"
    write_mcpr(out)
    print("wrote", out)
