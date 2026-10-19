"""Pure helpers: locate .minecraft / resourcepacks / mods from a client jar path."""
from __future__ import annotations

import os


def resourcepacks_json_key(world_path: str) -> str:
    """Stable key for resourcepacks.json enable/disable toggles.

    Normal worlds: parent of the world folder (the saves dir), matching historical behavior.
    Replay .mcpr: absolute path of the .mcpr file (not the ephemeral temp Anvil parent).
    """
    p = os.path.abspath(os.path.normpath(world_path or ""))
    if p.lower().endswith(".mcpr"):
        return p
    return os.path.dirname(p)


def discover_game_dirs_from_jar(jar_path: str) -> tuple[str, str, str]:
    """Walk parents of a client jar to find the game root.

    Returns (dot_minecraft_or_instance_root, resourcepacks_dir, mods_dir).
    Paths may be set even if the resourcepacks/mods folders do not exist yet
    (same as normal Crafter import once a .minecraft root is known).

    Layouts handled:
    - Official: ``.../.minecraft/versions/<ver>/<ver>.jar``
    - Version-isolated / Prism-like: jar under ``.../versions/x/x.jar`` with
      ``resourcepacks`` / ``mods`` on the instance root (parent of ``versions``)
      or as siblings of an intermediate folder.
    """
    jar_path = os.path.abspath(os.path.normpath(jar_path or ""))
    if not jar_path:
        return "", "", ""

    found_root = ""
    found_rp = ""
    found_mods = ""
    cur = os.path.dirname(jar_path)

    for _ in range(10):
        if not cur:
            break
        base = os.path.basename(cur)
        rp = os.path.join(cur, "resourcepacks")
        mods = os.path.join(cur, "mods")
        has_rp = os.path.isdir(rp)
        has_mods = os.path.isdir(mods)
        is_dot = base == ".minecraft"

        if is_dot or has_rp or has_mods:
            if is_dot or not found_root:
                found_root = cur
            if has_rp and not found_rp:
                found_rp = rp
            if has_mods and not found_mods:
                found_mods = mods
            if is_dot or (found_rp and found_mods):
                break

        parent = os.path.dirname(cur)
        if parent == cur:
            break
        cur = parent

    if found_root:
        if not found_rp:
            found_rp = os.path.join(found_root, "resourcepacks")
        if not found_mods:
            found_mods = os.path.join(found_root, "mods")

    return found_root, found_rp, found_mods


def apply_jar_suggestions(info: dict, jar_path: str) -> None:
    """Set suggest_jar / suggest_resourcepacks / suggest_mods / suggest_dot_minecraft on info."""
    if not jar_path or not os.path.isfile(jar_path):
        return
    info["suggest_jar"] = jar_path
    root, rp, mods = discover_game_dirs_from_jar(jar_path)
    if root:
        info["suggest_dot_minecraft"] = root
    if rp:
        info["suggest_resourcepacks"] = rp
    if mods:
        info["suggest_mods"] = mods


def find_jar_for_version(version: str, *, extra_roots: list[str] | None = None) -> str:
    """Locate a client jar for ``version`` under a known game root.

    Tries each root in ``extra_roots`` (a ``.minecraft``/instance dir, i.e. the
    parent of ``versions``), then ``CRAFTER_PIPELINE_MC``. Returns "" when
    nothing matches, so the caller can fall back to a manual jar pick.
    """
    if not version:
        return ""
    roots = [r for r in (extra_roots or []) if r]
    env_root = os.environ.get("CRAFTER_PIPELINE_MC", "").strip()
    if env_root:
        roots.append(env_root)
    for root in roots:
        for name in (f"{version}.jar", "client.jar"):
            cand = os.path.join(root, "versions", version, name)
            if os.path.isfile(cand):
                return cand
    return ""
