"""The blockstates and models of an overlay pack (docs/design/texture-density-2.md): each ore block draws its host rock's own texture,
by reference, and a cutout layer of our ore art over it, so the ore's edge is the host's and no host texture is copied or replaced.
An ore block has a state property that says which rock it sits in (ADR 0055), and a model for each rock.
"""
import json
import re
from collections.abc import Mapping
from dataclasses import dataclass

from recipe import Book, RecipeError

NAMESPACE = "deepcharter"
FACES = ("down", "up", "north", "south", "west", "east")
# The model every ore of the pack inherits: two full cubes, the host rock's texture (its end texture on top and bottom, as a rock block's
# cube_column does) and the overlay over it, so the overlay's clear pixels show the host and its opaque ones cover it. 26.3 puts each quad in
# a chunk layer by its sprite's pixels under the face's UVs (FaceBakery.computeMaterialTransparency, then ChunkSectionLayer.byTransparency):
# clear pixels make the overlay CUTOUT and the opaque host SOLID, with no render_type in the model.
PARENT = "block/ore_overlay"
PARENT_MODEL = {
    "parent": "minecraft:block/block",
    "textures": {"particle": "#side"},
    "elements": [{"from": [0, 0, 0], "to": [16, 16, 16],
                  "faces": {face: {"texture": ("#end" if face in ("down", "up") else "#side") if layer == "host" else "#overlay",
                                   "cullface": face} for face in FACES}} for layer in ("host", "overlay")],
}
NAME = re.compile(r"[a-z][a-z0-9_]*")


@dataclass(frozen=True)
class Overlays:
    """property is the blockstate property of an ore block that says which rock it sits in, and hosts the rocks by property value, each the
    name of a rock block of the mod: its textures block/<rock>_<n> (the side, n below variants) and block/<rock>_end_<n> (the end, n below
    ends). An ore block has variants models for each rock, the nth with side <rock>_<n>, end <rock>_end_<n mod ends> and the overlay
    block/<block>_overlay_<n mod textures>, and the world picks one for each block at random, as vanilla picks a stone variant."""

    property: str
    hosts: Mapping[str, str]
    variants: int
    ends: int
    textures: int
    blocks: tuple[str, ...]

    @staticmethod
    def parse(body: object, where: str) -> "Overlays":
        keys = {"property", "hosts", "variants", "ends", "textures", "blocks"}
        if not isinstance(body, dict) or set(body) != keys:
            raise RecipeError(f"{where}: overlays has exactly {', '.join(sorted(keys))}, found {body!r}")
        if not isinstance(body["property"], str) or not NAME.fullmatch(body["property"]):
            raise RecipeError(f"{where}: the overlay property is a blockstate property name, got {body['property']!r}")
        hosts = body["hosts"]
        if not isinstance(hosts, dict) or not hosts or not all(
                isinstance(value, str) and NAME.fullmatch(value) and isinstance(rock, str) and NAME.fullmatch(rock)
                for value, rock in hosts.items()):
            raise RecipeError(f"{where}: the overlay hosts are a non-empty map from a property value to a rock block name, got {hosts!r}")
        for key in ("variants", "ends", "textures"):
            if not isinstance(body[key], int) or isinstance(body[key], bool) or body[key] < 1:
                raise RecipeError(f"{where}: overlay {key} is 1 or more, got {body[key]!r}")
        blocks = body["blocks"]
        if not blocks or not all(isinstance(b, str) for b in blocks) or len(set(blocks)) != len(blocks):
            raise RecipeError(f"{where}: the overlay blocks are a non-empty list of distinct block names, got {blocks!r}")
        return Overlays(body["property"], hosts, body["variants"], body["ends"], body["textures"], tuple(blocks))

    def files(self, book: Book, keys: tuple[str, ...]) -> Mapping[str, bytes]:
        """Every blockstate and model, as JSON text, by its path under the pack's assets/. Each overlay texture must be one of keys
        (the textures the pack makes) and a cutout."""
        bodies: dict[str, object] = {f"{NAMESPACE}/models/{PARENT}.json": PARENT_MODEL}
        for block in self.blocks:
            for index in range(self.textures):
                texture = f"block/{block}_overlay_{index}"
                if texture not in keys:
                    raise RecipeError(f"overlay block {block} needs the recipe {texture}, which the pack does not make")
                if book.recipes[texture].kind != "cutout":
                    raise RecipeError(f"{texture}: an overlay is a cutout texture, so the host shows through it")
            states = {}
            for value, rock in self.hosts.items():
                models = []
                for index in range(self.variants):
                    name = f"block/{block}_{value}_{index}"
                    bodies[f"{NAMESPACE}/models/{name}.json"] = {"parent": f"{NAMESPACE}:{PARENT}", "textures": {
                        "side": f"{NAMESPACE}:block/{rock}_{index}", "end": f"{NAMESPACE}:block/{rock}_end_{index % self.ends}",
                        "overlay": f"{NAMESPACE}:block/{block}_overlay_{index % self.textures}"}}
                    models.append({"model": f"{NAMESPACE}:{name}"})
                states[f"{self.property}={value}"] = models
            bodies[f"{NAMESPACE}/blockstates/{block}.json"] = {"variants": states}
        return {path: (json.dumps(body, indent=2) + "\n").encode() for path, body in bodies.items()}
