"""RGBA PNG read and write with the standard library (zlib and struct).

Writes 8-bit RGBA, filter 0, no ancillary chunks, so a file's bytes depend only on its pixels and zlib. Reads any 8-bit,
non-interlaced PNG (grey, RGB, palette, grey with alpha, RGBA) into RGBA, so a texture saved by another editor can be
compared too.
"""
import struct
import zlib
from dataclasses import dataclass

SIGNATURE = b"\x89PNG\r\n\x1a\n"
# Colour type -> channels per pixel.
CHANNELS = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}


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
    return SIGNATURE + _chunk(b"IHDR", header) + _chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + _chunk(b"IEND", b"")


def _chunk(kind: bytes, body: bytes) -> bytes:
    return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)


def decode(data: bytes, name: str = "PNG") -> Rgba:
    if not data.startswith(SIGNATURE):
        raise ValueError(f"{name}: not a PNG")
    header = None
    palette = b""
    alpha = b""
    idat = bytearray()
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
            alpha = body
        elif kind == b"IDAT":
            idat += body
        elif kind == b"IEND":
            break
    if header is None:
        raise ValueError(f"{name}: no IHDR chunk")
    width, height, depth, colour, _, _, interlace = header
    if depth != 8 or interlace != 0 or colour not in CHANNELS:
        raise ValueError(f"{name}: only 8-bit non-interlaced PNGs are read (depth {depth}, colour type {colour}, interlace {interlace})")
    bpp = CHANNELS[colour]
    raw = _unfilter(zlib.decompress(bytes(idat)), width, height, bpp, name)
    count = width * height
    out = bytearray(4 * count)
    if colour == 6:
        out[:] = raw
    elif colour == 2:
        for channel in range(3):
            out[channel::4] = raw[channel::3]
        out[3::4] = b"\xff" * count
    elif colour == 0:
        for channel in range(3):
            out[channel::4] = raw
        out[3::4] = b"\xff" * count
    elif colour == 4:
        for channel in range(3):
            out[channel::4] = raw[0::2]
        out[3::4] = raw[1::2]
    else:
        for i, index in enumerate(raw):
            out[4 * i:4 * i + 3] = palette[3 * index:3 * index + 3]
            out[4 * i + 3] = alpha[index] if index < len(alpha) else 255
    return Rgba(width, height, bytes(out))


def _unfilter(raw: bytes, width: int, height: int, bpp: int, name: str) -> bytearray:
    stride = width * bpp
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
