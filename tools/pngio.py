"""PNG read and write with the standard library only (zlib and struct), for the tools that handle stills and textures.

Reads every non-interlaced PNG Minecraft and the game write: grey, RGB, palette, grey with alpha and RGBA, palette and grey at
1, 2, 4 or 8 bits, with or without a tRNS chunk. 16-bit and interlaced PNGs are refused with a ValueError naming the file.
"""
import struct
import zlib
from pathlib import Path

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
# Colour type -> channels per pixel.
CHANNELS = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}
# Colour type -> bit depths it may have.
DEPTHS = {0: (1, 2, 4, 8), 2: (8,), 3: (1, 2, 4, 8), 4: (8,), 6: (8,)}


class Image:
    """Width, height and RGB bytes (3 per pixel, row by row)."""

    def __init__(self, width, height, rgb):
        self.width = width
        self.height = height
        self.rgb = rgb


class RgbaImage:
    """Width, height and RGBA bytes (4 per pixel, row by row)."""

    def __init__(self, width, height, rgba):
        if len(rgba) != width * height * 4:
            raise ValueError(f"{width} x {height} RGBA needs {width * height * 4} bytes, got {len(rgba)}")
        self.width = width
        self.height = height
        self.rgba = rgba

    def to_png(self):
        return _encode(self.width, self.height, 6, self.rgba)


def read_chunks(data, path):
    if not data.startswith(PNG_SIGNATURE):
        raise ValueError(f"{path}: not a PNG")
    pos = len(PNG_SIGNATURE)
    while pos + 8 <= len(data):
        length, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        yield kind, body
        pos += 12 + length
        if kind == b"IEND":
            return


def unfilter(raw, stride, height, bpp, path):
    if len(raw) != height * (stride + 1):
        raise ValueError(f"{path}: image data is {len(raw)} bytes, expected {height * (stride + 1)}")
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
                predictor = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
                line[i] = (line[i] + predictor) & 255
        elif kind != 0:
            raise ValueError(f"{path}: unknown PNG filter {kind}")
        out[y * stride:(y + 1) * stride] = line
        prior = line
    return out


def _samples(rows, width, height, depth, channels):
    """Unpacks rows of sub-byte samples (depth 1, 2 or 4) into one byte per sample."""
    if depth == 8:
        return rows
    stride = (width * channels * depth + 7) // 8
    per_byte = 8 // depth
    mask = (1 << depth) - 1
    out = bytearray(width * height * channels)
    count = width * channels
    for y in range(height):
        row = rows[y * stride:(y + 1) * stride]
        for i in range(count):
            byte = row[i // per_byte]
            shift = 8 - depth * (i % per_byte + 1)
            out[y * count + i] = (byte >> shift) & mask
    return out


def _decode(path, data):
    """(width, height, RGBA bytes) of a PNG."""
    header = None
    palette = b""
    trns = b""
    idat = bytearray()
    for kind, body in read_chunks(data, path):
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", body)
        elif kind == b"PLTE":
            palette = body
        elif kind == b"tRNS":
            trns = body
        elif kind == b"IDAT":
            idat += body
    if header is None:
        raise ValueError(f"{path}: no IHDR chunk")
    width, height, depth, colour, _, _, interlace = header
    if interlace != 0 or colour not in CHANNELS or depth not in DEPTHS[colour]:
        raise ValueError(f"{path}: unsupported PNG (depth {depth}, colour type {colour}, interlace {interlace}); "
                         "16-bit and interlaced PNGs are not read")
    channels = CHANNELS[colour]
    stride = (width * channels * depth + 7) // 8
    bpp = max(1, channels * depth // 8)
    rows = unfilter(zlib.decompress(bytes(idat)), stride, height, bpp, path)
    samples = _samples(rows, width, height, depth, channels)
    count = width * height
    rgba = bytearray(count * 4)
    if colour == 6:
        rgba[:] = samples
    elif colour == 2:
        for channel in range(3):
            rgba[channel::4] = samples[channel::3]
        rgba[3::4] = b"\xff" * count
        if len(trns) == 6:
            key = bytes(trns[1::2])
            for i in range(count):
                if bytes(samples[3 * i:3 * i + 3]) == key:
                    rgba[4 * i + 3] = 0
    elif colour == 4:
        for channel in range(3):
            rgba[channel::4] = samples[0::2]
        rgba[3::4] = samples[1::2]
    elif colour == 0:
        scale = 255 // ((1 << depth) - 1)
        grey = bytes(value * scale for value in samples)
        for channel in range(3):
            rgba[channel::4] = grey
        rgba[3::4] = b"\xff" * count
        if len(trns) == 2:
            key = struct.unpack(">H", trns)[0]
            for i in range(count):
                if samples[i] == key:
                    rgba[4 * i + 3] = 0
    else:
        if not palette:
            raise ValueError(f"{path}: palette PNG without a PLTE chunk")
        entries = len(palette) // 3
        table = [palette[3 * n:3 * n + 3] + bytes((trns[n] if n < len(trns) else 255,)) for n in range(entries)]
        for i, index in enumerate(samples):
            if index >= entries:
                raise ValueError(f"{path}: palette index {index} past the {entries} entries of PLTE")
            rgba[4 * i:4 * i + 4] = table[index]
    return width, height, bytes(rgba)


def decode_rgba(path, data=None):
    """Reads a PNG into an RgbaImage: the bytes in data when given, else the file at path."""
    if data is None:
        data = Path(path).read_bytes()
    width, height, rgba = _decode(path, data)
    return RgbaImage(width, height, rgba)


def decode_png(path):
    """Reads a PNG file into an Image. Alpha is dropped: the stills are opaque screenshots."""
    width, height, rgba = _decode(path, Path(path).read_bytes())
    rgb = bytearray(width * height * 3)
    for channel in range(3):
        rgb[channel::3] = rgba[channel::4]
    return Image(width, height, bytes(rgb))


def _encode(width, height, colour, pixels):
    channels = CHANNELS[colour]
    stride = width * channels
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        raw += pixels[y * stride:(y + 1) * stride]

    def chunk(kind, body):
        crc = zlib.crc32(kind + body) & 0xFFFFFFFF
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", crc)

    header = struct.pack(">IIBBBBB", width, height, 8, colour, 0, 0, 0)
    return PNG_SIGNATURE + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(bytes(raw), 6)) + chunk(b"IEND", b"")


def encode_png(image):
    """Writes an Image as an 8-bit RGB PNG."""
    return _encode(image.width, image.height, 2, image.rgb)
