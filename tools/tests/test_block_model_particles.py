"""Tests that every block model a blockstate uses resolves its `particle` texture (#1298), directly or through its parent chain.
Without one, breaking or landing on the block shows the missing-texture particle and the game logs a missing reference."""
import json
import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(TOOLS / "textures"))

import texgen  # noqa: E402

ASSETS = texgen.ROOT / "src/main/resources/assets/deepcharter"
CONNECTED = "deepcharter:connected"
# The vanilla parents our models use, with the textures they define, from the 26.3 jar.
VANILLA = {
    "minecraft:block/block": {},
    "minecraft:block/cube_all": {"particle": "#all"},
    "minecraft:block/cube_column": {"particle": "#side"},
    "minecraft:block/cube_bottom_top": {"particle": "#side"},
    "minecraft:block/cross": {"particle": "#cross"},
}


def read(path):
    return json.loads(path.read_text())


def split_id(name):
    return name.split(":") if ":" in name else ("minecraft", name)


def textures(name):
    if name in VANILLA:
        return dict(VANILLA[name])
    namespace, path = split_id(name)
    assert namespace == "deepcharter", f"{name}: a vanilla model or parent missing from VANILLA; add its textures"
    model = read(ASSETS / "models" / f"{path}.json")
    return {**(textures(model["parent"]) if "parent" in model else {}), **model.get("textures", {})}


def resolve(name):
    """The texture id `particle` ends at, or None when a reference in the chain is undefined."""
    mapping, seen = textures(name), set()
    value = mapping.get("particle")
    while value is not None and value.startswith("#") and value not in seen:
        seen.add(value)
        value = mapping.get(value[1:])
    return None if value is None or value.startswith("#") else value


def blockstate_models():
    models = set()
    for path in sorted((ASSETS / "blockstates").glob("*.json")):
        state = read(path)
        entries = list(state.get("variants", {}).values()) + [part["apply"] for part in state.get("multipart", [])]
        for entry in entries:
            for variant in entry if isinstance(entry, list) else [entry]:
                # A deepcharter:connected variant (the conduit) names tile textures, not a model; its Java model picks the particle.
                if variant.get("fabric:type") == CONNECTED:
                    continue
                assert "model" in variant, f"{path.name}: a variant with no model: {variant}"
                models.add(variant["model"])
    return sorted(models)


class BlockModelParticleTests(unittest.TestCase):
    def test_the_blockstates_are_found(self):
        self.assertIn("deepcharter:block/fuel_pump_active", blockstate_models())

    def test_every_blockstate_model_resolves_particle_to_a_texture_that_exists(self):
        for name in blockstate_models():
            with self.subTest(name):
                texture = resolve(name)
                self.assertIsNotNone(texture, f"{name}: particle does not resolve")
                namespace, path = split_id(texture)
                if namespace == "deepcharter":
                    self.assertTrue((ASSETS / "textures" / f"{path}.png").exists(), f"{name}: {texture} has no png")


if __name__ == "__main__":
    unittest.main()
