import subprocess
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import merge_message as m  # noqa: E402

TOOL = Path(__file__).resolve().parent.parent / "merge_message.py"
ADR = (
    "docs/adr/0074-pods-stay-server-authoritative-and-the-client-slides-them-over-two-ticks.md"
)
ADR_URL = f"https://github.com/pkeppeler/deepcharter/blob/961-smooth-pod-driving/{ADR}"
RUN80 = "a" * 80


class MergeMessageTest(unittest.TestCase):
    def rewritten(self, text):
        return m.rewrite(text)[0]

    def test_the_adr_url_is_150_characters(self):
        self.assertEqual(len(ADR_URL.split("://", 1)[1]), 150)
        self.assertTrue(m.trips(ADR_URL))

    def test_bare_blob_url_becomes_the_backticked_path(self):
        self.assertEqual(self.rewritten(f"See {ADR_URL}."), f"See `{ADR}`.")

    def test_149_characters_after_the_scheme_is_kept(self):
        url = ("https://e.com/" + "ab/" * 60)[: len("https://") + 149]  # no run of 80
        self.assertEqual(len(url.split("://", 1)[1]), 149)
        self.assertFalse(m.trips(url))
        self.assertEqual(self.rewritten(url), url)

    def test_run_of_80_after_a_slash_trips_and_79_does_not(self):
        self.assertTrue(m.trips(f"https://example.com/{RUN80}"))
        self.assertFalse(m.trips(f"https://example.com/{RUN80[:-1]}"))

    def test_run_of_80_after_each_delimiter_trips(self):
        for delimiter in "?/=#":
            with self.subTest(delimiter):
                self.assertTrue(m.trips(f"https://e.com/x{delimiter}{RUN80}"))

    def test_run_counts_plus_underscore_and_dash(self):
        self.assertTrue(m.trips("https://e.com/" + "a+_-" * 20))

    def test_dot_ends_a_run(self):
        self.assertFalse(m.trips("https://e.com/" + ("a" * 70 + ".") * 2))

    def test_non_blob_long_run_url_is_removed(self):
        self.assertEqual(self.rewritten(f"Video https://x.io/m/{RUN80}.mp4 here"), "Video  here")

    def test_link_keeps_text_and_adds_the_blob_path(self):
        self.assertEqual(self.rewritten(f"[ADR 74]({ADR_URL})"), f"ADR 74 (`{ADR}`)")

    def test_link_to_other_url_keeps_only_the_text(self):
        self.assertEqual(self.rewritten(f"[the clip](https://x.io/{RUN80}.mp4)"), "the clip")

    def test_image_link_keeps_alt_text(self):
        self.assertEqual(self.rewritten(f"![the gif](https://x.io/{RUN80}.gif)"), "the gif")

    def test_link_whose_text_is_the_url_becomes_the_path(self):
        self.assertEqual(self.rewritten(f"[{ADR_URL}]({ADR_URL})"), f"`{ADR}`")

    def test_blob_path_drops_fragment_and_query(self):
        url = f"https://github.com/o/r/blob/{'b' * 150}/src/A.java#L10-L20"
        self.assertEqual(self.rewritten(url), "`src/A.java`")

    def test_short_urls_are_untouched(self):
        text = "[a](https://github.com/pkeppeler/deepcharter/blob/main/docs/SPEC.md) and https://example.com/x?y=1."
        self.assertEqual(self.rewritten(text), text)
        self.assertEqual(m.rewrite(text)[1], [])

    def test_text_without_urls_is_untouched(self):
        text = "Closes #12\n\n- one\n- two\n"
        self.assertEqual(self.rewritten(text), text)

    def test_result_never_trips(self):
        text = f"{ADR_URL}\n[x]({ADR_URL}) https://x.io/{RUN80}\n![i](https://x.io/m/{RUN80})"
        out = self.rewritten(text)
        self.assertEqual(m.rewrite(out)[1], [])
        self.assertNotIn("://", out)

    def assertSafe(self, text):
        out = self.rewritten(text)
        self.assertEqual(m.rewrite(out)[1], [], out)
        return out

    def test_uppercase_scheme_and_other_schemes(self):
        self.assertEqual(self.assertSafe(f"a HTTPS://x.io/{RUN80} b"), "a  b")
        self.assertEqual(self.assertSafe(f"a ftp://x.io/{RUN80} b"), "a  b")
        blob = ADR_URL.replace("https://", "HTTPS://")
        self.assertEqual(self.assertSafe(blob), f"`{ADR}`")

    def test_long_url_with_parentheses_is_judged_whole(self):
        url = "https://e.com/" + "ab/" * 40 + "(y)" + "cd/" * 10
        self.assertGreaterEqual(len(url.split("://", 1)[1]), 150)
        self.assertEqual(self.assertSafe(f"see {url} end"), "see  end")
        self.assertEqual(self.assertSafe(f"see ({url})."), "see ().")

    def test_wikipedia_style_link(self):
        url = f"https://en.wikipedia.org/wiki/{RUN80}_(a)"
        self.assertEqual(self.assertSafe(f"[wiki]({url}) ok"), "wiki ok")
        self.assertEqual(self.assertSafe(f"[wiki]({url})."), "wiki.")

    def test_code_span_goes_with_the_url(self):
        self.assertEqual(self.assertSafe(f"run `https://x.io/{RUN80}` now"), "run  now")
        self.assertEqual(self.assertSafe(f"see `{ADR_URL}` now"), f"see `{ADR}` now")

    def test_autolink_goes_with_the_url(self):
        self.assertEqual(self.assertSafe(f"see <https://x.io/{RUN80}> now"), "see  now")
        self.assertEqual(self.assertSafe(f"see <{ADR_URL}> now"), f"see `{ADR}` now")

    def test_link_text_that_is_a_url_keeps_a_label(self):
        self.assertEqual(self.assertSafe(f"[https://x.io/{RUN80}](https://x.io/{RUN80})"), "link")

    def test_exact_150_boundary(self):
        for n, tripped in ((149, False), (150, True)):
            with self.subTest(n):
                rest = ("e.com/" + "ab/" * 60)[:n]
                url = "https://" + rest
                self.assertEqual(m.trips(url), tripped)
                self.assertEqual(self.rewritten(url) == url, not tripped)

    def test_final_check_replaces_what_the_url_rules_missed(self):
        long_token = "x" + "ab/" * 30 + "(" + "cd/" * 30 + ")"
        text = f"scheme-less /{RUN80}\nweird ://{long_token}\nok"
        out = self.assertSafe(text)
        self.assertEqual(out.count(m.PLACEHOLDER), 2)
        self.assertTrue(out.endswith("\nok"))
        self.assertEqual(self.assertSafe(f"[a](b){'/' + RUN80}"), m.PLACEHOLDER)

    def test_title_is_rewritten_too(self):
        self.assertEqual(self.assertSafe(f"Fix {ADR_URL}"), f"Fix `{ADR}`")

    def test_cli_rewrites_and_lists(self):
        text = f"a {ADR_URL} b\n"
        run = lambda *args: subprocess.run(  # noqa: E731
            [sys.executable, "-I", str(TOOL), *args], input=text, capture_output=True, text=True, check=True
        ).stdout
        self.assertEqual(run(), f"a `{ADR}` b\n")
        self.assertEqual(run("--list"), ADR_URL + "\n")


if __name__ == "__main__":
    unittest.main()
