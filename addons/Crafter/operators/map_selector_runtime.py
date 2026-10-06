"""Pure helpers for the map selector; never touch Blender objects here."""
import json
import os
import subprocess
import time

_java_cache = {}


def probe_java(exe_path):
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
        result = subprocess.run([path, "-version"], capture_output=True, timeout=5,
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
    """Reject malformed coordinates before assigning preferences on the main thread."""
    with open(path, "r", encoding="utf-8-sig") as stream:
        data = json.load(stream)
    keys = ("minX", "minY", "minZ", "maxX", "maxY", "maxZ")
    for key in keys:
        value = data.get(key)
        if isinstance(value, bool) or not isinstance(value, int):
            raise ValueError("Invalid coordinate: " + key)
        if not -(2 ** 31) <= value < 2 ** 31:
            raise ValueError("Coordinate out of range: " + key)
    return ((min(data["minX"], data["maxX"]), min(data["minY"], data["maxY"]), min(data["minZ"], data["maxZ"])),
            (max(data["minX"], data["maxX"]), max(data["minY"], data["maxY"]), max(data["minZ"], data["maxZ"])))
