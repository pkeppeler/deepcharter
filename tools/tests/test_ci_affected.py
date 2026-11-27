"""tools/ci_affected.py: which GameTest classes a pull request's changed paths can affect, and when CI runs the suites whole (ADR 0116).

The mapping is tested on a small fixture tree (every rule, and the conservative fall-back), then on the real tree for what must hold
whatever the code is: a docs change runs no GameTest, a batch PR runs everything, a change to a test class runs that class."""
import json
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
FULL = {"server": 15, "client": 29}


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


class RealTreeTest(unittest.TestCase):
    full = (15, 29)

    def real(self, changed: list[str], **kwargs: object) -> dict[str, str]:
        return affected.plan(changed, kwargs.get("head_ref", "1-slug"), set(), *self.full)  # type: ignore[arg-type]

    def test_a_docs_change_runs_no_game_test(self):
        result = self.real(["docs/design/mechanics.md"])
        self.assertEqual(("0", "0"), (result["server-count"], result["client-count"]))

    def test_a_batch_runs_every_shard(self):
        result = self.real(["docs/design/mechanics.md"], head_ref="batch/20261127T101500Z")
        self.assertEqual(("15", "29"), (result["server-count"], result["client-count"]))

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
