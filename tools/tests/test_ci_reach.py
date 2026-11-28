"""tools/ci_reach.py and tools/ci_members.py: the reference graph CI selects GameTests with (ADR 0121), and the member diff it travels by.

The planner's rules are tested in test_ci_affected.py; this file pins the pieces under them: how a name resolves to a file, what a change
to a file touches, and the parser over every file of the real tree."""
import sys
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools"))
import ci_members as members  # noqa: E402
import ci_reach as reach  # noqa: E402


def graph(files: dict[str, str], leaves: tuple[str, ...] = ()) -> reach.Graph:
    """The graph of these files; `; import` starts a line, as it does in a real file."""
    return reach.Graph((reach.parse(path, text.replace("; import", ";\nimport")) for path, text in files.items()), frozenset(leaves))


def touched(old: str | None, new: str) -> frozenset[str] | None:
    return members.changed_names(None if old is None else reach.member_code(old), reach.member_code(new))


class ResolveTest(unittest.TestCase):
    """A name resolves the way javac sees it, so a nested name two files declare is no longer a use of both."""

    FILES = {
        "src/main/java/a/Pod.java": "package a; public class Pod { public enum Mode { IDLE } }",
        "src/main/java/b/Cue.java": "package b; public class Cue { public enum Mode { LOUD } }",
        "src/main/java/a/UsesPodMode.java": "package a; public class UsesPodMode { Pod.Mode m; }",
        "src/main/java/c/Imports.java": "package c; import b.Cue; public class Imports { Cue.Mode m; }",
        "src/main/java/c/Wild.java": "package c; import a.*; public class Wild { Pod p; }",
        "src/main/java/c/Qualified.java": "package c; public class Qualified { a.Pod p; }",
        "src/main/java/c/Nested.java": "package c; import a.Pod.Mode; public class Nested { Mode m; }",
        "src/main/java/c/Static.java": "package c; import static b.Cue.helper; public class Static { void x() { helper(); } }",
        "src/main/java/c/Unexplained.java": "package c; public class Unexplained extends Something { Mode m; }",
    }

    def refers(self, name: str) -> set[str]:
        g = graph(self.FILES)
        return {Path(p).stem for p in g.refers[f"src/main/java/{name}.java"]}

    def test_same_package_single_import_wildcard_qualified_nested_and_static(self):
        self.assertEqual({"Pod"}, self.refers("a/UsesPodMode"))
        self.assertEqual({"Cue"}, self.refers("c/Imports"))
        self.assertEqual({"Pod"}, self.refers("c/Wild"))
        self.assertEqual({"Pod"}, self.refers("c/Qualified"))
        self.assertEqual({"Pod"}, self.refers("c/Nested"))
        self.assertEqual({"Cue"}, self.refers("c/Static"))

    def test_a_name_nothing_explains_counts_for_every_file_that_declares_it(self):
        self.assertEqual({"Pod", "Cue"}, self.refers("c/Unexplained"))

    def test_a_name_in_a_comment_or_a_string_is_no_use(self):
        g = graph({**self.FILES, "src/main/java/c/Words.java": 'package c; public class Words { /* Pod */ String s = "Pod"; }'})
        self.assertEqual(set(), g.refers["src/main/java/c/Words.java"])

    def test_a_test_that_names_a_key_a_game_file_names_refers_to_it(self):
        g = graph(
            {
                "src/main/java/a/Hint.java": 'package a; class Hint { String k() { return "deepcharter.pod.rescue." + "left"; } }',
                "src/gametest/java/t/HintTest.java": 'package t; class HintTest { String k = "deepcharter.pod.rescue.left"; }',
            }
        )
        self.assertEqual({"src/gametest/java/t/HintTest.java"}, g.key_referrers["src/main/java/a/Hint.java"])


class ChangedNamesTest(unittest.TestCase):
    def test_a_changed_method_and_the_members_that_call_it(self):
        old = "class A { int a() { return 1; } int b() { return a(); } int c() { return 3; } }"
        self.assertEqual({"a", "b"}, touched(old, old.replace("return 1", "return 2")))

    def test_a_changed_string_is_a_change(self):
        old = 'class A { String a() { return "x"; } String b() { return "y"; } }'
        self.assertEqual({"a"}, touched(old, old.replace('"x"', '"z"')))

    def test_formatting_alone_changes_nothing(self):
        old = "class A { int a() { return 1; } }"
        self.assertEqual(set(), touched(old, old.replace("{ return", "{\n        return")))

    def test_a_tuning_argument_names_its_component_not_the_default(self):
        old = "record T(int speed, int depth) { static final T DEFAULT = new T(4, 2); int fast() { return speed * 2; } }"
        self.assertEqual({"depth"}, touched(old, old.replace("new T(4, 2)", "new T(4, 3)")))
        self.assertEqual({"speed", "fast"}, touched(old, old.replace("new T(4, 2)", "new T(5, 2)")))

    def test_a_component_added_with_its_argument_names_the_new_component(self):
        old = "record T(int speed) { static final T DEFAULT = new T(4); }"
        new = "record T(int speed, int depth) { static final T DEFAULT = new T(4, 2); }"
        self.assertEqual({"depth"}, touched(old, new))

    def test_a_constructor_is_x_new_and_an_instance_field_initializer_runs_in_it(self):
        old = "class A { int n = Seed.make(); A() { } int get() { return 1; } }"
        self.assertEqual({"n", "A__new"}, touched(old, old.replace("Seed.make()", "Seed.other()")))
        self.assertEqual({"A__new"}, touched(old, old.replace("A() { }", "A() { get(); }")))
        self.assertIn("A__new", reach.member_code("x = new A(); y = A::new;"))

    def test_an_enum_constant_reaches_what_iterates_the_enum(self):
        old = 'enum S { A("a"), B("b"); S(String id) { } }'
        self.assertEqual({"B", "values", "valueOf", "ordinal", "EnumMap", "EnumSet", "compareTo"}, touched(old, old.replace('"b"', '"c"')))

    def test_the_header_is_marked_and_an_initializer_is_the_whole_file(self):
        old = "class A implements B { void a() { } }"
        self.assertEqual({members.HEADER, "C", "B"}, touched(old, old.replace("implements B", "implements C")))
        static = "class A { static { go(); } void a() { } }"
        self.assertIsNone(touched(static, static.replace("go()", "stop()")))

    def test_no_old_side_or_an_unparsable_side_is_the_whole_file(self):
        self.assertIsNone(touched(None, "class A { }"))
        self.assertIsNone(touched("class A { }", "class A { void a() { "))

    def test_an_override_and_a_record_compact_constructor_are_parsed(self):
        _, found = members.parse(reach.member_code("record R(int a) { R { check(a); } @Override public String toString() { return \"\"; } }"))
        self.assertIn("R.R__new", found.texts)
        self.assertEqual({"toString"}, found.overrides)


class ReachTest(unittest.TestCase):
    MAIN = "src/main/java/a"
    TESTS = "src/gametest/java/t"

    def files(self) -> dict[str, str]:
        return {
            f"{self.MAIN}/Core.java": "package a; public class Core { public static int speed() { return 4; } public static int depth() { return 2; } }",
            f"{self.MAIN}/Mid.java": "package a; public class Mid { public int run() { return Core.speed(); } private Core held; }",
            f"{self.TESTS}/SpeedTest.java": "package t; import a.Core; class SpeedTest { void t() { Core.speed(); } }",
            f"{self.TESTS}/DepthTest.java": "package t; import a.Core; class DepthTest { void t() { Core.depth(); } }",
            f"{self.TESTS}/RunTest.java": "package t; import a.Mid; class RunTest { void t() { new Mid().run(); } }",
            f"{self.TESTS}/report/Other.java": "package t.report; import a.Core; class Other { void speed() { } Core c; }",
        }

    def leaves(self) -> tuple[str, ...]:
        return tuple(p for p in self.files() if p.startswith(self.TESTS))

    def test_a_name_reaches_a_referrer_only_beside_a_handle_to_its_file(self):
        g = graph(self.files(), self.leaves())
        found = {Path(p).stem for p in g.reach({f"{self.MAIN}/Core.java": frozenset({"speed"})}) & g.leaves}
        self.assertEqual({"SpeedTest", "RunTest"}, found, "Other declares its own speed() beside a Core field it never calls through")

    def test_whole_reaches_every_member_that_names_the_file(self):
        g = graph(self.files(), self.leaves())
        found = {Path(p).stem for p in g.reach({f"{self.MAIN}/Core.java": reach.WHOLE}) & g.leaves}
        self.assertEqual({"SpeedTest", "DepthTest", "RunTest", "Other"}, found)

    def test_a_hub_reached_stops_and_a_callback_makes_its_file_whole(self):
        files = self.files()
        files[f"{self.MAIN}/Fire.java"] = "package a; public class Fire { public static void tick() { Core.depth(); } public static void light() { } }"
        files[f"{self.MAIN}/FireInit.java"] = "package a; public class FireInit { static void init() { Fire.tick(); } }"
        files[f"{self.MAIN}/DeepCharter.java"] = "package a; public class DeepCharter { void onInitialize() { FireInit.init(); } }"
        files[f"{self.TESTS}/FireTest.java"] = "package t; import a.Fire; class FireTest { void t() { Fire.light(); } }"
        g = graph(files, self.leaves() + (f"{self.TESTS}/FireTest.java",))
        found = {Path(p).stem for p in g.reach({f"{self.MAIN}/Fire.java": frozenset({"tick"})}) & g.leaves}
        self.assertEqual({"FireTest"}, found, "tick is called only from init: the game calls it, so every user of Fire may see it")

    def test_the_hop_limit_counts_game_classes_not_test_helpers(self):
        files = self.files()
        files[f"{self.MAIN}/Far.java"] = "package a; public class Far { public int go() { return new Mid().run(); } }"
        files[f"{self.TESTS}/FarTest.java"] = "package t; import a.Far; class FarTest { void t() { new Far().go(); } }"
        files[f"{self.TESTS}/support/Kit.java"] = "package t.support; import a.Mid; public class Kit { public static int kit() { return new Mid().run(); } }"
        files[f"{self.TESTS}/KitTest.java"] = "package t; import t.support.Kit; class KitTest { void t() { Kit.kit(); } }"
        leaves = self.leaves() + (f"{self.TESTS}/FarTest.java", f"{self.TESTS}/KitTest.java")
        g = graph(files, leaves)
        found = {Path(p).stem for p in g.reach({f"{self.MAIN}/Core.java": frozenset({"speed"})}) & g.leaves}
        self.assertEqual({"SpeedTest", "RunTest", "KitTest"}, found, "FarTest is two game classes away; KitTest one, through a helper")


class LiteralMatchTest(unittest.TestCase):
    def test_a_lang_key_by_itself_its_dotted_prefix_and_its_dotted_tail(self):
        matches = reach.names_lang_key("deepcharter.pod.rescue.left")
        for literal in ("deepcharter.pod.rescue.left", "deepcharter.pod.rescue.", ".rescue.left"):
            self.assertTrue(matches(literal), literal)
        # A one-segment tail (`".title"`) would name every key of its kind; the code's prefix names the key instead.
        for literal in ("deepcharter.pod", "left", ".left", "deepcharter.wreck.", ".", "deepcharter.pod.rescue.leftover"):
            self.assertFalse(matches(literal), literal)

    def test_an_asset_by_a_tail_of_at_least_two_segments(self):
        matches = reach.names_asset("textures/gui/sprites/hud/fuel.png")
        for literal in ("hud/fuel", "deepcharter:hud/fuel", "gui/sprites/hud/fuel", "textures/gui/sprites/hud/fuel.png"):
            self.assertTrue(matches(literal), literal)
        for literal in ("fuel", "hud/fuel_low", "sprites/hud"):
            self.assertFalse(matches(literal), literal)

    def test_the_events_that_play_a_sound_file(self):
        sounds = '{"a": {"sounds": [{"name": "deepcharter:x/y"}]}, "b": {"sounds": ["deepcharter:x/y"]}, "c": {"sounds": ["z"]}}'
        self.assertEqual({"a", "b"}, reach.sound_events_of(sounds, "x/y"))
        self.assertIsNone(reach.sound_events_of("[", "x/y"))

    def test_changed_keys_and_what_is_no_object(self):
        self.assertEqual({"b", "c"}, reach.changed_keys('{"a": 1, "b": 2}', '{"a": 1, "b": 3, "c": 4}'))
        self.assertEqual({"a"}, reach.changed_keys(None, '{"a": 1}'))
        self.assertIsNone(reach.changed_keys("[]", "{}"))
        self.assertIsNone(reach.changed_keys("{", "{}"))


class RealTreeTest(unittest.TestCase):
    def test_the_parser_reads_every_file_but_a_few_mixins(self):
        unread = []
        for root in reach.ROOTS:
            for path in (REPO / root).rglob("*.java"):
                if path.name == "package-info.java":
                    continue
                try:
                    members.parse(reach.member_code(path.read_text()))
                except members.Unparsable:
                    unread.append(str(path.relative_to(REPO)))
        # A file that does not parse is read whole, which is safe; a mixin's change runs everything anyway.
        self.assertEqual([], [p for p in unread if "/mixin/" not in p])

    def test_the_hubs_are_the_core_and_few(self):
        g = reach.graph(REPO)
        stems = {Path(p).stem for p in g.hubs}
        self.assertTrue({"PodEntity", "PodRegistry", "ClientTestLog"} <= stems, sorted(stems))
        self.assertLess(len(g.hubs), len(g.sources) / 20)


if __name__ == "__main__":
    unittest.main()
