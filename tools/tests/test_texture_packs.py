"""Tests the texture density test packs (tools/textures/variants.json): each is a complete resource pack over the mod's assets.

A pack's blockstates name models, its models name parents and textures, and each must be in the pack or in the mod's own assets
(a minecraft: model is vanilla's, not ours to check). A texture taller than wide needs its .png.mcmeta. A pack replaces only
blockstates the mod has, or the vanilla ones it lists in VANILLA_BLOCKSTATES, and every model and texture it holds is used. An
overlay pack (docs/design/texture-density-2.md) holds no vanilla file, and its ores draw the host's texture from vanilla. The mod's own
ores (docs/design/ores.md) are overlays too: the tests below hold them to the same rules, and to the table of looks in that page.
"""
import json
import math
import re
import struct
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(TOOLS))
sys.path.insert(0, str(TOOLS / "textures"))
sys.path.insert(0, str(TOOLS / "layer_concepts"))

import pngio  # noqa: E402
import seams  # noqa: E402
import texgen  # noqa: E402

MOD_ASSETS = texgen.ROOT / "src/main/resources/assets"
ORES_DOC = texgen.ROOT / "docs/design/ores.md"
# The vanilla files the mod ships: the sky's sun (ADR 0030) and the animated lava of the layers (#241). Anything else under assets/minecraft/
# overrides vanilla art the mod promised not to touch, an ore's host stone first.
VANILLA_FILES = {"textures/environment/celestial/sun.png", *(f"textures/block/{name}{suffix}" for name in ("lava_still", "lava_flow")
                                                           for suffix in (".png", ".png.mcmeta"))}
# The vanilla blocks a pack may redraw: the texture density packs redraw stone and deepslate. The layer concept packs (docs/design/layer-concepts-2.md)
# stand in vanilla andesite and diorite for the rock and rubble of layer 3, which has no blocks of its own until M3 (#504).
# Delta E of a fleck pixel from the host rock's mean at which the two are plainly different colours (a just noticeable difference is about 2.3).
DISTINCT = 20.0
# The floors of the flecks' contrast against a host rock (test_every_ore_stands_out_of_every_rock_it_sits_in).
MIN_FLECK_MEAN_DELTA_E = 38.0
MIN_FLECK_CLEAR_SHARE = 0.9
VANILLA_BLOCKSTATES = {f"minecraft/blockstates/{name}.json" for name in ("stone", "deepslate", "andesite", "diorite")}


def resolve(pack_assets: Path, ref: str, kind: str, suffix: str) -> Path | None:
    """The file a namespaced reference names, in the pack first and then in the mod; None for a vanilla one."""
    namespace, _, path = ref.partition(":") if ":" in ref else ("minecraft", "", ref)
    if namespace == "minecraft":
        return None
    for root in (pack_assets, MOD_ASSETS):
        candidate = root / namespace / kind / f"{path}{suffix}"
        if candidate.is_file():
            return candidate
    return pack_assets / namespace / kind / f"{path}{suffix}"


def model_refs(body) -> list[str]:
    """Every string under a key called model, at any depth of a blockstate."""
    if isinstance(body, dict):
        return [v for k, v in body.items() if k == "model" and isinstance(v, str)] + [r for v in body.values() for r in model_refs(v)]
    if isinstance(body, list):
        return [r for v in body for r in model_refs(v)]
    return []


def ore_models(assets: Path) -> list[Path]:
    """The models of the ore blocks in a pack: those that inherit the overlay parent."""
    return [model for model in sorted((assets / "deepcharter/models/block").glob("*.json"))
            if json.loads(model.read_text()).get("parent") == "deepcharter:block/ore_overlay"]


def lab(rgb) -> tuple[float, float, float]:
    """CIE L*a*b* (D65) of an sRGB colour."""
    def linear(channel: float) -> float:
        channel /= 255
        return channel / 12.92 if channel <= 0.04045 else ((channel + 0.055) / 1.055) ** 2.4

    r, g, b = (linear(c) for c in rgb[:3])
    xyz = ((0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047, 0.2126 * r + 0.7152 * g + 0.0722 * b,
           (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883)
    x, y, z = (v ** (1 / 3) if v > 0.008856 else 7.787 * v + 16 / 116 for v in xyz)
    return 116 * y - 16, 500 * (x - y), 200 * (y - z)


def delta_e(a, b) -> float:
    return math.dist(lab(a), lab(b))


def png_size(path: Path) -> tuple[int, int]:
    return struct.unpack(">II", path.read_bytes()[16:24])


def write(path: Path, body) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(body))


class TexturePacksTest(unittest.TestCase):
    def test_every_variant_pack_is_complete_and_holds_nothing_unused(self):
        variants = texgen.variants()
        self.assertTrue(variants)
        for name, entry in sorted(variants.items()):
            with self.subTest(variant=name):
                self.assertEqual([], self.problems(texgen.ROOT / entry["pack"]))

    def test_the_mod_ships_no_vanilla_file_but_the_sun_and_the_lava(self):
        root = MOD_ASSETS / "minecraft"
        shipped = {path.relative_to(root).as_posix() for path in root.rglob("*") if path.is_file()}
        self.assertEqual(VANILLA_FILES, shipped, "a file under assets/minecraft/ overrides a vanilla one; draw over it by reference")

    def test_an_overlay_replaces_nothing_of_vanilla_and_draws_each_ore_over_a_rock_of_the_mod_by_reference(self):
        """The mod's own ores and every overlay pack: each ore model names the textures of a rock block of the mod, and not a copy of
        vanilla's stone."""
        places = [("the mod", MOD_ASSETS)] + [(name, texgen.ROOT / entry["pack"] / "assets")
                                              for name, entry in sorted(texgen.variants().items()) if "overlays" in entry]
        for name, assets in places:
            with self.subTest(overlay=name):
                if assets != MOD_ASSETS:
                    self.assertFalse((assets / "minecraft").exists(), "an overlay pack holds a vanilla file")
                models = ore_models(assets)
                self.assertTrue(models)
                for model in models:
                    textures = json.loads(model.read_text())["textures"]
                    for use in ("side", "end", "overlay"):
                        self.assertTrue(resolve(assets, textures[use], "textures", ".png").is_file(),
                                        f"{model.name}: the {use} texture {textures[use]} is not a texture of the mod")

    def test_each_ore_block_has_a_model_of_every_rock_it_can_sit_in_and_each_draws_that_rock(self):
        """The shipped ore blocks (overlays.json): a state for each host, with a model for each of its variants, and that model's side and
        end textures are the host's own rock."""
        body = json.loads(texgen.OVERLAYS.read_text())
        for block in body["blocks"]:
            state = json.loads((MOD_ASSETS / f"deepcharter/blockstates/{block}.json").read_text())["variants"]
            self.assertEqual({f"{body['property']}={host}" for host in body["hosts"]}, set(state), block)
            for host, rock in body["hosts"].items():
                models = state[f"{body['property']}={host}"]
                self.assertEqual(body["variants"], len(models), f"{block} in {host}")
                for entry in models:
                    textures = json.loads(resolve(MOD_ASSETS, entry["model"], "models", ".json").read_text())["textures"]
                    self.assertRegex(textures["side"], rf"^deepcharter:block/{rock}_\d$", entry["model"])
                    self.assertRegex(textures["end"], rf"^deepcharter:block/{rock}_end_\d$", entry["model"])

    def test_an_ore_tiles_like_the_rock_it_sits_in(self):
        """#549: the ore over its host rock, as a block face draws it, passes the seam check of tools/layer_concepts/seams.py (the colour step
        across the wrap seams, and across the outer rings, is no bigger than the steps inside the texture, within its margin). An overlay's edge
        is only ore ramp colours (the test above), so this holds the composite to what the rock alone is held to."""
        shipped = texgen.shipped()
        body = json.loads(texgen.OVERLAYS.read_text())
        for block in body["blocks"]:
            for host, rock in body["hosts"].items():
                for index in range(body["variants"]):
                    overlay = pngio.decode((shipped.out / f"block/{block}_overlay_{index % body['textures']}.png").read_bytes())
                    for host_texture in (f"{rock}_{index}", f"{rock}_end_{index % body['ends']}"):
                        rock_image = pngio.decode((shipped.out / f"block/{host_texture}.png").read_bytes())
                        pixels = bytearray(rock_image.pixels)
                        for at in range(0, len(pixels), 4):
                            if overlay.pixels[at + 3]:
                                pixels[at:at + 3] = overlay.pixels[at:at + 3]
                        found = seams.measure(bytes(pixels), rock_image.width)
                        with self.subTest(block=block, host=host_texture, overlay=index % body["textures"]):
                            self.assertTrue(found.seamless(), f"wrap {found.wrap_ratio:.2f}, rim {found.rim_ratio:.2f}")

    def test_every_ore_stands_out_of_every_rock_it_sits_in(self):
        """#549: a player reads a wall by lamplight, with no scanner, so the flecks of an ore must differ from the rock they sit in. The measure
        is CIE76 delta E (a distance in a space built so that equal steps look equal, and that counts hue as well as lightness: teal on green rock
        and red on brown rock are not told by lightness alone) between each fleck pixel and the mean colour of the host's side textures. A lamp
        dims every colour alike, so the order holds in the dark. The flecks are the ore's ramp but its darkest shade, which is the rim. A fleck
        pixel is clear when its delta E is DISTINCT or more. Measured on the textures before #549's retune, the worst were ironium in packed shale
        (mean 27.9, 62% clear), cicatrium in packed shale (mean 30.8), silverium in green shale (mean 31.8, 41% clear), platinium in green shale (36.4) and einsteinium in green
        shale (77% clear); after it every ore clears both floors, the lowest being silverium in green shale at 41.5 and platinium in green shale at 43.8, and the floors sit under them."""
        shipped = texgen.shipped()
        body = json.loads(texgen.OVERLAYS.read_text())

        def pixels(name: str) -> list[tuple[int, ...]]:
            image = pngio.decode((shipped.out / f"block/{name}.png").read_bytes())
            return [tuple(image.pixels[at:at + 4]) for at in range(0, len(image.pixels), 4)]

        for host, rock in body["hosts"].items():
            rock_pixels = [p for index in range(body["variants"]) for p in pixels(f"{rock}_{index}")]
            mean = tuple(sum(p[channel] for p in rock_pixels) / len(rock_pixels) for channel in range(3))
            for block in body["blocks"]:
                flecks = [p for index in range(body["textures"]) for p in pixels(f"{block}_overlay_{index}")
                          if p[3] == 255 and p in {colour for layer in shipped.book.recipes[f"block/{block}_overlay_{index}"].layers
                                                   if layer["op"] in ("cluster", "seams")
                                                   for colour in shipped.book.palette.ramp(layer["ramp"], block)[1:]}]
                with self.subTest(block=block, host=host):
                    self.assertTrue(flecks)
                    steps = [delta_e(p, mean) for p in flecks]
                    self.assertGreaterEqual(sum(steps) / len(steps), MIN_FLECK_MEAN_DELTA_E)
                    self.assertGreaterEqual(sum(step >= DISTINCT for step in steps) / len(steps), MIN_FLECK_CLEAR_SHARE)

    def test_only_ore_reaches_the_edge_of_an_overlay(self):
        """Over the host's texture, any pixel on a block's outer ring that is not the ore itself (a shadow, a socket, a tint) draws
        the block's edge. So an edge pixel is clear, or one of the shades of the ore ramps its recipe draws with: a vein or a seam
        running out to the edge."""
        shipped = texgen.shipped()
        targets = [("the mod", shipped, [key for key in shipped.keys if "_ore_overlay_" in key])]
        for name, entry in sorted(texgen.variants().items()):
            if "overlays" in entry:
                targets.append((name, texgen.variant(name), None))
        for name, target, keys in targets:
            for key in keys or target.keys:
                with self.subTest(variant=name, texture=key):
                    ore = {colour for layer in target.book.recipes[key].layers if layer["op"] in ("cluster", "seams")
                           for colour in target.book.palette.ramp(layer["ramp"], key)}
                    self.assertTrue(ore, "the overlay draws no ore")
                    image = pngio.decode((target.out / f"{key}.png").read_bytes())
                    size = image.width
                    edge = {tuple(image.pixels[4 * (y * size + x):][:4]) for y in range(size) for x in range(size)
                            if x in (0, size - 1) or y in (0, size - 1)}
                    self.assertEqual(set(), edge - ore - {(0, 0, 0, 0)})

    def test_an_ore_overlay_never_glows_and_is_cutout(self):
        """No glow recipe, no emissive or light property in an ore's models or blockstates, and only clear or opaque pixels: 26.3 puts
        a face in the cutout layer from its sprite's transparency, so a half-clear pixel would make the overlay translucent."""
        shipped = texgen.shipped()
        keys = [key for key in shipped.keys if "_ore_overlay_" in key]
        self.assertTrue(keys)
        for key in keys:
            with self.subTest(texture=key):
                self.assertIsNone(shipped.book.recipes[key].glow)
                image = pngio.decode((shipped.out / f"{key}.png").read_bytes())
                self.assertTrue(set(image.pixels[3::4]) <= {0, 255}, "an overlay pixel is half clear")
        glow_keys = {"light_emission", "emissive", "block_light", "sky_light", "light", "emission"}

        def lit(body) -> list[str]:
            if isinstance(body, dict):
                return [k for k in body if k in glow_keys] + [f for v in body.values() for f in lit(v)]
            if isinstance(body, list):
                return [f for v in body for f in lit(v)]
            return []

        assets = MOD_ASSETS / "deepcharter"
        files = [assets / "models/block/ore_overlay.json", *ore_models(MOD_ASSETS), *sorted(assets.glob("blockstates/*ium_ore.json"))]
        body = json.loads(texgen.OVERLAYS.read_text())
        self.assertEqual(1 + len(body["blocks"]) * len(body["hosts"]) * body["variants"] + len(body["blocks"]), len(files))
        for path in files:
            with self.subTest(file=path.name):
                self.assertEqual([], lit(json.loads(path.read_text())))

    def test_every_ore_has_a_look_from_b1_b3_and_b4_or_a_blend_and_the_page_says_which(self):
        """B1 is a cluster, B3 a seams thread, B4 a cluster in a socket. Each ore's four textures are one look, the ores use all
        three families and at least two blend, and docs/design/ores.md has a row for each, with the same families."""
        shipped = texgen.shipped()
        looks: dict[str, frozenset[str]] = {}
        for key in shipped.keys:
            match = re.fullmatch(r"block/(\w+)_ore_overlay_\d", key)
            if not match:
                continue
            layers = shipped.book.recipes[key].layers
            self.assertLessEqual({layer["op"] for layer in layers}, {"cluster", "seams"}, f"{key}: a layer that is no look")
            families = frozenset("B3" if layer["op"] == "seams" else "B4" if "socket" in layer else "B1" for layer in layers)
            self.assertEqual(looks.setdefault(match.group(1), families), families, f"{key}: the ore's textures are not one look")
        self.assertEqual(7, len(looks))
        self.assertEqual({"B1", "B3", "B4"}, set().union(*looks.values()))
        for family in ("B1", "B3", "B4"):
            self.assertTrue(any(have == {family} for have in looks.values()), f"no ore is plain {family}")
        self.assertGreaterEqual(sum(len(have) > 1 for have in looks.values()), 2, "fewer than two blends")
        table = re.finditer(r"^\| (\w+ium) \| ([B\d +]+?) \|", ORES_DOC.read_text(), re.M)
        rows = {match.group(1): frozenset(re.findall(r"B\d", match.group(2))) for match in table}
        self.assertEqual(looks, rows, f"the table in {ORES_DOC} differs from the recipes")

    def test_a_missing_texture_an_unused_model_and_a_block_the_mod_lacks_are_each_named(self):
        with tempfile.TemporaryDirectory() as tmp:
            pack = Path(tmp)
            (pack / "pack.mcmeta").write_text(json.dumps({"pack": {"description": "test"}}))
            write(pack / "assets/deepcharter/blockstates/ironium_ore.json", {"variants": {"": {"model": "deepcharter:block/x"}}})
            write(pack / "assets/deepcharter/blockstates/no_such_block.json", {"variants": {"": {"model": "minecraft:block/stone"}}})
            write(pack / "assets/deepcharter/models/block/x.json", {"parent": "minecraft:block/cube_all", "textures": {"all": "deepcharter:block/gone"}})
            write(pack / "assets/deepcharter/models/block/spare.json", {"parent": "minecraft:block/cube_all"})
            self.assertEqual({"deepcharter/blockstates/no_such_block.json: the mod has no such blockstate to replace",
                              "deepcharter/blockstates/ironium_ore.json > deepcharter:block/x: texture deepcharter:block/gone is in neither the pack nor the mod",
                              "deepcharter/models/block/spare.json: no blockstate of the pack uses it"}, set(self.problems(pack)))

    def problems(self, pack: Path) -> list[str]:
        assets = pack / "assets"
        problems = []
        meta = pack / "pack.mcmeta"
        if not meta.is_file() or "description" not in json.loads(meta.read_text()).get("pack", {}):
            problems.append(f"{meta}: missing, or no pack description")
        used_models: set[Path] = set()
        used_textures: set[Path] = set()
        blockstates = sorted(assets.glob("*/blockstates/*.json"))
        # A pack of textures alone (the tow chain's braided cable, #508) redraws textures the mod already has, and the loop below checks each.
        if not blockstates and not any(assets.glob("*/textures/**/*.png")):
            problems.append(f"{pack}: no blockstates")
        for blockstate in blockstates:
            relative = blockstate.relative_to(assets).as_posix()
            if not (MOD_ASSETS / relative).is_file() and relative not in VANILLA_BLOCKSTATES:
                problems.append(f"{relative}: the mod has no such blockstate to replace")
            refs = model_refs(json.loads(blockstate.read_text()))
            if not refs:
                problems.append(f"{relative}: names no model")
            for ref in refs:
                problems += self.model(assets, ref, relative, used_models, used_textures)
        for model in sorted(assets.glob("*/models/**/*.json")):
            if model not in used_models:
                problems.append(f"{model.relative_to(assets)}: no blockstate of the pack uses it")
        for texture in sorted(assets.glob("*/textures/**/*.png")):
            relative = texture.relative_to(assets).as_posix()
            if texture not in used_textures and not (MOD_ASSETS / relative).is_file():
                problems.append(f"{relative}: no model of the pack uses it, and it replaces no texture of the mod")
        return problems

    def model(self, assets: Path, ref: str, owner: str, used_models: set[Path], used_textures: set[Path]) -> list[str]:
        path = resolve(assets, ref, "models", ".json")
        if path is None or path in used_models:
            return []
        if not path.is_file():
            return [f"{owner}: model {ref} is in neither the pack nor the mod"]
        used_models.add(path)
        body = json.loads(path.read_text())
        problems = []
        if "parent" in body:
            problems += self.model(assets, body["parent"], f"{owner} > {ref}", used_models, used_textures)
        for texture_ref in body.get("textures", {}).values():
            if texture_ref.startswith("#"):
                continue
            texture = resolve(assets, texture_ref, "textures", ".png")
            if texture is None:
                continue
            if not texture.is_file():
                problems.append(f"{owner} > {ref}: texture {texture_ref} is in neither the pack nor the mod")
                continue
            used_textures.add(texture)
            width, height = png_size(texture)
            if height != width and not texture.with_suffix(".png.mcmeta").is_file():
                problems.append(f"{owner} > {ref}: {texture_ref} is {width} x {height}, an animation with no .png.mcmeta")
        return problems


if __name__ == "__main__":
    unittest.main()
