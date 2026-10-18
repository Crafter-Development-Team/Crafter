"""Open ReplayMod .mcpr (ZIP) and iterate recording.tmcpr packets."""
from __future__ import annotations

import json
import struct
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator


@dataclass
class ReplayMeta:
    raw: dict
    mcversion: str
    protocol: int | None
    duration_ms: int
    file_format_version: int
    self_id: int
    players: list[str]

    @classmethod
    def from_dict(cls, d: dict) -> "ReplayMeta":
        return cls(
            raw=d,
            mcversion=str(d.get("mcversion") or d.get("mcVersion") or ""),
            protocol=d.get("protocol"),
            duration_ms=int(d.get("duration") or 0),
            file_format_version=int(d.get("fileFormatVersion") or 0),
            self_id=int(d.get("selfId") if d.get("selfId") is not None else -1),
            players=list(d.get("players") or []),
        )


@dataclass
class TimedPacket:
    timestamp_ms: int
    packet_id: int
    payload: bytes  # after packet id varint


def open_mcpr(path: str | Path) -> zipfile.ZipFile:
    return zipfile.ZipFile(path, "r")


def read_meta(zf: zipfile.ZipFile) -> ReplayMeta:
    with zf.open("metaData.json") as f:
        return ReplayMeta.from_dict(json.load(f))


def read_timelines_raw(zf: zipfile.ZipFile) -> dict | None:
    try:
        with zf.open("timelines.json") as f:
            return json.load(f)
    except KeyError:
        return None


def iter_tmcpr(data: bytes) -> Iterator[tuple[int, bytes]]:
    """Yield (timestamp_ms, raw_packet_including_id_varint). Big-endian."""
    mv = memoryview(data)
    off = 0
    n = len(mv)
    while off + 8 <= n:
        ts, length = struct.unpack_from(">ii", mv, off)
        off += 8
        if length < 0 or off + length > n:
            raise ValueError(f"bad tmcpr length={length} at {off-8}")
        yield ts, bytes(mv[off : off + length])
        off += length


def iter_packets(zf: zipfile.ZipFile):
    from .varint import read_varint

    with zf.open("recording.tmcpr") as f:
        data = f.read()
    for ts, raw in iter_tmcpr(data):
        pid, poff = read_varint(raw, 0)
        yield TimedPacket(ts, pid, raw[poff:])


def iter_play_packets(zf: zipfile.ZipFile):
    """Yield only play-state packets. ServerReplay/ReplayMod v14 recordings begin with
    login success + configuration packets (registry_data 0x07 ... finish_configuration 0x03)."""
    pkts = list(iter_packets(zf))
    start = 0
    for i, p in enumerate(pkts[:400]):
        if p.packet_id == 0x03 and len(p.payload) == 0 and any(q.packet_id == 0x07 for q in pkts[:i]):
            start = i + 1
            break
    state_play = True
    for p in pkts[start:]:
        if state_play and p.packet_id == 0x69 and len(p.payload) == 0:  # start_configuration
            state_play = False
            continue
        if not state_play:
            if p.packet_id == 0x03 and len(p.payload) == 0:
                state_play = True
            continue
        yield p
