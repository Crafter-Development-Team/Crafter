"""Pure helpers for the map selector; never touch Blender objects here."""
import json
import os
import subprocess
import time

_java_cache = {}


def probe_java(exe_path, runner=None):
    """Cache both successful and failed JVM probes (preferences draw is frequent)."""
    if not exe_path or not os.path.isfile(exe_path):
        return None
    path = os.path.abspath(exe_path)
    stat = os.stat(path)
    key = (path, stat.st_mtime_ns, stat.st_size)
    now = time.monotonic()
    cached = _java_cache.get(key)
    if cached and now - cached[0] < 60:
        return cached[1]
    valid = None
    try:
        flags = getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0
        result = (runner or subprocess.run)([path, "-version"], capture_output=True, timeout=5,
                                creationflags=flags)
        output = (result.stdout + result.stderr).decode("utf-8", errors="replace").lower()
        if result.returncode == 0 and not any(s in output for s in ("error:", "a fatal error", "32-bit")):
            valid = path
    except (OSError, subprocess.TimeoutExpired):
        pass
    if len(_java_cache) > 128:
        _java_cache.clear()
    _java_cache[key] = (now, valid)
    return valid


def read_coordinates(path):
    """Return (first XYZ, second XYZ, optional importLod); validate before applying any prefs."""
    with open(path, "r", encoding="utf-8-sig") as stream:
        data = json.load(stream)
    if not isinstance(data, dict):
        raise ValueError("Coordinates must be an object")
    lod = parse_import_lod(data["importLod"]) if "importLod" in data else None
    keys = ("minX", "minY", "minZ", "maxX", "maxY", "maxZ")
    for key in keys:
        value = data.get(key)
        if isinstance(value, bool) or not isinstance(value, int):
            raise ValueError("Invalid coordinate: " + key)
        if not -(2 ** 31) <= value < 2 ** 31:
            raise ValueError("Coordinate out of range: " + key)
    return ((min(data["minX"], data["maxX"]), min(data["minY"], data["maxY"]), min(data["minZ"], data["maxZ"])),
            (max(data["minX"], data["maxX"]), max(data["minY"], data["maxY"]), max(data["minZ"], data["maxZ"])), lod)


_LOD_PREFS = {
    "maxLevel": "Max_LOD_Level",
    "autoCenter": "isLODAutoCenter",
    "centerX": "LODCenterX",
    "centerZ": "LODCenterZ",
    **{"distance" + str(i): "LOD" + str(i) + "renderDistance" for i in range(4)},
}


def parse_import_lod(data):
    """A present LOD object must be complete and valid, including when Off.

    Unknown keys are ignored. Distances are nonnegative int32 chunks, centers
    signed int32 blocks; bool is never accepted as an integer.
    """
    if not isinstance(data, dict):
        raise ValueError("importLod must be an object")
    parsed = {}
    for key in _LOD_PREFS:
        value = data.get(key)
        if key == "autoCenter":
            if not isinstance(value, bool):
                raise ValueError("Invalid importLod.autoCenter")
        else:
            if isinstance(value, bool) or not isinstance(value, int):
                raise ValueError("Invalid importLod." + key)
            lower, upper = (0, 4) if key == "maxLevel" else (
                (0, 2 ** 31 - 1) if key.startswith("distance") else (-(2 ** 31), 2 ** 31 - 1))
            if not lower <= value <= upper:
                raise ValueError("importLod out of range: " + key)
        parsed[key] = value
    return parsed


def import_lod_seed_args(prefs):
    """Serialize a main-thread preference snapshot to selector CLI arguments."""
    values = {key: getattr(prefs, name) for key, name in _LOD_PREFS.items()}
    values["maxLevel"] = int(values["maxLevel"])
    values = parse_import_lod(values)
    flags = {"maxLevel": "max", "autoCenter": "auto-center", "centerX": "cx", "centerZ": "cz",
             **{"distance" + str(i): "d" + str(i) for i in range(4)}}
    args = []
    for key, value in values.items():
        args.extend(["--import-lod-" + flags[key], str(value).lower()])
    return args


def apply_import_lod(prefs, lod):
    """Apply a validated result on Blender's main thread; legacy files preserve LOD prefs."""
    if lod is None:
        return
    values = parse_import_lod(lod)
    for key, value in values.items():
        setattr(prefs, _LOD_PREFS[key], str(value) if key == "maxLevel" else value)
