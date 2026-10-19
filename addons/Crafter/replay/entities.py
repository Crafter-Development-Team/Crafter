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
SET_ENTITY_DATA = 0x58  # ClientboundSetEntityDataPacket (protocol 767)

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
    # set_entity_data: index -> [(t_ms, value)]
    meta: dict[int, list[tuple[int, object]]] = field(default_factory=dict)

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

    def put_meta(self, t: int, index: int, value: object) -> None:
        hist = self.meta.setdefault(index, [])
        if hist and hist[-1][0] == t:
            hist[-1] = (t, value)
        else:
            hist.append((t, value))

    def meta_at(self, index: int, t: int | None = None, default=None):
        hist = self.meta.get(index)
        if not hist:
            return default
        if t is None:
            return hist[0][1]
        val = hist[0][1]
        for tt, v in hist:
            if tt > t:
                break
            val = v
        return val

    def flags_byte(self, t: int | None = None) -> int:
        return int(self.meta_at(0, t, 0) or 0)

    def invisible(self, t: int | None = None) -> bool:
        return bool(self.flags_byte(t) & 0x20)

    def baby(self, t: int | None = None) -> bool:
        # AgeableMob DATA_BABY_ID = 16 (1.21.1); Zombie uses 16 too
        return bool(self.meta_at(16, t, False))

    def cat_variant_id(self, t: int | None = None) -> int | None:
        v = self.meta_at(19, t, None)
        return int(v) if isinstance(v, int) else None

    def sheep_wool(self, t: int | None = None) -> int:
        return int(self.meta_at(17, t, 0) or 0)

    def sheep_color(self, t: int | None = None) -> int:
        return self.sheep_wool(t) & 0x0F

    def sheep_sheared(self, t: int | None = None) -> bool:
        return bool(self.sheep_wool(t) & 0x10)

    def villager_data(self, t: int | None = None):
        v = self.meta_at(18, t, None)
        if isinstance(v, tuple) and len(v) == 3:
            return v
        return None

    def player_skin_parts(self, t: int | None = None) -> int:
        # Player DATA_PLAYER_MODE_CUSTOMISATION = 17
        return int(self.meta_at(17, t, 0x7E) or 0x7E)  # default: all but cape


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



# --- set_entity_data (1.21.1 / protocol 767) ---------------------------------
# EntityDataSerializers registration order (ajy.<clinit>), NOT field decl order:
# 0 BYTE, 1 INT(varint), 2 LONG(varlong), 3 FLOAT, 4 STRING, 5 COMPONENT,
# 6 OPTIONAL_COMPONENT, 7 ITEM_STACK, 8 BOOLEAN, 9 ROTATIONS, 10 BLOCK_POS,
# 11 OPTIONAL_BLOCK_POS, 12 DIRECTION, 13 OPTIONAL_UUID, 14 BLOCK_STATE,
# 15 OPTIONAL_BLOCK_STATE, 16 COMPOUND_TAG, 17 PARTICLE, 18 PARTICLES,
# 19 VILLAGER_DATA, 20 OPTIONAL_UNSIGNED_INT, 21 POSE, 22 CAT_VARIANT,
# 23 WOLF_VARIANT, 24 FROG_VARIANT, 25 OPTIONAL_GLOBAL_POS, 26 PAINTING_VARIANT,
# 27 SNIFFER_STATE, 28 ARMADILLO_STATE, 29 VECTOR3, 30 QUATERNION

CAT_VARIANTS = (
    "tabby", "black", "red", "siamese", "british_shorthair", "calico",
    "persian", "ragdoll", "white", "jellie", "all_black",
)
VILLAGER_TYPES = ("desert", "jungle", "plains", "savanna", "snow", "swamp", "taiga")
VILLAGER_PROFESSIONS = (
    "none", "armorer", "butcher", "cartographer", "cleric", "farmer", "fisherman",
    "fletcher", "leatherworker", "librarian", "mason", "nitwit", "shepherd",
    "toolsmith", "weaponsmith",
)
# DyeColor.getTextureDiffuseColor (1.21.1 ctor arg) as linear RGB floats
DYE_TEXTURE_DIFFUSE = (
    (0.976471, 1.000000, 0.996078),  # white
    (0.976471, 0.501961, 0.113725),  # orange
    (0.780392, 0.305882, 0.741176),  # magenta
    (0.227451, 0.701961, 0.854902),  # light_blue
    (0.996078, 0.847059, 0.239216),  # yellow
    (0.501961, 0.780392, 0.121569),  # lime
    (0.952941, 0.545098, 0.666667),  # pink
    (0.278431, 0.309804, 0.321569),  # gray
    (0.615686, 0.615686, 0.592157),  # light_gray
    (0.086275, 0.611765, 0.611765),  # cyan
    (0.537255, 0.196078, 0.721569),  # purple
    (0.235294, 0.266667, 0.666667),  # blue
    (0.513725, 0.329412, 0.196078),  # brown
    (0.368627, 0.486275, 0.086275),  # green
    (0.690196, 0.180392, 0.149020),  # red
    (0.113725, 0.113725, 0.129412),  # black
)


class MetaStop(Exception):
    """Stop reading further metadata entries in this packet (prior values kept)."""


def _read_varlong(b: Buf) -> int:
    n = 0
    shift = 0
    while True:
        by = b.u8()
        n |= (by & 0x7F) << shift
        if not by & 0x80:
            break
        shift += 7
        if shift > 63:
            raise ValueError("varlong too long")
    if n >= 1 << 63:
        n -= 1 << 64
    return n


def _skip_network_nbt(b: Buf) -> None:
    """Advance past a 1.20.2+ network NBT value (TAG_End or nameless compound)."""
    b.network_nbt()


def _skip_text_component(b: Buf) -> None:
    # 1.20.3+: Text Component is network NBT
    _skip_network_nbt(b)


def _read_block_pos(b: Buf) -> tuple[int, int, int]:
    v = struct.unpack(">q", b.bytes(8))[0]
    x = v >> 38
    y = (v << 52) >> 52
    z = (v << 26) >> 38
    if x >= 1 << 25:
        x -= 1 << 26
    if y >= 1 << 11:
        y -= 1 << 12
    if z >= 1 << 25:
        z -= 1 << 26
    return x, y, z


def _skip_particle(b: Buf) -> None:
    """Skip one Particle. SimpleParticleType has no extras; complex → MetaStop."""
    # particle type registry id
    b.varint()
    # Without a full particle-data table we cannot safely skip complex payloads.
    # LivingEntity effect particles (type PARTICLES list) are common; stopping keeps
    # prior entries. Callers that need only looks (cat/sheep/…) usually see those
    # before particle fields, so values we care about are already stored.
    raise MetaStop("particle payload")


def _read_meta_value(b: Buf, typ: int):
    """Read one EntityDataSerializer value by registration id. May raise MetaStop."""
    if typ == 0:  # BYTE
        return b.u8()
    if typ == 1:  # INT (VarInt)
        return b.varint()
    if typ == 2:  # LONG (VarLong)
        return _read_varlong(b)
    if typ == 3:  # FLOAT
        return struct.unpack(">f", b.bytes(4))[0]
    if typ == 4:  # STRING
        return _read_string(b)
    if typ == 5:  # COMPONENT
        _skip_text_component(b)
        return None
    if typ == 6:  # OPTIONAL_COMPONENT
        if b.u8():
            _skip_text_component(b)
            return True
        return None
    if typ == 7:  # ITEM_STACK
        try:
            return _read_slot(b)
        except ValueError as e:
            # components present — cannot skip; stop rest of packet
            raise MetaStop("item components") from e
    if typ == 8:  # BOOLEAN
        return bool(b.u8())
    if typ == 9:  # ROTATIONS
        return struct.unpack(">fff", b.bytes(12))
    if typ == 10:  # BLOCK_POS
        return _read_block_pos(b)
    if typ == 11:  # OPTIONAL_BLOCK_POS
        if b.u8():
            return _read_block_pos(b)
        return None
    if typ == 12:  # DIRECTION
        return b.varint()
    if typ == 13:  # OPTIONAL_UUID
        if b.u8():
            return b.bytes(16).hex()
        return None
    if typ == 14:  # BLOCK_STATE
        return b.varint()
    if typ == 15:  # OPTIONAL_BLOCK_STATE
        return b.varint()  # 0 = absent
    if typ == 16:  # COMPOUND_TAG
        _skip_network_nbt(b)
        return None
    if typ == 17:  # PARTICLE
        _skip_particle(b)
        return None
    if typ == 18:  # PARTICLES
        n = b.varint()
        for _ in range(n):
            _skip_particle(b)
        return None
    if typ == 19:  # VILLAGER_DATA
        return (b.varint(), b.varint(), b.varint())  # type, profession, level
    if typ == 20:  # OPTIONAL_UNSIGNED_INT
        v = b.varint()
        return None if v == 0 else v - 1
    if typ == 21:  # POSE
        return b.varint()
    if typ in (22, 23, 24):  # CAT / WOLF / FROG variant (Holder → registry VarInt id)
        return b.varint()
    if typ == 25:  # OPTIONAL_GLOBAL_POS
        if b.u8():
            _read_string(b)  # dimension id
            _read_block_pos(b)
            return True
        return None
    if typ == 26:  # PAINTING_VARIANT (Holder / id-or-inline)
        # IdOnly holder: VarInt. Inline is rare in entity metadata.
        return b.varint()
    if typ in (27, 28):  # SNIFFER / ARMADILLO state
        return b.varint()
    if typ == 29:  # VECTOR3
        return struct.unpack(">fff", b.bytes(12))
    if typ == 30:  # QUATERNION
        return struct.unpack(">ffff", b.bytes(16))
    raise MetaStop(f"unknown serializer {typ}")


def parse_set_entity_data_entries(payload: bytes) -> tuple[int, list[tuple[int, int, object]]]:
    """Parse SET_ENTITY_DATA payload → (eid, [(index, type_id, value), ...]).

    Stops early on MetaStop; returns entries read so far.
    """
    b = Buf(payload)
    eid = b.varint()
    out: list[tuple[int, int, object]] = []
    while True:
        idx = b.u8()
        if idx == 0xFF:
            break
        typ = b.varint()
        try:
            val = _read_meta_value(b, typ)
        except MetaStop:
            break
        except Exception:
            break
        out.append((idx, typ, val))
    return eid, out


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
        if pid == SET_ENTITY_DATA:
            eid, entries = parse_set_entity_data_entries(payload)
            tr = self.tracks.get(eid)
            if not tr:
                return
            for idx, _typ, val in entries:
                tr.put_meta(t, idx, val)
            return
        if pid == REMOVE_ENTITIES:
            for _ in range(b.varint()):
                eid = b.varint()
                if eid in self.tracks:
                    self.tracks[eid].remove_t = t

    def visible_tracks(self) -> list[EntityTrack]:
        return [tr for tr in self.tracks.values() if tr.uuid not in self.recorder_uuids]
