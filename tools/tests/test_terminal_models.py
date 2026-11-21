"""Tests the terminals' block models (#857): one shared cabinet-and-CRT parent, a model per terminal and state, and a blockstate that
turns each to its facing. The glow states are the models' `front_glow` texture, so a terminal that loses one goes dark or stays lit."""
import json
import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(TOOLS / "textures"))

import texgen  # noqa: E402

ASSETS = texgen.ROOT / "src/main/resources/assets/deepcharter"
PARENT = "deepcharter:block/terminal"
FACING_Y = {"north": None, "east": 90, "south": 180, "west": 270}


def read(path):
    return json.loads(path.read_text())


def model(name):
    return read(ASSETS / "models/block" / f"{name.removeprefix('deepcharter:block/')}.json")


def terminals():
    """The terminals: every block whose front texture comes from the terminal_front template."""
    recipes = read(texgen.DEFAULT_RECIPES / "blocks.json")["recipes"]
    return sorted(key.removeprefix("block/").removesuffix("_front") for key, recipe in recipes.items()
                  if recipe.get("template") == "terminal_front")


class TerminalModelTests(unittest.TestCase):
    def test_the_terminals_are_found(self):
        self.assertEqual(
            ["contract_terminal", "fuel_pump", "hangar_console", "ore_processor", "repair_station", "upgrade_terminal"],
            terminals())

    def test_every_state_and_facing_resolves_to_a_model_that_shares_the_parent(self):
        for name in terminals():
            variants = read(ASSETS / f"blockstates/{name}.json")["variants"]
            self.assertEqual(8, len(variants), name)
            for key, variant in variants.items():
                active, facing = (part.split("=")[1] for part in key.split(","))
                self.assertEqual(f"deepcharter:block/{name}" + ("_active" if active == "true" else ""), variant["model"], key)
                self.assertEqual(FACING_Y[facing], variant.get("y"), f"{name} {key}")
                self.assertEqual(PARENT, model(variant["model"])["parent"], variant["model"])

    def test_active_lights_the_screen_and_standby_only_the_lamp(self):
        for name in terminals():
            standby = model(f"deepcharter:block/{name}")["textures"]
            active = model(f"deepcharter:block/{name}_active")["textures"]
            self.assertEqual("deepcharter:block/terminal_standby_glow", standby["front_glow"], name)
            self.assertEqual(f"deepcharter:block/{name}_front_glow", active["front_glow"], name)
            self.assertEqual(f"deepcharter:block/{name}_front", active["front"], name)
            self.assertEqual(standby["front"], active["front"], name)

    def test_the_parent_is_a_cabinet_with_a_monitor_on_top_and_not_a_full_cube(self):
        elements = model(PARENT)["elements"]
        for element in elements:
            self.assertTrue(all(-16 <= v <= 32 for v in element["from"] + element["to"]), element["name"])
            self.assertEqual(0, element.get("rotation", {}).get("angle", 0) % 22.5, element["name"])
            self.assertNotEqual(([0, 0, 0], [16, 16, 16]), (element["from"], element["to"]), element["name"])
        self.assertEqual(16, max(e["to"][1] for e in elements), "the monitor reaches the top of the block")
        self.assertLess(max(e["to"][1] for e in elements if e["name"] == "cabinet"), 16)

    def test_the_screen_glow_is_emissive_and_in_front_of_the_glass(self):
        elements = model(PARENT)["elements"]
        glow = [e for e in elements if any(f["texture"] == "#front_glow" for f in e["faces"].values())]
        self.assertTrue(glow)
        for element in glow:
            self.assertEqual(15, element["light_emission"], element["name"])
            self.assertEqual(["north"], list(element["faces"]), element["name"])
        screen = next(e for e in glow if e["name"] == "glow_screen")
        glass = [e for e in elements if e["name"].startswith("glass")]
        self.assertLess(screen["from"][2], min(e["from"][2] for e in glass))

    def test_every_face_texture_is_one_the_children_define(self):
        child = model("deepcharter:block/fuel_pump")["textures"]
        for element in model(PARENT)["elements"]:
            for face in element["faces"].values():
                self.assertIn(face["texture"].removeprefix("#"), child, element["name"])


if __name__ == "__main__":
    unittest.main()
