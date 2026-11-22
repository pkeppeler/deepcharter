"""Tests the phonograph and Company PA horn block models (#911): the craftable, colony and wreck phonographs each have a real cabinet,
mandrel, crank and horn model of their own, the loaded states add the cylinder, and the horn is a roof-hung trumpet with its own
Company textures. The models are data, so these checks are the gate: every blockstate resolves, every texture exists and comes from
a recipe, and the selection shapes in Java still cover what the models draw."""
import json
import math
import re
import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(TOOLS / "textures"))

import texgen  # noqa: E402

ASSETS = texgen.ROOT / "src/main/resources/assets/deepcharter"
JAVA = texgen.ROOT / "src/main/java/io/github/pkeppeler/deepcharter/sound"
FACING_Y = {"north": None, "east": 90, "south": 180, "west": 270}


def read(path):
    return json.loads(path.read_text())


def model(name):
    return read(ASSETS / "models/block" / f"{name.removeprefix('deepcharter:block/')}.json")


def names(m):
    return [e["name"] for e in m["elements"]]


def shape_boxes(java_file):
    source = (JAVA / java_file).read_text()
    boxes = [list(map(float, args.split(","))) for args in re.findall(r"Block\.box\(([^)]*)\)", source)]
    assert len(boxes) >= 2, f"{java_file} has {len(boxes)} Block.box literals: the shape moved, so teach shape_boxes where it went"
    return boxes


MODELS = ("phonograph", "phonograph_loaded", "colony_phonograph", "colony_phonograph_loaded", "wreck_phonograph", "company_horn")
ANGLES = {-45, -22.5, 0, 22.5, 45}


def corners(element):
    """The 8 corners of the element, turned about its rotation's origin on its axis, as the game does."""
    rotation = element.get("rotation")
    points = [(x, y, z) for x in (element["from"][0], element["to"][0]) for y in (element["from"][1], element["to"][1])
              for z in (element["from"][2], element["to"][2])]
    if not rotation:
        return points
    cos, sin = math.cos(math.radians(rotation["angle"])), math.sin(math.radians(rotation["angle"]))
    ox, oy, oz = rotation["origin"]
    turned = []
    for x, y, z in points:
        x, y, z = x - ox, y - oy, z - oz
        if rotation["axis"] == "x":
            y, z = y * cos - z * sin, y * sin + z * cos
        elif rotation["axis"] == "y":
            x, z = x * cos + z * sin, -x * sin + z * cos
        else:
            x, y = x * cos - y * sin, x * sin + y * cos
        turned.append((x + ox, y + oy, z + oz))
    return turned


def covered(element, boxes):
    """Every corner of the element, turned, lies in some box: the union of the boxes holds the element."""
    eps = 1e-6
    return all(any(all(b[i] - eps <= p[i] <= b[i + 3] + eps for i in range(3)) for b in boxes) for p in corners(element))


class PhonographModelTests(unittest.TestCase):
    def test_every_blockstate_variant_resolves_to_the_model_of_its_block(self):
        expected = {"phonograph": ("phonograph", "phonograph_loaded"),
                    "colony_phonograph": ("colony_phonograph", "colony_phonograph_loaded"),
                    "wreck_phonograph": ("wreck_phonograph", "wreck_phonograph")}
        for block, (empty, loaded) in expected.items():
            variants = read(ASSETS / f"blockstates/{block}.json")["variants"]
            for key, variant in variants.items():
                facing = key.split("facing=")[1].split(",")[0]
                self.assertEqual(FACING_Y[facing], variant.get("y"), f"{block} {key}")
                want = loaded if "loaded=true" in key else empty
                self.assertEqual(f"deepcharter:block/{want}", variant["model"], f"{block} {key}")

    def test_the_item_shows_the_craftable_model_and_the_models_inherit_the_block_display(self):
        item = read(ASSETS / "items/phonograph.json")["model"]["model"]
        self.assertEqual("deepcharter:block/phonograph", item)
        for name in MODELS:
            self.assertEqual("minecraft:block/block", model(name)["parent"], name)

    def test_every_model_texture_exists_and_every_face_texture_is_defined(self):
        for name in MODELS:
            m = model(name)
            for ref in m["textures"].values():
                if not ref.startswith("#"):
                    self.assertTrue((ASSETS / "textures" / f"{ref.split(':')[1]}.png").exists(), f"{name} {ref}")
            for element in m["elements"]:
                for face in element["faces"].values():
                    self.assertIn(face["texture"].removeprefix("#"), m["textures"], f"{name} {element['name']}")

    def test_elements_stay_in_vanilla_limits_and_turn_in_steps_the_renderer_accepts(self):
        for name in MODELS:
            for element in model(name)["elements"]:
                self.assertTrue(all(-16 <= v <= 32 for v in element["from"] + element["to"]), element["name"])
                rotation = element.get("rotation", {"angle": 0})
                self.assertIn(rotation["angle"], ANGLES, element["name"])
                self.assertEqual({"angle", "origin", "axis"} if "origin" in rotation else {"angle"}, set(rotation), element["name"])
                self.assertIn(rotation.get("axis", "x"), {"x", "y", "z"}, element["name"])

    def test_a_loaded_model_is_the_empty_one_plus_the_cylinder(self):
        for empty, loaded in (("phonograph", "phonograph_loaded"), ("colony_phonograph", "colony_phonograph_loaded")):
            extra = [n for n in names(model(loaded)) if n not in names(model(empty))]
            self.assertEqual(["cylinder_a", "cylinder_b"], extra, loaded)
            self.assertEqual(names(model(empty)), [n for n in names(model(loaded)) if n not in extra])
            for element in model(loaded)["elements"]:
                if element["name"].startswith("cylinder"):
                    self.assertEqual("#wax", element["faces"]["up"]["texture"])

    def test_the_colony_phonograph_has_a_bolted_rack_of_three_cylinders_and_the_others_do_not(self):
        rack = [n for n in names(model("colony_phonograph")) if n.startswith("rack_cylinder")]
        self.assertEqual(3, len(rack))
        self.assertEqual("deepcharter:block/colony/riveted_plate", model("colony_phonograph")["textures"]["plate"])
        for name in ("phonograph", "wreck_phonograph"):
            self.assertFalse([n for n in names(model(name)) if n.startswith("rack")], name)

    def test_the_wreck_is_its_own_charred_texture_set_with_the_horn_snapped(self):
        wreck, plain = model("wreck_phonograph"), model("phonograph")
        for key in ("wood", "top", "steel", "horn", "front"):
            self.assertTrue(wreck["textures"][key].startswith("deepcharter:block/wreck_phonograph_"), key)
            self.assertNotEqual(plain["textures"][key], wreck["textures"][key])
        self.assertLess(len([n for n in names(wreck) if n.startswith("horn_ring")]), len([n for n in names(plain) if n.startswith("horn_ring")]))
        self.assertFalse([n for n in names(wreck) if n.startswith("crank")])

    def test_the_company_horn_is_hung_from_the_top_of_its_block_on_its_own_textures(self):
        horn = model("company_horn")
        self.assertEqual(16, max(e["to"][1] for e in horn["elements"]))
        self.assertEqual({"steel", "red", "bore", "particle"}, set(horn["textures"]))
        for ref in horn["textures"].values():
            self.assertNotIn("phonograph", ref)

    def test_the_selection_shapes_cover_what_the_models_draw(self):
        boxes = shape_boxes("phonograph/PhonographBlock.java")
        for name in ("phonograph", "phonograph_loaded", "colony_phonograph_loaded", "wreck_phonograph"):
            for element in model(name)["elements"]:
                if element["name"].startswith(("crank", "rack")):
                    continue
                self.assertTrue(covered(element, boxes), f"{name} {element['name']}")
        horn_boxes = shape_boxes("ambience/CompanyHornBlock.java")
        for element in model("company_horn")["elements"]:
            self.assertTrue(covered(element, horn_boxes), element["name"])
        self.assertEqual(16, max(b[4] for b in horn_boxes), "the plate stays at the roof")


if __name__ == "__main__":
    unittest.main()
