"""多世界导入布局。

规则（偏好里可随时开关，但只影响之后的导入）：
- 智能布局关闭：保持旧行为，OBJ 用 Minecraft 绝对坐标，不做任何平移。
- 智能布局打开：
  * 本次 .blend 的第一次导入：整体包围盒在 X/Z 居中到世界原点，最低点落到 Y=0。
  * 之后的导入：按世界路径的相对路径分配一个互不重叠的“货架”槽位，排在第一个
    世界的右侧；一行排满后换到下一行。同一个世界再次导入会复用原来的槽位。
  * “后导入也按相对路径分布”关闭时，之后的导入保持绝对坐标，不参与排布。

槽位表写在场景自定义属性 ``Crafter_world_layout`` 里（JSON 字符串），随 .blend
保存。这里只平移物体，不建父级/Empty：开关变化不会动已经导入的旧模型。
"""

import json
import os
import re

from mathutils import Vector

LAYOUT_PROP = "Crafter_world_layout"

# 槽位间距（方块/米）与单行最大长度。间距只是视觉留白，真正的防重叠靠包围盒。
SLOT_GAP = 64.0
ROW_LIMIT = 8192.0


def _as_float_list(values, fallback):
    try:
        result = [float(v) for v in values]
        if len(result) == len(fallback):
            return result
    except (TypeError, ValueError):
        pass
    return list(fallback)


def _read_layout(scene):
    """读场景里的布局表；损坏或旧格式时退回空表。"""
    data = {}
    raw = scene.get(LAYOUT_PROP) if scene is not None else None
    if isinstance(raw, str) and raw:
        try:
            parsed = json.loads(raw)
            if isinstance(parsed, dict):
                data = parsed
        except (ValueError, TypeError):
            data = {}
    entries = data.get("entries")
    if not isinstance(entries, dict):
        entries = {}
    data["version"] = 1
    data["entries"] = entries
    for key, fallback in (("cursor_x", 0.0), ("cursor_z", 0.0),
                          ("row_x0", 0.0), ("row_depth", 0.0),
                          ("row_limit", ROW_LIMIT)):
        try:
            data[key] = float(data.get(key, fallback))
        except (TypeError, ValueError):
            data[key] = float(fallback)
    return data


def _write_layout(scene, data):
    if scene is None:
        return
    try:
        scene[LAYOUT_PROP] = json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    except (TypeError, ValueError):
        # 极端情况下（比如场景属性被只读限制）不能让导入本身失败
        pass


def world_layout_key(world_path):
    """把世界路径压成稳定的相对路径键。

    - .mcpr 录像：``replay/<文件名>``，避免不同目录下同名录像互相顶掉槽位。
    - 存档：取 ``saves`` 之后的部分；版本隔离时把版本号也带上，例如 ``1.20/MyWorld``。
    - 其它（自定义 jar / 临时世界）：退回目录名。
    """
    if not world_path:
        return "unknown"
    raw = os.path.normpath(os.path.abspath(world_path))
    if raw.lower().endswith(".mcpr"):
        return "replay/" + os.path.basename(raw)
    parts = [part for part in re.split(r"[\\/]+", raw) if part]
    lower = [part.lower() for part in parts]
    if "saves" in lower:
        index = lower.index("saves")
        tail = parts[index + 1:]
        if not tail:
            return os.path.basename(raw) or "unknown"
        relative = "/".join(tail)
        # .../versions/<version>/saves/<world>：不同版本的同名存档要分开
        if index >= 2 and lower[index - 2] == "versions":
            relative = parts[index - 1] + "/" + relative
        return relative
    # 不在 saves 下（自定义 jar / 直接选的目录）：带上父目录，降低同名冲突
    if len(parts) >= 2:
        return parts[-2] + "/" + parts[-1]
    return os.path.basename(raw) or "unknown"


def objects_bounds(objects):
    """返回一组物体的世界包围盒 ``(min_x, min_y, min_z, max_x, max_y, max_z)``。

    只统计有顶点的 MESH；空物体/曲线/骨骼不参与，避免把布局撑出奇怪的尺寸。
    """
    min_x = min_y = min_z = float("inf")
    max_x = max_y = max_z = float("-inf")
    found = False
    for obj in objects:
        if obj is None or obj.type != 'MESH':
            continue
        data = getattr(obj, "data", None)
        if data is None or len(getattr(data, "vertices", ())) == 0:
            continue
        try:
            matrix = obj.matrix_world
            for corner in obj.bound_box:
                point = matrix @ Vector(corner)
                min_x = min(min_x, point.x)
                min_y = min(min_y, point.y)
                min_z = min(min_z, point.z)
                max_x = max(max_x, point.x)
                max_y = max(max_y, point.y)
                max_z = max(max_z, point.z)
                found = True
        except (ReferenceError, ValueError):
            continue
    if not found:
        return None
    return (min_x, min_y, min_z, max_x, max_y, max_z)


def _new_slot(data, width, depth):
    """给新世界分配一个货架槽位，返回包围盒最小角应落到的 (x, z)。"""
    cursor_x = data["cursor_x"]
    cursor_z = data["cursor_z"]
    row_x0 = data["row_x0"]
    row_depth = data["row_depth"]
    row_limit = max(data["row_limit"], width + SLOT_GAP)

    # 当前行放不下就换行；row_x0 是第一行左边界，第一行自身不触发换行
    if cursor_x > row_x0 and cursor_x + width > row_limit:
        cursor_x = row_x0
        cursor_z = cursor_z + row_depth + SLOT_GAP
        row_depth = 0.0

    slot_x = cursor_x
    slot_z = cursor_z
    data["cursor_x"] = slot_x + width + SLOT_GAP
    data["cursor_z"] = cursor_z
    data["row_depth"] = max(row_depth, depth)
    data["row_limit"] = row_limit
    return slot_x, slot_z


def reset_world_layout(scene):
    """忘掉所有槽位（不动已导入的物体）。下一次导入会重新成为“第一个世界”并居中。"""
    if scene is None:
        return False
    if scene.get(LAYOUT_PROP) is not None:
        del scene[LAYOUT_PROP]
        return True
    return False


def apply_world_layout(prefs, scene, world_path, objects):
    """按偏好给本次导入的物体排位。

    prefs: Crafter 偏好（要有 World_Layout / World_Layout_Distribute_Later）
    scene: 当前场景，槽位表存在它的自定义属性里
    world_path: 原始世界路径（.mcpr 或存档目录）
    objects: 本次导入新增的物体列表

    返回一个描述本次操作的 dict（写日志用）；没有布局或没有几何时返回 None。
    """
    if prefs is None or not getattr(prefs, "World_Layout", False):
        return None
    if scene is None:
        return None
    bounds = objects_bounds(objects)
    if bounds is None:
        return None

    min_x, min_y, min_z, max_x, max_y, max_z = bounds
    width = max_x - min_x
    depth = max_z - min_z
    key = world_layout_key(world_path)
    layout = _read_layout(scene)
    entries = layout["entries"]

    entry = entries.get(key)
    if isinstance(entry, dict):
        offset = _as_float_list(entry.get("offset"), (0.0, 0.0, 0.0))
        entry["size"] = [width, max_y - min_y, depth]
        mode = "reuse"
    elif not entries:
        # 第一个世界：X/Z 居中到原点，最低点落到地面
        offset = [-(min_x + max_x) * 0.5, -min_y, -(min_z + max_z) * 0.5]
        entry = {"offset": offset, "size": [width, max_y - min_y, depth]}
        entries[key] = entry
        layout["cursor_x"] = width * 0.5 + SLOT_GAP
        layout["cursor_z"] = -depth * 0.5
        layout["row_x0"] = -width * 0.5
        layout["row_depth"] = depth
        layout["row_limit"] = max(ROW_LIMIT, width * 2.0)
        mode = "center_first"
    else:
        if not getattr(prefs, "World_Layout_Distribute_Later", True):
            return {"mode": "absolute", "key": key}
        slot_x, slot_z = _new_slot(layout, width, depth)
        offset = [slot_x - min_x, -min_y, slot_z - min_z]
        entry = {"offset": offset, "size": [width, max_y - min_y, depth]}
        entries[key] = entry
        mode = "next_slot"

    _write_layout(scene, layout)

    if offset[0] == 0.0 and offset[1] == 0.0 and offset[2] == 0.0:
        return {"mode": mode, "key": key, "offset": offset}
    delta = Vector(offset)
    moved = 0
    for obj in objects:
        if obj is None or obj.type != 'MESH':
            continue
        try:
            obj.location = obj.location + delta
            moved += 1
        except (ReferenceError, AttributeError):
            continue
    return {"mode": mode, "key": key, "offset": offset, "moved": moved}
