import copy
import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import feature_map as fm  # noqa: E402

ROOT = fm.repo_root()
DATA = fm.load(ROOT)


def mutated(change):
    data = copy.deepcopy(DATA)
    change(data)
    return fm.validate(data, ROOT)


class TheCommittedMap(unittest.TestCase):
    def test_validates(self):
        self.assertEqual([], fm.validate(DATA, ROOT))

    def test_markdown_is_what_the_generator_makes(self):
        made = fm.render_markdown(DATA, ROOT, False)
        self.assertEqual(made, (ROOT / fm.MARKDOWN).read_text(encoding="utf-8"),
                         "docs/FEATURE-MAP.md is stale: run python3 -I tools/feature_map.py")

    def test_page_is_what_the_generator_makes(self):
        made = fm.render_page(DATA, ROOT)
        self.assertEqual(made, (ROOT / fm.PAGE).read_text(encoding="utf-8"),
                         "docs/feature-map/index.html is stale: run python3 -I tools/feature_map.py")

    def test_every_still_is_a_pr_media_path(self):
        stills = [n["still"] for n in DATA["nodes"] if "still" in n]
        self.assertGreater(len(stills), 50)
        for still in stills:
            self.assertRegex(still, fm.STILL_RE)
            self.assertTrue(fm.still_url(still).startswith("https://github.com/pkeppeler/deepcharter/blob/pr-media/"))

    def test_page_uses_relative_paths_and_pinned_libraries_only(self):
        page = (ROOT / fm.PAGE).read_text(encoding="utf-8")
        self.assertNotRegex(page, r'(?:src|href)="/', "an absolute path breaks the page on GitHub Pages and as a local file")
        self.assertNotIn("file://", page)
        sources = re.findall(r'<script src="([^"]+)" integrity="sha384-', page)
        self.assertEqual([url for url, _ in fm.CDN], sources)
        for url in sources:
            self.assertRegex(url, r"^https://cdn\.jsdelivr\.net/npm/[a-z-]+@[0-9.]+/")

    def test_the_game_has_every_status_and_ladder_kind(self):
        self.assertEqual(set(fm.STATUSES), {n["status"] for n in DATA["nodes"]})
        self.assertEqual(set(fm.LADDER_KINDS), {l["kind"] for l in DATA["ladders"]})

    def test_poster_draws_every_node(self):
        poster = fm.render_poster(DATA, ROOT)
        self.assertTrue(poster.startswith("<svg"))
        self.assertEqual(len(DATA["nodes"]), poster.count("<rect x="))
        self.assertEqual(sum(1 for n in DATA["nodes"] if n.get("icon")), poster.count("<image "))

    def test_mermaid_modes_differ_only_in_the_node_shape(self):
        text = fm.render_markdown(DATA, ROOT, False)
        images = fm.render_markdown(DATA, ROOT, True)
        self.assertNotIn("@{ img:", text)
        self.assertIn("@{ img:", images)
        self.assertEqual(text.count("```mermaid"), images.count("```mermaid"))


class TheValidator(unittest.TestCase):
    def test_a_repeated_node_id_is_found(self):
        problems = mutated(lambda d: d["nodes"].append(dict(d["nodes"][0])))
        self.assertTrue(any("repeated" in p for p in problems), problems)

    def test_an_edge_to_nowhere_is_found(self):
        problems = mutated(lambda d: d["nodes"][0].setdefault("requires", []).append("no-such-node"))
        self.assertTrue(any("no-such-node" in p for p in problems), problems)

    def test_a_bad_status_is_found(self):
        def change(d):
            d["nodes"][0]["status"] = "finished"
        self.assertTrue(any("finished" in p for p in mutated(change)))

    def test_a_missing_icon_is_found(self):
        def change(d):
            d["nodes"][0]["icon"] = fm.ICON_DIR + "item/no_such_texture.png"
        self.assertTrue(any("no_such_texture" in p for p in mutated(change)))

    def test_a_still_that_is_not_pr_media_is_found(self):
        def change(d):
            d["nodes"][0]["still"] = "https://example.com/x.png"
        self.assertTrue(any("still" in p for p in mutated(change)))

    def test_a_ref_to_a_missing_heading_or_file_is_found(self):
        def change(d):
            d["nodes"][0]["refs"] = ["docs/SPEC.md#no-such-heading", "docs/no-such.md", "adr:9999", "issue 5"]
        problems = mutated(change)
        self.assertEqual(4, len([p for p in problems if "ref" in p]), problems)

    def test_a_cover_claimed_twice_is_found(self):
        def change(d):
            node = next(n for n in d["nodes"] if n.get("covers"))
            other = next(n for n in d["nodes"] if n is not node)
            other.setdefault("covers", []).append(node["covers"][0])
        self.assertTrue(any("covered by both" in p for p in mutated(change)))

    def test_an_unknown_field_is_found(self):
        def change(d):
            d["nodes"][0]["colour"] = "red"
        self.assertTrue(any("colour" in p for p in mutated(change)))

    def test_the_core_loop_must_lead(self):
        def change(d):
            d["ladders"].reverse()
        self.assertTrue(any("core-loop" in p for p in mutated(change)))


if __name__ == "__main__":
    unittest.main()
