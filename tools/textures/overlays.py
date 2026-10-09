"""The blockstates and models of an overlay pack (docs/design/texture-density-2.md): each ore block draws its host block's own texture,
by reference, and a cutout layer of our ore art over it, so the ore's edge is the host's and no host texture is copied or replaced.
"""
import json
from collections.abc import Mapping
from dataclasses import dataclass

from recipe import Book, RecipeError

NAMESPACE = "deepcharter"
FACES = ("down", "up", "north", "south", "west", "east")
# The model every ore of the pack inherits: two full cubes, the host's texture and the overlay over it, so the overlay's
# transparent pixels show the host and its opaque ones (rendered as cutout) cover it.
PARENT = "block/ore_overlay"
PARENT_MODEL = {
    "parent": "minecraft:block/block",
    "textures": {"particle": "#host"},
    "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": {face: {"texture": texture, "cullface": face} for face in FACES}}
                 for texture in ("#host", "#overlay")],
}


@dataclass(frozen=True)
class Overlays:
    """host is the texture the ores sit in (a vanilla one, by reference); each of blocks takes textures overlays, the recipes
    block/<block>_overlay_<n>, one picked at random per block as vanilla picks a stone variant."""

    host: str
    textures: int
    blocks: tuple[str, ...]

    @staticmethod
    def parse(body: object, where: str) -> "Overlays":
        if not isinstance(body, dict) or set(body) != {"host", "textures", "blocks"}:
            raise RecipeError(f"{where}: overlays has exactly host, textures and blocks, found {body!r}")
        host, textures, blocks = body["host"], body["textures"], body["blocks"]
        if not isinstance(host, str) or ":" not in host:
            raise RecipeError(f"{where}: the overlay host is a namespaced texture such as minecraft:block/stone, got {host!r}")
        if not isinstance(textures, int) or textures < 1:
            raise RecipeError(f"{where}: an overlay block takes 1 or more textures, got {textures!r}")
        if not blocks or not all(isinstance(b, str) for b in blocks) or len(set(blocks)) != len(blocks):
            raise RecipeError(f"{where}: the overlay blocks are a non-empty list of distinct block names, got {blocks!r}")
        return Overlays(host, textures, tuple(blocks))

    def files(self, book: Book, keys: tuple[str, ...]) -> Mapping[str, bytes]:
        """Every blockstate and model, as JSON text, by its path under the pack's assets/. Each overlay texture must be one of keys
        (the textures the pack makes) and a cutout."""
        bodies: dict[str, object] = {f"{NAMESPACE}/models/{PARENT}.json": PARENT_MODEL}
        for block in self.blocks:
            models = []
            for index in range(self.textures):
                texture = f"block/{block}_overlay_{index}"
                if texture not in keys:
                    raise RecipeError(f"overlay block {block} needs the recipe {texture}, which the pack does not make")
                if book.recipes[texture].kind != "cutout":
                    raise RecipeError(f"{texture}: an overlay is a cutout texture, so the host shows through it")
                bodies[f"{NAMESPACE}/models/{texture}.json"] = {"parent": f"{NAMESPACE}:{PARENT}",
                                                                "textures": {"host": self.host, "overlay": f"{NAMESPACE}:{texture}"}}
                models.append({"model": f"{NAMESPACE}:{texture}"})
            bodies[f"{NAMESPACE}/blockstates/{block}.json"] = {"variants": {"": models}}
        return {path: (json.dumps(body, indent=2) + "\n").encode() for path, body in bodies.items()}
