"""Validate Minecraft animation metadata before changing Blender nodes.

The existing shader supports vertical strips only. Unsupported atlas layouts
raise ValueError rather than silently sampling incorrect frame coordinates.
"""


def _positive_integer(value, field):
    if type(value) is not int or value <= 0:
        raise ValueError(f"{field} must be a positive integer, got {value!r}")
    return value


def parse_animation_metadata(metadata, image_width, image_height):
    """Return legacy [rows, frametime, duration_units, frames, interpolate], or None."""
    if not isinstance(metadata, dict):
        raise ValueError("mcmeta root must be an object")
    if "animation" not in metadata:
        return None
    animation = metadata["animation"]
    if not isinstance(animation, dict):
        raise ValueError("animation must be an object")
    width = _positive_integer(image_width, "image width")
    height = _positive_integer(image_height, "image height")
    frametime = _positive_integer(animation.get("frametime", 1), "frametime")
    if "width" in animation or "height" in animation:
        frame_width = _positive_integer(animation.get("width", width), "animation.width")
        frame_height = _positive_integer(animation.get("height", height), "animation.height")
    else:
        frame_width = frame_height = min(width, height)
    if width % frame_width or height % frame_height:
        raise ValueError("frame dimensions must evenly divide the texture dimensions")
    if width // frame_width != 1:
        raise ValueError("multi-column animation atlases are not supported; use a vertical strip")
    rows = height // frame_height
    interpolate = animation.get("interpolate", False)
    if type(interpolate) is not bool:
        raise ValueError("interpolate must be a boolean")
    raw_frames = animation.get("frames", [])
    if not isinstance(raw_frames, list):
        raise ValueError("frames must be a list")
    frames = []
    for entry in raw_frames:
        if type(entry) is int:
            index, duration = entry, frametime
        elif isinstance(entry, dict):
            index = entry.get("index")
            duration = _positive_integer(entry.get("time", frametime), "frame.time")
        else:
            raise ValueError(f"invalid frame entry: {entry!r}")
        if type(index) is not int or not 0 <= index < rows:
            raise ValueError(f"frame index {index!r} outside [0, {rows})")
        frames.append([index, duration / frametime])
    if not frames:
        frames = [[index, 1] for index in range(rows)]
    return [rows, frametime, sum(frame[1] for frame in frames), frames, interpolate]
