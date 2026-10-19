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


def _natural_spline(xs: list[float], ys: list[float]):
    """Natural cubic spline coefficients (ReplayStudio CubicSplineInterpolator uses a natural
    cubic spline through keyframes over keyframe time)."""
    n = len(xs) - 1
    h = [xs[i + 1] - xs[i] or 1e-6 for i in range(n)]
    al = [0.0] + [3 / h[i] * (ys[i + 1] - ys[i]) - 3 / h[i - 1] * (ys[i] - ys[i - 1]) for i in range(1, n)]
    l, mu, z = [1.0] + [0.0] * n, [0.0] * (n + 1), [0.0] * (n + 1)
    for i in range(1, n):
        l[i] = 2 * (xs[i + 1] - xs[i - 1]) - h[i - 1] * mu[i - 1]
        mu[i] = h[i] / l[i]
        z[i] = (al[i] - h[i - 1] * z[i - 1]) / l[i]
    l[n] = 1.0
    c, bb, d = [0.0] * (n + 1), [0.0] * n, [0.0] * n
    for j in range(n - 1, -1, -1):
        c[j] = z[j] - mu[j] * c[j + 1]
        bb[j] = (ys[j + 1] - ys[j]) / h[j] - h[j] * (c[j + 1] + 2 * c[j]) / 3
        d[j] = (c[j + 1] - c[j]) / (3 * h[j])
    return bb, c, d


_SPLINE_CACHE: dict = {}


def _unwrap(vals: list[float]) -> list[float]:
    out = [vals[0]]
    for v in vals[1:]:
        prev = out[-1]
        out.append(prev + ((v - prev + 180.0) % 360.0 - 180.0))
    return out


def sample_spline(keys: list[CameraKeyframe], t: float) -> CameraKeyframe:
    if t <= keys[0].time_ms:
        return keys[0]
    if t >= keys[-1].time_ms:
        return keys[-1]
    ck = tuple((k.time_ms, k.x, k.y, k.z, k.yaw, k.pitch, k.roll) for k in keys)
    if ck not in _SPLINE_CACHE:
        xs = [float(k.time_ms) for k in keys]
        comps = [[k.x for k in keys], [k.y for k in keys], [k.z for k in keys],
                 _unwrap([k.yaw for k in keys]), [k.pitch for k in keys], [k.roll for k in keys]]
        _SPLINE_CACHE[ck] = (xs, comps, [_natural_spline(xs, c) for c in comps])
    xs, comps, coefs = _SPLINE_CACHE[ck]
    i = max(0, min(len(xs) - 2, next(j for j in range(len(xs) - 1) if xs[j] <= t <= xs[j + 1])))
    dx = t - xs[i]
    vals = [comps[k][i] + coefs[k][0][i] * dx + coefs[k][1][i] * dx * dx + coefs[k][2][i] * dx ** 3
            for k in range(6)]
    return CameraKeyframe(int(t), *vals)


def sample_camera(tl: Timeline, t_ms: float) -> CameraKeyframe:
    """Sample camera: linear, or natural cubic spline for cubic-spline / catmull-rom paths."""
    if tl.camera_interp == "linear" or len(tl.camera_keys) < 3:
        return sample_linear(tl.camera_keys, t_ms)
    return sample_spline(tl.camera_keys, t_ms)


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
    """Blender XYZ Euler (radians) for a camera with MC yaw/pitch (degrees).

    MC view dir = (-sin(yaw)cos(p), -sin(p), cos(yaw)cos(p)); MC->Blender (x, -z, y).
    Camera looks down local -Z: Rz(180 - yaw) * Rx(90 - pitch) reproduces that direction.
    Roll is applied about the view axis by camera_blender (needs mathutils).
    """
    return (math.radians(90.0 - pitch), 0.0, math.radians(180.0 - yaw))
