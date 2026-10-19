"""Parse 1.21.1 (protocol 767) particle-related play packets.

Packet IDs (minecraft-data pc/1.21.1):
  block_break_animation 0x06
  explosion              0x20
  world_event            0x28  (LevelEvent; PARTICLES_DESTROY_BLOCK=2001)
  world_particles        0x29
"""
from __future__ import annotations

import struct
from dataclasses import dataclass, field

from .chunks import Buf
from .varint import write_varint  # noqa: F401 — tests may use

BLOCK_BREAK_ANIMATION = 0x06
EXPLOSION = 0x20
WORLD_EVENT = 0x28
WORLD_PARTICLES = 0x29

PARTICLES_DESTROY_BLOCK = 2001  # LevelEvent.X

# ParticleTypes registration order → name (minecraft-data Particle mapper)
PARTICLE_NAMES = {
    0: "angry_villager", 1: "block", 2: "block_marker", 3: "bubble", 4: "cloud",
    5: "crit", 6: "damage_indicator", 7: "dragon_breath", 8: "dripping_lava",
    9: "falling_lava", 10: "landing_lava", 11: "dripping_water", 12: "falling_water",
    13: "dust", 14: "dust_color_transition", 15: "effect", 16: "elder_guardian",
    17: "enchanted_hit", 18: "enchant", 19: "end_rod", 20: "entity_effect",
    21: "explosion_emitter", 22: "explosion", 23: "gust", 24: "small_gust",
    25: "gust_emitter_large", 26: "gust_emitter_small", 27: "sonic_boom",
    28: "falling_dust", 29: "firework", 30: "fishing", 31: "flame", 32: "infested",
    33: "cherry_leaves", 34: "sculk_soul", 35: "sculk_charge", 36: "sculk_charge_pop",
    37: "soul_fire_flame", 38: "soul", 39: "flash", 40: "happy_villager",
    41: "composter", 42: "heart", 43: "instant_effect", 44: "item", 45: "vibration",
    46: "item_slime", 47: "item_cobweb", 48: "item_snowball", 49: "large_smoke",
    50: "lava", 51: "mycelium", 52: "note", 53: "poof", 54: "portal", 55: "rain",
    56: "smoke", 57: "white_smoke", 58: "sneeze", 59: "spit", 60: "squid_ink",
    61: "sweep_attack", 62: "totem_of_undying", 63: "underwater", 64: "splash",
    65: "witch", 66: "bubble_pop", 67: "current_down", 68: "bubble_column_up",
    69: "nautilus", 70: "dolphin", 71: "campfire_cosy_smoke", 72: "campfire_signal_smoke",
    73: "dripping_honey", 74: "falling_honey", 75: "landing_honey", 76: "falling_nectar",
    77: "falling_spore_blossom", 78: "ash", 79: "crimson_spore", 80: "warped_spore",
    81: "spore_blossom_air", 82: "dripping_obsidian_tear", 83: "falling_obsidian_tear",
    84: "landing_obsidian_tear", 85: "reverse_portal", 86: "white_ash", 87: "small_flame",
    88: "snowflake", 89: "dripping_dripstone_lava", 90: "falling_dripstone_lava",
    91: "dripping_dripstone_water", 92: "falling_dripstone_water", 93: "glow_squid_ink",
    94: "glow", 95: "wax_on", 96: "wax_off", 97: "electric_spark", 98: "scrape",
    99: "shriek", 100: "egg_crack", 101: "dust_plume", 102: "trial_spawner_detected_player",
    103: "trial_spawner_detected_player_ominous", 104: "vault_connection",
    105: "dust_pillar", 106: "ominous_spawning", 107: "raid_omen", 108: "trial_omen",
}

# Types that carry extra data after the type id
_BLOCKISH = {"block", "block_marker", "falling_dust", "dust_pillar"}


@dataclass
class ParticleBurst:
    t: int
    kind: str  # particle name or 'explosion' / 'destroy_block' / 'break_anim'
    x: float
    y: float
    z: float
    count: int = 1
    ox: float = 0.0
    oy: float = 0.0
    oz: float = 0.0
    speed: float = 0.0
    block_state: int | None = None  # for block / destroy
    stage: int | None = None  # break anim 0-9, 10=clear
    radius: float = 0.0
    eid: int | None = None


@dataclass
class ParticleStore:
    bursts: list[ParticleBurst] = field(default_factory=list)

    def handle(self, pid: int, payload: bytes, t: int) -> None:
        try:
            self._handle(pid, payload, t)
        except Exception:
            pass

    def _handle(self, pid, payload, t):
        b = Buf(payload)
        if pid == WORLD_PARTICLES:
            long_d = bool(b.u8())
            x, y, z = struct.unpack(">ddd", b.bytes(24))
            ox, oy, oz, speed = struct.unpack(">ffff", b.bytes(16))
            count = struct.unpack(">i", b.bytes(4))[0]
            name, data = _read_particle(b)
            self.bursts.append(ParticleBurst(
                t, name, x, y, z, max(1, count), ox, oy, oz, speed,
                block_state=data if isinstance(data, int) else None,
            ))
            return
        if pid == BLOCK_BREAK_ANIMATION:
            eid = b.varint()
            pos = _read_pos(b)
            stage = b.u8()  # unsigned in packet; 0-9 digging, >=10 clear
            # signed i8 in schema but destroyStage is 0-9; treat as unsigned
            if stage > 127:
                stage = stage - 256
            self.bursts.append(ParticleBurst(
                t, "break_anim", pos[0] + 0.5, pos[1] + 0.5, pos[2] + 0.5,
                count=1, stage=stage, eid=eid,
            ))
            return
        if pid == WORLD_EVENT:
            effect = struct.unpack(">i", b.bytes(4))[0]
            pos = _read_pos(b)
            data = struct.unpack(">i", b.bytes(4))[0]
            _global = bool(b.u8())
            if effect == PARTICLES_DESTROY_BLOCK:
                self.bursts.append(ParticleBurst(
                    t, "destroy_block", pos[0] + 0.5, pos[1] + 0.5, pos[2] + 0.5,
                    count=24, ox=0.4, oy=0.4, oz=0.4, speed=0.15,
                    block_state=data,
                ))
            return
        if pid == EXPLOSION:
            x, y, z = struct.unpack(">ddd", b.bytes(24))
            radius = struct.unpack(">f", b.bytes(4))[0]
            n = b.varint()
            b.bytes(n * 3)  # skip block offsets
            b.bytes(12)  # player motion
            _interact = b.varint()
            # small + large explosion particles (skip payloads but keep names)
            try:
                small, _ = _read_particle(b)
            except Exception:
                small = "explosion"
            try:
                _large, _ = _read_particle(b)
            except Exception:
                pass
            # skip sound holder if present (best-effort)
            self.bursts.append(ParticleBurst(
                t, "explosion", x, y, z,
                count=max(12, int(radius * 20)),
                ox=radius * 0.6, oy=radius * 0.6, oz=radius * 0.6,
                speed=0.4, radius=radius,
            ))
            if small and small not in ("explosion", "explosion_emitter"):
                self.bursts.append(ParticleBurst(
                    t, small, x, y, z, count=8, ox=radius * 0.3, oy=radius * 0.3,
                    oz=radius * 0.3, speed=0.2, radius=radius,
                ))

    def in_box(self, box) -> list[ParticleBurst]:
        if not box:
            return list(self.bursts)
        x0, y0, z0, x1, y1, z1 = box
        return [p for p in self.bursts
                if x0 - 8 <= p.x <= x1 + 8 and y0 - 8 <= p.y <= y1 + 8 and z0 - 8 <= p.z <= z1 + 8]


def _read_pos(b: Buf) -> tuple[int, int, int]:
    # Protocol Position: unsigned 64-bit; Python ints need masks (<<>> alone is not Java).
    v = struct.unpack(">Q", b.bytes(8))[0]
    x = v >> 38
    y = v & 0xFFF
    z = (v >> 12) & 0x3FFFFFF
    if x >= 1 << 25:
        x -= 1 << 26
    if y >= 1 << 11:
        y -= 1 << 12
    if z >= 1 << 25:
        z -= 1 << 26
    return x, y, z


def _read_particle(b: Buf) -> tuple[str, object | None]:
    tid = b.varint()
    name = PARTICLE_NAMES.get(tid, f"particle#{tid}")
    data = None
    if name in _BLOCKISH:
        data = b.varint()
    elif name == "dust":
        data = struct.unpack(">ffff", b.bytes(16))
    elif name == "dust_color_transition":
        data = struct.unpack(">fffffff", b.bytes(28))
    elif name == "entity_effect":
        data = struct.unpack(">i", b.bytes(4))[0]
    elif name == "item":
        # Slot: stop safely — count varint; if >0 skip item id + components poorly
        from .entities import _read_slot
        try:
            data = _read_slot(b)
        except Exception:
            raise
    elif name == "sculk_charge":
        data = struct.unpack(">f", b.bytes(4))[0]
    elif name == "shriek":
        data = b.varint()
    elif name == "vibration":
        # skip: positionType varint + pos + ticks
        pt = b.varint()
        if pt == 0:
            _read_pos(b)
        else:
            b.varint()
            b.bytes(4)
        b.varint()
    return name, data
