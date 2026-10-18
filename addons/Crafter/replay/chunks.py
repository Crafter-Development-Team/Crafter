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

import json
import math
import struct
import zlib
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .varint import read_varint, write_varint

# 1.21.1 overworld
MIN_SECTION_Y = -4
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

_STATE_TABLE: list[dict | None] | None = None
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
        raw = json.loads(_tables_path().read_text())
        _STATE_TABLE = raw["blockStates"]
        _BIOME_TABLE = {str(k): v for k, v in raw["biomes"].items()}
    return _STATE_TABLE, _BIOME_TABLE


def state_id_to_nbt(state_id: int) -> dict:
    """Return nbtlib-tag dict for a block_states palette entry."""
    from nbtlib.tag import Compound, String

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
        import nbtlib

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


def section_to_nbt(section_y: int, block_ids: list[int], biome_ids: list[int]):
    """Build one Anvil section compound (as plain dict for nbtlib)."""
    import nbtlib
    from nbtlib.tag import Byte, Compound, Int, List, LongArray, String

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

    return Compound({
        "Y": Byte(section_y),
        "block_states": Compound(bs),
        "biomes": Compound(biomes_tag),
    })


def column_to_chunk_nbt(cx: int, cz: int, sections: list[tuple[list[int], list[int]]]):
    import nbtlib
    from nbtlib.tag import Byte, Compound, Int, List, Long, String

    sec_tags = []
    for i, (blocks, biomes) in enumerate(sections):
        sy = MIN_SECTION_Y + i
        # skip empty air-only sections? WI handles empty; still include for height consistency
        sec_tags.append(section_to_nbt(sy, blocks, biomes))

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
        "Heightmaps": Compound({}),
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

    def handle_packet(self, packet_id: int, payload: bytes, timestamp_ms: int = 0) -> None:
        self.packets_seen += 1
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
        sx = section_pos >> 42
        if sx >= 1 << 21:
            sx -= 1 << 22
        sz = (section_pos << 22) >> 42
        if sz >= 1 << 21:
            sz -= 1 << 22
        sy = section_pos << 44 >> 44  # 20 bits signed
        n = buf.varint()
        for _ in range(n):
            v = buf.varint()  # actually varlong in some versions — use varint for small
            # encode: blockStateId << 12 | localPos
            # localPos = x | (z << 4) | (y << 8)
            # In 1.20+: VarLong: state << 12 | pos
            local = v & 0xFFF
            state = v >> 12
            lx = local & 0xF
            lz = (local >> 4) & 0xF
            ly = (local >> 8) & 0xF
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
        blocks[idx] = state_id

    def write_anvil_world(self, dest_dir: Path) -> int:
        """Write level.dat + region/*.mca. Returns number of chunks written."""
        from .anvil import write_level_dat, write_region_from_chunk_nbts

        dest_dir = Path(dest_dir)
        write_level_dat(dest_dir, data_version=DATA_VERSION, name="replay_import")
        # group by region
        by_region: dict[tuple[int, int], dict[tuple[int, int], bytes]] = {}
        for (cx, cz), sections in self.columns.items():
            rx, rz = cx >> 5, cz >> 5
            lcx, lcz = cx & 31, cz & 31
            nbt = column_to_chunk_nbt(cx, cz, sections)
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
    import nbtlib
    from nbtlib.tag import Compound

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
