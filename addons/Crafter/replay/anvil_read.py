"""Read Anvil regions into ChunkStore-compatible section state-id arrays."""
from __future__ import annotations

import io
import struct
import zlib
from pathlib import Path

from . import nbt_mini as nbtlib
from .chunks import (
    BIOME_VOLUME,
    BLOCK_VOLUME,
    MIN_SECTION_Y,
    NUM_SECTIONS,
    load_state_tables,
)


_REV: dict[tuple, int] | None = None
_BIOME_REV: dict[str, int] | None = None


def _reverse_tables():
    global _REV, _BIOME_REV
    if _REV is not None:
        return _REV, _BIOME_REV
    states, biomes = load_state_tables()
    rev = {}
    for sid, entry in enumerate(states):
        if not entry:
            continue
        props = tuple(sorted((entry.get("Properties") or {}).items()))
        rev[(entry["Name"], props)] = sid
    brev = {v: int(k) for k, v in biomes.items()}
    _REV, _BIOME_REV = rev, brev
    return rev, brev


def nbt_block_to_state_id(entry) -> int:
    rev, _ = _reverse_tables()
    name = str(entry["Name"])
    props = {}
    if "Properties" in entry:
        props = {str(k): str(v) for k, v in dict(entry["Properties"]).items()}
    key = (name, tuple(sorted(props.items())))
    return rev.get(key, 0)


def nbt_biome_to_id(name: str) -> int:
    _, brev = _reverse_tables()
    return brev.get(str(name), 0)


def _read_packed(longs, bits, count):
    if bits == 0 or not longs:
        return [0] * count
    epl = 64 // bits
    mask = (1 << bits) - 1
    out = [0] * count
    for i in range(count):
        li = i // epl
        bi = (i % epl) * bits
        val = int(longs[li]) & 0xFFFFFFFFFFFFFFFF
        out[i] = (val >> bi) & mask
    return out


def section_nbt_to_ids(sec) -> tuple[list[int], list[int]]:
    bs = sec["block_states"]
    pal = list(bs["palette"])
    state_ids_pal = [nbt_block_to_state_id(e) for e in pal]
    if "data" in bs and len(pal) > 1:
        import math
        bits = max(4, math.ceil(math.log2(len(pal))))
        longs = list(bs["data"])
        indices = _read_packed(longs, bits, BLOCK_VOLUME)
        blocks = [state_ids_pal[i] if i < len(state_ids_pal) else 0 for i in indices]
    else:
        blocks = [state_ids_pal[0] if state_ids_pal else 0] * BLOCK_VOLUME

    biomes_tag = sec.get("biomes")
    if biomes_tag is None:
        biomes = [0] * BIOME_VOLUME
    else:
        bpal = [nbt_biome_to_id(str(x)) for x in biomes_tag["palette"]]
        if "data" in biomes_tag and len(bpal) > 1:
            import math
            bits = max(1, math.ceil(math.log2(len(bpal))))
            indices = _read_packed(list(biomes_tag["data"]), bits, BIOME_VOLUME)
            biomes = [bpal[i] if i < len(bpal) else 0 for i in indices]
        else:
            biomes = [bpal[0] if bpal else 0] * BIOME_VOLUME
    return blocks, biomes


def read_region_columns(mca_path: Path) -> dict[tuple[int, int], list]:
    """Return {(cx,cz): sections} for all chunks in one .mca file."""
    data = Path(mca_path).read_bytes()
    # region coords from filename r.X.Z.mca
    parts = Path(mca_path).stem.split(".")
    rx, rz = int(parts[1]), int(parts[2])
    out = {}
    for i in range(1024):
        loc = struct.unpack_from(">I", data, i * 4)[0]
        sector, nsec = loc >> 8, loc & 0xFF
        if not sector:
            continue
        start = sector * 4096
        length, ctype = struct.unpack_from(">iB", data, start)
        payload = data[start + 5 : start + 4 + length]
        if ctype == 2:
            raw = zlib.decompress(payload)
        elif ctype == 1:
            import gzip
            raw = gzip.decompress(payload)
        else:
            raw = payload
        nbt = nbtlib.File.parse(io.BytesIO(raw))
        cx = rx * 32 + (i % 32)
        cz = rz * 32 + (i // 32)
        # build 24 sections
        by_y = {}
        for sec in nbt.get("sections", []):
            by_y[int(sec["Y"])] = section_nbt_to_ids(sec)
        sections = []
        for si in range(NUM_SECTIONS):
            sy = MIN_SECTION_Y + si
            if sy in by_y:
                sections.append(by_y[sy])
            else:
                sections.append(([0] * BLOCK_VOLUME, [0] * BIOME_VOLUME))
        out[(cx, cz)] = sections
    return out
