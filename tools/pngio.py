"""The one PNG codec of the tools, standard library only (zlib and struct).

Writes 8-bit RGBA with filter 0, zlib level 9 and no ancillary chunks, so a file's bytes depend only on its pixels and the zlib
build. A generator's --check therefore compares a PNG by its pixels with `stale`, so another zlib on another machine does not fail it.
Reads every non-interlaced PNG Minecraft and the game write into RGBA: grey, RGB, palette, grey with alpha and RGBA, palette and
grey at 1, 2, 4 or 8 bits, with or without a tRNS chunk. 16-bit and interlaced PNGs are refused with a ValueError naming the file.
"""
import struct
import zlib
from dataclasses import dataclass
from pathlib import Path

SIGNATURE = b"\x89PNG\r\n\x1a\n"
# Colour type -> channels per pixel.
CHANNELS = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}
# Colour type -> bit depths it may have.
DEPTHS = {0: (1, 2, 4, 8), 2: (8,), 3: (1, 2, 4, 8), 4: (8,), 6: (8,)}
# Fixed so the bytes of a written file do not move: the committed generated PNGs were written at this level.
COMPRESSION_LEVEL = 9


@dataclass(frozen=True)
class Rgba:
    """An image as rows of RGBA bytes: pixel (x, y) is pixels[4 * (y * width + x):][:4]."""

    width: int
    height: int
    pixels: bytes

    def __post_init__(self):
        if len(self.pixels) != 4 * self.width * self.height:
            raise ValueError(f"{self.width} x {self.height} RGBA needs {4 * self.width * self.height} bytes, got {len(self.pixels)}")


def encode(image: Rgba) -> bytes:
    stride = 4 * image.width
    raw = bytearray()
    for y in range(image.height):
        raw.append(0)
        raw += image.pixels[y * stride:(y + 1) * stride]
    header = struct.pack(">IIBBBBB", image.width, image.height, 8, 6, 0, 0, 0)
    return SIGNATURE + _chunk(b"IHDR", header) + _chunk(b"IDAT", zlib.compress(bytes(raw), COMPRESSION_LEVEL)) + _chunk(b"IEND", b"")


def _chunk(kind: bytes, body: bytes) -> bytes:
    return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)


def decode(data: bytes, name: str = "PNG") -> Rgba:
    header = None
    palette = b""
    trns = b""
    idat = bytearray()
    if not data.startswith(SIGNATURE):
        raise ValueError(f"{name}: not a PNG")
    pos = len(SIGNATURE)
    while pos + 8 <= len(data):
        length, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", body)
        elif kind == b"PLTE":
            palette = body
        elif kind == b"tRNS":
            trns = body
        elif kind == b"IDAT":
            idat += body
        elif kind == b"IEND":
            break
    if header is None:
        raise ValueError(f"{name}: no IHDR chunk")
    width, height, depth, colour, _, _, interlace = header
    if interlace != 0 or colour not in CHANNELS or depth not in DEPTHS[colour]:
        raise ValueError(f"{name}: unsupported PNG (depth {depth}, colour type {colour}, interlace {interlace}); "
                         "16-bit and interlaced PNGs are not read")
    channels = CHANNELS[colour]
    stride = (width * channels * depth + 7) // 8
    try:
        inflated = zlib.decompress(bytes(idat))
    except zlib.error as error:
        raise ValueError(f"{name}: image data does not inflate ({error})") from error
    rows = _unfilter(inflated, stride, height, max(1, channels * depth // 8), name)
    samples = _samples(rows, width, height, depth, channels)
    count = width * height
    out = bytearray(4 * count)
    if colour == 6:
        out[:] = samples
    elif colour == 2:
        for channel in range(3):
            out[channel::4] = samples[channel::3]
        out[3::4] = b"\xff" * count
        if len(trns) == 6:
            key = bytes(trns[1::2])
            for i in range(count):
                if bytes(samples[3 * i:3 * i + 3]) == key:
                    out[4 * i + 3] = 0
    elif colour == 4:
        for channel in range(3):
            out[channel::4] = samples[0::2]
        out[3::4] = samples[1::2]
    elif colour == 0:
        scale = 255 // ((1 << depth) - 1)
        grey = bytes(value * scale for value in samples)
        for channel in range(3):
            out[channel::4] = grey
        out[3::4] = b"\xff" * count
        if len(trns) == 2:
            key = struct.unpack(">H", trns)[0]
            for i in range(count):
                if samples[i] == key:
                    out[4 * i + 3] = 0
    else:
        if not palette:
            raise ValueError(f"{name}: palette PNG without a PLTE chunk")
        entries = len(palette) // 3
        table = [palette[3 * n:3 * n + 3] + bytes((trns[n] if n < len(trns) else 255,)) for n in range(entries)]
        for i, index in enumerate(samples):
            if index >= entries:
                raise ValueError(f"{name}: palette index {index} past the {entries} entries of PLTE")
            out[4 * i:4 * i + 4] = table[index]
    return Rgba(width, height, bytes(out))


def stale(path: Path, body: bytes) -> bool:
    """Whether the file is missing or differs from body. A PNG is compared by its pixels, so another zlib makes no difference; an
    unreadable PNG is stale. Any other file is compared by bytes."""
    if not path.is_file():
        return True
    held = path.read_bytes()
    if path.suffix != ".png":
        return held != body
    want = decode(body, path.name)
    try:
        return decode(held, path.name) != want
    except ValueError:
        return True


def _samples(rows: bytearray, width: int, height: int, depth: int, channels: int) -> bytearray:
    """Unpacks rows of sub-byte samples (depth 1, 2 or 4) into one byte per sample."""
    if depth == 8:
        return rows
    stride = (width * channels * depth + 7) // 8
    per_byte = 8 // depth
    mask = (1 << depth) - 1
    count = width * channels
    out = bytearray(count * height)
    for y in range(height):
        row = rows[y * stride:(y + 1) * stride]
        for i in range(count):
            out[y * count + i] = (row[i // per_byte] >> (8 - depth * (i % per_byte + 1))) & mask
    return out


def _unfilter(raw: bytes, stride: int, height: int, bpp: int, name: str) -> bytearray:
    if len(raw) != height * (stride + 1):
        raise ValueError(f"{name}: image data is {len(raw)} bytes, expected {height * (stride + 1)}")
    out = bytearray(height * stride)
    prior = bytes(stride)
    for y in range(height):
        start = y * (stride + 1)
        kind = raw[start]
        line = bytearray(raw[start + 1:start + 1 + stride])
        if kind == 1:
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 255
        elif kind == 2:
            for i in range(stride):
                line[i] = (line[i] + prior[i]) & 255
        elif kind == 3:
            for i in range(stride):
                left = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((left + prior[i]) >> 1)) & 255
        elif kind == 4:
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                b = prior[i]
                c = prior[i - bpp] if i >= bpp else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else (b if pb <= pc else c))) & 255
        elif kind != 0:
            raise ValueError(f"{name}: unknown PNG filter {kind}")
        out[y * stride:(y + 1) * stride] = line
        prior = line
    return out
