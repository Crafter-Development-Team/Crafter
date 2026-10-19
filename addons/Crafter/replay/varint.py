"""Minecraft protocol varint helpers."""
from __future__ import annotations


def read_varint(buf: memoryview | bytes, offset: int = 0) -> tuple[int, int]:
    num_read = 0
    result = 0
    while True:
        if offset + num_read >= len(buf):
            raise ValueError("varint truncated")
        read = buf[offset + num_read]
        result |= (read & 0x7F) << (7 * num_read)
        num_read += 1
        if num_read > 5:
            raise ValueError("varint too long")
        if (read & 0x80) == 0:
            break
    return result, offset + num_read


def write_varint(value: int) -> bytes:
    out = bytearray()
    v = value & 0xFFFFFFFF
    while True:
        if (v & ~0x7F) == 0:
            out.append(v)
            break
        out.append((v & 0x7F) | 0x80)
        v >>= 7
    return bytes(out)
