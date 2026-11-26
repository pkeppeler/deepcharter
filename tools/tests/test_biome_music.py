"""Every Deep Charter biome declares `minecraft:audio/background_music` (#1296); `{}` is silence.

Without it a biome falls back to vanilla's music, creative music included.
"""
import json
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ATTRIBUTE = "minecraft:audio/background_music"


def without_music(root: Path) -> list[str]:
    """The `deepcharter` biome files under `root` whose attributes lack the background music."""
    found: list[str] = []
    for path in sorted(root.glob("src/main/resources/data/deepcharter/worldgen/biome/*.json")):
        attributes = json.loads(path.read_text(encoding="utf-8")).get("attributes", {})
        if ATTRIBUTE not in attributes:
            found.append(str(path.relative_to(root)))
    return found


class BiomeMusicTest(unittest.TestCase):
    def test_every_biome_declares_background_music(self):
        self.assertEqual([], without_music(ROOT))

    def test_a_biome_without_it_is_found(self):
        with tempfile.TemporaryDirectory() as tmp:
            biomes = Path(tmp) / "src/main/resources/data/deepcharter/worldgen/biome"
            biomes.mkdir(parents=True)
            (biomes / "silent.json").write_text(json.dumps({"attributes": {ATTRIBUTE: {}}}), encoding="utf-8")
            (biomes / "loud.json").write_text(json.dumps({"attributes": {}}), encoding="utf-8")
            self.assertEqual(["src/main/resources/data/deepcharter/worldgen/biome/loud.json"], without_music(Path(tmp)))


if __name__ == "__main__":
    unittest.main()
