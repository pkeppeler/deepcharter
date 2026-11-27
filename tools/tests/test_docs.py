"""Docs gate: no docs/**/*.md file repeats a heading under the same parent, no markdown link points at a file that
is gone, and every version the README and docs/PLAYING.md quote matches the build.

A heading is a duplicate when the same text appears at the same level with the same chain of ancestor headings, so a repeated
sub-heading such as "Knobs" under two different sections is fine, and a section pasted twice (the corruption this guards against)
is not. Headings inside fenced code blocks do not count.
"""
import os
import re
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def generated_docs():
    """The markdown files the merge tools generate (tools/lib/generated-paths.sh), by repo-relative path. The landing
    regenerates them after CI has run, so no check here may fail on their content (#1567)."""
    text = (ROOT / "tools" / "lib" / "generated-paths.sh").read_text(encoding="utf-8")
    listed = re.search(r"^GENERATED_PATHS=\((.*)\)$", text, re.MULTILINE)
    assert listed, "tools/lib/generated-paths.sh has no GENERATED_PATHS list"
    return {name for name in listed.group(1).split() if name.endswith(".md")}
HEADING = re.compile(r"^(#{1,6})[ \t]+(.+?)[ \t]*#*[ \t]*$")
FENCE = re.compile(r"^[ \t]{0,3}(```|~~~)")


def duplicates(text):
    """(line, first_line, heading) for each heading that repeats an earlier one under the same parent chain."""
    seen = {}
    stack = []  # (level, text) of the open ancestors
    found = []
    fence = None
    for number, line in enumerate(text.splitlines(), 1):
        fenced = FENCE.match(line)
        if fenced:
            fence = None if fence == fenced.group(1) else fence or fenced.group(1)
            continue
        match = None if fence else HEADING.match(line)
        if not match:
            continue
        level, title = len(match.group(1)), match.group(2)
        while stack and stack[-1][0] >= level:
            stack.pop()
        key = (tuple(t for _, t in stack), level, title)
        if key in seen:
            found.append((number, seen[key], title))
        else:
            seen[key] = number
        stack.append((level, title))
    return found


# The target of [text](target) and ![alt](target); a "title" after a space is not part of it.
LINK = re.compile(r"\]\(([^)\s]+)")
INLINE_CODE = re.compile(r"`[^`\n]*`")
# Never committed (XGen's assets, never committed), so absent in CI and in a fresh checkout; docs may still point at them.
IGNORED_DIRS = ("original_flash_game", "private")
EXTERNAL = re.compile(r"^(?:[a-zA-Z][a-zA-Z0-9+.-]*:|#)")


def link_targets(text):
    """(line, target) for each markdown link outside code fences and inline code."""
    found = []
    fence = None
    for number, line in enumerate(text.splitlines(), 1):
        fenced = FENCE.match(line)
        if fenced:
            fence = None if fence == fenced.group(1) else fence or fenced.group(1)
            continue
        if fence:
            continue
        for match in LINK.finditer(INLINE_CODE.sub("", line)):
            found.append((number, match.group(1)))
    return found


def dangling(path, root):
    """(line, target) for each relative link in the markdown file `path` whose file does not exist.

    Skips URLs with a scheme (http, https, mailto), pure #anchors and the IGNORED_DIRS (never committed).
    Targets resolve against the linking file; a #fragment or ?query is dropped; a leading / is the repo root.
    """
    missing = []
    for line, target in link_targets(path.read_text(encoding="utf-8")):
        if EXTERNAL.match(target):
            continue
        file_part = re.split(r"[#?]", target, maxsplit=1)[0]
        if not file_part:
            continue
        base = root if file_part.startswith("/") else path.parent
        resolved = Path(os.path.normpath(base / file_part.lstrip("/")))
        inside = resolved.relative_to(root).parts if resolved.is_relative_to(root) else ()
        if inside[:1] and inside[0] in IGNORED_DIRS:
            continue
        if not resolved.exists():
            missing.append((line, target))
    return missing


def tracked_markdown():
    """README.md, docs/**/*.md (ADRs included: none cites a moved file today) and .claude/**/*.md."""
    paths = [ROOT / "README.md"]
    paths += sorted((ROOT / "docs").rglob("*.md"))
    paths += sorted((ROOT / ".claude").rglob("*.md"))
    generated = generated_docs()
    return [p for p in paths if "worktrees" not in p.relative_to(ROOT).parts and p.relative_to(ROOT).as_posix() not in generated]


class DocLinks(unittest.TestCase):
    def test_no_markdown_link_points_at_a_missing_file(self):
        problems = [
            f"{path.relative_to(ROOT)}:{line}: {target}" for path in tracked_markdown() for line, target in dangling(path, ROOT)
        ]
        self.assertEqual([], problems, "links to files that do not exist (a renamed or deleted file?)")

    def test_a_dangling_link_is_caught(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "docs").mkdir()
            (root / "gradle").mkdir()
            (root / "gradle" / "here.kts").write_text("")
            page = root / "docs" / "page.md"
            page.write_text(
                "[ok](../gradle/here.kts#L3) [gone](../gradle/gone.gradle) [abs](/gradle/gone.kts)\n"
                "[web](https://example.com/x) [mail](mailto:a@b.c) [top](#top) [ign](../original_flash_game/R.md)\n"
                "`[code](nope.md)`\n```\n[fenced](nope.md)\n```\n"
            )
            self.assertEqual([(1, "../gradle/gone.gradle"), (1, "/gradle/gone.kts")], dangling(page, root))


# What a doc may quote, and where the build sets it: gradle/libs.versions.toml, the Java release in build.gradle.kts,
# and the Sodium and Lithium jars in tools/package-friends-build.sh. A line with an HTML comment
# "<!-- version-ok: reason -->" may mention another version (Java 17 mods, Minecraft 1.21 worlds).
NUMBER = r"(\d+(?:\.\d+)+)"
TOML = "gradle/libs.versions.toml"
FRIENDS = "tools/package-friends-build.sh"
QUOTED_VERSIONS = (
    ("Fabric Loader", re.compile(rf"Fabric Loader {NUMBER}"), "fabricLoader", TOML),
    ("Fabric API", re.compile(r"Fabric API (\d+(?:\.\d+)+(?:\+[\w.]+)?)"), "fabricApi", TOML),
    ("Minecraft", re.compile(rf"Minecraft {NUMBER}"), "minecraft", TOML),
    ("Java", re.compile(r"\b(?:Java|JDK) (\d+)\b"), "javaRelease", "build.gradle.kts"),
    ("Sodium", re.compile(rf"Sodium {NUMBER}"), "sodium", FRIENDS),
    ("Lithium", re.compile(rf"Lithium {NUMBER}"), "lithium", FRIENDS),
)
VERSION_OK = re.compile(r"<!--\s*version-ok:\s*\S")
VERSION_DOCS = ("README.md", "docs/PLAYING.md")
CURRENT_FIXTURE = {
    "fabricLoader": "0.19.5",
    "fabricApi": "0.164.0+26.3",
    "minecraft": "26.3",
    "javaRelease": "25",
    "sodium": "0.9.2",
    "lithium": "0.26.2",
}


def build_versions(root):
    """The versions the build sets: the [versions] table of libs.versions.toml, javaRelease, sodium and lithium."""
    found = dict(re.findall(r'^(\w+)\s*=\s*"([^"]*)"', (root / "gradle/libs.versions.toml").read_text(), re.M))
    release = re.search(r"options\.release\s*=\s*(\d+)", (root / "build.gradle.kts").read_text())
    found["javaRelease"] = release.group(1) if release else ""
    script = (root / "tools/package-friends-build.sh").read_text()
    for mod in ("sodium", "lithium"):
        jar = re.search(rf"{mod}-fabric-{NUMBER}\+mc", script)
        found[mod] = jar.group(1) if jar else ""
    return found


def stale_versions(text, name, current):
    """One message for each version `text` quotes that the build does not set.

    A Fabric API quote may leave off the "+<minecraft>" suffix, so 0.164.0 matches 0.164.0+26.3. A line with
    "<!-- version-ok: reason -->" is skipped.
    """
    problems = []
    for number, line in enumerate(text.splitlines(), 1):
        if VERSION_OK.search(line):
            continue
        prose = INLINE_CODE.sub("", line)
        for label, pattern, key, where in QUOTED_VERSIONS:
            for match in pattern.finditer(prose):
                want = current[key]
                if match.group(1) not in (want, want.split("+")[0]):
                    problems.append(
                        f"{name}:{number}: stale '{match.group(0)}', the build has {label} {want} ({where});"
                        " if the line means another version on purpose, add <!-- version-ok: reason -->"
                    )
    return problems


class DocVersions(unittest.TestCase):
    def test_the_versions_the_docs_quote_are_the_builds(self):
        current = build_versions(ROOT)
        problems = []
        for name in VERSION_DOCS:
            problems += stale_versions((ROOT / name).read_text(encoding="utf-8"), name, current)
        self.assertEqual([], problems, "a doc quotes a version the build no longer uses")

    def test_a_line_marked_version_ok_may_name_another_version(self):
        text = "Java 17 mods and Minecraft 1.21 worlds. <!-- version-ok: players' old mods -->\n"
        self.assertEqual([], stale_versions(text, "d.md", CURRENT_FIXTURE))
        self.assertEqual(2, len(stale_versions(text.split(" <!--")[0], "d.md", CURRENT_FIXTURE)))

    def test_a_stale_version_fails_and_names_the_right_one(self):
        text = "Fabric Loader 0.19.4, Fabric API 0.162.0, Minecraft 26.2, Java 21, Sodium 0.9.1, Lithium 0.2.\n"
        hint = "; if the line means another version on purpose, add <!-- version-ok: reason -->"
        self.assertEqual(
            [
                f"d.md:1: stale 'Fabric Loader 0.19.4', the build has Fabric Loader 0.19.5 ({TOML}){hint}",
                f"d.md:1: stale 'Fabric API 0.162.0', the build has Fabric API 0.164.0+26.3 ({TOML}){hint}",
                f"d.md:1: stale 'Minecraft 26.2', the build has Minecraft 26.3 ({TOML}){hint}",
                f"d.md:1: stale 'Java 21', the build has Java 25 (build.gradle.kts){hint}",
                f"d.md:1: stale 'Sodium 0.9.1', the build has Sodium 0.9.2 ({FRIENDS}){hint}",
                f"d.md:1: stale 'Lithium 0.2', the build has Lithium 0.26.2 ({FRIENDS}){hint}",
            ],
            stale_versions(text, "d.md", CURRENT_FIXTURE),
        )

    def test_a_fabric_api_quote_with_the_wrong_suffix_is_stale(self):
        hint = "; if the line means another version on purpose, add <!-- version-ok: reason -->"
        self.assertEqual(
            [f"d.md:1: stale 'Fabric API 0.164.0+wrong', the build has Fabric API 0.164.0+26.3 ({TOML}){hint}"],
            stale_versions("Fabric API 0.164.0+wrong\n", "d.md", CURRENT_FIXTURE),
        )

    def test_current_versions_pass(self):
        text = (
            "Fabric Loader 0.19.5, Fabric API 0.164.0 or Fabric API 0.164.0+26.3, Minecraft 26.3, JDK 25,"
            " Sodium 0.9.2, Lithium 0.26.2.\nMinecraft Java Edition\n"
        )
        self.assertEqual([], stale_versions(text, "d.md", CURRENT_FIXTURE))

    def test_the_build_files_still_say_where_the_versions_are(self):
        current = build_versions(ROOT)
        for key in CURRENT_FIXTURE:
            self.assertTrue(current.get(key), f"{key} not found in the build files; the docs gate cannot read it")


class DocHeadings(unittest.TestCase):
    def test_no_doc_repeats_a_heading_under_the_same_parent(self):
        problems = []
        for path in sorted((ROOT / "docs").rglob("*.md")):
            if path.relative_to(ROOT).as_posix() in generated_docs():
                continue
            for line, first, title in duplicates(path.read_text(encoding="utf-8")):
                problems.append(f"{path.relative_to(ROOT)}: '{title}' at line {line} repeats line {first}")
        self.assertEqual([], problems, "duplicate headings (a section pasted twice?)")

    def test_the_generated_roadmap_is_not_judged(self):
        self.assertIn("docs/ROADMAP.md", generated_docs())
        self.assertNotIn(ROOT / "docs" / "ROADMAP.md", tracked_markdown())

    def test_a_repeated_section_is_caught(self):
        text = "# T\n\n## A\n\n### Knobs\n\n## B\n\n### Knobs\n\n## A\n\n### Knobs\n"
        self.assertEqual([(11, 3, "A"), (13, 5, "Knobs")], duplicates(text))

    def test_the_same_subheading_under_different_parents_is_not_a_duplicate(self):
        self.assertEqual([], duplicates("# T\n## A\n### Knobs\n## B\n### Knobs\n"))

    def test_headings_in_code_fences_do_not_count(self):
        self.assertEqual([], duplicates("# T\n## A\n```\n## A\n```\n"))


if __name__ == "__main__":
    unittest.main()
