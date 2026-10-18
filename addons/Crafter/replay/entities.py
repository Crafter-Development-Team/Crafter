"""Entity / player tracks from 1.21.1 (protocol 767) play packets.

Produces per-entity keyframes (position, body yaw, head yaw, pitch), equipment,
player profile (name + skin URL) and swing events. Coordinates are MC world space.
"""
from __future__ import annotations

import base64
import json
import struct
from dataclasses import dataclass, field
from pathlib import Path

from .chunks import Buf

SPAWN_ENTITY = 0x01
ANIMATION = 0x03
REL_MOVE = 0x2E
MOVE_LOOK = 0x2F
LOOK = 0x30
PLAYER_INFO_UPDATE = 0x3E
REMOVE_ENTITIES = 0x42
HEAD_ROT = 0x48
EQUIPMENT = 0x5B
TELEPORT = 0x70

EQUIP_SLOTS = {0: "mainhand", 1: "offhand", 2: "feet", 3: "legs", 4: "chest", 5: "head", 6: "body"}

_REG = None


def registries() -> dict:
    global _REG
    if _REG is None:
        _REG = json.loads((Path(__file__).parent / "registries-1.21.1.json").read_text())
    return _REG


def angle(b: int) -> float:
    return (b if b < 128 else b - 256) * 360.0 / 256.0


@dataclass
class Key:
    t: int
    x: float
    y: float
    z: float
    yaw: float
    pitch: float
    head_yaw: float
    on_ground: bool = True


@dataclass
class EntityTrack:
    eid: int
    uuid: str
    type_id: int
    type_name: str
    keys: list[Key] = field(default_factory=list)
    equipment: dict[str, list[tuple[int, str | None]]] = field(default_factory=dict)  # slot -> [(t, item)]
    swings: list[int] = field(default_factory=list)
    spawn_t: int = 0
    remove_t: int | None = None

    def last(self) -> Key:
        return self.keys[-1]

    def push(self, t, **kw):
        k = self.last()
        nk = Key(t, kw.get("x", k.x), kw.get("y", k.y), kw.get("z", k.z), kw.get("yaw", k.yaw),
                 kw.get("pitch", k.pitch), kw.get("head_yaw", k.head_yaw), kw.get("on_ground", k.on_ground))
        if self.keys and self.keys[-1].t == t:
            self.keys[-1] = nk
        else:
            self.keys.append(nk)


@dataclass
class PlayerProfile:
    uuid: str
    name: str
    skin_url: str | None = None
    slim: bool = False


def _read_string(b: Buf) -> str:
    n = b.varint()
    return b.bytes(n).decode("utf-8", "replace")


def _read_slot(b: Buf) -> str | None:
    """1.21.1 Slot: varint count; if >0: varint item id, varint add, varint remove, components...
    Returns item name, or raises ValueError if components present (can't skip generically)."""
    count = b.varint()
    if count <= 0:
        return None
    item = b.varint()
    add = b.varint()
    rem = b.varint()
    name = registries()["items"].get(str(item), f"item#{item}")
    if add or rem:
        raise ValueError(("components", name))
    return name


@dataclass
class EntityStore:
    tracks: dict[int, EntityTrack] = field(default_factory=dict)
    profiles: dict[str, PlayerProfile] = field(default_factory=dict)
    recorder_uuids: set = field(default_factory=set)

    def handle(self, pid: int, payload: bytes, t: int) -> None:
        try:
            self._handle(pid, payload, t)
        except Exception:
            pass

    def _handle(self, pid, payload, t):
        b = Buf(payload)
        if pid == SPAWN_ENTITY:
            eid = b.varint(); uuid = b.bytes(16).hex(); typ = b.varint()
            x, y, z = struct.unpack(">ddd", b.bytes(24))
            pitch = angle(b.u8()); yaw = angle(b.u8()); head = angle(b.u8())
            name = registries()["entity_types"].get(str(typ), f"entity#{typ}")
            tr = EntityTrack(eid, uuid, typ, name, spawn_t=t)
            tr.keys.append(Key(t, x, y, z, yaw, pitch, head))
            self.tracks[eid] = tr
            return
        if pid == PLAYER_INFO_UPDATE:
            actions = b.u8(); n = b.varint()
            for _ in range(n):
                uuid = b.bytes(16).hex()
                if not actions & 0x01:
                    break
                name = _read_string(b)
                prof = PlayerProfile(uuid, name)
                for _p in range(b.varint()):
                    pn = _read_string(b); pv = _read_string(b)
                    if b.u8():
                        _read_string(b)
                    if pn == "textures":
                        try:
                            tex = json.loads(base64.b64decode(pv))["textures"].get("SKIN", {})
                            prof.skin_url = tex.get("url")
                            prof.slim = (tex.get("metadata") or {}).get("model") == "slim"
                        except Exception:
                            pass
                self.profiles[uuid] = prof
                if name.startswith("-") or "Recorder" in name:
                    self.recorder_uuids.add(uuid)
                if n > 1:
                    break  # following action data not parsed generically
            return
        eid_actions = (REL_MOVE, MOVE_LOOK, LOOK, HEAD_ROT, EQUIPMENT, TELEPORT, ANIMATION)
        if pid in eid_actions:
            eid = b.varint()
            tr = self.tracks.get(eid)
            if not tr:
                return
            k = tr.last()
            if pid == REL_MOVE:
                dx, dy, dz = struct.unpack(">hhh", b.bytes(6)); og = bool(b.u8())
                tr.push(t, x=k.x + dx / 4096, y=k.y + dy / 4096, z=k.z + dz / 4096, on_ground=og)
            elif pid == MOVE_LOOK:
                dx, dy, dz = struct.unpack(">hhh", b.bytes(6)); yaw = angle(b.u8()); pitch = angle(b.u8()); og = bool(b.u8())
                tr.push(t, x=k.x + dx / 4096, y=k.y + dy / 4096, z=k.z + dz / 4096, yaw=yaw, pitch=pitch, on_ground=og)
            elif pid == LOOK:
                yaw = angle(b.u8()); pitch = angle(b.u8())
                tr.push(t, yaw=yaw, pitch=pitch)
            elif pid == HEAD_ROT:
                tr.push(t, head_yaw=angle(b.u8()))
            elif pid == TELEPORT:
                x, y, z = struct.unpack(">ddd", b.bytes(24)); yaw = angle(b.u8()); pitch = angle(b.u8()); og = bool(b.u8())
                tr.push(t, x=x, y=y, z=z, yaw=yaw, pitch=pitch, on_ground=og)
            elif pid == ANIMATION:
                if b.u8() in (0, 3):
                    tr.swings.append(t)
            elif pid == EQUIPMENT:
                while True:
                    s = b.u8()
                    slot = EQUIP_SLOTS.get(s & 0x7F, str(s & 0x7F))
                    try:
                        item = _read_slot(b)
                    except ValueError as e:
                        item = e.args[0][1]
                        tr.equipment.setdefault(slot, []).append((t, item))
                        break
                    tr.equipment.setdefault(slot, []).append((t, item))
                    if not s & 0x80:
                        break
            return
        if pid == REMOVE_ENTITIES:
            for _ in range(b.varint()):
                eid = b.varint()
                if eid in self.tracks:
                    self.tracks[eid].remove_t = t

    def visible_tracks(self) -> list[EntityTrack]:
        return [tr for tr in self.tracks.values() if tr.uuid not in self.recorder_uuids]
