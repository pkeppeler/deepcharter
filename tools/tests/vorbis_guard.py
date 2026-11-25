"""The guard for a test that writes mono Vorbis (#1332).

Only libvorbis writes mono Vorbis (ffmpeg's native encoder writes stereo), and some ffmpeg builds lack it. Without it such a test skips,
except in CI (`CI=true`), where it fails, so CI never hides these tests. The probe is `first_sounds.has_libvorbis`, the one the tools use.
"""
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "sounds"))

import first_sounds  # noqa: E402

REASON = "ffmpeg without libvorbis cannot write mono Vorbis"


def needs_mono_vorbis(cls):
    """Class decorator: each test of `cls` first asks the probe, and skips (or fails in CI) before its own setUp."""
    set_up = cls.setUp

    def guarded_set_up(self):
        if not first_sounds.has_libvorbis():
            if os.environ.get("CI") == "true":
                self.fail(f"{REASON}, and CI must run this test")
            self.skipTest(REASON)
        set_up(self)

    cls.setUp = guarded_set_up
    return cls
