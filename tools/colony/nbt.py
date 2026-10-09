"""Named Binary Tag: the gzipped format of Minecraft's structure files. Standard library only.

A compound is a dict, a string a str. Numbers and lists carry their tag type, because NBT has several of each: Byte, Short, Int,
Long, Float, Double, and List (whose items share one type). decode() returns the same shapes, so a file round-trips.
"""
import gzip
import struct
import zlib
from dataclasses import dataclass

END, BYTE, SHORT, INT, LONG, FLOAT, DOUBLE, BYTE_ARRAY, STRING, LIST, COMPOUND, INT_ARRAY, LONG_ARRAY = range(13)


@dataclass(frozen=True)
class Byte:
    value: int


@dataclass(frozen=True)
class Short:
    value: int


@dataclass(frozen=True)
class Int:
    value: int


@dataclass(frozen=True)
class Long:
    value: int


@dataclass(frozen=True)
class Float:
    value: float


@dataclass(frozen=True)
class Double:
    value: float


@dataclass(frozen=True)
class List:
    """A list tag: every item has the tag type kind (END for an empty list)."""

    kind: int
    items: tuple


_SCALARS = {Byte: (BYTE, ">b"), Short: (SHORT, ">h"), Int: (INT, ">i"), Long: (LONG, ">q"), Float: (FLOAT, ">f"), Double: (DOUBLE, ">d")}
_BY_TAG = {tag: (cls, fmt) for cls, (tag, fmt) in _SCALARS.items()}


def tag_of(value) -> int:
    if isinstance(value, dict):
        return COMPOUND
    if isinstance(value, str):
        return STRING
    if isinstance(value, List):
        return LIST
    if type(value) in _SCALARS:
        return _SCALARS[type(value)][0]
    raise TypeError(f"no NBT tag for {value!r} ({type(value).__name__}); wrap numbers in Int, Double and the like")


def ints(*values: int) -> List:
    return List(INT, tuple(Int(v) for v in values))


def doubles(*values: float) -> List:
    return List(DOUBLE, tuple(Double(v) for v in values))


def floats(*values: float) -> List:
    return List(FLOAT, tuple(Float(v) for v in values))


def compounds(items) -> List:
    items = tuple(items)
    return List(COMPOUND if items else END, items)


def _string(text: str) -> bytes:
    data = text.encode("utf-8")
    return struct.pack(">H", len(data)) + data


def _payload(value) -> bytes:
    tag = tag_of(value)
    if tag == COMPOUND:
        out = bytearray()
        for name, item in value.items():
            out += bytes([tag_of(item)]) + _string(name) + _payload(item)
        return bytes(out + bytes([END]))
    if tag == STRING:
        return _string(value)
    if tag == LIST:
        for item in value.items:
            if tag_of(item) != value.kind:
                raise TypeError(f"list of tag {value.kind} holds {item!r}")
        return bytes([value.kind]) + struct.pack(">i", len(value.items)) + b"".join(_payload(i) for i in value.items)
    return struct.pack(_SCALARS[type(value)][1], value.value)


# A gzip header with every field fixed: no time, maximum compression, an unknown OS. gzip.compress fills these in differently
# from one Python version to the next (3.12 takes zlib's own header, whose OS byte is the build's).
_GZIP_HEADER = b"\x1f\x8b\x08\x00\x00\x00\x00\x00\x02\xff"


def encode(root: dict) -> bytes:
    """The gzipped file of a root compound with an empty name, as Minecraft writes a structure. The header is fixed, so one zlib
    makes the same bytes on every Python; another zlib build may deflate differently, so compare files by decode(), not bytes."""
    raw = bytes([COMPOUND]) + _string("") + _payload(root)
    deflate = zlib.compressobj(9, zlib.DEFLATED, -zlib.MAX_WBITS)
    body = deflate.compress(raw) + deflate.flush()
    return _GZIP_HEADER + body + struct.pack("<II", zlib.crc32(raw) & 0xFFFFFFFF, len(raw) & 0xFFFFFFFF)


class _Reader:
    def __init__(self, data: bytes):
        self.data = data
        self.at = 0

    def take(self, n: int) -> bytes:
        chunk = self.data[self.at:self.at + n]
        if len(chunk) != n:
            raise ValueError("NBT data ends early")
        self.at += n
        return chunk

    def unpack(self, fmt: str):
        return struct.unpack(fmt, self.take(struct.calcsize(fmt)))[0]

    def string(self) -> str:
        return self.take(self.unpack(">H")).decode("utf-8")

    def payload(self, tag: int):
        if tag in _BY_TAG:
            cls, fmt = _BY_TAG[tag]
            return cls(self.unpack(fmt))
        if tag == STRING:
            return self.string()
        if tag == LIST:
            kind = self.unpack(">b")
            return List(kind, tuple(self.payload(kind) for _ in range(self.unpack(">i"))))
        if tag == COMPOUND:
            out = {}
            while (inner := self.unpack(">b")) != END:
                name = self.string()
                out[name] = self.payload(inner)
            return out
        raise ValueError(f"NBT tag {tag} is not supported here")


def decode(data: bytes) -> dict:
    reader = _Reader(gzip.decompress(data))
    if reader.unpack(">b") != COMPOUND:
        raise ValueError("the root tag is not a compound")
    reader.string()
    return reader.payload(COMPOUND)
