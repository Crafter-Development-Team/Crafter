"""Parse ReplayMod timelines.json and sample camera poses."""
from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Any, Literal


InterpKind = Literal["linear", "cubic-spline", "catmull-rom-spline"]


@dataclass
class CameraKeyframe:
    time_ms: int  # keyframe timeline time (video time)
    x: float
    y: float
    z: float
    yaw: float
    pitch: float
    roll: float


@dataclass
class TimeKeyframe:
    time_ms: int
    replay_ms: int


@dataclass
class Timeline:
    name: str
    time_keys: list[TimeKeyframe]
    camera_keys: list[CameraKeyframe]
    camera_interp: InterpKind = "cubic-spline"
    catmull_alpha: float = 0.5


def _interp_type(entry: Any) -> tuple[InterpKind, float]:
    if entry is None:
        return "linear", 0.5
    t = entry.get("type") if isinstance(entry, dict) else entry
    if isinstance(t, dict):
        kind = t.get("type", "linear")
        alpha = float(t.get("alpha", 0.5))
    else:
        kind = t or "linear"
        alpha = 0.5
    if kind in ("cubic-spline", "cubic"):
        return "cubic-spline", alpha
    if kind in ("catmull-rom-spline", "catmull-rom"):
        return "catmull-rom-spline", alpha
    return "linear", alpha


def parse_timelines(data: dict) -> list[Timeline]:
    out: list[Timeline] = []
    for name, paths in data.items():
        if not isinstance(paths, list) or len(paths) < 2:
            continue
        time_path, pos_path = paths[0], paths[1]
        time_keys: list[TimeKeyframe] = []
        for kf in time_path.get("keyframes") or []:
            props = kf.get("properties") or {}
            # property id may be "timestamp" or grouped
            ts_val = props.get("timestamp")
            if ts_val is None:
                for k, v in props.items():
                    if k.endswith("timestamp") or k == "timestamp":
                        ts_val = v
                        break
            if ts_val is None:
                continue
            time_keys.append(TimeKeyframe(int(kf["time"]), int(ts_val)))

        cam_keys: list[CameraKeyframe] = []
        for kf in pos_path.get("keyframes") or []:
            props = kf.get("properties") or {}
            pos = props.get("camera:position") or props.get("position")
            rot = props.get("camera:rotation") or props.get("rotation")
            if not pos or not rot:
                continue
            cam_keys.append(
                CameraKeyframe(
                    int(kf["time"]),
                    float(pos[0]),
                    float(pos[1]),
                    float(pos[2]),
                    float(rot[0]),
                    float(rot[1]),
                    float(rot[2]),
                )
            )

        interps = pos_path.get("interpolators") or []
        kind, alpha = ("cubic-spline", 0.5)
        if interps:
            kind, alpha = _interp_type(interps[0])

        out.append(
            Timeline(
                name=str(name),
                time_keys=sorted(time_keys, key=lambda k: k.time_ms),
                camera_keys=sorted(cam_keys, key=lambda k: k.time_ms),
                camera_interp=kind,
                catmull_alpha=alpha,
            )
        )
    return out


def _lerp(a: float, b: float, t: float) -> float:
    return a + (b - a) * t


def sample_linear(keys: list[CameraKeyframe], t: float) -> CameraKeyframe:
    if not keys:
        raise ValueError("no camera keyframes")
    if t <= keys[0].time_ms:
        return keys[0]
    if t >= keys[-1].time_ms:
        return keys[-1]
    for i in range(len(keys) - 1):
        a, b = keys[i], keys[i + 1]
        if a.time_ms <= t <= b.time_ms:
            u = 0.0 if b.time_ms == a.time_ms else (t - a.time_ms) / (b.time_ms - a.time_ms)
            return CameraKeyframe(
                int(t),
                _lerp(a.x, b.x, u),
                _lerp(a.y, b.y, u),
                _lerp(a.z, b.z, u),
                _lerp(a.yaw, b.yaw, u),
                _lerp(a.pitch, b.pitch, u),
                _lerp(a.roll, b.roll, u),
            )
    return keys[-1]


def sample_camera(tl: Timeline, t_ms: float) -> CameraKeyframe:
    """Sample camera. v1 uses linear; cubic/catmull filled in next iteration."""
    if tl.camera_interp == "linear" or len(tl.camera_keys) < 3:
        return sample_linear(tl.camera_keys, t_ms)
    # TODO: cubic / catmull-rom matching ReplayStudio; linear is safe fallback
    return sample_linear(tl.camera_keys, t_ms)


def map_replay_time(tl: Timeline, video_t_ms: float) -> int:
    keys = tl.time_keys
    if not keys:
        return int(video_t_ms)
    if video_t_ms <= keys[0].time_ms:
        return keys[0].replay_ms
    if video_t_ms >= keys[-1].time_ms:
        return keys[-1].replay_ms
    for i in range(len(keys) - 1):
        a, b = keys[i], keys[i + 1]
        if a.time_ms <= video_t_ms <= b.time_ms:
            u = 0.0 if b.time_ms == a.time_ms else (video_t_ms - a.time_ms) / (b.time_ms - a.time_ms)
            return int(_lerp(a.replay_ms, b.replay_ms, u))
    return keys[-1].replay_ms


# Minecraft yaw/pitch (degrees) → Blender camera Euler helpers (Y-up RH → Blender Z-up)
def mc_rot_to_blender_euler(yaw: float, pitch: float, roll: float) -> tuple[float, float, float]:
    """Return Blender XYZ Euler radians for a camera looking with MC yaw/pitch/roll.

    MC: yaw 0 looks south (+Z), increases clockwise; pitch + looks down.
    Blender camera looks -Z local; world Z-up.
    """
    # Convert to radians; negate yaw for Blender; pitch sign flip
    yr = math.radians(-yaw + 180.0)
    pr = math.radians(-pitch)
    rr = math.radians(roll)
    # Order applied as XYZ euler on camera: approximate mapping used by mc-world-export style importers
    return (pr, yr, rr)
