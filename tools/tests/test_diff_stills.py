"""Tests tools/diff-stills with tiny generated PNGs."""
import contextlib
import importlib.machinery
import importlib.util
import io
import tempfile
import unittest
from pathlib import Path

TOOL = Path(__file__).resolve().parent.parent / "diff-stills"
loader = importlib.machinery.SourceFileLoader("diff_stills", str(TOOL))
spec = importlib.util.spec_from_loader("diff_stills", loader)
ds = importlib.util.module_from_spec(spec)
loader.exec_module(ds)

W, H = 4, 3


def png(path, pixel_at):
    """Writes a W x H PNG whose pixel (x, y) is pixel_at(x, y), an (r, g, b) tuple."""
    rgb = bytearray()
    for y in range(H):
        for x in range(W):
            rgb += bytes(pixel_at(x, y))
    Path(path).write_bytes(ds.encode_png(ds.Image(W, H, bytes(rgb))))


def grey(x, y):
    return (40 * x, 40 * y, 100)


def run(*args):
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        try:
            code = ds.main(list(args))
        except SystemExit as exit_:
            code = exit_.code
    return code, out.getvalue(), err.getvalue()


class DiffStillsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.a = Path(self.tmp.name) / "a"
        self.b = Path(self.tmp.name) / "b"
        self.out = Path(self.tmp.name) / "out"
        self.a.mkdir()
        self.b.mkdir()

    def test_same_stills_report_nothing(self):
        png(self.a / "x.png", grey)
        png(self.b / "x.png", grey)
        code, out, _ = run(str(self.a), str(self.b))
        self.assertEqual(0, code)
        self.assertIn("0 of 1 stills differ", out)

    def test_changed_pixels_are_counted_and_images_written(self):
        png(self.a / "same.png", grey)
        png(self.b / "same.png", grey)
        png(self.a / "moved.png", grey)
        # Two of the twelve pixels change, one by 140 and one by 50.
        png(self.b / "moved.png", lambda x, y: {(0, 0): (140, 0, 100), (3, 2): (120, 130, 100)}.get((x, y), grey(x, y)))
        code, out, _ = run(str(self.a), str(self.b), "--out", str(self.out))
        self.assertEqual(1, code)
        self.assertIn("moved", out)
        self.assertNotIn("same ", out)
        self.assertIn("16.67%", out)
        self.assertIn("140", out)
        self.assertIn("1 of 2 stills differ", out)
        side = ds.decode_png(self.out / "moved-side-by-side.png")
        self.assertEqual((2 * W, H), (side.width, side.height))
        diff = ds.decode_png(self.out / "moved-diff.png")
        self.assertEqual(b"\xff\x00\x00", diff.rgb[0:3])
        self.assertFalse((self.out / "same-diff.png").exists())

    def test_tolerance_hides_small_differences(self):
        png(self.a / "x.png", grey)
        png(self.b / "x.png", lambda x, y: tuple(v + 3 for v in grey(x, y)))
        self.assertEqual(1, run(str(self.a), str(self.b), "--tolerance", "2")[0])
        code, out, _ = run(str(self.a), str(self.b), "--tolerance", "3")
        self.assertEqual(0, code)
        self.assertIn("0 of 1 stills differ", out)

    def test_missing_and_resized_stills_are_listed(self):
        png(self.a / "gone.png", grey)
        png(self.b / "new.png", grey)
        png(self.a / "both.png", grey)
        Path(self.b / "both.png").write_bytes(ds.encode_png(ds.Image(2, 2, bytes(12))))
        code, out, _ = run(str(self.a), str(self.b))
        self.assertEqual(1, code)
        self.assertIn("only in A", out)
        self.assertIn("only in B", out)
        self.assertIn("size 4x3 vs 2x2", out)

    def test_reads_the_screenshots_subdirectory(self):
        (self.a / "screenshots").mkdir()
        png(self.a / "screenshots" / "x.png", grey)
        png(self.b / "x.png", grey)
        self.assertEqual(0, run(str(self.a), str(self.b))[0])

    def test_empty_directories_are_an_error(self):
        code, _, err = run(str(self.a), str(self.b))
        self.assertEqual(2, code)
        self.assertIn("no PNGs", err)

    def test_noise_floor_hides_a_noisy_family_lists_it_and_keeps_a_real_change(self):
        # 12 pixels: "lava-noisy" changes 2 of them by 60 (16.67%), "grass" changes 2 of them by 60 too.
        for name in ("lava-noisy", "grass"):
            png(self.a / f"{name}.png", grey)
            png(self.b / f"{name}.png", lambda x, y: (100, 100, 100) if (x, y) in ((0, 0), (1, 1)) else grey(x, y))
        code, out, _ = run(str(self.a), str(self.b), "--noise", "lava=2:20", "--noise", "=2:1")
        self.assertEqual(1, code)
        table, _, hidden = out.partition("1 stills hidden by floors")
        self.assertNotIn("lava-noisy", table)
        self.assertIn("grass", table)
        self.assertIn("lava-noisy", hidden)
        self.assertIn("16.67%", hidden)
        self.assertIn("floors applied", out)
        # A larger tolerance for the family also hides it, and the longest prefix wins over the catch-all.
        code, out, _ = run(str(self.a), str(self.b), "--noise", "lava=200:0", "--noise", "=2:1")
        self.assertIn("1 of 2 stills differ (floors applied)", out)
        self.assertIn("1 stills hidden by floors", out)

    def test_floors_that_hide_everything_exit_zero_and_say_what_they_hid(self):
        png(self.a / "x.png", grey)
        png(self.b / "x.png", lambda x, y: (100, 100, 100) if (x, y) == (0, 0) else grey(x, y))
        code, out, _ = run(str(self.a), str(self.b), "--noise", "x=2:50")
        self.assertEqual(0, code)
        self.assertIn("1 stills hidden by floors", out)
        self.assertIn("0 of 1 stills differ (floors applied)", out)

    def test_design_tour_uses_the_noise_families(self):
        self.assertTrue(any(prefix == "layer-2-cave-" for prefix, _, _ in ds.NOISE_FAMILIES))
        # 200 pixels, 2 of them (1%) changed by 60: under the 6% floor of the lava caves, and at the 1% catch-all.
        cave = {(0, 0), (1, 0)}
        for name in ("layer-2-cave-south", "surface-south-noon"):
            ds_png = ds.encode_png(ds.Image(20, 10, bytes(600)))
            (self.a / f"{name}.png").write_bytes(ds_png)
            changed = bytearray(600)
            for x, _ in cave:
                changed[3 * x:3 * x + 3] = b"\x3c\x3c\x3c"
            (self.b / f"{name}.png").write_bytes(ds.encode_png(ds.Image(20, 10, bytes(changed))))
        code, out, _ = run(str(self.a), str(self.b), "--design-tour")
        self.assertEqual(0, code)
        self.assertIn("2 stills hidden by floors", out)
        self.assertIn("layer-2-cave-south", out)
        self.assertIn("floors applied", out)

    def test_design_tour_with_an_explicit_tolerance_is_a_usage_error(self):
        png(self.a / "x.png", grey)
        png(self.b / "x.png", grey)
        code, _, err = run(str(self.a), str(self.b), "--design-tour", "--tolerance", "2")
        self.assertEqual(2, code)
        self.assertIn("--design-tour", err)

    def test_equal_rows_are_skipped_and_the_rest_still_compared(self):
        a = ds.Image(4, 3, bytes(36))
        changed = bytearray(36)
        changed[3 * 4 + 5] = 90
        b = ds.Image(4, 3, bytes(changed))
        count, biggest, mask = ds.compare(a, b, 2)
        self.assertEqual((1, 90), (count, biggest))
        self.assertEqual(1, mask[5])
        self.assertEqual(1, sum(mask))

    def test_bad_noise_spec_is_a_usage_error(self):
        png(self.a / "x.png", grey)
        png(self.b / "x.png", grey)
        self.assertEqual(2, run(str(self.a), str(self.b), "--noise", "oops")[0])


if __name__ == "__main__":
    unittest.main()
