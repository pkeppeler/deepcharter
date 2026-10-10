"""Tests the texture density test packs (tools/textures/variants.json): each is a complete resource pack over the mod's assets.

A pack's blockstates name models, its models name parents and textures, and each must be in the pack or in the mod's own assets
(a minecraft: model is vanilla's, not ours to check). A texture taller than wide needs its .png.mcmeta. A pack replaces only
blockstates the mod has, or the vanilla ones it lists in VANILLA_BLOCKSTATES, and every model and texture it holds is used. An
overlay pack (docs/design/texture-density-2.md) holds no vanilla file, and its ores draw the host's texture from vanilla. The mod's own
ores (docs/design/ores.md) are overlays too: the tests below hold them to the same rules, and to the table of looks in that page.
"""
import json
import re
import struct
import sys
import tempfile
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(TOOLS / "textures"))

import pngio  # noqa: E402
import texgen  # noqa: E402

MOD_ASSETS = texgen.ROOT / "src/main/resources/assets"
ORES_DOC = texgen.ROOT / "docs/design/ores.md"
# The one vanilla file the mod ships: the sky's sun (ADR 0030). Anything else under assets/minecraft/ overrides vanilla art the mod
# promised not to touch, an ore's host stone first.
VANILLA_FILES = {"textures/environment/celestial/sun.png"}
# The vanilla blocks a pack may redraw: layer rock is vanilla stone until it has blocks of its own (#241). The layer concept packs
# (docs/design/layer-concepts.md) stand in vanilla cobblestone for layer 1's rubble, and deepslate and cobbled deepslate for layer 2's rock.
VANILLA_BLOCKSTATES = {"minecraft/blockstates/stone.json", "minecraft/blockstates/cobblestone.json", "minecraft/blockstates/deepslate.json",
                       "minecraft/blockstates/cobbled_deepslate.json"}


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

    def test_the_mod_ships_no_vanilla_file_but_the_sun(self):
        root = MOD_ASSETS / "minecraft"
        shipped = {path.relative_to(root).as_posix() for path in root.rglob("*") if path.is_file()}
        self.assertEqual(VANILLA_FILES, shipped, "a file under assets/minecraft/ overrides a vanilla one; draw over it by reference")

    def test_an_overlay_replaces_nothing_of_vanilla_and_draws_each_ore_over_the_host_by_reference(self):
        """The mod's own ores and every overlay pack: each ore model names the host's texture, vanilla's, and not a copy of it."""
        places = [("the mod", MOD_ASSETS)] + [(name, texgen.ROOT / entry["pack"] / "assets")
                                              for name, entry in sorted(texgen.variants().items()) if "overlays" in entry]
        for name, assets in places:
            with self.subTest(overlay=name):
                if assets != MOD_ASSETS:
                    self.assertFalse((assets / "minecraft").exists(), "an overlay pack holds a vanilla file")
                models = sorted((assets / "deepcharter/models/block").glob("*_overlay_*.json"))
                self.assertTrue(models)
                for model in models:
                    host = json.loads(model.read_text())["textures"]["host"]
                    self.assertIsNone(resolve(assets, host, "textures", ".png"), f"{model.name}: the host {host} is not vanilla's")

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
        files = sorted(assets.glob("models/block/*ore_overlay*.json")) + sorted(assets.glob("blockstates/*ium_ore.json"))
        self.assertEqual(29 + 7, len(files))
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
        if not blockstates:
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
