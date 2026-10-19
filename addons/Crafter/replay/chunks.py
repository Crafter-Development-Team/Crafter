"""Chunk reconstruction from map_chunk (0x27) / block_change for protocol 767 (1.21.1).

Network chunkData layout (1.18–1.21.1): for each of 24 sections (Y=-4..19):
  short block_count
  PalettedContainer(blocks, 4096)
  PalettedContainer(biomes, 64)

PalettedContainer:
  u8 bits
  bits==0: varint single_value; u8 0
  1..max_indirect: varint palette_len; varint*len; varint num_longs; num_longs * i64 BE
  >max_indirect: varint num_longs; longs (direct global IDs)
"""
from __future__ import annotations

import array
import json
import math
import struct
import zlib
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .varint import read_varint, write_varint

# 1.21.1 overworld
MIN_SECTION_Y = -4
MIN_Y = -64  # heightmap stored value = top_block_y - MIN_Y + 1 (0 if the column has none)
NUM_SECTIONS = 24  # 384 / 16
BLOCK_VOLUME = 4096
BIOME_VOLUME = 64
MAX_INDIRECT_BLOCK_BITS = 8
MAX_INDIRECT_BIOME_BITS = 3
MAP_CHUNK_ID = 0x27
BLOCK_CHANGE_ID = 0x09
MULTI_BLOCK_CHANGE_ID = 0x49
UNLOAD_CHUNK_ID = 0x21
DATA_VERSION = 3955
# 9 bits covers world height 384; 64//9 = 7 entries per long, no spanning → 37 longs.
HEIGHTMAP_BITS = 9
HEIGHTMAP_LONGS = 37
HEIGHTMAP_NAMES = (
    "MOTION_BLOCKING",
    "MOTION_BLOCKING_NO_LEAVES",
    "OCEAN_FLOOR",
    "WORLD_SURFACE",
)
# Per-state flags. Approximates Java Heightmap predicates:
#   WORLD_SURFACE = not air; OCEAN_FLOOR = blocksMotion (legacy solid);
#   MOTION_BLOCKING = blocksMotion or has a fluid; NO_LEAVES drops *_leaves.
F_AIR = 1
F_SOLID = 2
F_FLUID = 4
F_LEAVES = 8
_NO_TOP = -1_000_000

# Non-motion-blocking blocks (no legacy solid). Fluids / waterlogged still count
# for MOTION_BLOCKING via F_FLUID. Not a collision-box test: banners and ladders
# stay solid; cobwebs, bamboo shoots, and snow layer 1 do not.
_NON_SOLID_EXACT = frozenset({
    "short_grass", "tall_grass", "fern", "large_fern", "dead_bush", "sugar_cane",
    "bamboo", "vine", "glow_lichen", "sculk_vein", "hanging_roots", "cobweb",
    "seagrass", "tall_seagrass", "kelp", "kelp_plant",
    "nether_sprouts", "crimson_roots", "warped_roots",
    "crimson_fungus", "warped_fungus", "brown_mushroom", "red_mushroom",
    "twisting_vines", "twisting_vines_plant", "weeping_vines", "weeping_vines_plant",
    "cave_vines", "cave_vines_plant", "small_dripleaf", "frogspawn", "spore_blossom",
    "mangrove_propagule",
    "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet",
    "red_tulip", "orange_tulip", "white_tulip", "pink_tulip",
    "oxeye_daisy", "cornflower", "lily_of_the_valley", "wither_rose",
    "torchflower", "sunflower", "lilac", "rose_bush", "peony",
    "pitcher_plant", "pink_petals", "lily_pad",
    "wheat", "carrots", "potatoes", "beetroots",
    "melon_stem", "pumpkin_stem", "attached_melon_stem", "attached_pumpkin_stem",
    "nether_wart", "cocoa", "sweet_berry_bush", "torchflower_crop", "pitcher_crop",
    "fire", "soul_fire", "scaffolding", "light", "structure_void", "sea_pickle",
    "redstone_wire", "repeater", "comparator", "lever",
    "tripwire", "tripwire_hook", "string", "rail",
    "torch", "wall_torch",
    "nether_portal", "end_portal", "end_gateway",
})
_NON_SOLID_SUFFIXES = (
    "_sapling", "_button", "_pressure_plate", "_sign", "_torch", "_rail",
    "_carpet", "_coral_fan", "_coral_wall_fan",
)
_PURE_FLUIDS = frozenset({"water", "lava", "bubble_column"})
_ALWAYS_FLUID = _PURE_FLUIDS | frozenset({"seagrass", "tall_seagrass", "kelp", "kelp_plant"})
_FLAG_TABLE: list[int] | None = None
# Per-state sky opacity (0 / 1 / 15) plus bit 4 when the block's bottom face
# stops the vertical level-15 sun column (stairs, bottom slabs, paths, …).
_LIGHT_TABLE: bytes | None = None
_SKY_FULL = b"\xff" * 2048
# Horizontal spread under roofs/overhangs. Deeper than this stays unlit.
_SKY_FLOOR_MARGIN = 20

_STATE_TABLE: list[dict | None] | None = None
_EXT_SEEN: set | None = None
_BIOME_TABLE: dict[str, str] | None = None


def _tables_path() -> Path:
    # Prefer docs next to workspace; fall back beside this package
    candidates = [
        Path(__file__).resolve().parents[2] / "docs" / "blockstates-1.21.1.json",
        Path(__file__).resolve().parent / "blockstates-1.21.1.json",
    ]
    for c in candidates:
        if c.is_file():
            return c
    raise FileNotFoundError("blockstates-1.21.1.json not found")


def load_state_tables() -> tuple[list[dict | None], dict[str, str]]:
    global _STATE_TABLE, _BIOME_TABLE
    if _STATE_TABLE is None:
        import os
        raw = json.loads(_tables_path().read_text())
        _STATE_TABLE = raw["blockStates"]
        _BIOME_TABLE = {str(k): v for k, v in raw["biomes"].items()}
        ext = os.environ.get("CRAFTER_REPLAY_STATE_EXT") or ""
        if ext and Path(ext).is_file():
            data = json.loads(Path(ext).read_text())
            entries = data["blockStates"] if isinstance(data, dict) and "blockStates" in data else data
            if isinstance(entries, list) and entries:
                extend_state_table(entries)
    return _STATE_TABLE, _BIOME_TABLE


def _state_key(entry: dict) -> tuple:
    name = str(entry.get("Name") or "minecraft:air")
    props = entry.get("Properties") or {}
    if hasattr(props, "items"):
        props = {str(k): str(v) for k, v in dict(props).items()}
    else:
        props = {}
    return (name, tuple(sorted(props.items())))


def extend_state_table(entries: list[dict]) -> int:
    """Append unique {Name, Properties?} states; keep vanilla IDs stable. Returns # added."""
    global _STATE_TABLE, _FLAG_TABLE, _LIGHT_TABLE, _BIOME_TABLE, _EXT_SEEN
    if _STATE_TABLE is None:
        raw = json.loads(_tables_path().read_text())
        _STATE_TABLE = raw["blockStates"]
        if _BIOME_TABLE is None:
            _BIOME_TABLE = {str(k): v for k, v in raw["biomes"].items()}
    if "_EXT_SEEN" not in globals() or _EXT_SEEN is None:
        _EXT_SEEN = {_state_key(e) for e in _STATE_TABLE if e}
    added = 0
    for e in entries:
        if not e:
            continue
        key = _state_key(e)
        if key in _EXT_SEEN:
            continue
        name, props_t = key
        entry: dict = {"Name": name}
        if props_t:
            entry["Properties"] = {k: v for k, v in props_t}
        _STATE_TABLE.append(entry)
        _EXT_SEEN.add(key)
        added += 1
    if added:
        _FLAG_TABLE = None
        _LIGHT_TABLE = None
    return added


def state_id_to_nbt(state_id: int) -> dict:
    """Return nbtlib-tag dict for a block_states palette entry."""
    from .nbt_mini import Compound, String

    table, _ = load_state_tables()
    entry = table[state_id] if 0 <= state_id < len(table) else None
    if not entry:
        return {"Name": String("minecraft:air")}
    out: dict[str, Any] = {"Name": String(str(entry["Name"]))}
    if "Properties" in entry:
        out["Properties"] = Compound(
            {str(k): String(str(v)) for k, v in dict(entry["Properties"]).items()}
        )
    return out


def biome_id_to_name(biome_id: int) -> str:
    _, biomes = load_state_tables()
    return biomes.get(str(biome_id), "minecraft:plains")


class Buf:
    __slots__ = ("data", "off")

    def __init__(self, data: bytes, off: int = 0):
        self.data = memoryview(data)
        self.off = off

    def u8(self) -> int:
        v = self.data[self.off]
        self.off += 1
        return v

    def i16(self) -> int:
        v = struct.unpack_from(">h", self.data, self.off)[0]
        self.off += 2
        return v

    def i32(self) -> int:
        v = struct.unpack_from(">i", self.data, self.off)[0]
        self.off += 4
        return v

    def i64(self) -> int:
        v = struct.unpack_from(">q", self.data, self.off)[0]
        self.off += 8
        return v

    def varint(self) -> int:
        v, self.off = read_varint(self.data, self.off)
        return v

    def bytes(self, n: int) -> bytes:
        b = bytes(self.data[self.off : self.off + n])
        self.off += n
        return b

    def network_nbt(self):
        """Read 1.20.2+ network NBT (root compound has no name). Returns nbtlib.File or None."""
        import io
        from . import nbt_mini as nbtlib
        raw = bytes(self.data[self.off :])
        if not raw:
            raise ValueError("EOF reading network NBT")
        if raw[0] == 0x00:
            self.off += 1
            return None
        if raw[0] != 0x0A:
            raise ValueError(f"network NBT expected TAG_Compound 0x0A, got {raw[0]:#x}")
        # Classic File format inserts a 2-byte empty root name after the type byte.
        classic = bytes([0x0A, 0x00, 0x00]) + raw[1:]
        bio = io.BytesIO(classic)
        tag = nbtlib.File.parse(bio)
        self.off += bio.tell() - 2
        return tag


def _entries_per_long(bits: int) -> int:
    return 64 // bits if bits else 0


def _num_longs(n_entries: int, bits: int) -> int:
    if bits == 0:
        return 0
    epl = _entries_per_long(bits)
    return (n_entries + epl - 1) // epl


def read_packed_ids(longs: list[int], bits: int, count: int) -> list[int]:
    if bits == 0:
        return [0] * count
    epl = _entries_per_long(bits)
    mask = (1 << bits) - 1
    out = [0] * count
    for i in range(count):
        li = i // epl
        bi = (i % epl) * bits
        out[i] = (longs[li] >> bi) & mask
    return out


def write_packed_ids(ids: list[int], bits: int) -> list[int]:
    if bits == 0:
        return []
    epl = _entries_per_long(bits)
    nlongs = _num_longs(len(ids), bits)
    longs = [0] * nlongs
    for i, v in enumerate(ids):
        li = i // epl
        bi = (i % epl) * bits
        longs[li] |= (v & ((1 << bits) - 1)) << bi
    return longs


def read_paletted_container(buf: Buf, volume: int, max_indirect: int) -> list[int]:
    """Return list of global palette IDs, length=volume."""
    bits = buf.u8()
    if bits == 0:
        value = buf.varint()
        _nlongs = buf.varint()  # must be 0
        return [value] * volume
    if bits > max_indirect:
        # direct
        nlongs = buf.varint()
        longs = [buf.i64() & 0xFFFFFFFFFFFFFFFF for _ in range(nlongs)]
        # bits for direct: use actual bits from neededBits of global; stored bits may be 15
        return read_packed_ids(longs, bits, volume)
    # indirect
    plen = buf.varint()
    palette = [buf.varint() for _ in range(plen)]
    nlongs = buf.varint()
    longs = [buf.i64() & 0xFFFFFFFFFFFFFFFF for _ in range(nlongs)]
    indices = read_packed_ids(longs, bits, volume)
    return [palette[i] if i < len(palette) else 0 for i in indices]


def read_chunk_data(chunk_data: bytes) -> list[tuple[list[int], list[int]]]:
    """Parse chunkData buffer → list of (block_state_ids[4096], biome_ids[64]) per section."""
    buf = Buf(chunk_data)
    sections = []
    for _ in range(NUM_SECTIONS):
        _block_count = buf.i16()
        blocks = read_paletted_container(buf, BLOCK_VOLUME, MAX_INDIRECT_BLOCK_BITS)
        biomes = read_paletted_container(buf, BIOME_VOLUME, MAX_INDIRECT_BIOME_BITS)
        sections.append((blocks, biomes))
    return sections


def parse_map_chunk_payload(payload: bytes) -> tuple[int, int, bytes, list]:
    """Parse map_chunk packet payload → (cx, cz, chunkData, leftover_info).

    Heightmaps = network NBT (1.20.2+: root compound, no name); then varint-sized
    chunkData; then block entities + light (skipped for WI mesh).
    """
    buf = Buf(payload)
    cx = buf.i32()
    cz = buf.i32()
    heightmaps = buf.network_nbt()
    data_len = buf.varint()
    chunk_data = buf.bytes(data_len)
    return cx, cz, chunk_data, {"heightmaps": heightmaps, "rest_off": buf.off}


def _palette_bits(n: int, minimum: int = 4) -> int:
    if n <= 1:
        return 0
    b = max(minimum, math.ceil(math.log2(n)))
    return b


def _bare_block_name(name: str) -> str:
    return name[10:] if name.startswith("minecraft:") else name


def _flags_for_state(name: str, props: dict) -> int:
    """Bitset F_AIR / F_SOLID / F_FLUID / F_LEAVES for one block state."""
    bare = _bare_block_name(name)
    if bare in ("air", "cave_air", "void_air"):
        return F_AIR
    if bare in _PURE_FLUIDS:
        return F_FLUID
    leaves = bare.endswith("_leaves")
    if leaves:
        solid = True
    elif bare == "snow":
        # One snow layer has an empty collision; thicker layers block motion.
        solid = str(props.get("layers", "1")) != "1"
    elif bare == "big_dripleaf":
        solid = str(props.get("tilt", "none")) != "full"
    elif bare in _NON_SOLID_EXACT or bare.endswith(_NON_SOLID_SUFFIXES):
        solid = False
    else:
        solid = True
    fluid = bare in _ALWAYS_FLUID or str(props.get("waterlogged", "false")) == "true"
    flags = 0
    if solid:
        flags |= F_SOLID
    if fluid:
        flags |= F_FLUID
    if leaves:
        flags |= F_LEAVES
    return flags


def _state_flag_table() -> list[int]:
    global _FLAG_TABLE
    if _FLAG_TABLE is None:
        states, _ = load_state_tables()
        table = [F_AIR] * len(states)
        for i, entry in enumerate(states):
            if not entry:
                continue
            table[i] = _flags_for_state(str(entry.get("Name") or "minecraft:air"), entry.get("Properties") or {})
        _FLAG_TABLE = table
    return _FLAG_TABLE


# Opacity 1: vanilla sky-light filters (block light is unaffected). Opacity 0 names
# that are not caught by a suffix live in _SKY_CLEAR_EXACT. Everything else is a
# full opaque cube (opacity 15). Matches minecraft-data filterLight for 1.21.1.
_SKY_FILTER_EXACT = frozenset({
    "water", "lava", "bubble_column", "ice", "frosted_ice", "cobweb",
    "slime_block", "honey_block", "spawner", "beacon", "end_gateway",
    "chorus_plant", "chorus_flower", "mangrove_roots", "powder_snow",
    "seagrass", "tall_seagrass", "kelp", "kelp_plant", "sea_pickle", "conduit",
    "sculk_vein", "sculk_shrieker", "trial_spawner", "vault", "shulker_box",
})
_SKY_CLEAR_SUFFIXES = (
    "_stairs", "_slab", "_fence", "_fence_gate", "_door", "_trapdoor", "_wall",
    "_pane", "_bars", "_carpet", "_pressure_plate", "_button", "_sign",
    "_hanging_sign", "_banner", "_head", "_skull", "_bed", "_rail", "_candle",
    "_sapling", "_torch", "_glass",
)
_SKY_CLEAR_EXACT = frozenset({
    "air", "cave_air", "void_air", "glass", "tinted_glass",
    "short_grass", "fern", "tall_grass", "large_fern", "dead_bush", "vine",
    "glow_lichen", "hanging_roots", "seagrass", "tall_seagrass",
    "nether_sprouts", "crimson_roots", "warped_roots", "crimson_fungus",
    "warped_fungus", "brown_mushroom", "red_mushroom", "twisting_vines",
    "twisting_vines_plant", "weeping_vines", "weeping_vines_plant",
    "cave_vines", "cave_vines_plant", "small_dripleaf", "big_dripleaf",
    "big_dripleaf_stem", "frogspawn", "spore_blossom", "mangrove_propagule",
    "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip",
    "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy", "cornflower",
    "lily_of_the_valley", "wither_rose", "torchflower", "sunflower", "lilac",
    "rose_bush", "peony", "pitcher_plant", "pitcher_crop", "pink_petals",
    "lily_pad", "wheat", "carrots", "potatoes", "beetroots", "melon_stem",
    "pumpkin_stem", "attached_melon_stem", "attached_pumpkin_stem",
    "nether_wart", "cocoa", "sweet_berry_bush", "torchflower_crop", "fire",
    "soul_fire", "scaffolding", "light", "structure_void", "redstone_wire",
    "repeater", "comparator", "lever", "tripwire", "tripwire_hook", "rail",
    "torch", "nether_portal", "end_portal", "sugar_cane", "bamboo", "cactus",
    "snow", "ladder", "chain", "iron_bars", "glass_pane",
    "chest", "trapped_chest", "ender_chest", "farmland", "dirt_path",
    "enchanting_table", "end_portal_frame", "brewing_stand", "cauldron",
    "water_cauldron", "lava_cauldron", "powder_snow_cauldron", "dragon_egg",
    "flower_pot", "anvil", "chipped_anvil", "damaged_anvil", "daylight_detector",
    "hopper", "barrier", "end_rod", "turtle_egg", "sniffer_egg", "grindstone",
    "lectern", "stonecutter", "bell", "lantern", "soul_lantern", "campfire",
    "soul_campfire", "composter", "candle", "candle_cake", "amethyst_cluster",
    "large_amethyst_bud", "medium_amethyst_bud", "small_amethyst_bud",
    "sculk_sensor", "calibrated_sculk_sensor", "lightning_rod", "pointed_dripstone",
    "azalea", "flowering_azalea", "decorated_pot", "heavy_core", "moving_piston",
    "piston_head", "copper_grate", "exposed_copper_grate", "weathered_copper_grate",
    "oxidized_copper_grate", "waxed_copper_grate", "waxed_exposed_copper_grate",
    "waxed_weathered_copper_grate", "waxed_oxidized_copper_grate",
    "white_stained_glass", "orange_stained_glass", "magenta_stained_glass",
    "light_blue_stained_glass", "yellow_stained_glass", "lime_stained_glass",
    "pink_stained_glass", "gray_stained_glass", "light_gray_stained_glass",
    "cyan_stained_glass", "purple_stained_glass", "blue_stained_glass",
    "brown_stained_glass", "green_stained_glass", "red_stained_glass",
    "black_stained_glass",
    "white_candle_cake", "orange_candle_cake", "magenta_candle_cake",
    "light_blue_candle_cake", "yellow_candle_cake", "lime_candle_cake",
    "pink_candle_cake", "gray_candle_cake", "light_gray_candle_cake",
    "cyan_candle_cake", "purple_candle_cake", "blue_candle_cake",
    "brown_candle_cake", "green_candle_cake", "red_candle_cake", "black_candle_cake",
})
# Bottom face blocks the sun column (the cell itself can still be 15).
_SKY_OCCLUDERS = frozenset({
    "dirt_path", "farmland", "snow", "enchanting_table", "end_portal_frame",
    "lectern", "stonecutter", "daylight_detector", "sculk_sensor",
    "calibrated_sculk_sensor", "piston_head",
})


def _sky_opacity(name: str, props: dict) -> int:
    """0 clear, 1 filter, 15 opaque. Bit 4 set when the bottom face stops sun."""
    bare = _bare_block_name(name)
    if bare in ("air", "cave_air", "void_air"):
        return 0
    if bare == "tinted_glass":
        return 15
    if bare.endswith("_slab") and str(props.get("type", "")) == "double":
        return 15
    if (
        bare in _SKY_FILTER_EXACT
        or bare.endswith("_leaves")
        or bare.endswith("_shulker_box")
        or "coral" in bare
    ):
        return 1
    clear = bare in _SKY_CLEAR_EXACT or bare.endswith(_SKY_CLEAR_SUFFIXES) or bare.startswith("potted_")
    if not clear:
        return 15
    # Waterlogged clear blocks filter sky light; copper grates do not.
    base = 1 if str(props.get("waterlogged", "false")) == "true" and "grate" not in bare else 0
    occ = False
    if bare.endswith("_stairs"):
        occ = str(props.get("half", "")) == "bottom"
    elif bare.endswith("_slab"):
        occ = str(props.get("type", "")) == "bottom"
    elif bare in _SKY_OCCLUDERS:
        occ = True
    # Bit 4: bottom face stops the sun column and does not emit downward.
    return base | (16 if occ else 0)


def _light_table() -> bytes:
    global _LIGHT_TABLE
    if _LIGHT_TABLE is None:
        states, _ = load_state_tables()
        tab = bytearray(len(states))
        for i, entry in enumerate(states):
            if not entry:
                continue
            tab[i] = _sky_opacity(str(entry.get("Name") or "minecraft:air"), entry.get("Properties") or {})
        _LIGHT_TABLE = bytes(tab)
    return _LIGHT_TABLE


def _column_geometry(sections: list[tuple[list[int], list[int]]]):
    """Heightmap stored-values, NO_LEAVES block-y per column, and top non-air section.

    ``sections[0]`` is section Y ``MIN_SECTION_Y``. ``nl_top`` is the world y of
    the highest MOTION_BLOCKING_NO_LEAVES block, or ``_NO_TOP`` when the column
    has none.
    """
    flags = _state_flag_table()
    nflags = len(flags)
    ws = [0] * 256
    of = [0] * 256
    mb = [0] * 256
    nl = [0] * 256
    nl_top = [_NO_TOP] * 256
    done = [False] * 256
    remaining = 256

    top_si = -1
    for si in range(len(sections) - 1, -1, -1):
        blocks = sections[si][0]
        sid0 = blocks[0]
        if blocks.count(sid0) == BLOCK_VOLUME:
            f0 = flags[sid0] if 0 <= sid0 < nflags else F_AIR
            if f0 & F_AIR:
                continue
            top_si = si
            break
        hit = False
        for sid in blocks:
            f = flags[sid] if 0 <= sid < nflags else F_AIR
            if not (f & F_AIR):
                hit = True
                break
        if hit:
            top_si = si
            break

    if top_si >= 0:
        # Sections top-down, and inside a section Y top-down, so the first hit is the highest.
        for si in range(top_si, -1, -1):
            if remaining == 0:
                break
            blocks = sections[si][0]
            base = (MIN_SECTION_Y + si) << 4
            for ly in range(15, -1, -1):
                if remaining == 0:
                    break
                y = base + ly
                stored = y - MIN_Y + 1
                layer = ly << 8
                for lz in range(16):
                    row = layer | (lz << 4)
                    col0 = lz << 4
                    for lx in range(16):
                        col = col0 | lx
                        if done[col]:
                            continue
                        sid = blocks[row | lx]
                        f = flags[sid] if 0 <= sid < nflags else F_AIR
                        if f & F_AIR:
                            continue
                        if ws[col] == 0:
                            ws[col] = stored
                        solid = f & F_SOLID
                        fluid = f & F_FLUID
                        if solid and of[col] == 0:
                            of[col] = stored
                        if (solid or fluid) and mb[col] == 0:
                            mb[col] = stored
                        if (solid or fluid) and not (f & F_LEAVES) and nl[col] == 0:
                            nl[col] = stored
                            nl_top[col] = y
                        if ws[col] and mb[col] and nl[col] and of[col]:
                            done[col] = True
                            remaining -= 1

    hm = {
        "WORLD_SURFACE": ws,
        "OCEAN_FLOOR": of,
        "MOTION_BLOCKING": mb,
        "MOTION_BLOCKING_NO_LEAVES": nl,
    }
    return hm, nl_top, top_si


def _section_span(top_si: int, n_sections: int) -> int:
    """Highest section index (inclusive) that receives a SkyLight array."""
    light_hi = 0 if top_si < 0 else top_si + 1
    if n_sections <= 0:
        return -1
    if light_hi >= n_sections:
        light_hi = n_sections - 1
    return light_hi


def _pack_band_section(
    sy: int,
    chunk_base: int,
    light: bytearray,
    fifteen: array.array,
    y_lo: int,
    y_hi: int,
    height: int,
) -> bytes:
    """Nibble SkyLight for one section. y > y_hi is sun; y < y_lo is 0 unless the column is open."""
    base_y = sy << 4
    if base_y > y_hi:
        return _SKY_FULL
    buf = bytearray(2048)
    for ly in range(16):
        y = base_y + ly
        layer = ly << 8
        if y > y_hi:
            for i in range(256):
                idx = layer | i
                bi = idx >> 1
                if idx & 1:
                    buf[bi] |= 0xF0
                else:
                    buf[bi] |= 0x0F
            continue
        if y < y_lo:
            for local in range(256):
                if y < fifteen[chunk_base + local]:
                    continue
                idx = layer | local
                bi = idx >> 1
                if idx & 1:
                    buf[bi] |= 0xF0
                else:
                    buf[bi] |= 0x0F
            continue
        rel = y - y_lo
        for local in range(256):
            lv = light[(chunk_base + local) * height + rel]
            if not lv:
                continue
            idx = layer | local
            bi = idx >> 1
            if idx & 1:
                buf[bi] |= lv << 4
            else:
                buf[bi] |= lv
    return bytes(buf)


def _compute_skylight(
    columns: dict[tuple[int, int], list],
    geo: dict[tuple[int, int], tuple[list[int], int]],
) -> dict[tuple[int, int], dict[int, bytes]]:
    """Vanilla-like sky light for every loaded column.

    Column pass: 15 straight down through opacity-0 blocks that do not occlude
    downward; filters (water, ice, leaves, cobweb, …) cost 1; full cubes are 0.
    A bottom stair, bottom slab, path, or similar keeps 15 but does not emit
    downward. Then a 6-direction BFS spreads at a cost of 1 through passable
    cells, including across chunk borders and upward into rooms. The flood stays
    in ``[min(NO_LEAVES) - 20, top of the highest non-air section]`` and will not
    enter a column below that column's own NO_LEAVES - 20.
    """
    out: dict[tuple[int, int], dict[int, bytes]] = {}
    if not columns:
        return out
    keys = list(columns.keys())
    chunk_index = {k: i for i, k in enumerate(keys)}
    nchunks = len(keys)
    # Band from the lowest surface margin up to the tallest non-air section.
    y_hi = MIN_Y
    y_lo = 10**9
    any_surface = False
    for key in keys:
        nl_top, top_si = geo[key]
        if top_si >= 0:
            y_hi = max(y_hi, ((MIN_SECTION_Y + top_si) << 4) + 15)
        for top in nl_top:
            if top > _NO_TOP // 2:
                any_surface = True
                y_lo = min(y_lo, top - _SKY_FLOOR_MARGIN)
    if not any_surface:
        y_lo = y_hi
    y_lo = max(MIN_Y, y_lo)
    if y_hi < y_lo:
        y_hi = y_lo
    height = y_hi - y_lo + 1
    ncolumns = nchunks * 256
    total = ncolumns * height
    light = bytearray(total)
    # 15 blocks the flood; 0 lets it through. Column pass overwrites the band.
    opacity = bytearray(b"\x0f") * total
    fifteen = array.array("i", [y_hi + 1]) * ncolumns
    table = _light_table()
    ntable = len(table)

    plus_x = array.array("i", [-1]) * ncolumns
    minus_x = array.array("i", [-1]) * ncolumns
    plus_z = array.array("i", [-1]) * ncolumns
    minus_z = array.array("i", [-1]) * ncolumns
    for (cx, cz), ci in chunk_index.items():
        nix = chunk_index.get((cx + 1, cz))
        ndx = chunk_index.get((cx - 1, cz))
        niz = chunk_index.get((cx, cz + 1))
        ndz = chunk_index.get((cx, cz - 1))
        base = ci * 256
        for lz in range(16):
            zrow = lz << 4
            for lx in range(16):
                c = base + zrow + lx
                if lx < 15:
                    plus_x[c] = c + 1
                elif nix is not None:
                    plus_x[c] = nix * 256 + zrow
                if lx > 0:
                    minus_x[c] = c - 1
                elif ndx is not None:
                    minus_x[c] = ndx * 256 + zrow + 15
                if lz < 15:
                    plus_z[c] = c + 16
                elif niz is not None:
                    plus_z[c] = niz * 256 + lx
                if lz > 0:
                    minus_z[c] = c - 16
                elif ndz is not None:
                    minus_z[c] = ndz * 256 + (15 << 4) + lx

    for (cx, cz), ci in chunk_index.items():
        sections = columns[(cx, cz)]
        nl_top, _top_si = geo[(cx, cz)]
        block_secs = [sec[0] for sec in sections]
        nsec = len(block_secs)
        base = ci * 256
        for local in range(256):
            col = base + local
            lx = local & 15
            lz = local >> 4
            col_in_sec = (lz << 4) | lx
            top = nl_top[local]
            floor_y = top - _SKY_FLOOR_MARGIN if top > _NO_TOP // 2 else y_lo
            source = True
            prev = 15
            ff = y_hi + 1
            cbase = col * height
            for y in range(y_hi, y_lo - 1, -1):
                rel = y - y_lo
                idx = cbase + rel
                if y < floor_y:
                    # Tail is already opacity 15 / light 0. Stop so an unblocked
                    # column is not treated as open sky below the flood floor.
                    source = False
                    break
                si = (y >> 4) - MIN_SECTION_Y
                if not (0 <= si < nsec):
                    opacity[idx] = 0
                    light[idx] = 15
                    prev = 15
                    ff = y
                    continue
                sid = block_secs[si][((y & 15) << 8) | col_in_sec]
                info = table[sid] if 0 <= sid < ntable else 0
                op = info & 15
                # Bit 4 stored on the cell: passable, but do not emit downward.
                blocked_down = info & 16
                if op >= 15:
                    opacity[idx] = 15
                    light[idx] = 0
                    source = False
                    prev = 0
                    continue
                opacity[idx] = blocked_down
                if source and op == 0 and not blocked_down:
                    light[idx] = 15
                    prev = 15
                    ff = y
                    continue
                arrived = 15 if source else prev
                source = False
                if arrived <= 0:
                    light[idx] = 0
                    prev = 0
                    continue
                # Sun into an opacity-0 occluder stays 15; every other step costs
                # max(1, opacity). The bottom face then ends the column.
                if not (op == 0 and blocked_down and arrived == 15):
                    arrived -= 1 if op <= 1 else op
                    if arrived < 0:
                        arrived = 0
                light[idx] = arrived
                if arrived == 15:
                    ff = y
                prev = 0 if blocked_down else arrived
            if source:
                ff = MIN_Y
            fifteen[col] = ff

    # Seed only the vertical frontier: the bottom of each level-15 run, the
    # attenuated tail under it, and columns whose sun height differs from a
    # neighbor. Scanning every lit sky cell is most of the cost and almost
    # none of those cells can raise a neighbor.
    queued = bytearray(total)
    q: deque[int] = deque()

    def _enqueue(i: int) -> None:
        if queued[i] or light[i] <= 1 or (opacity[i] & 15) >= 15:
            return
        queued[i] = 1
        q.append(i)

    for col in range(ncolumns):
        ff = fifteen[col]
        cbase = col * height
        if y_lo <= ff <= y_hi:
            _enqueue(cbase + (ff - y_lo))
        y = ff - 1
        if y > y_hi:
            y = y_hi
        while y >= y_lo:
            i = cbase + (y - y_lo)
            if light[i] <= 1:
                break
            _enqueue(i)
            y -= 1
        for nc in (plus_x[col], plus_z[col]):
            if nc < 0:
                continue
            nff = fifteen[nc]
            if ff <= nff:
                lower, higher, src = ff, nff, col
            else:
                lower, higher, src = nff, ff, nc
            y0 = y_lo if lower < y_lo else lower
            y1 = y_hi if higher - 1 > y_hi else higher - 1
            if y0 > y1:
                continue
            sbase = src * height
            rel = y0 - y_lo
            last = y1 - y_lo
            while rel <= last:
                _enqueue(sbase + rel)
                rel += 1

    while q:
        i = q.popleft()
        queued[i] = 0
        lv = light[i]
        if lv <= 1:
            continue
        col = i // height
        rel = i - col * height
        spread = lv - 1
        no_down = opacity[i] & 16
        if rel > 0 and not no_down:
            nidx = i - 1
            if (opacity[nidx] & 15) < 15 and spread > light[nidx]:
                light[nidx] = spread
                if spread > 1 and not queued[nidx]:
                    queued[nidx] = 1
                    q.append(nidx)
        if rel + 1 < height:
            nidx = i + 1
            if (opacity[nidx] & 15) < 15 and spread > light[nidx]:
                light[nidx] = spread
                if spread > 1 and not queued[nidx]:
                    queued[nidx] = 1
                    q.append(nidx)
        for nc in (plus_x[col], minus_x[col], plus_z[col], minus_z[col]):
            if nc < 0:
                continue
            nidx = nc * height + rel
            if (opacity[nidx] & 15) < 15 and spread > light[nidx]:
                light[nidx] = spread
                if spread > 1 and not queued[nidx]:
                    queued[nidx] = 1
                    q.append(nidx)

    for key, ci in chunk_index.items():
        _nl, top_si = geo[key]
        nsec = len(columns[key])
        light_hi = _section_span(top_si, nsec)
        sky: dict[int, bytes] = {}
        if light_hi >= 0:
            chunk_base = ci * 256
            for si in range(light_hi + 1):
                sy = MIN_SECTION_Y + si
                sky[sy] = _pack_band_section(sy, chunk_base, light, fifteen, y_lo, y_hi, height)
        out[key] = sky
    return out


def _heightmaps_and_skylight(sections: list[tuple[list[int], list[int]]]):
    """Heightmap stored-values (256, index z*16+x) and SkyLight bytes keyed by section Y.

    Single-chunk entry point. World writes use ``_prepare_chunk_light`` so the
    flood crosses chunk borders. ``sections[0]`` is section Y ``MIN_SECTION_Y``.
    """
    hm, nl_top, top_si = _column_geometry(sections)
    sky = _compute_skylight({(0, 0): sections}, {(0, 0): (nl_top, top_si)})[(0, 0)]
    return hm, sky


def _prepare_chunk_light(columns: dict[tuple[int, int], list]):
    """Heightmaps and cross-chunk sky light for every loaded column."""
    geo_nl: dict[tuple[int, int], tuple[list[int], int]] = {}
    hms: dict[tuple[int, int], dict] = {}
    for key, sections in columns.items():
        hm, nl_top, top_si = _column_geometry(sections)
        hms[key] = hm
        geo_nl[key] = (nl_top, top_si)
    skies = _compute_skylight(columns, geo_nl)
    return {key: (hms[key], skies[key]) for key in columns}


def section_to_nbt(section_y: int, block_ids: list[int], biome_ids: list[int], skylight: bytes | None = None):
    """Build one Anvil section compound (as plain dict for nbtlib)."""
    from . import nbt_mini as nbtlib
    from .nbt_mini import Byte, ByteArray, Compound, Int, List, LongArray, String

    # Block palette
    uniq_blocks: list[int] = []
    index_of: dict[int, int] = {}
    for sid in block_ids:
        if sid not in index_of:
            index_of[sid] = len(uniq_blocks)
            uniq_blocks.append(sid)
    block_indices = [index_of[s] for s in block_ids]
    block_palette = List[Compound]([Compound(state_id_to_nbt(s)) for s in uniq_blocks])
    bs: dict[str, Any] = {"palette": block_palette}
    bb = _palette_bits(len(uniq_blocks), 4)
    if bb > 0:
        longs = write_packed_ids(block_indices, bb)
        # store as signed i64
        signed = [l - 0x10000000000000000 if l >= 0x8000000000000000 else l for l in longs]
        bs["data"] = LongArray(signed)

    # Biome palette
    uniq_biomes: list[int] = []
    bindex: dict[int, int] = {}
    for bid in biome_ids:
        if bid not in bindex:
            bindex[bid] = len(uniq_biomes)
            uniq_biomes.append(bid)
    biome_indices = [bindex[b] for b in biome_ids]
    biome_palette = List[String]([String(biome_id_to_name(b)) for b in uniq_biomes])
    biomes_tag: dict[str, Any] = {"palette": biome_palette}
    bib = _palette_bits(len(uniq_biomes), 1)
    if bib > 0:
        longs = write_packed_ids(biome_indices, bib)
        signed = [l - 0x10000000000000000 if l >= 0x8000000000000000 else l for l in longs]
        biomes_tag["data"] = LongArray(signed)

    tag = {
        "Y": Byte(section_y),
        "block_states": Compound(bs),
        "biomes": Compound(biomes_tag),
    }
    if skylight is not None:
        tag["SkyLight"] = ByteArray.from_bytes(skylight)
    return Compound(tag)


def column_to_chunk_nbt(
    cx: int,
    cz: int,
    sections: list[tuple[list[int], list[int]]],
    hm_vals: dict | None = None,
    sky: dict | None = None,
):
    from . import nbt_mini as nbtlib
    from .nbt_mini import Byte, Compound, Int, List, Long, LongArray, String

    if hm_vals is None or sky is None:
        hm_vals, sky = _heightmaps_and_skylight(sections)
    sec_tags = []
    for i, (blocks, biomes) in enumerate(sections):
        sy = MIN_SECTION_Y + i
        # skip empty air-only sections? WI handles empty; still include for height consistency
        sec_tags.append(section_to_nbt(sy, blocks, biomes, sky.get(sy)))

    hm_tag = {}
    for name in HEIGHTMAP_NAMES:
        longs = write_packed_ids(hm_vals[name], HEIGHTMAP_BITS)
        signed = [v - 0x10000000000000000 if v >= 0x8000000000000000 else v for v in longs]
        hm_tag[name] = LongArray(signed)

    root = Compound({
        "DataVersion": Int(DATA_VERSION),
        "xPos": Int(cx),
        "zPos": Int(cz),
        "yPos": Int(MIN_SECTION_Y),
        "Status": String("minecraft:full"),
        "LastUpdate": Long(0),
        "InhabitedTime": Long(0),
        "isLightOn": Byte(1),
        "sections": List[Compound](sec_tags),
        "block_entities": List[Compound]([]),
        "Heightmaps": Compound(hm_tag),
        "structures": Compound({"References": Compound({}), "starts": Compound({})}),
    })
    return nbtlib.File(root)


def chunk_nbt_to_compressed(nbt_file) -> bytes:
    import io
    bio = io.BytesIO()
    nbt_file.write(bio, byteorder="big")
    return zlib.compress(bio.getvalue())


@dataclass
class ChunkStore:
    columns: dict[tuple[int, int], list[tuple[list[int], list[int]]]] = field(default_factory=dict)
    protocol: int = 767
    packets_seen: int = 0
    map_chunks: int = 0
    # (t_ms, x, y, z, old_state, new_state) for block updates after initial chunk load
    changes: list = field(default_factory=list)
    _t: int = 0

    def handle_packet(self, packet_id: int, payload: bytes, timestamp_ms: int = 0) -> None:
        self.packets_seen += 1
        self._t = timestamp_ms
        if packet_id == MAP_CHUNK_ID:
            cx, cz, chunk_data, _ = parse_map_chunk_payload(payload)
            sections = read_chunk_data(chunk_data)
            self.columns[(cx, cz)] = sections
            self.map_chunks += 1
        elif packet_id == UNLOAD_CHUNK_ID:
            buf = Buf(payload)
            cx, cz = buf.i32(), buf.i32()
            self.columns.pop((cx, cz), None)
        elif packet_id == BLOCK_CHANGE_ID:
            self._block_change(payload)
        elif packet_id == MULTI_BLOCK_CHANGE_ID:
            self._multi_block_change(payload)

    def _block_change(self, payload: bytes) -> None:
        buf = Buf(payload)
        pos = buf.i64()  # Position packed
        state = buf.varint()
        x = pos >> 38
        y = pos << 52 >> 52
        z = pos << 26 >> 38
        # sign extend helpers for 26/12/26 bit fields — use python style
        x = pos >> 38
        if x >= 1 << 25:
            x -= 1 << 26
        z = (pos >> 12) & 0x3FFFFFF
        if z >= 1 << 25:
            z -= 1 << 26
        y = pos & 0xFFF
        if y >= 1 << 11:
            y -= 1 << 12
        self.set_block(x, y, z, state)

    def _multi_block_change(self, payload: bytes) -> None:
        # 1.21: section position as i64 (or varlong?), then varint count, then varlongs
        buf = Buf(payload)
        section_pos = buf.i64()
        # section X,Y,Z packed: (x & 0x3FFFFF) << 42 | (z & 0x3FFFFF) << 20 | (y & 0xFFFFF)
        u = section_pos & 0xFFFFFFFFFFFFFFFF
        sx = u >> 42
        sz = (u >> 20) & 0x3FFFFF
        sy = u & 0xFFFFF
        if sx >= 1 << 21:
            sx -= 1 << 22
        if sz >= 1 << 21:
            sz -= 1 << 22
        if sy >= 1 << 19:
            sy -= 1 << 20
        n = buf.varint()
        for _ in range(n):
            v = buf.varint()  # actually varlong in some versions — use varint for small
            # In 1.20+: VarLong: state << 12 | pos
            local = v & 0xFFF
            state = v >> 12
            # 1.16+: (stateId << 12) | (localX << 8 | localZ << 4 | localY)
            lx = (local >> 8) & 0xF
            lz = (local >> 4) & 0xF
            ly = local & 0xF
            self.set_block(sx * 16 + lx, sy * 16 + ly, sz * 16 + lz, state)

    def set_block(self, x: int, y: int, z: int, state_id: int) -> None:
        cx, cz = x >> 4, z >> 4
        col = self.columns.get((cx, cz))
        if not col:
            return
        sy = y >> 4
        si = sy - MIN_SECTION_Y
        if not (0 <= si < NUM_SECTIONS):
            return
        blocks, biomes = col[si]
        lx, ly, lz = x & 15, y & 15, z & 15
        idx = (ly << 8) | (lz << 4) | lx
        old = blocks[idx]
        if old != state_id:
            self.changes.append((self._t, x, y, z, old, state_id))
        blocks[idx] = state_id

    def get_block(self, x: int, y: int, z: int) -> int:
        col = self.columns.get((x >> 4, z >> 4))
        si = (y >> 4) - MIN_SECTION_Y
        if not col or not (0 <= si < NUM_SECTIONS):
            return 0
        return col[si][0][((y & 15) << 8) | ((z & 15) << 4) | (x & 15)]

    def block_segments(self):
        """Per changed position: list of (state, t_from, t_to|None) covering the whole recording.
        t_from=0 is the initially loaded state."""
        per: dict = {}
        for t, x, y, z, old, new in self.changes:
            per.setdefault((x, y, z), []).append((t, old, new))
        out = {}
        for pos, evs in per.items():
            segs = []
            cur, t0 = evs[0][1], 0
            for t, _old, new in evs:
                segs.append((cur, t0, t))
                cur, t0 = new, t
            segs.append((cur, t0, None))
            out[pos] = segs
        return out

    def write_anvil_world(self, dest_dir: Path) -> int:
        """Write level.dat + region/*.mca. Returns number of chunks written."""
        from .anvil import write_level_dat, write_region_from_chunk_nbts

        dest_dir = Path(dest_dir)
        write_level_dat(dest_dir, data_version=DATA_VERSION, name="replay_import")
        # group by region
        by_region: dict[tuple[int, int], dict[tuple[int, int], bytes]] = {}
        prepared = _prepare_chunk_light(self.columns)
        for (cx, cz), sections in self.columns.items():
            rx, rz = cx >> 5, cz >> 5
            lcx, lcz = cx & 31, cz & 31
            hm_vals, sky = prepared[(cx, cz)]
            nbt = column_to_chunk_nbt(cx, cz, sections, hm_vals, sky)
            by_region.setdefault((rx, rz), {})[(lcx, lcz)] = chunk_nbt_to_compressed(nbt)
        for (rx, rz), chunks in by_region.items():
            path = dest_dir / "region" / f"r.{rx}.{rz}.mca"
            write_region_from_chunk_nbts(path, chunks)
        return len(self.columns)


# --- encode helpers for synthetic tests (Anvil → network chunkData) ---

def encode_paletted_container(global_ids: list[int], max_indirect: int, min_bits: int) -> bytes:
    uniq: list[int] = []
    index_of: dict[int, int] = {}
    for g in global_ids:
        if g not in index_of:
            index_of[g] = len(uniq)
            uniq.append(g)
    out = bytearray()
    if len(uniq) == 1:
        out.append(0)
        out += write_varint(uniq[0])
        out.append(0)
        return bytes(out)
    bits = max(min_bits, math.ceil(math.log2(len(uniq))))
    if bits > max_indirect:
        # direct
        bits = 15 if max_indirect >= 8 else 7
        out.append(bits)
        longs = write_packed_ids(global_ids, bits)
        out += write_varint(len(longs))
        for L in longs:
            out += struct.pack(">q", L if L < 0x8000000000000000 else L - 0x10000000000000000)
        return bytes(out)
    out.append(bits)
    out += write_varint(len(uniq))
    for u in uniq:
        out += write_varint(u)
    indices = [index_of[g] for g in global_ids]
    longs = write_packed_ids(indices, bits)
    out += write_varint(len(longs))
    for L in longs:
        out += struct.pack(">q", L if L < 0x8000000000000000 else L - 0x10000000000000000)
    return bytes(out)


def encode_chunk_data(sections: list[tuple[list[int], list[int]]]) -> bytes:
    out = bytearray()
    for blocks, biomes in sections:
        non_air = sum(1 for s in blocks if s != 0)
        out += struct.pack(">h", non_air)
        out += encode_paletted_container(blocks, MAX_INDIRECT_BLOCK_BITS, 4)
        out += encode_paletted_container(biomes, MAX_INDIRECT_BIOME_BITS, 1)
    return bytes(out)


def build_map_chunk_packet(cx: int, cz: int, sections: list[tuple[list[int], list[int]]]) -> bytes:
    """Full map_chunk payload (no packet id). Empty heightmaps as network NBT."""
    import io
    from . import nbt_mini as nbtlib
    from .nbt_mini import Compound

    chunk_data = encode_chunk_data(sections)
    # Classic File writes 0A 00 00 <end>; network NBT drops the empty name → 0A 00
    hm = nbtlib.File(Compound({}))
    bio = io.BytesIO()
    hm.write(bio, byteorder="big")
    classic = bio.getvalue()
    if classic[:3] != b"\x0a\x00\x00":
        raise RuntimeError(f"unexpected classic NBT header {classic[:4]!r}")
    hm_bytes = b"\x0a" + classic[3:]  # strip empty root name
    out = bytearray()
    out += struct.pack(">ii", cx, cz)
    out += hm_bytes
    out += write_varint(len(chunk_data))
    out += chunk_data
    out += write_varint(0)  # block entities
    for _ in range(4):
        out += write_varint(0)  # light bit masks (empty long arrays)
    out += write_varint(0)  # skyLight arrays
    out += write_varint(0)  # blockLight arrays
    return bytes(out)
