"""Tests tools/pngio.py: every PNG format the tools read or write, and the pixel comparison the generators' --check uses."""
import struct
import sys
import tempfile
import unittest
import zlib
from pathlib import Path

TOOLS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(TOOLS))

import pngio  # noqa: E402


def chunk(kind, body):
    return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)


def png(width, height, depth, colour, raw, palette=b"", trns=b"", interlace=0, level=6):
    """A PNG whose filtered scanlines are raw, with the optional PLTE and tRNS chunks."""
    parts = [pngio.SIGNATURE, chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, depth, colour, 0, 0, interlace))]
    if palette:
        parts.append(chunk(b"PLTE", palette))
    if trns:
        parts.append(chunk(b"tRNS", trns))
    parts += [chunk(b"IDAT", zlib.compress(raw, level)), chunk(b"IEND", b"")]
    return b"".join(parts)


def unfiltered(*rows):
    return b"".join(b"\x00" + row for row in rows)


def chunk_kinds(data):
    kinds, pos = [], len(pngio.SIGNATURE)
    while pos < len(data):
        (length,) = struct.unpack(">I", data[pos:pos + 4])
        kinds.append(data[pos + 4:pos + 8])
        pos += 12 + length
    return kinds


class EncodeTest(unittest.TestCase):
    def test_rgba_round_trip_keeps_alpha(self):
        image = pngio.Rgba(2, 2, bytes([10, 20, 30, 255, 0, 0, 0, 0, 200, 100, 50, 128, 1, 2, 3, 4]))
        self.assertEqual(image, pngio.decode(pngio.encode(image)))

    def test_a_file_is_8_bit_rgba_with_filter_0_and_no_ancillary_chunks(self):
        data = pngio.encode(pngio.Rgba(2, 2, bytes(range(16))))
        self.assertEqual([b"IHDR", b"IDAT", b"IEND"], chunk_kinds(data))
        self.assertEqual((2, 2, 8, 6, 0, 0, 0), struct.unpack(">IIBBBBB", data[16:29]))
        rows = zlib.decompress(data[41:-12])
        self.assertEqual(bytes([0]) + bytes(range(8)) + bytes([0]) + bytes(range(8, 16)), rows)

    def test_pixels_of_the_wrong_length_are_refused(self):
        with self.assertRaises(ValueError):
            pngio.Rgba(2, 2, bytes(15))


class DecodeTest(unittest.TestCase):
    def test_rgb_gets_opaque_alpha(self):
        data = png(2, 1, 8, 2, unfiltered(bytes([1, 2, 3, 4, 5, 6])))
        self.assertEqual(bytes([1, 2, 3, 255, 4, 5, 6, 255]), pngio.decode(data).pixels)

    def test_rgba_is_read_as_it_is(self):
        data = png(1, 2, 8, 6, unfiltered(bytes([1, 2, 3, 4]), bytes([5, 6, 7, 0])))
        image = pngio.decode(data)
        self.assertEqual((1, 2, bytes([1, 2, 3, 4, 5, 6, 7, 0])), (image.width, image.height, image.pixels))

    def test_grey_with_alpha(self):
        data = png(2, 1, 8, 4, unfiltered(bytes([10, 200, 20, 100])))
        self.assertEqual(bytes([10, 10, 10, 200, 20, 20, 20, 100]), pngio.decode(data).pixels)

    def test_grey_8_bit(self):
        self.assertEqual(bytes([7, 7, 7, 255, 9, 9, 9, 255]), pngio.decode(png(2, 1, 8, 0, unfiltered(bytes([7, 9])))).pixels)

    def test_grey_at_1_2_and_4_bits_scales_to_the_full_range(self):
        # 1 bit 0b10100000 is 1, 0, 1; 2 bits 0b00011011 is 0, 1, 2, 3; 4 bits 0x0F 0x80 is 0, 15, 8.
        cases = [(1, 3, bytes([0b10100000]), [255, 0, 255]), (2, 4, bytes([0b00011011]), [0, 85, 170, 255]),
                 (4, 3, bytes([0x0F, 0x80]), [0, 255, 136])]
        for depth, width, row, expected in cases:
            pixels = pngio.decode(png(width, 1, depth, 0, unfiltered(row))).pixels
            self.assertEqual(expected, list(pixels[0::4]), f"{depth} bit")
            self.assertEqual([255] * width, list(pixels[3::4]), f"{depth} bit alpha")

    def test_a_row_of_sub_byte_samples_starts_on_a_byte(self):
        # 3 pixels at 4 bits fill 2 bytes, so the second row starts at byte 2 and the last nibble of the first row is padding.
        data = png(3, 2, 4, 0, unfiltered(bytes([0x12, 0x30]), bytes([0x45, 0x60])))
        self.assertEqual([17, 34, 51, 68, 85, 102], list(pngio.decode(data).pixels[0::4]))

    def test_palette_at_8_bits_with_and_without_trns(self):
        palette = bytes([255, 0, 0, 0, 255, 0, 0, 0, 255])
        raw = unfiltered(bytes([0, 1, 2]))
        self.assertEqual(bytes([255, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255]), pngio.decode(png(3, 1, 8, 3, raw, palette)).pixels)
        # tRNS names fewer entries than the palette: the rest stay opaque.
        self.assertEqual(bytes([255, 0, 0, 0, 0, 255, 0, 128, 0, 0, 255, 255]), pngio.decode(png(3, 1, 8, 3, raw, palette, bytes([0, 128]))).pixels)

    def test_palette_at_1_2_and_4_bits(self):
        palette = bytes([10, 10, 10, 20, 20, 20, 30, 30, 30, 40, 40, 40])
        cases = [(1, 3, 0b01000000, [10, 20, 10]), (2, 3, 0b00011011, [10, 20, 30]), (4, 2, 0x21, [30, 20])]
        for depth, width, row, expected in cases:
            data = png(width, 1, depth, 3, unfiltered(bytes([row])), palette)
            self.assertEqual(expected, list(pngio.decode(data).pixels[0::4]), f"{depth} bit")

    def test_grey_trns_key_makes_that_value_clear(self):
        data = png(3, 1, 8, 0, unfiltered(bytes([5, 6, 5])), trns=struct.pack(">H", 5))
        self.assertEqual([0, 255, 0], list(pngio.decode(data).pixels[3::4]))

    def test_rgb_trns_key_makes_that_colour_clear(self):
        data = png(2, 1, 8, 2, unfiltered(bytes([1, 2, 3, 4, 5, 6])), trns=bytes([0, 4, 0, 5, 0, 6]))
        self.assertEqual([255, 0], list(pngio.decode(data).pixels[3::4]))

    def test_every_filter_decodes_to_the_same_pixels(self):
        rows = [bytes([10, 20, 30, 40, 50, 60]), bytes([15, 25, 35, 45, 55, 65])]
        for kind in (1, 2, 3, 4):
            second = bytearray([kind])
            for i in range(6):
                left = rows[1][i - 3] if i >= 3 else 0
                up = rows[0][i]
                upleft = rows[0][i - 3] if i >= 3 else 0
                if kind == 1:
                    predictor = left
                elif kind == 2:
                    predictor = up
                elif kind == 3:
                    predictor = (left + up) >> 1
                else:
                    p = left + up - upleft
                    pa, pb, pc = abs(p - left), abs(p - up), abs(p - upleft)
                    predictor = left if pa <= pb and pa <= pc else (up if pb <= pc else upleft)
                second.append((rows[1][i] - predictor) & 255)
            data = png(2, 2, 8, 2, bytes([0]) + rows[0] + bytes(second))
            self.assertEqual(bytes([10, 20, 30, 255, 40, 50, 60, 255, 15, 25, 35, 255, 45, 55, 65, 255]), pngio.decode(data).pixels, f"filter {kind}")

    def test_what_cannot_be_read_is_refused_naming_the_file(self):
        good = unfiltered(bytes([0, 0, 0]))
        cases = {
            "not a PNG": b"GIF89a",
            "no IHDR": pngio.SIGNATURE + chunk(b"IEND", b""),
            "16-bit": png(1, 1, 16, 2, unfiltered(bytes(6))),
            "interlaced": png(1, 1, 8, 2, good, interlace=1),
            "depth 4, colour type 2": png(1, 1, 4, 2, good),
            "without a PLTE": png(1, 1, 8, 3, unfiltered(bytes([0]))),
            "past the 1 entries": png(1, 1, 8, 3, unfiltered(bytes([1])), bytes([1, 2, 3])),
            "unknown PNG filter": png(1, 1, 8, 2, bytes([9, 0, 0, 0])),
            "image data is": png(2, 1, 8, 2, good),
            "does not inflate": pngio.SIGNATURE + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)) + chunk(b"IDAT", b"junk"),
        }
        for reason, data in cases.items():
            with self.subTest(reason):
                with self.assertRaisesRegex(ValueError, f"x.png: .*{reason}"):
                    pngio.decode(data, "x.png")


class StaleTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)
        self.pixels = bytes(range(64))
        self.body = pngio.encode(pngio.Rgba(4, 4, self.pixels))

    def test_a_missing_file_is_stale(self):
        self.assertTrue(pngio.stale(self.dir / "a.png", self.body))

    def test_the_same_pixels_in_other_bytes_are_not_stale(self):
        raw = b"".join(b"\x00" + self.pixels[16 * y:16 * y + 16] for y in range(4))
        other = png(4, 4, 8, 6, raw, level=1)
        self.assertNotEqual(self.body, other)
        (self.dir / "a.png").write_bytes(other)
        self.assertFalse(pngio.stale(self.dir / "a.png", self.body))

    def test_the_same_pixels_in_another_colour_type_are_not_stale(self):
        (self.dir / "a.png").write_bytes(png(1, 1, 8, 2, unfiltered(bytes([1, 2, 3]))))
        self.assertFalse(pngio.stale(self.dir / "a.png", pngio.encode(pngio.Rgba(1, 1, bytes([1, 2, 3, 255])))))

    def test_other_pixels_are_stale(self):
        (self.dir / "a.png").write_bytes(pngio.encode(pngio.Rgba(4, 4, bytes(64))))
        self.assertTrue(pngio.stale(self.dir / "a.png", self.body))

    def test_an_unreadable_png_is_stale(self):
        (self.dir / "a.png").write_bytes(b"junk")
        self.assertTrue(pngio.stale(self.dir / "a.png", self.body))

    def test_a_body_that_is_not_a_png_raises(self):
        (self.dir / "a.png").write_bytes(self.body)
        with self.assertRaises(ValueError):
            pngio.stale(self.dir / "a.png", b"junk")

    def test_a_file_that_is_not_a_png_is_compared_by_bytes(self):
        (self.dir / "a.json").write_bytes(b"{}\n")
        self.assertFalse(pngio.stale(self.dir / "a.json", b"{}\n"))
        self.assertTrue(pngio.stale(self.dir / "a.json", b"{ }\n"))


if __name__ == "__main__":
    unittest.main()
