"""Tests the terminals' block models (#857): a terminal is two blocks high, a cabinet (lower half) under a CRT monitor (upper half).
One shared parent per half, a monitor model per terminal and state, an item model showing both, and a blockstate that turns each
to its facing. The glow states are the monitor models' `front_glow` texture, so a terminal that loses one goes dark or stays lit."""
import json
import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(TOOLS / "textures"))

import texgen  # noqa: E402

ASSETS = texgen.ROOT / "src/main/resources/assets/deepcharter"
MONITOR = "deepcharter:block/terminal"
CABINET = "deepcharter:block/terminal_cabinet"
ITEM = "deepcharter:block/terminal_item"
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


def box(element):
    return element["from"] + element["to"]


class TerminalModelTests(unittest.TestCase):
    def test_the_terminals_are_found(self):
        self.assertEqual(
            ["contract_terminal", "fuel_pump", "hangar_console", "ore_processor", "repair_station", "upgrade_terminal"],
            terminals())

    def test_every_state_facing_and_half_resolves_to_a_model_that_shares_the_parent(self):
        for name in terminals():
            variants = read(ASSETS / f"blockstates/{name}.json")["variants"]
            self.assertEqual(16, len(variants), name)
            for key, variant in variants.items():
                active, facing, half = (part.split("=")[1] for part in key.split(","))
                if half == "lower":
                    self.assertEqual(CABINET, variant["model"], key)
                else:
                    self.assertEqual(f"deepcharter:block/{name}" + ("_active" if active == "true" else ""), variant["model"], key)
                    self.assertEqual(MONITOR, model(variant["model"])["parent"], variant["model"])
                self.assertEqual(FACING_Y[facing], variant.get("y"), f"{name} {key}")

    def test_active_lights_the_screen_and_standby_only_the_lamp(self):
        for name in terminals():
            standby = model(f"deepcharter:block/{name}")["textures"]
            active = model(f"deepcharter:block/{name}_active")["textures"]
            self.assertEqual("deepcharter:block/terminal_standby_glow", standby["front_glow"], name)
            self.assertEqual(f"deepcharter:block/{name}_front_glow", active["front_glow"], name)
            self.assertEqual(f"deepcharter:block/{name}_front", active["front"], name)
            self.assertEqual(standby["front"], active["front"], name)

    def test_each_item_shows_the_whole_terminal(self):
        for name in terminals():
            item = read(ASSETS / f"items/{name}.json")["model"]["model"]
            self.assertEqual(f"deepcharter:block/{name}_item", item)
            self.assertEqual(ITEM, model(item)["parent"])
            self.assertEqual(f"deepcharter:block/{name}_front", model(item)["textures"]["front"])

    def test_the_halves_are_a_block_each_and_neither_is_the_old_full_cube(self):
        monitor, cabinet = model(MONITOR)["elements"], model(CABINET)["elements"]
        for elements in (monitor, cabinet):
            for element in elements:
                self.assertTrue(all(0 <= v <= 16 for v in box(element)), element["name"])
                self.assertNotEqual(([0, 0, 0], [16, 16, 16]), (element["from"], element["to"]), element["name"])
                self.assertEqual(0, element.get("rotation", {}).get("angle", 0) % 22.5, element["name"])
        self.assertEqual(16, max(e["to"][1] for e in cabinet), "the cabinet fills its block's height")
        casing = next(e for e in monitor if e["name"] == "monitor_casing")
        self.assertEqual([0, 0, 16, 16], [casing["from"][0], casing["from"][1], casing["to"][0], casing["to"][1]],
                         "the monitor fills the width and height of the upper block")

    def test_the_item_and_its_elements_stay_within_vanilla_limits(self):
        for element in model(ITEM)["elements"]:
            self.assertTrue(all(-16 <= v <= 32 for v in box(element)), element["name"])

    def test_the_front_texture_maps_one_to_one_onto_the_monitor_face(self):
        casing = next(e for e in model(MONITOR)["elements"] if e["name"] == "monitor_casing")
        self.assertEqual([0, 0, 16, 16], casing["faces"]["north"]["uv"])
        self.assertEqual("#front", casing["faces"]["north"]["texture"])
        self.assertEqual(16, casing["to"][0] - casing["from"][0])
        self.assertEqual(16, casing["to"][1] - casing["from"][1])

    def test_the_screen_glow_is_emissive_and_in_front_of_the_glass(self):
        elements = model(MONITOR)["elements"]
        glow = [e for e in elements if any(f["texture"] == "#front_glow" for f in e["faces"].values())]
        self.assertTrue(glow)
        for element in glow:
            self.assertEqual(15, element["light_emission"], element["name"])
            self.assertEqual(["north"], list(element["faces"]), element["name"])
        screen = next(e for e in glow if e["name"] == "glow_screen")
        glass = [e for e in elements if e["name"].startswith("glass")]
        self.assertLess(screen["from"][2], min(e["from"][2] for e in glass))

    def test_the_casing_has_its_own_textures_and_only_the_cabinet_front_carries_the_plaque(self):
        for key in ("casing", "casing_top"):
            self.assertTrue((ASSETS / "textures/block" / f"terminal_{key}.png").exists(), key)
            self.assertEqual(f"deepcharter:block/terminal_{key}", model(MONITOR)["textures"][key])
        for element in model(MONITOR)["elements"]:
            self.assertNotIn("#side", {f["texture"] for f in element["faces"].values()}, element["name"])
        cabinet = model(CABINET)["elements"][0]
        self.assertEqual("#side", cabinet["faces"]["north"]["texture"])

    def test_the_glass_steps_out_and_the_knobs_are_brass(self):
        elements = {e["name"]: e for e in model(MONITOR)["elements"]}
        rim, bulge, casing = elements["glass_rim"], elements["glass_bulge"], elements["monitor_casing"]
        self.assertEqual(1.0, rim["from"][2] - bulge["from"][2], "the centre of the glass is a whole pixel proud of its rim")
        self.assertLess(rim["from"][2], casing["from"][2])
        knobs = [e for n, e in elements.items() if n.startswith("knob")]
        self.assertEqual(2, len(knobs))
        for knob in knobs:
            self.assertLess(knob["from"][2], casing["from"][2], "a knob stands out of the front")
            self.assertEqual("#front", knob["faces"]["north"]["texture"])
            self.assertEqual(12, knob["faces"]["north"]["uv"][1], "its face is a pixel of the brass plate of the front texture")

    def test_every_face_texture_is_one_the_children_or_the_parent_define(self):
        for parent, child in ((MONITOR, "deepcharter:block/fuel_pump"), (ITEM, "deepcharter:block/fuel_pump_item"), (CABINET, CABINET)):
            parent_model = model(parent)
            textures = {**parent_model["textures"], **model(child)["textures"]}
            for element in parent_model["elements"]:
                for face in element["faces"].values():
                    self.assertIn(face["texture"].removeprefix("#"), textures, element["name"])


if __name__ == "__main__":
    unittest.main()
