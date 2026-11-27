"""Tests the keybind allow-list gate in gradle/keybind-gate.gradle.kts (the rule is in its header): CLEAN trees pass, BAD trees fail naming
the file, line and key, and saying to look for a vanilla key or click first.

Its fixture trees go through checkGametestFixtures in one Gradle run (gate_checks.py, gate_batch.py), so it needs the repo's Gradle wrapper
on JDK 25. Its name misses the test_*.py glob of the tool-tests job, which has no JDK 25; the build job and the pre-push hook run it.
Usage: tools/py tools/tests/gate_checks.py keybind
"""
import unittest

import gate_batch
from gate_batch import Case

ALLOW = "keybind-allowlist.txt"
REASON = "no vanilla key or click fits here"


def allow(*ids: str) -> str:
    return "# fixture\n" + "".join(f"{key}  {REASON}\n" for key in ids)


def lang(*ids: str) -> str:
    return "{\n" + ",\n".join(f'  "{key}": "x"' for key in ids) + "\n}\n"


def keys(body: str) -> str:
    return f"class Keys {{\n{body}\n}}\n"


def mapping(first_argument: str) -> str:
    return f"    KeyMapping k = new KeyMapping({first_argument}, Type.KEYBOARD, 1, category);"


LISTED = "key.deepcharter.ping"
# Clean trees, label: files. Each must pass.
CLEAN = {
    "a listed literal": {"Keys.java": keys(mapping(f'"{LISTED}"')), ALLOW: allow(LISTED), "lang.json": lang(LISTED)},
    "a wrapped call and a registration": {
        "Keys.java": keys(f'    KeyMapping k = new KeyMapping(\n        "{LISTED}",\n        Type.KEYBOARD, 1, category);\n'
                          "    void init() { KeyMappingHelper.registerKeyMapping(k); }"),
        ALLOW: allow(LISTED), "lang.json": lang(LISTED)},
    "a prefix with a listed entry under it": {
        "Keys.java": keys(mapping('"key.deepcharter.catalogue." + name')),
        ALLOW: allow("key.deepcharter.catalogue.back"), "lang.json": lang("key.deepcharter.catalogue.back")},
    "a constant in another file": {
        "Keys.java": keys(mapping("Dump.KEY")), "Dump.java": f'class Dump {{ static final String KEY = "{LISTED}"; }}\n',
        ALLOW: allow(LISTED), "lang.json": lang(LISTED)},
    "a key category is not a key": {
        "Keys.java": keys(mapping(f'"{LISTED}"')), ALLOW: allow(LISTED), "lang.json": lang(LISTED, "key.category.deepcharter.main")},
    "a mention in a comment": {
        "Keys.java": keys('    // new KeyMapping("key.deepcharter.gone", t, 1, c)\n    /* new KeyMapping("key.deepcharter.gone2" */'),
        ALLOW: allow(), "lang.json": lang()},
    "a read of a mapping": {"Keys.java": keys("    boolean down = options.keyUse.isDown();"), ALLOW: allow(), "lang.json": lang()},
}
# label: (files, the text the message holds besides the advice).
BAD = {
    "an unlisted literal": (
        {"Keys.java": keys(mapping('"key.deepcharter.newkey"')), ALLOW: allow(), "lang.json": lang()},
        "Keys.java:2 key 'key.deepcharter.newkey' has no entry in"),
    "an unlisted key beside a listed one": (
        {"Keys.java": keys(mapping(f'"{LISTED}"') + "\n" + mapping('"key.deepcharter.other"')), ALLOW: allow(LISTED), "lang.json": lang(LISTED)},
        "Keys.java:3 key 'key.deepcharter.other' has no entry in"),
    "an unlisted constant": (
        {"Keys.java": keys(mapping("KEY")) + 'class Const { static final String KEY = "key.deepcharter.dump"; }\n', ALLOW: allow(), "lang.json": lang()},
        "Keys.java:2 key 'key.deepcharter.dump' has no entry in"),
    "a prefix with nothing under it": (
        {"Keys.java": keys(mapping('"key.deepcharter.catalogue." + name')), ALLOW: allow(LISTED), "lang.json": lang(LISTED)},
        "Keys.java:2 keys named from 'key.deepcharter.catalogue.' have no entry in"),
    "an id that cannot be read": (
        {"Keys.java": keys(mapping("idFor(slot)")), ALLOW: allow(), "lang.json": lang()},
        "Keys.java:2 cannot read the key id from 'new KeyMapping(idFor(slot)'"),
    "a registration of a key made elsewhere": (
        {"Keys.java": keys("    void init() {\n        KeyMappingHelper.registerKeyMapping(Other.KEY);\n    }"), ALLOW: allow(), "lang.json": lang()},
        "Keys.java:3 registers a key this file does not create"),
    "a lang key with no entry": (
        {"Keys.java": keys(mapping(f'"{LISTED}"')), ALLOW: allow(LISTED), "lang.json": lang(LISTED, "key.deepcharter.hidden")},
        "lang.json:3 key 'key.deepcharter.hidden' has no entry in"),
    "a stale entry": (
        {"Keys.java": keys(mapping(f'"{LISTED}"')), ALLOW: allow(LISTED, "key.deepcharter.retired"), "lang.json": lang(LISTED)},
        "entry 'key.deepcharter.retired' matches no key"),
    "an entry with no reason": (
        {"Keys.java": keys(mapping(f'"{LISTED}"')), ALLOW: f"{LISTED}\n", "lang.json": lang(LISTED)},
        f"{ALLOW}:1 '{LISTED}' needs a reason of at least 3 words"),
    "an entry with a one-line reason": (
        {"Keys.java": keys(mapping(f'"{LISTED}"')), ALLOW: f"{LISTED}  because\n", "lang.json": lang(LISTED)},
        f"{ALLOW}:1 '{LISTED}' needs a reason of at least 3 words"),
    "a duplicate entry": (
        {"Keys.java": keys(mapping(f'"{LISTED}"')), ALLOW: allow(LISTED, LISTED), "lang.json": lang(LISTED)},
        f"{ALLOW}:3 '{LISTED}' is listed twice"),
    "a line that is not an id": (
        {"Keys.java": keys(mapping(f'"{LISTED}"')), ALLOW: allow(LISTED) + f"handbook  {REASON}\n", "lang.json": lang(LISTED)},
        f"{ALLOW}:3 'handbook' is not a key id"),
}

CASES = {
    **{label: Case(files, None, gate="keybind") for label, (files, _) in BAD.items()},
    **{f"clean/{label}": Case(files, None, gate="keybind") for label, files in CLEAN.items()},
}


def outcome(label: str) -> gate_batch.Result:
    return gate_batch.result(f"{__name__}/{label}")


class KeybindGateTest(unittest.TestCase):
    def test_each_clean_tree_passes(self):
        for label in CLEAN:
            with self.subTest(label):
                result = outcome(f"clean/{label}")
                self.assertTrue(result.ok, result.message)

    def test_a_bad_tree_fails_and_says_to_look_for_a_vanilla_key(self):
        for label, (_, expected) in BAD.items():
            with self.subTest(label):
                result = outcome(label)
                self.assertFalse(result.ok, f"the gate should fail the build for {label}")
                self.assertIn(expected, result.message)
                self.assertIn("a vanilla key or a click is what players already know", result.message)
