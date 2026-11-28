"""tools/ci_affected.py: which GameTest classes a pull request's changed paths can affect, and when CI runs the suites whole (ADR 0116,
ADR 0120).

The mapping is tested on a small fixture tree (every rule, and the conservative fall-backs), then on the real tree for what must hold
whatever the code is: a docs change runs no GameTest, a batch PR runs everything, a change to a test class runs that class, no game file is
silent, a tuning value reaches the readers of that value only, and a known PR (#1699) selects what it should."""
import json
import re
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools"))
import ci_affected as affected  # noqa: E402
import ci_reach  # noqa: E402

PKG = "src/gametest/java/io/x/test"
MAIN = "src/main/java/io/x"
CLIENT = "src/client/java/io/x/client"
GAME_TEST = "@GameTest public void t()"
FILES = {
    # main: AlphaTest and DepthTest call Core's two methods; Mid calls Core.speed and MidTest calls Mid; Far calls Mid and FarTest calls
    # Far (two game classes from Core); Island is used only by the test helper Helper.
    f"{MAIN}/Core.java": "public class Core { public static int speed() { return 4; } public static int depth() { return 2; } }\n",
    f"{MAIN}/Mid.java": "public class Mid { public int run() { return Core.speed(); } }\n",
    f"{MAIN}/Far.java": "public class Far { public int go() { return new Mid().run(); } }\n",
    f"{MAIN}/Island.java": "public class Island { public void visit() { } }\n",
    f"{MAIN}/mixin/CoreMixin.java": "public class CoreMixin { void hook() { Core.depth(); } }\n",
    f"{MAIN}/DeepCharter.java": "public class DeepCharter { void onInitialize() { PodInit.init(); } }\n",
    f"{MAIN}/PodInit.java": "public class PodInit { static void init() { } }\n",
    f"{MAIN}/Hint.java": 'public class Hint { static void init() { } static String key() { return "deepcharter.pod.hint"; } }\n',
    f"{MAIN}/sound/Sounds.java": 'public enum Sounds { BEEP("ui.beep"), HUM("ui.hum"); Sounds(String id) { } }\n',
    f"{CLIENT}/Screen.java": 'public class Screen { int draw() { return new Mid().run(); } String sprite() { return "hud/fuel"; } }\n',
    f"{PKG}/support/Helper.java": "public class Helper { public static void island() { new Island().visit(); } }\n",
    f"{PKG}/support/Unused.java": "public class Unused {}\n",
    f"{PKG}/AlphaTest.java": f"public class AlphaTest {{ {GAME_TEST} {{ Core.speed(); }} }}\n",
    f"{PKG}/DepthTest.java": f"public class DepthTest {{ {GAME_TEST} {{ Core.depth(); }} }}\n",
    f"{PKG}/MidTest.java": f"public class MidTest {{ {GAME_TEST} {{ new Mid().run(); }} }}\n",
    f"{PKG}/FarTest.java": f"public class FarTest {{ {GAME_TEST} {{ new Far().go(); }} }}\n",
    f"{PKG}/BetaTest.java": f"public class BetaTest {{ {GAME_TEST} {{ Helper.island(); }} }}\n",
    f"{PKG}/HintTest.java": f'public class HintTest {{ {GAME_TEST} {{ String k = "deepcharter.pod.hint"; }} }}\n',
    f"{PKG}/SoundTest.java": f"public class SoundTest {{ {GAME_TEST} {{ Sounds s = Sounds.BEEP; }} }}\n",
    f"{PKG}/LangTest.java": f'public class LangTest {{ {GAME_TEST} {{ String f = "lang/en_us"; }} }}\n',
    f"{PKG}/OneClientTest.java": "public class OneClientTest implements FabricClientGameTest { void runTest() { new Screen().draw(); new Screen().sprite(); } }\n",
    f"{PKG}/TwoClientTest.java": "public class TwoClientTest implements FabricClientGameTest { void runTest() { Helper.island(); } }\n",
    f"{PKG}/ThreeClientTest.java": "public class ThreeClientTest implements FabricClientGameTest { }\n",
    f"{PKG}/evidence/OneScenario.java": "public class OneScenario extends EvidenceScenario { void run() { new Mid().run(); } }\n",
    "gradle/server-test-weights.txt": "".join(f"{n}Test 10\n" for n in ("Alpha", "Depth", "Mid", "Far", "Beta", "Hint", "Sound", "Lang")),
    "gradle/client-test-weights.txt": "OneClientTest 40\nTwoClientTest 40\nThreeClientTest 40\n",
    "gradle/ci-budget.txt": "server-shard-seconds 100\nclient-shard-seconds 100\n",
}
FULL = {"server": 16, "client": 34}


def plan(
    changed: list[str],
    head_ref: str = "1-slug",
    labels: tuple[str, ...] = (),
    files: dict[str, str] | None = None,
    old: dict[str, str | None] | None = None,
) -> dict[str, str]:
    """Plans on the fixture tree (plus `files`). With `old`, the base's copies of the changed paths are those (a path missing from it is
    unchanged, None is a new file); without it there is no base."""
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        for name, text in {**FILES, **(files or {})}.items():
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)

        def texts(path: str) -> tuple[str | None, str | None]:
            new = (root / path).read_text() if (root / path).is_file() else None
            return (old[path] if path in old else new), new  # type: ignore[index, operator]

        return affected.plan(changed, head_ref, set(labels), FULL["server"], FULL["client"], root, texts if old is not None else None)


def names(result: dict[str, str], suite: str) -> list[str]:
    return [n for n in result[f"{suite}-classes"].split(",") if n]


class MappingTest(unittest.TestCase):
    def test_a_change_runs_the_tests_that_call_what_it_touched(self):
        result = plan([f"{MAIN}/Core.java"], old={f"{MAIN}/Core.java": FILES[f"{MAIN}/Core.java"].replace("return 4", "return 5")})
        self.assertEqual(["AlphaTest", "MidTest"], names(result, "server"), "speed() changed: its caller, and the caller of Mid.run")
        self.assertEqual([], names(result, "client"), "Screen is a second game class from Core, so OneClientTest is out of reach")

    def test_without_a_base_a_change_is_the_whole_file(self):
        self.assertEqual(["AlphaTest", "DepthTest", "MidTest"], names(plan([f"{MAIN}/Core.java"]), "server"))

    def test_a_change_is_followed_through_one_game_class_and_any_test_helper(self):
        self.assertEqual(["FarTest", "MidTest"], names(plan([f"{MAIN}/Mid.java"]), "server"))
        self.assertEqual(["OneClientTest"], names(plan([f"{MAIN}/Mid.java"]), "client"))
        island = plan([f"{MAIN}/Island.java"])
        self.assertEqual(["BetaTest"], names(island, "server"), "Island reaches BetaTest through the helper, which costs no hop")
        self.assertEqual(["TwoClientTest"], names(island, "client"))

    def test_a_changed_test_class_runs_itself_only(self):
        self.assertEqual(["AlphaTest"], names(plan([f"{PKG}/AlphaTest.java"]), "server"))
        self.assertEqual(["ThreeClientTest"], names(plan([f"{PKG}/ThreeClientTest.java"]), "client"))

    def test_an_unused_support_class_runs_no_test(self):
        result = plan([f"{PKG}/support/Unused.java"])
        self.assertEqual(("0", "0"), (result["server-count"], result["client-count"]))

    def test_a_deleted_file_reaches_what_still_names_it(self):
        gone = {f"{MAIN}/Island.java": ""}
        with tempfile.TemporaryDirectory() as tmp:
            for name, text in FILES.items():
                if name in gone:
                    continue
                path = Path(tmp) / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text)
            result = affected.plan([f"{MAIN}/Island.java"], "1-x", set(), 15, 30, Path(tmp))
        self.assertEqual(["BetaTest"], names(result, "server"))

    def test_a_scenario_is_never_listed(self):
        self.assertNotIn("OneScenario", plan([f"{MAIN}/Mid.java"])["client-classes"])

    def test_a_package_info_change_runs_nothing(self):
        result = plan([f"{MAIN}/package-info.java"], files={f"{MAIN}/package-info.java": "package io.x;\n"})
        self.assertEqual(("0", "0"), (result["server-count"], result["client-count"]))

    def test_docs_tools_weights_and_gates_run_no_game_test(self):
        for path in (
            "docs/design/a.md", "README.md", "tools/merge-pr.sh", ".claude/CLAUDE.md", "gradle/client-test-weights.txt", "gradle/ci-budget.txt",
            "gradle/keybind-gate.gradle.kts", "gradle/keybind-allowlist.txt", "gradle/literals.gradle.kts",
        ):
            with self.subTest(path):
                result = plan([path])
                self.assertEqual(("false", "false", "0", "0"), (result["server-full"], result["client-full"], result["server-count"], result["client-count"]))
                self.assertEqual("[]", result["server-matrix"])
        self.assertEqual("true", plan(["gradle/keybind-gate.gradle.kts"])["build-needed"], "a gate runs in check, which build-checks runs")

    def test_the_count_is_the_fewest_shards_that_fit_the_budget_and_never_more_than_a_full_run(self):
        # Four server classes of 10 s fit one shard under a 100 s budget; under a 15 s budget they need four, capped at the full count.
        changed = [f"{PKG}/AlphaTest.java", f"{PKG}/DepthTest.java", f"{PKG}/MidTest.java", f"{PKG}/FarTest.java"]
        files = {"gradle/ci-budget.txt": "server-shard-seconds 15\nclient-shard-seconds 100\n", f"{PKG}/HeavyTest.java": f"public class HeavyTest {{ {GAME_TEST} {{ }} }}\n"}
        files["gradle/server-test-weights.txt"] = FILES["gradle/server-test-weights.txt"] + "HeavyTest 400\n"
        result = plan(changed, files=files)
        self.assertEqual(["AlphaTest", "DepthTest", "FarTest", "MidTest"], names(result, "server"))
        self.assertEqual("4", result["server-count"])
        with mock.patch.dict(FULL, {"server": 3}):
            self.assertEqual("3", plan(changed, files=files)["server-count"])

    def test_the_log_says_how_each_class_was_reached(self):
        lines: list[str] = []
        with tempfile.TemporaryDirectory() as tmp:
            for name, text in FILES.items():
                (Path(tmp) / name).parent.mkdir(parents=True, exist_ok=True)
                (Path(tmp) / name).write_text(text)
            affected.plan([f"{MAIN}/Mid.java"], "1-x", set(), 16, 34, Path(tmp), log=lines.append)
        self.assertIn("FarTest <- Far <- Mid", lines)


class MemberTest(unittest.TestCase):
    """A change passes on the members it touched, not its whole file."""

    def test_a_tuning_value_reaches_the_readers_of_that_component_only(self):
        tune = "public record Tune(int speed, int depth) { public static final Tune DEFAULT = new Tune(4, 2); }\n"
        files = {
            f"{MAIN}/Tune.java": tune,
            f"{PKG}/SpeedTest.java": f"public class SpeedTest {{ {GAME_TEST} {{ Tune.DEFAULT.speed(); }} }}\n",
            f"{PKG}/DepthTuneTest.java": f"public class DepthTuneTest {{ {GAME_TEST} {{ Tune.DEFAULT.depth(); }} }}\n",
        }
        result = plan([f"{MAIN}/Tune.java"], files=files, old={f"{MAIN}/Tune.java": tune.replace("new Tune(4, 2)", "new Tune(4, 3)")})
        self.assertEqual(["DepthTuneTest"], names(result, "server"))

    def test_an_override_runs_every_class_that_names_its_file(self):
        mid = "public class Mid extends Base { public int run() { return Core.speed(); } @Override public void tick() { } }\n"
        result = plan([f"{MAIN}/Mid.java"], files={f"{MAIN}/Mid.java": mid}, old={f"{MAIN}/Mid.java": mid.replace("tick() { }", "tick() { run(); }")})
        self.assertEqual(["FarTest", "MidTest"], names(result, "server"), "the game calls tick(): whoever uses a Mid may see it")

    def test_a_hub_passes_on_only_what_its_change_touched_and_a_hub_reached_stops_at_the_tests_that_use_it(self):
        users = {f"{MAIN}/User{i}.java": f"public class User{i} {{ int u() {{ return Core.speed(); }} }}\n" for i in range(4)}
        with mock.patch.object(ci_reach, "HUB_FAN_IN", 3):
            result = plan([f"{MAIN}/Island.java"], files={**users, f"{MAIN}/Island.java": "public class Island { public void visit() { Core.depth(); } }\n"})
            self.assertEqual(["BetaTest"], names(result, "server"))
            # Core is a hub now: a change to depth() runs DepthTest (it calls depth), not AlphaTest or MidTest.
            core = FILES[f"{MAIN}/Core.java"]
            touched = plan([f"{MAIN}/Core.java"], files=users, old={f"{MAIN}/Core.java": core.replace("return 2", "return 3")})
            self.assertEqual(["DepthTest"], names(touched, "server"))


class WiringTest(unittest.TestCase):
    """Init and registration code, which no test names, runs what its changed lines register."""

    def test_init_code_runs_what_it_newly_registers(self):
        init = "public class PodInit { static void init() { Hint.init(); } }\n"
        result = plan([f"{MAIN}/PodInit.java"], files={f"{MAIN}/PodInit.java": init}, old={f"{MAIN}/PodInit.java": FILES[f"{MAIN}/PodInit.java"]})
        self.assertEqual(["HintTest"], names(result, "server"), "HintTest names Hint's lang key, so it tests Hint")
        self.assertEqual("false", result["server-full"])

    def test_init_code_registering_what_no_test_reaches_runs_its_package_or_its_side_whole(self):
        pod = "src/main/java/io/x/pod"
        files = {
            f"{pod}/PodInit.java": "public class PodInit { static void init() { Lonely.init(); } }\n",
            f"{pod}/Lonely.java": "public class Lonely { static void init() { } }\n",
            f"{pod}/PodThing.java": "public class PodThing { static void thing() { } }\n",
            f"{PKG}/PodThingTest.java": f"public class PodThingTest {{ {GAME_TEST} {{ PodThing.thing(); }} }}\n",
            f"{MAIN}/DeepCharter.java": "public class DeepCharter { void onInitialize() { PodInit.init(); } }\n",
        }
        before = {f"{pod}/PodInit.java": "public class PodInit { static void init() { } }\n"}
        self.assertEqual(["PodThingTest"], names(plan([f"{pod}/PodInit.java"], files=files, old=before), "server"))
        del files[f"{PKG}/PodThingTest.java"]
        result = plan([f"{pod}/PodInit.java"], files=files, old=before)
        self.assertEqual(("true", "true"), (result["server-full"], result["client-full"]))
        self.assertIn("PodInit.java: init or registry code", result["why-full"])

    def test_a_client_init_with_nothing_to_test_runs_the_client_suite_not_the_server_one(self):
        files = {
            f"{CLIENT}/DeepCharterClient.java": "public class DeepCharterClient { void onInitializeClient() { BeepInit.init(); } }\n",
            f"{CLIENT}/beep/BeepInit.java": "public class BeepInit { static void init() { Beep.init(); } }\n",
            f"{CLIENT}/beep/Beep.java": "public class Beep { static void init() { } }\n",
        }
        before = {f"{CLIENT}/beep/BeepInit.java": "public class BeepInit { static void init() { } }\n"}
        result = plan([f"{CLIENT}/beep/BeepInit.java"], files=files, old=before)
        self.assertEqual(("false", "true"), (result["server-full"], result["client-full"]))

    def test_a_new_file_is_left_to_the_tests_the_pr_changes(self):
        result = plan([f"{MAIN}/Hint.java", f"{PKG}/HintTest.java"], old={f"{MAIN}/Hint.java": None, f"{PKG}/HintTest.java": None})
        self.assertEqual(["HintTest"], names(result, "server"))


class ResourceTest(unittest.TestCase):
    """Lang keys, sounds and assets reach the Java members that name them."""

    LANG = "src/lang/en_us/pod.json"
    SOUNDS = "src/main/resources/assets/deepcharter/sounds.json"

    def test_a_lang_key_runs_the_tests_that_reach_its_name_and_the_tests_that_read_the_language(self):
        new = '{"deepcharter.pod.hint": "Tow her home", "deepcharter.pod.other": "x"}'
        result = plan([self.LANG], files={self.LANG: new}, old={self.LANG: new.replace("Tow her", "Bring her")})
        self.assertEqual(["HintTest", "LangTest"], names(result, "server"))
        self.assertEqual("false", result["server-full"])

    def test_a_key_still_in_the_file_that_no_java_names_runs_everything(self):
        new = '{"deepcharter.pod.nobody": "x"}'
        result = plan([self.LANG], files={self.LANG: new}, old={self.LANG: "{}"})
        self.assertEqual(("true", "true"), (result["server-full"], result["client-full"]))
        self.assertIn("deepcharter.pod.nobody", result["why-full"])

    def test_a_removed_key_no_java_names_runs_only_the_tests_that_read_the_language(self):
        result = plan([self.LANG], files={self.LANG: "{}"}, old={self.LANG: '{"deepcharter.pod.gone": "x"}'})
        self.assertEqual(["LangTest"], names(result, "server"))

    def test_a_broken_lang_file_runs_everything(self):
        result = plan([self.LANG], files={self.LANG: "{"}, old={self.LANG: "{}"})
        self.assertEqual("true", result["server-full"])

    def test_a_sound_event_and_its_file_reach_the_members_that_name_it(self):
        sounds = '{"ui.beep": {"sounds": [{"name": "deepcharter:ui/beep"}]}, "ui.hum": {"sounds": ["deepcharter:ui/hum"]}}'
        changed = plan([self.SOUNDS], files={self.SOUNDS: sounds}, old={self.SOUNDS: sounds.replace("ui/beep", "ui/old")})
        self.assertEqual(["SoundTest"], names(changed, "server"), "BEEP changed; SoundTest uses BEEP")
        ogg = "src/main/resources/assets/deepcharter/sounds/ui/hum.ogg"
        hum = plan([ogg], files={self.SOUNDS: sounds, ogg: "x"}, old={})
        self.assertEqual([], names(hum, "server"), "HUM plays ui/hum; no test uses HUM")
        self.assertEqual("false", hum["client-full"])

    def test_an_asset_reaches_the_members_that_name_it_and_one_nothing_names_runs_the_client_suite(self):
        sprite = "src/main/resources/assets/deepcharter/textures/gui/sprites/hud/fuel.png"
        self.assertEqual(["OneClientTest"], names(plan([sprite], old={}), "client"))
        self.assertEqual(["DeltaTest"], names(plan([sprite], old={}, files={f"{PKG}/DeltaTest.java": f'public class DeltaTest {{ {GAME_TEST} {{ String s = "assets/x/y.png"; }} }}\n'}), "server"))
        unnamed = plan(["src/main/resources/assets/deepcharter/textures/block/rock.png"], old={})
        self.assertEqual(("true", "false"), (unnamed["client-full"], unnamed["server-full"]))


class BuildNeededTest(unittest.TestCase):
    def needed(self, *paths: str, **kwargs: object) -> str:
        return plan(list(paths), **kwargs)["build-needed"]  # type: ignore[arg-type]

    def test_docs_tools_and_prose_need_no_gradle_job(self):
        for path in ("docs/a.md", "README.md", "tools/merge-pr.sh", "tools/tests/test_x.py", ".claude/CLAUDE.md", ".papercuts.jsonl"):
            with self.subTest(path):
                self.assertEqual("false", self.needed(path))

    def test_anything_else_needs_one(self):
        for path in ("src/main/java/io/x/Island.java", "gradle/libs.versions.toml", "build.gradle.kts", "gradle.properties", "extras/new.txt", "src/lang/en_us/pod.json"):
            with self.subTest(path):
                self.assertEqual("true", self.needed(path))

    def test_a_tool_a_gradle_gate_runs_needs_one(self):
        for path in ("tools/TestOnlyGate.java", "tools/tests/gate_checks.py", "tools/tests/room_carver_gate_check.py"):
            with self.subTest(path):
                self.assertEqual("true", self.needed(path))

    def test_the_workflows_and_a_batch_need_one(self):
        self.assertEqual("true", self.needed(".github/workflows/ci.yml"))
        self.assertEqual("true", self.needed("docs/a.md", head_ref="batch/20261127T101500Z"))

    def test_the_deny_list_is_the_fingerprints(self):
        script = (REPO / "tools/ci-reuse-compiled.sh").read_text()
        shell = re.search(r"^DENY=\$'\^\[\^\\t\]\*\\t\((.*)\)'$", script, re.M)[1].replace("\\\\", "\\")
        self.assertEqual(shell, affected._DENY.pattern[len("^("):-1])


class GradleToolPathsTest(unittest.TestCase):
    """The tools a Gradle task or a gate check runs make a push build-needed (W2): derived from what they import and name."""

    def read_paths(self) -> set[str]:
        found = set()
        texts = [p for p in (REPO / "tools/tests").glob("*.py") if "gate_check" in p.name or p.name.startswith("gate_")]
        texts += [REPO / "build.gradle.kts", *(REPO / "gradle").glob("*.kts")]
        for path in texts:
            text = path.read_text()
            # A path in a string literal, which the code opens or runs; a path in a comment or a docstring only describes.
            found |= set(re.findall(r"""["'](tools/[A-Za-z0-9_./-]*[A-Za-z0-9_])["']""", text))
            for module in re.findall(r"^(?:import|from) (\w+)", text, re.M):
                for candidate in (REPO / "tools" / f"{module}.py", REPO / "tools/tests" / f"{module}.py"):
                    if candidate.exists():
                        found.add(str(candidate.relative_to(REPO)))
        # The gate checks run through tools/py.
        found.add("tools/py")
        return {p for p in found if (REPO / p).is_file()}

    def test_every_tool_a_gradle_task_or_gate_check_reads_is_build_needed(self):
        paths = self.read_paths()
        self.assertIn("tools/ci_shards.py", paths)
        self.assertIn("tools/TestOnlyGate.java", paths)
        missing = sorted(p for p in paths if not affected.needs_gradle([p]))
        self.assertEqual([], missing, "a push changing these would skip the Gradle jobs that read them")

    def test_the_shared_library_directory_is_build_needed(self):
        self.assertTrue(affected.needs_gradle(["tools/lib/ci-gate.sh"]))

    def test_credits_and_the_license_are_jar_inputs_so_they_need_gradle(self):
        for path in ("CREDITS.md", "LICENSE"):
            with self.subTest(path):
                self.assertTrue(affected.needs_gradle([path]))
        self.assertFalse(affected.needs_gradle(["README.md"]))


class FallBackTest(unittest.TestCase):
    def assert_full(self, result: dict[str, str]):
        for suite in ("server", "client"):
            self.assertEqual("true", result[f"{suite}-full"])
            self.assertEqual(str(FULL[suite]), result[f"{suite}-count"])
            self.assertEqual("", result[f"{suite}-classes"])
            self.assertEqual(list(range(1, FULL[suite] + 1)), json.loads(result[f"{suite}-matrix"]))

    def test_a_batch_pr_runs_everything(self):
        self.assert_full(plan(["docs/a.md"], head_ref="batch/20261127T101500Z"))

    def test_the_full_ci_label_runs_everything(self):
        self.assert_full(plan(["docs/a.md"], labels=("full-ci",)))

    def test_what_can_reach_every_test_runs_everything(self):
        for path in (
            "build.gradle.kts", "gradle/libs.versions.toml", "gradle/gametest.gradle.kts", "gradle.lockfile",
            ".github/workflows/ci.yml", ".github/actions/client-gametest/action.yml", f"{MAIN}/mixin/CoreMixin.java",
            "src/main/resources/data/deepcharter/loot_table/x.json", "src/main/resources/deepcharter.mixins.json",
            "src/gametest/resources/x.json", "src/lang/en_us/nested/pod.json", "src/main/unknown/file.txt",
        ):
            with self.subTest(path):
                result = plan([path])
                self.assert_full(result)
                self.assertIn(path, result["why-full"])

    def test_an_affected_set_over_half_the_suite_runs_the_suite_whole(self):
        # Five of eight server classes (50 of 80 s) is over half.
        tests = [f"{PKG}/{n}Test.java" for n in ("Alpha", "Depth", "Mid", "Far", "Beta")]
        result = plan(tests)
        self.assertEqual("true", result["server-full"])
        self.assertEqual("false", result["client-full"])


class RealTreeSelectionTest(unittest.TestCase):
    """Over the real tree: no main or client file escapes the mapping unseen."""

    # A file that selects no test, and why. A new entry needs a reason a reviewer can check.
    ALLOWED: dict[str, str] = {
        "src/main/java/io/github/pkeppeler/deepcharter/wreck/WreckTuning.java": "a tuning record that no other file names (no code reads it yet)",
    }

    @classmethod
    def setUpClass(cls):
        cls.files = sorted(
            str(p.relative_to(REPO))
            for root in ("src/main/java", "src/client/java")
            for p in (REPO / root).rglob("*.java")
            if p.name != "package-info.java" and "/mixin/" not in str(p)
        )

    def selects_nothing(self, path: str) -> bool:
        result = affected.plan([path], "1-slug", set(), 16, 34)
        return result["server-count"] == "0" and result["client-count"] == "0"

    def test_no_main_or_client_file_selects_no_test_unless_listed_with_a_reason(self):
        silent = [p for p in self.files if self.selects_nothing(p) and p not in self.ALLOWED]
        self.assertEqual([], silent, "these select no test: a class no test reaches, or a gap in the mapping")
        stale = [p for p in self.ALLOWED if p not in self.files or not self.selects_nothing(p)]
        self.assertEqual([], stale, "ALLOWED names a file that selects a test now or is gone")


class RealTreeTest(unittest.TestCase):
    full = (16, 34)

    def real(self, changed: list[str], head_ref: str = "1-slug", old: dict[str, str] | None = None) -> dict[str, str]:
        def texts(path: str) -> tuple[str | None, str | None]:
            new = (REPO / path).read_text() if (REPO / path).is_file() else None
            return (old or {}).get(path, new), new

        return affected.plan(changed, head_ref, set(), *self.full, texts=texts if old is not None else None)

    def test_a_docs_change_runs_no_game_test(self):
        result = self.real(["docs/design/mechanics.md"])
        self.assertEqual(("0", "0"), (result["server-count"], result["client-count"]))

    def test_a_batch_runs_every_shard(self):
        result = self.real(["docs/design/mechanics.md"], head_ref="batch/20261127T101500Z")
        self.assertEqual(("16", "34"), (result["server-count"], result["client-count"]))

    def test_a_changed_server_test_class_runs_at_least_that_class(self):
        test = next((REPO / "src/gametest/java").rglob("PodDrillTest.java"))
        result = self.real([str(test.relative_to(REPO))])
        self.assertIn("PodDrillTest", result["server-classes"].split(","))
        self.assertEqual("false", result["server-full"])

    def test_a_tuning_value_runs_far_fewer_tests_than_name_its_record(self):
        path = "src/main/java/io/github/pkeppeler/deepcharter/pod/PodTuning.java"
        text = (REPO / path).read_text()
        default = re.search(r"Movement DEFAULT = new Movement\(([^)]*)\)", text)
        assert default, "PodTuning.Movement.DEFAULT is built from arguments"
        first = default[1].split(",")[0]
        result = self.real([path], old={path: text.replace(default[0], default[0].replace(first, first + "1", 1))})
        self.assertEqual(("false", "false"), (result["server-full"], result["client-full"]))
        whole = self.real([path])
        selected = len(names(result, "server")) + len(names(result, "client"))
        everyone = len(names(whole, "server")) + len(names(whole, "client")) if whole["server-full"] == "false" else 10**6
        self.assertLess(selected, everyone / 2, "horizontalSpeed changed: its readers, not every test that names PodTuning")

    def test_a_known_pr_the_low_fuel_alarm_1699_selects_its_sound_tests(self):
        # #1699 pointed two sound events at new files and changed which one the beep plays.
        sounds = "src/main/resources/assets/deepcharter/sounds.json"
        beep = "src/client/java/io/github/pkeppeler/deepcharter/client/pod/LowFuelBeep.java"
        events = json.loads((REPO / sounds).read_text())
        before = {k: v for k, v in events.items() if k not in ("fuel.low", "alarm.fuel_critical")}
        beep_text = (REPO / beep).read_text()
        result = self.real([sounds, beep], old={sounds: json.dumps(before), beep: beep_text.replace("ALARM_FUEL_CRITICAL", "FUEL_LOW")})
        self.assertIn("SoundEventsTest", names(result, "server"))
        self.assertIn("SoundWiringClientTest", names(result, "client"))
        self.assertEqual(("false", "false"), (result["server-full"], result["client-full"]))
        self.assertLessEqual(int(result["client-count"]) + int(result["server-count"]), 6)

    def test_every_listed_class_exists_and_the_matrix_matches_the_count(self):
        server, client, _ = affected.scanned(REPO)
        known = {c.rsplit(".", 1)[-1] for c in server + client}
        for path in ("src/main/java/io/github/pkeppeler/deepcharter/pod/PodSonar.java", "src/main/java/io/github/pkeppeler/deepcharter/colony/Colony.java"):
            if not (REPO / path).exists():
                continue
            with self.subTest(path):
                result = self.real([path])
                for suite in ("server", "client"):
                    listed = [n for n in result[f"{suite}-classes"].split(",") if n]
                    self.assertLessEqual(set(listed), known)
                    self.assertEqual(int(result[f"{suite}-count"]), len(json.loads(result[f"{suite}-matrix"])))


if __name__ == "__main__":
    unittest.main()
