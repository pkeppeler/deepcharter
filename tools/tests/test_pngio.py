"""Tests tools/lookbook/pngio.py with tiny PNGs built chunk by chunk."""
import struct
import sys
import unittest
import zlib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "lookbook"))
import pngio  # noqa: E402


def chunk(kind, body):
    return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)


def png(width, height, depth, colour, rows, plte=b"", trns=b""):
    """A PNG of filter-0 rows (bytes each, already packed at the bit depth)."""
    raw = b"".join(b"\x00" + row for row in rows)
    body = chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, depth, colour, 0, 0, 0))
    if plte:
        body += chunk(b"PLTE", plte)
    if trns:
        body += chunk(b"tRNS", trns)
    return pngio.PNG_SIGNATURE + body + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")


class PngioTest(unittest.TestCase):
    def test_rgba_round_trip_keeps_alpha(self):
        rgba = bytes([10, 20, 30, 0, 40, 50, 60, 128, 70, 80, 90, 255, 1, 2, 3, 4])
        image = pngio.decode_rgba("t", pngio.RgbaImage(2, 2, rgba).to_png())
        self.assertEqual((2, 2, rgba), (image.width, image.height, image.rgba))

    def test_four_bit_palette_with_transparency(self):
        # Two pixels a row, two rows: indexes 0 1 / 2 0. Entry 0 is transparent, the others opaque.
        plte = bytes([9, 9, 9, 200, 0, 0, 0, 0, 200])
        data = png(2, 2, 4, 3, [bytes([0x01]), bytes([0x20])], plte=plte, trns=bytes([0]))
        image = pngio.decode_rgba("t", data)
        self.assertEqual(bytes([9, 9, 9, 0, 200, 0, 0, 255, 0, 0, 200, 255, 9, 9, 9, 0]), image.rgba)

    def test_two_bit_grey_scales_to_full_range(self):
        data = png(4, 1, 2, 0, [bytes([0b00011011])])
        image = pngio.decode_rgba("t", data)
        self.assertEqual([0, 85, 170, 255], list(image.rgba[0::4]))
        self.assertEqual([255] * 4, list(image.rgba[3::4]))

    def test_grey_with_alpha(self):
        data = png(2, 1, 8, 4, [bytes([100, 7, 200, 255])])
        self.assertEqual(bytes([100, 100, 100, 7, 200, 200, 200, 255]), pngio.decode_rgba("t", data).rgba)

    def test_rgb_colour_key_is_transparent(self):
        data = png(2, 1, 8, 2, [bytes([1, 2, 3, 4, 5, 6])], trns=struct.pack(">HHH", 4, 5, 6))
        self.assertEqual(bytes([1, 2, 3, 255, 4, 5, 6, 0]), pngio.decode_rgba("t", data).rgba)

    def test_sixteen_bit_is_refused_with_the_name(self):
        data = png(1, 1, 16, 2, [bytes(6)])
        with self.assertRaisesRegex(ValueError, "^odd.png: unsupported PNG"):
            pngio.decode_rgba("odd.png", data)

    def test_all_png_filters_decode(self):
        # A 2 x 2 RGB image written with filter types 1 to 4 on its second row, decoded back to the same pixels.
        rows = [bytes([10, 20, 30, 40, 50, 60]), bytes([15, 25, 35, 45, 55, 65])]
        for kind in (1, 2, 3, 4):
            first = bytearray([0]) + rows[0]
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
            raw = bytes(first) + bytes(second)
            self.assertEqual(rows[0] + rows[1], bytes(pngio.unfilter(raw, 6, 2, 3, "t")), f"filter {kind}")


if __name__ == "__main__":
    unittest.main()
