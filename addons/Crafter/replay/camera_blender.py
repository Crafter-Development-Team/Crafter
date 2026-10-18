"""Apply ReplayMod camera timeline to a Blender camera (callable from bpy scripts)."""
from __future__ import annotations

from typing import TYPE_CHECKING

from .timelines import Timeline, sample_camera, mc_rot_to_blender_euler

if TYPE_CHECKING:
    import bpy


def apply_camera_timeline(
    tl: Timeline,
    *,
    fps: int = 60,
    camera_name: str = "ReplayCamera",
    fov_degrees: float = 70.0,
    scene=None,
):
    import bpy
    import math

    scene = scene or bpy.context.scene
    scene.render.fps = fps
    if not tl.camera_keys:
        raise ValueError("timeline has no camera keyframes")

    start_ms = tl.camera_keys[0].time_ms
    end_ms = tl.camera_keys[-1].time_ms
    duration_s = max((end_ms - start_ms) / 1000.0, 1.0 / fps)
    frame_start = 1
    frame_end = max(frame_start, int(round(duration_s * fps)) + frame_start)
    scene.frame_start = frame_start
    scene.frame_end = frame_end

    cam_data = bpy.data.cameras.new(camera_name)
    # FOV: Blender camera angle is horizontal/vertical depending on sensor_fit; use angle
    cam_data.lens_unit = "FOV"
    cam_data.angle = math.radians(fov_degrees)
    cam_obj = bpy.data.objects.new(camera_name, cam_data)
    scene.collection.objects.link(cam_obj)
    scene.camera = cam_obj
    cam_obj.rotation_mode = "XYZ"

    # Sample every frame
    for frame in range(frame_start, frame_end + 1):
        t_ms = start_ms + (frame - frame_start) / fps * 1000.0
        pose = sample_camera(tl, t_ms)
        # MC Y-up: Blender X=MC X, Y=MC -Z, Z=MC Y
        cam_obj.location = (pose.x, -pose.z, pose.y)
        rx, ry, rz = mc_rot_to_blender_euler(pose.yaw, pose.pitch, pose.roll)
        cam_obj.rotation_euler = (rx, ry, rz)
        cam_obj.keyframe_insert(data_path="location", frame=frame)
        cam_obj.keyframe_insert(data_path="rotation_euler", frame=frame)

    # Linear interpolation between dense samples is fine; set bezier for smoother scrubbing
    if cam_obj.animation_data and cam_obj.animation_data.action:
        for fcurve in cam_obj.animation_data.action.fcurves:
            for kp in fcurve.keyframe_points:
                kp.interpolation = "LINEAR"

    return cam_obj
