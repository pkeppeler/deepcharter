"""tools/ci_affected.py: which GameTest classes a pull request's changed paths can affect, and when CI runs the suites whole (ADR 0116).

The mapping is tested on a small fixture tree (every rule, and the conservative fall-back), then on the real tree for what must hold
whatever the code is: a docs change runs no GameTest, a batch PR runs everything, a change to a test class runs that class."""
import json
import re
import sys
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools"))
import ci_affected as affected  # noqa: E402

PKG = "src/gametest/java/io/x/test"
MAIN = "src/main/java/io/x"
FILES = {
    # main: Core is mentioned by Mid, Mid by LeafUser; Island by nothing; Registry names every main class.
    f"{MAIN}/Core.java": "public class Core { public static class Inner {} }\n",
    f"{MAIN}/Mid.java": "public class Mid { Core core; }\n",
    f"{MAIN}/Island.java": "public class Island {}\n",
    f"{MAIN}/mixin/CoreMixin.java": "public class CoreMixin {}\n",
    "src/client/java/io/x/Screen.java": "public class Screen { Mid mid; }\n",
    f"{PKG}/support/Helper.java": "public class Helper { Island island; }\n",
    f"{PKG}/support/Unused.java": "public class Unused {}\n",
    f"{PKG}/AlphaTest.java": "public class AlphaTest { @GameTest public void a() { new Mid(); } }\n",
    f"{PKG}/BetaTest.java": "public class BetaTest { @GameTest public void b() { new Helper(); } }\n",
    f"{PKG}/GammaTest.java": "public class GammaTest { @GameTest public void c() { } }\n",
    f"{PKG}/DeltaTest.java": 'public class DeltaTest { @GameTest public void d() { String s = "assets/x/y.png"; } }\n',
    f"{PKG}/InnerTest.java": "public class InnerTest { @GameTest public void e() { Inner i; } }\n",
    f"{PKG}/OneClientTest.java": "public class OneClientTest implements FabricClientGameTest { Screen s; }\n",
    f"{PKG}/TwoClientTest.java": "public class TwoClientTest implements FabricClientGameTest { Helper h; }\n",
    f"{PKG}/ThreeClientTest.java": "public class ThreeClientTest implements FabricClientGameTest { }\n",
    f"{PKG}/evidence/OneScenario.java": "public class OneScenario extends EvidenceScenario { Mid m; }\n",
    "gradle/server-test-weights.txt": "AlphaTest 10\nBetaTest 10\nGammaTest 10\nDeltaTest 10\nInnerTest 10\n",
    "gradle/client-test-weights.txt": "OneClientTest 40\nTwoClientTest 40\nThreeClientTest 40\n",
    "gradle/ci-budget.txt": "server-shard-seconds 100\nclient-shard-seconds 100\n",
}
FULL = {"server": 16, "client": 34}


def plan(changed: list[str], head_ref: str = "1-slug", labels: tuple[str, ...] = (), files: dict[str, str] | None = None) -> dict[str, str]:
    with tempfile.TemporaryDirectory() as tmp:
        for name, text in {**FILES, **(files or {})}.items():
            path = Path(tmp) / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
        return affected.plan(changed, head_ref, set(labels), FULL["server"], FULL["client"], Path(tmp))


def names(result: dict[str, str], suite: str) -> list[str]:
    return [n for n in result[f"{suite}-classes"].split(",") if n]


class MappingTest(unittest.TestCase):
    def test_a_class_nothing_mentions_affects_nothing(self):
        result = plan([f"{MAIN}/Island.java"])
        self.assertEqual(["BetaTest"], names(result, "server"), "Island is named by Helper, which BetaTest names")
        self.assertEqual(["TwoClientTest"], names(result, "client"))

    def test_a_change_reaches_the_tests_that_mention_it_through_other_classes(self):
        result = plan([f"{MAIN}/Core.java"])
        self.assertEqual(["AlphaTest", "InnerTest"], names(result, "server"), "Mid mentions Core; Inner is Core's nested type")
        self.assertEqual(["OneClientTest"], names(result, "client"), "Screen mentions Mid")

    def test_a_nested_type_is_a_use_of_its_file(self):
        self.assertEqual(["InnerTest"], names(plan([f"{MAIN}/Core.java"], files={f"{MAIN}/Mid.java": "public class Mid {}\n"}), "server"))

    def test_a_changed_test_class_runs_itself_only(self):
        result = plan([f"{PKG}/GammaTest.java"])
        self.assertEqual(["GammaTest"], names(result, "server"))
        self.assertEqual("0", result["client-count"])

    def test_a_changed_client_test_class_runs_itself_only(self):
        result = plan([f"{PKG}/ThreeClientTest.java"])
        self.assertEqual(["ThreeClientTest"], names(result, "client"))
        self.assertEqual("0", result["server-count"])

    def test_a_test_support_class_reaches_the_tests_that_mention_it(self):
        result = plan([f"{PKG}/support/Helper.java"])
        self.assertEqual(["BetaTest"], names(result, "server"))
        self.assertEqual(["TwoClientTest"], names(result, "client"))

    def test_an_unused_support_class_runs_no_test(self):
        result = plan([f"{PKG}/support/Unused.java"])
        self.assertEqual(("0", "0"), (result["server-count"], result["client-count"]))

    def test_a_deleted_file_is_found_by_its_name(self):
        self.assertEqual(["BetaTest"], names(plan([f"{MAIN}/Island.java"], files={f"{MAIN}/Island.java": "x\n"}), "server"))
        gone = FILES.copy()
        with tempfile.TemporaryDirectory() as tmp:
            for name, text in gone.items():
                if name.endswith("Island.java"):
                    continue
                path = Path(tmp) / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text)
            result = affected.plan([f"{MAIN}/Island.java"], "1-x", set(), 15, 30, Path(tmp))
        self.assertEqual(["BetaTest"], names(result, "server"))

    def test_a_scenario_is_never_listed(self):
        result = plan([f"{MAIN}/Mid.java"])
        self.assertNotIn("OneScenario", result["client-classes"])

    def test_a_package_info_change_runs_nothing(self):
        result = plan([f"{MAIN}/package-info.java"], files={f"{MAIN}/package-info.java": "package io.x;\n"})
        self.assertEqual(("0", "0"), (result["server-count"], result["client-count"]))

    def test_docs_tools_and_weights_run_no_game_test(self):
        for path in ("docs/design/a.md", "README.md", "tools/merge-pr.sh", ".claude/CLAUDE.md", "gradle/client-test-weights.txt", "gradle/ci-budget.txt"):
            with self.subTest(path):
                result = plan([path])
                self.assertEqual(("false", "false", "0", "0"), (result["server-full"], result["client-full"], result["server-count"], result["client-count"]))
                self.assertEqual("[]", result["server-matrix"])

    def test_the_count_is_the_fewest_shards_that_fit_the_budget(self):
        # Five server classes of 10 s under a 100 s budget fit one shard; at a 25 s budget they need three.
        result = plan(
            [f"{MAIN}/Core.java", f"{PKG}/GammaTest.java", f"{PKG}/support/Helper.java", f"{PKG}/DeltaTest.java"],
            files={
                "gradle/ci-budget.txt": "server-shard-seconds 25\nclient-shard-seconds 100\n",
                f"{PKG}/HeavyTest.java": "public class HeavyTest { @GameTest public void h() { } }\n",
                "gradle/server-test-weights.txt": "AlphaTest 10\nBetaTest 10\nGammaTest 10\nDeltaTest 10\nInnerTest 10\nHeavyTest 200\n",
            },
        )
        self.assertEqual(["AlphaTest", "BetaTest", "DeltaTest", "GammaTest", "InnerTest"], names(result, "server"))
        self.assertEqual("3", result["server-count"])
        self.assertEqual("[1, 2, 3]", result["server-matrix"])


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
            "src/gametest/resources/x.json", "src/lang/en_us/pod.json", "src/main/unknown/file.txt",
        ):
            with self.subTest(path):
                self.assert_full(plan([path]))

    def test_an_affected_set_over_half_the_suite_runs_the_suite_whole(self):
        # Three of five server classes (30 of 50 s) is over half.
        result = plan([f"{PKG}/AlphaTest.java", f"{PKG}/BetaTest.java", f"{PKG}/GammaTest.java"])
        self.assertEqual("true", result["server-full"])
        self.assertEqual("false", result["client-full"])

    def test_an_asset_runs_the_client_suite_and_the_server_classes_that_name_assets(self):
        result = plan(["src/client/resources/assets/x/textures/a.png"])
        self.assertEqual("true", result["client-full"])
        self.assertEqual(["DeltaTest"], names(result, "server"))


class EntryPointAndHelperTest(unittest.TestCase):
    """A change that reaches a mod entry point has no test that names it, so it widens to that suite's full run (#1612 review C1), and a
    helper under test/ that is not itself a test class passes the change on to the tests that use it (W1)."""

    ENTRY = {
        f"{MAIN}/DeepCharter.java": "public class DeepCharter { void init() { new PodInit(); } }\n",
        f"{MAIN}/PodInit.java": "public class PodInit { Island i; }\n",
        "src/client/java/io/x/DeepCharterClient.java": "public class DeepCharterClient { void init() { new PodClientInit(); } }\n",
        "src/client/java/io/x/PodClientInit.java": "public class PodClientInit { }\n",
        "src/client/java/io/x/LowFuelBeep.java": "public class LowFuelBeep { }\n",
        f"{PKG}/ClientTestLog.java": "public class ClientTestLog { }\n",
        f"{PKG}/HandCases.java": "public abstract class HandCases { ClientTestLog log; }\n",
        f"{PKG}/HandClientTest.java": "public class HandClientTest extends HandCases implements FabricClientGameTest { }\n",
        f"{PKG}/BoxClientTest.java": "public class BoxClientTest implements FabricClientGameTest { ClientTestLog log; }\n",
    }

    def test_a_client_init_change_selects_every_client_test(self):
        result = plan(["src/client/java/io/x/PodClientInit.java"], files=self.ENTRY)
        self.assertEqual("true", result["client-full"])
        self.assertEqual(str(FULL["client"]), result["client-count"])

    def test_a_file_the_client_entry_point_reaches_selects_the_client_suite_not_the_server_one(self):
        result = plan(["src/client/java/io/x/PodClientInit.java"], files=self.ENTRY)
        self.assertEqual("false", result["server-full"])

    def test_a_main_init_change_selects_both_suites(self):
        result = plan([f"{MAIN}/PodInit.java"], files=self.ENTRY)
        self.assertEqual(("true", "true"), (result["server-full"], result["client-full"]))

    def test_the_entry_point_itself_selects_its_suite(self):
        self.assertEqual("true", plan(["src/client/java/io/x/DeepCharterClient.java"], files=self.ENTRY)["client-full"])

    def test_a_file_nothing_reaches_still_selects_nothing(self):
        result = plan(["src/client/java/io/x/LowFuelBeep.java"], files=self.ENTRY)
        self.assertEqual(("0", "0", "false"), (result["client-count"], result["server-count"], result["client-full"]))

    def test_a_helper_that_is_not_a_test_class_passes_a_change_on_through_an_abstract_base(self):
        result = plan([f"{PKG}/ClientTestLog.java"], files=self.ENTRY)
        self.assertEqual(["BoxClientTest", "HandClientTest"], names(result, "client"))

    def test_a_file_a_mixin_reaches_selects_the_suites_the_mixin_runs_in(self):
        files = {
            **self.ENTRY,
            "src/client/java/io/x/mixin/ScreenMixin.java": "public class ScreenMixin { LowFuelBeep b; }\n",
            f"{MAIN}/mixin/WorldMixin.java": "public class WorldMixin { PodInit p; }\n",
        }
        client = plan(["src/client/java/io/x/LowFuelBeep.java"], files=files)
        self.assertEqual(("true", "false"), (client["client-full"], client["server-full"]))
        both = plan([f"{MAIN}/Island.java"], files={**files, f"{MAIN}/mixin/IslandMixin.java": "public class IslandMixin { Island i; }\n"})
        self.assertEqual(("true", "true"), (both["client-full"], both["server-full"]))

    def test_a_listed_test_class_is_a_leaf(self):
        result = plan([f"{PKG}/BoxClientTest.java"], files=self.ENTRY)
        self.assertEqual(["BoxClientTest"], names(result, "client"))


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

    def test_every_client_init_and_registry_selects_every_client_test(self):
        wiring = [p for p in self.files if re.search(r"/[A-Z]\w*Client(Init|Registry)\.java$", p)]
        self.assertGreater(len(wiring), 10)
        for path in wiring:
            with self.subTest(path):
                result = affected.plan([path], "1-slug", set(), 16, 34)
                self.assertEqual("true", result["client-full"])

    def test_no_main_or_client_file_selects_no_test_unless_listed_with_a_reason(self):
        silent = [p for p in self.files if self.selects_nothing(p) and p not in self.ALLOWED]
        self.assertEqual([], silent, "these select no test: a class no test reaches, or a gap in the mapping")
        stale = [p for p in self.ALLOWED if p not in self.files or not self.selects_nothing(p)]
        self.assertEqual([], stale, "ALLOWED names a file that selects a test now or is gone")


class RealTreeTest(unittest.TestCase):
    full = (16, 34)

    def real(self, changed: list[str], **kwargs: object) -> dict[str, str]:
        return affected.plan(changed, kwargs.get("head_ref", "1-slug"), set(), *self.full)  # type: ignore[arg-type]

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

    def test_every_listed_class_exists_and_the_matrix_matches_the_count(self):
        sys.path.insert(0, str(REPO / "tools"))
        import ci_shards

        server, client, _ = ci_shards.scan()
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
