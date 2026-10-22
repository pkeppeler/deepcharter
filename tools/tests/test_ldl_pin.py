"""The opt-in LambDynamicLights loader stays pinned to the audit and never becomes a dependency (#629)."""
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = (ROOT / "gradle/lambdynamiclights.gradle.kts").read_text(encoding="utf-8")
AUDIT = (ROOT / "docs/tooling/lambdynamiclights-audit.md").read_text(encoding="utf-8")
LIGHTS = ROOT / "src/main/resources/assets/deepcharter/dynamiclights/entity"
TUNING = (ROOT / "src/main/java/io/github/pkeppeler/deepcharter/upgrade/UpgradeTuning.java").read_text(encoding="utf-8")


def pinned(name: str) -> str:
    found = re.search(rf'val {name} = "([^"]+)"', SCRIPT)
    assert found, f"gradle/lambdynamiclights.gradle.kts has no `val {name}`"
    return found.group(1)


class LdlPinTest(unittest.TestCase):
    def test_sha512_is_the_audited_one(self):
        self.assertIn(f"`{pinned('ldlSha512')}`", AUDIT)

    def test_url_and_file_are_the_audited_ones(self):
        self.assertIn(f"`{pinned('ldlUrl')}`", AUDIT)
        self.assertIn(f"`{pinned('ldlFileName')}`", AUDIT)

    def test_off_unless_the_flag_is_set(self):
        self.assertIn('System.getenv("DEEPCHARTER_LDL") == "1"', SCRIPT)
        self.assertEqual(SCRIPT.count("tasks.register"), 1)

    def test_never_a_dependency_of_the_mod(self):
        mod_json = (ROOT / "src/main/resources/fabric.mod.json").read_text(encoding="utf-8")
        self.assertNotIn("lambdynlights", mod_json)
        self.assertNotIn("lambdynamiclights", (ROOT / "gradle/libs.versions.toml").read_text(encoding="utf-8").lower())

    def test_only_pods_emit(self):
        names = sorted(path.name for path in LIGHTS.glob("*.json"))
        self.assertTrue(names)
        for name in names:
            self.assertRegex(name, r"^pod_headlamp_\d+\.json$")

    def test_pod_files_are_data_only(self):
        for path in sorted(LIGHTS.glob("*.json")):
            data = json.loads(path.read_text(encoding="utf-8"))
            self.assertEqual(set(data), {"match", "luminance"}, path.name)
            self.assertEqual(
                data["match"]["type"], ["deepcharter:pod", "deepcharter:prospector"], path.name
            )

    def test_pod_levels_are_the_lights_track(self):
        found = re.search(r"ComponentTrack\.LIGHTS,\s*new Tiers\(List\.of\(([^)]*)\)", TUNING)
        assert found, "UpgradeTuning.java has no LIGHTS tiers list"
        levels = {int(float(v.strip().rstrip("fF"))) for v in found.group(1).split(",")} - {0}
        files = {}
        for path in LIGHTS.glob("pod_headlamp_*.json"):
            data = json.loads(path.read_text(encoding="utf-8"))
            files[data["luminance"]] = data["match"]["location"]["light"]["block"]["min"]
        self.assertEqual(set(files), levels)
        for luminance, minimum in files.items():
            self.assertEqual(minimum, luminance - 1, f"level {luminance}")


if __name__ == "__main__":
    unittest.main()
