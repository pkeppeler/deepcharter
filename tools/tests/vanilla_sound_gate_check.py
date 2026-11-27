"""Tests the vanilla sound gate in gradle/vanilla-sound-gate.gradle.kts (the rule is in its header): CLEAN trees pass, BAD trees fail naming
the file, line and banned token, and saying to use a mod sound or mark the line `// vanilla-ok: <reason>`.

Its fixture trees go through checkGametestFixtures in one Gradle run (gate_checks.py, gate_batch.py), so it needs the repo's Gradle wrapper
on JDK 25. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build job and the pre-push hook run it.
Usage: tools/py tools/tests/gate_checks.py vanilla_sound
"""
import unittest

import gate_batch
from gate_batch import Case

LIST = "banned-vanilla-sounds.txt"
REASON = "it breaks the tone of the depth"
BANNED = (
    f"# fixture\nSoundEvents.GENERIC_EXPLODE  {REASON}\nSoundEvents.MUSIC_*  {REASON}\nMusics.*  {REASON}\n"
    f".explode(  {REASON}\nSoundEvents.AMBIENT_CAVE  {REASON}\n"
)


def source(body: str) -> str:
    return f"class Sounds {{\n{body}\n}}\n"


def files(body: str, banned: str = BANNED) -> dict[str, str]:
    return {"Sounds.java": source(body), LIST: banned}


PLAY = "    void breach() {\n        level.playSound(null, pos, %s, SoundSource.BLOCKS, 1f, 1f);\n    }"
# Clean trees, label: files. Each must pass.
CLEAN = {
    "a mod sound": files(PLAY % "DeepSound.POD_EXPLOSION.event()"),
    "a marked line above": files("    // vanilla-ok: the only explosion sound a creeper's fuse needs\n    var s = SoundEvents.GENERIC_EXPLODE;"),
    "a trailing marker": files("    var s = SoundEvents.GENERIC_EXPLODE; // vanilla-ok: a stand-in the pack replaces"),
    "a marker in a wrapped statement": files(
        "    var s = pick(\n        SoundEvents.GENERIC_EXPLODE,\n        other); // vanilla-ok: kept on purpose in this test"),
    "a mention in a comment": files("    // SoundEvents.GENERIC_EXPLODE is banned\n    /* Musics.GAME */\n    int x = 0;"),
    "a mention in a string": files('    String s = "SoundEvents.GENERIC_EXPLODE";'),
    "a longer name": files("    var s = SoundEvents.GENERIC_EXPLODE_2; var m = MyMusics.GAME;"),
    "a sound off the list": files("    var s = SoundEvents.STONE_BREAK;"),
    "an empty list bans nothing": files("    var s = SoundEvents.GENERIC_EXPLODE;", banned="# nothing\n"),
}
# label: (files, line the gate reports, the token).
BAD = {
    "the explosion": (files("    var s = SoundEvents.GENERIC_EXPLODE;"), 2, "SoundEvents.GENERIC_EXPLODE"),
    "the explosion played": (files(PLAY % "SoundEvents.GENERIC_EXPLODE.value()"), 3, "SoundEvents.GENERIC_EXPLODE"),
    "a fully qualified name": (files("    var s = net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE;"), 2, "SoundEvents.GENERIC_EXPLODE"),
    "a wildcard sound": (files("    var s = SoundEvents.MUSIC_DISC_13;"), 2, "SoundEvents.MUSIC_*"),
    "a music constant": (files("    var m = Musics.GAME;"), 2, "Musics.*"),
    "a method token": (files("    level.explode(null, 1.0, 2.0, 3.0, 4f, false);"), 2, ".explode("),
    "an ambient sound": (files("    var s = SoundEvents.AMBIENT_CAVE;"), 2, "SoundEvents.AMBIENT_CAVE"),
    "a marker with no reason": (files("    var s = SoundEvents.GENERIC_EXPLODE; // vanilla-ok:"), 2, "SoundEvents.GENERIC_EXPLODE"),
    "a marker two statements away": (files("    // vanilla-ok: for the first only\n    var a = 1;\n    var s = SoundEvents.GENERIC_EXPLODE;"), 4, "SoundEvents.GENERIC_EXPLODE"),
    "a later line": (files("    int x = 0;\n    var s = SoundEvents.MUSIC_GAME;"), 3, "SoundEvents.MUSIC_*"),
    "a static import": (
        {"Sounds.java": "import static net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE;\nclass Sounds {}\n", LIST: BANNED}, 1, "SoundEvents.GENERIC_EXPLODE"),
}
# label: (files, the message). A malformed list fails the build too, so a typo does not silently ban nothing.
BAD_LIST = {
    "a token with no reason": (files("    int x = 0;", banned="SoundEvents.GENERIC_EXPLODE\n"), f"{LIST}:1 'SoundEvents.GENERIC_EXPLODE' needs a reason of at least 3 words"),
    "a token that matches everything": (files("    int x = 0;", banned=f"*  {REASON}\n"), f"{LIST}:1 '*' matches everything"),
}

CASES = {
    **{label: Case(tree, None, gate="vanilla_sound") for label, (tree, _, _) in BAD.items()},
    **{f"list/{label}": Case(tree, None, gate="vanilla_sound") for label, (tree, _) in BAD_LIST.items()},
    **{f"clean/{label}": Case(tree, None, gate="vanilla_sound") for label, tree in CLEAN.items()},
}


def outcome(label: str) -> gate_batch.Result:
    return gate_batch.result(f"{__name__}/{label}")


class VanillaSoundGateTest(unittest.TestCase):
    def test_each_clean_tree_passes(self):
        for label in CLEAN:
            with self.subTest(label):
                result = outcome(f"clean/{label}")
                self.assertTrue(result.ok, result.message)

    def test_a_banned_sound_fails_and_says_to_use_a_mod_sound_or_mark_it(self):
        for label, (_, line, token) in BAD.items():
            with self.subTest(label):
                result = outcome(label)
                self.assertFalse(result.ok, f"the gate should fail the build for {label}")
                self.assertIn(f"Sounds.java:{line} banned vanilla sound '{token}'", result.message)
                self.assertIn("'// vanilla-ok: <reason>'", result.message)

    def test_a_malformed_list_fails(self):
        for label, (_, message) in BAD_LIST.items():
            with self.subTest(label):
                result = outcome(f"list/{label}")
                self.assertFalse(result.ok, f"the gate should fail the build for {label}")
                self.assertIn(message, result.message)
