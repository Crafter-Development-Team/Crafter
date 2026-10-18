"""Minimal Anvil region writer + stub level.dat for WI consumption."""
from __future__ import annotations

import struct
import zlib
from pathlib import Path

import nbtlib
from nbtlib.tag import Compound, Int, Long, String, Byte, Float, List


def write_level_dat(world_dir: Path, *, data_version: int = 3955, name: str = "replay_temp") -> None:
    """Write a minimal level.dat WI can open (DataVersion 3955 = 1.21.1)."""
    world_dir = Path(world_dir)
    world_dir.mkdir(parents=True, exist_ok=True)
    root = Compound({
        "Data": Compound({
            "version": Int(19133),
            "DataVersion": Int(data_version),
            "LevelName": String(name),
            "SpawnX": Int(0),
            "SpawnY": Int(64),
            "SpawnZ": Int(0),
            "GameType": Int(1),
            "LastPlayed": Long(0),
            "Difficulty": Byte(1),
            "hardcore": Byte(0),
            "allowCommands": Byte(1),
            "initialized": Byte(1),
            "GameRules": Compound({}),
            "WorldGenSettings": Compound({
                "seed": Long(0),
                "generate_features": Byte(0),
                "bonus_chest": Byte(0),
                "dimensions": Compound({}),
            }),
        })
    })
    nbtlib.File(root).save(world_dir / "level.dat", gzipped=True)


def write_region_from_chunk_nbts(
    region_path: Path,
    chunks: dict[tuple[int, int], bytes],
) -> None:
    """Write r.X.Z.mca from mapping of (local_cx, local_cz) -> compressed chunk NBT bytes.

    local coords are 0..31 within the region. `chunks` values are zlib-compressed
    NBT payloads (compression type 2), as stored in modern Anvil.
    """
    region_path = Path(region_path)
    region_path.parent.mkdir(parents=True, exist_ok=True)
    locations = bytearray(4096)
    timestamps = bytearray(4096)
    # sectors start after 8 KiB header
    data = bytearray()
    sector = 2
    for (lcx, lcz), payload in chunks.items():
        if not (0 <= lcx < 32 and 0 <= lcz < 32):
            raise ValueError("local chunk out of range")
        index = (lcx + lcz * 32) * 4
        body = struct.pack(">iB", len(payload) + 1, 2) + payload  # len includes comp type byte
        # pad to 4k
        pad = (4096 - (len(body) % 4096)) % 4096
        body = body + b"\x00" * pad
        nsectors = len(body) // 4096
        locations[index : index + 3] = struct.pack(">I", sector)[1:4]
        locations[index + 3] = nsectors
        timestamps[index : index + 4] = struct.pack(">I", 0)
        data.extend(body)
        sector += nsectors
    region_path.write_bytes(bytes(locations) + bytes(timestamps) + bytes(data))


def copy_region_files(src_region_dir: Path, dst_world: Path) -> int:
    """Copy existing region/*.mca into dst_world/region. Returns file count."""
    src = Path(src_region_dir)
    dst = Path(dst_world) / "region"
    dst.mkdir(parents=True, exist_ok=True)
    n = 0
    for f in src.glob("r.*.*.mca"):
        (dst / f.name).write_bytes(f.read_bytes())
        n += 1
    return n
