"""Shipped lang text is final (#1333).

Two checks, pure Python so the CI `tool-tests` job (no JDK) runs them:

- No string in a shipped lang fragment (`src/lang/**/*.json`) holds `[PLACEHOLDER`: a stand-in must never reach a player again.
- The body of each transmission the script marks for the vertical slice (T01 to T18) is the player-facing quote block of
  `docs/lore/transmissions.md`, word for word. The framing lines in backticks (`▌INCOMING — …`, `▌SIGNAL LOST`) are the
  framing's, not the body's, and a rotating transmission ships its first block. A canon edit that is not ported, or a lang edit that
  rewrites canon, fails here.
"""
import json
import re
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = Path("docs/lore/transmissions.md")
TRANSMISSION_LANG = Path("src/lang/en_us/transmission.json")
PLACEHOLDER = "[PLACEHOLDER"
SLICE = [f"T{number:02d}" for number in range(1, 19)]

HEADING = re.compile(r"^\*\*(T\d\d[a-z]?) ·")
FRAMING_LINE = re.compile(r"^`▌[^`]*`$")


def placeholders(root: Path) -> list[str]:
    """Each lang string that still holds a placeholder, as `<file>: <key>`."""
    found: list[str] = []
    for path in sorted(root.glob("src/lang/**/*.json")):
        for key, value in json.loads(path.read_text(encoding="utf-8")).items():
            if isinstance(value, str) and PLACEHOLDER in value:
                found.append(f"{path.relative_to(root)}: {key}")
    return found


def canon_bodies(script: str) -> dict[str, str]:
    """The first quote block under each `**Tnn · …**` heading, as the lang body: paragraphs joined by a blank line, framing dropped."""
    bodies: dict[str, str] = {}
    current: str | None = None
    paragraphs: list[str] = []
    in_block = False
    for line in script.splitlines():
        heading = HEADING.match(line)
        if heading or line.startswith("## "):
            current = heading.group(1) if heading else None
            paragraphs, in_block = [], False
            continue
        if current is None or current in bodies and not in_block:
            continue
        if line.startswith(">"):
            in_block = True
            text = line[1:].strip()
            if text and not FRAMING_LINE.match(text):
                paragraphs.append(text)
        elif in_block:
            bodies[current] = "\n\n".join(paragraphs)
            in_block = False
    if current is not None and in_block and current not in bodies:
        bodies[current] = "\n\n".join(paragraphs)
    return bodies


def canon_mismatches(root: Path) -> list[str]:
    """Each slice transmission whose lang body is not its canon text."""
    canon = canon_bodies((root / SCRIPT).read_text(encoding="utf-8"))
    lang = json.loads((root / TRANSMISSION_LANG).read_text(encoding="utf-8"))
    found: list[str] = []
    for name in SLICE:
        key = f"deepcharter.transmission.{name.lower()}.body"
        if name not in canon:
            found.append(f"{name}: no quote block in {SCRIPT}")
        elif lang.get(key) != canon[name]:
            found.append(f"{name}: {key} is {lang.get(key)!r}, the script says {canon[name]!r}")
    return found


class LangTextTest(unittest.TestCase):
    def test_no_shipped_lang_string_is_a_placeholder(self) -> None:
        self.assertEqual(placeholders(ROOT), [], "a lang string still holds " + PLACEHOLDER)

    def test_the_slice_transmissions_are_the_canon_text(self) -> None:
        self.assertEqual(canon_mismatches(ROOT), [])

    def test_a_placeholder_in_any_fragment_is_found(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            fragment = root / "src/lang/en_us/fixture.json"
            fragment.parent.mkdir(parents=True)
            fragment.write_text(json.dumps({"a.done": "Done.", "a.stub": "[PLACEHOLDER #12] Later."}), encoding="utf-8")
            self.assertEqual(placeholders(root), ["src/lang/en_us/fixture.json: a.stub"])

    def test_canon_bodies_drop_framing_join_paragraphs_and_take_the_first_rotation(self) -> None:
        script = "\n".join([
            "## Act",
            "**T01 · Event · Personnel**",
            "> First paragraph.",
            ">",
            "> Second paragraph.",
            "",
            "**T02 · Zone · source unknown**",
            "> `▌INCOMING — [SOURCE UNKNOWN]`",
            ">",
            "> …static…",
            ">",
            "> `▌SIGNAL LOST`",
            "",
            "**T03 · Event · Personnel** (rotate)",
            "> One.",
            "",
            "> Two.",
            "",
            "*(A note for the team.)*",
            "## Next act",
            "> Not under a transmission.",
        ])
        self.assertEqual(
            canon_bodies(script),
            {"T01": "First paragraph.\n\nSecond paragraph.", "T02": "…static…", "T03": "One."},
        )


if __name__ == "__main__":
    unittest.main()
