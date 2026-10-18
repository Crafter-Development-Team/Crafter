"""Minimal, dependency-free NBT (de)serializer exposing the subset of the
``nbtlib`` API the replay importer uses. Blender's bundled Python has no
nbtlib, so Crafter must not depend on it."""
from __future__ import annotations

import gzip
import struct
from pathlib import Path


class Base:
    tag_id = 0


class Byte(int, Base): tag_id = 1
class Short(int, Base): tag_id = 2
class Int(int, Base): tag_id = 3
class Long(int, Base): tag_id = 4
class Float(float, Base): tag_id = 5
class Double(float, Base): tag_id = 6
class ByteArray(list, Base): tag_id = 7
class String(str, Base): tag_id = 8
class IntArray(list, Base): tag_id = 11
class LongArray(list, Base): tag_id = 12


class Compound(dict, Base):
    tag_id = 10


class List(list, Base):
    tag_id = 9
    subtype = None
    _cache: dict = {}

    def __class_getitem__(cls, sub):
        if sub not in cls._cache:
            cls._cache[sub] = type(f"List[{sub.__name__}]", (List,), {"subtype": sub})
        return cls._cache[sub]


ID2CLS = {c.tag_id: c for c in (Byte, Short, Int, Long, Float, Double, ByteArray,
                                 String, List, Compound, IntArray, LongArray)}
_FMT = {1: ">b", 2: ">h", 3: ">i", 4: ">q", 5: ">f", 6: ">d"}


def _infer(v):
    if isinstance(v, Base):
        return v.tag_id
    if isinstance(v, bool) or isinstance(v, int):
        return 3
    if isinstance(v, float):
        return 6
    if isinstance(v, str):
        return 8
    if isinstance(v, dict):
        return 10
    if isinstance(v, list):
        return 9
    raise TypeError(f"cannot encode {type(v)!r} as NBT")


def _w(out: bytearray, tid: int, v) -> None:
    if tid in _FMT:
        out += struct.pack(_FMT[tid], v)
    elif tid == 8:
        b = str(v).encode("utf-8")
        out += struct.pack(">H", len(b)) + b
    elif tid == 7:
        out += struct.pack(">i", len(v)) + bytes((x & 0xFF) for x in v)
    elif tid == 11:
        out += struct.pack(f">i{len(v)}i", len(v), *v)
    elif tid == 12:
        out += struct.pack(f">i{len(v)}q", len(v), *v)
    elif tid == 9:
        sub = getattr(v, "subtype", None)
        st = sub.tag_id if sub else (_infer(v[0]) if v else 0)
        out += struct.pack(">bi", st, len(v))
        for x in v:
            _w(out, st, x)
    elif tid == 10:
        for k, x in v.items():
            t = _infer(x)
            kb = str(k).encode("utf-8")
            out += struct.pack(">bH", t, len(kb)) + kb
            _w(out, t, x)
        out += b"\x00"
    else:
        raise ValueError(tid)


class _R:
    def __init__(self, f):
        self.f = f

    def read(self, n):
        b = self.f.read(n)
        if len(b) != n:
            raise EOFError("truncated NBT")
        return b

    def val(self, tid):
        if tid in _FMT:
            fmt = _FMT[tid]
            return ID2CLS[tid](struct.unpack(fmt, self.read(struct.calcsize(fmt)))[0])
        if tid == 8:
            (n,) = struct.unpack(">H", self.read(2))
            return String(self.read(n).decode("utf-8", "replace"))
        if tid in (7, 11, 12):
            (n,) = struct.unpack(">i", self.read(4))
            c = {7: "b", 11: "i", 12: "q"}[tid]
            sz = {7: 1, 11: 4, 12: 8}[tid]
            return ID2CLS[tid](struct.unpack(f">{n}{c}", self.read(n * sz)))
        if tid == 9:
            st, n = struct.unpack(">bi", self.read(5))
            lst = (List[ID2CLS[st]] if st in ID2CLS else List)()
            for _ in range(max(n, 0)):
                lst.append(self.val(st))
            return lst
        if tid == 10:
            c = Compound()
            while True:
                (t,) = struct.unpack(">b", self.read(1))
                if t == 0:
                    return c
                (n,) = struct.unpack(">H", self.read(2))
                k = self.read(n).decode("utf-8", "replace")
                c[k] = self.val(t)
        raise ValueError(f"bad NBT tag id {tid}")


class File(Compound):
    """Root compound with an (empty) name, like ``nbtlib.File``."""

    def __init__(self, root=None, root_name: str = ""):
        super().__init__(root or {})
        self.root_name = root_name

    @classmethod
    def parse(cls, fileobj, byteorder: str = "big"):
        r = _R(fileobj)
        (t,) = struct.unpack(">b", r.read(1))
        if t != 10:
            raise ValueError(f"root tag must be compound, got {t}")
        (n,) = struct.unpack(">H", r.read(2))
        name = r.read(n).decode("utf-8", "replace")
        return cls(r.val(10), name)

    def to_bytes(self) -> bytes:
        nb = self.root_name.encode("utf-8")
        out = bytearray(struct.pack(">bH", 10, len(nb)) + nb)
        _w(out, 10, self)
        return bytes(out)

    def write(self, fileobj, byteorder: str = "big") -> None:
        fileobj.write(self.to_bytes())

    def save(self, path, gzipped: bool = False, byteorder: str = "big") -> None:
        data = self.to_bytes()
        Path(path).write_bytes(gzip.compress(data) if gzipped else data)
