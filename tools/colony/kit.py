"""The colony kit: every block the colony concepts are built from, with its block-state properties, blockstate file, models and
item definition. ColonyKit.java registers the same blocks; ColonyConceptsTest fails when a state has no variant.

A kit block is one of a few shapes (KINDS). Its textures are recipes in tools/textures/recipes/colony_kit.json, drawn by
tools/textures/texgen.py into textures/block/colony/. The display-only pieces (statues, sheave wheels, the gallery) are states of
one block, colony_sculpture, which is never placed: block-display entities draw it, scaled and turned.
"""
from dataclasses import dataclass
from typing import Callable

from models import Box, Model, cube_all, cube_column

NS = "deepcharter"
TEX = f"{NS}:block/colony/"
FACINGS = {"north": 0, "east": 90, "south": 180, "west": 270}
AXES = ("x", "y", "z")
# The number of tile states of the enamel sign; KitSignBlock.TILES in Java. signs.py says what each tile shows.
SIGN_TILES = 48


@dataclass(frozen=True)
class KitBlock:
    name: str
    english: str
    properties: dict[str, tuple[str, ...]]
    blockstate: Callable[[], dict]
    models: Callable[[], dict[str, Model]]
    item_model: str

    @property
    def id(self) -> str:
        return f"{NS}:{self.name}"

    def state(self, **props: str) -> tuple[str, dict[str, str]]:
        """A block state of this block, as a structure palette names it. Every property must be given and valid."""
        given = {k: str(v) for k, v in props.items()}
        if set(given) != set(self.properties):
            raise ValueError(f"{self.name} has properties {sorted(self.properties)}, got {sorted(given)}")
        for key, value in given.items():
            if value not in self.properties[key]:
                raise ValueError(f"{self.name}: {key}={value} is not one of {self.properties[key]}")
        return self.id, given


def tex(name: str) -> str:
    return TEX + name


def model_id(name: str) -> str:
    return f"{NS}:block/colony/{name}"


# ---------------------------------------------------------------------------------------------------------------- the kinds

def cube(name: str, english: str, variants: tuple[tuple[str, int], ...] = ()) -> KitBlock:
    """A full block of one texture, or of weighted texture variants (suffix, weight) picked at random per block, so a wall of it
    does not repeat one 16-pixel tile; the first variant is the plain texture."""
    if not variants:
        return KitBlock(name, english, {}, lambda: {"variants": {"": {"model": model_id(name)}}},
                        lambda: {name: cube_all(tex(name))}, model_id(name))
    names = [name + suffix for suffix, _ in variants]

    def states():
        return {"variants": {"": [{"model": model_id(n), "weight": w} for n, (_, w) in zip(names, variants)]}}
    return KitBlock(name, english, {}, states, lambda: {n: cube_all(tex(n)) for n in names}, model_id(name))


WEAR = (("", 8), ("_streaked", 3), ("_patched", 2))


def facing_variants(model: str, extra: str = "") -> dict:
    return {f"facing={f}{extra}": ({"model": model, "y": y} if y else {"model": model}) for f, y in FACINGS.items()}


def facing(name: str, english: str, build: Callable[[], Model]) -> KitBlock:
    """Faces one of four ways; the model is drawn facing north (its front at z = 0) and turned for the others."""
    return KitBlock(name, english, {"facing": tuple(FACINGS)}, lambda: {"variants": facing_variants(model_id(name))},
                    lambda: {name: build()}, model_id(name))


def pillar(name: str, english: str, build: Callable[[], Model]) -> KitBlock:
    """Runs along one of three axes; the model is drawn along Y."""
    def states():
        m = model_id(name)
        return {"variants": {"axis=y": {"model": m}, "axis=z": {"model": m, "x": 90}, "axis=x": {"model": m, "x": 90, "y": 90}}}
    return KitBlock(name, english, {"axis": AXES}, states, lambda: {name: build()}, model_id(name))


def horizontal_axis(name: str, english: str, build: Callable[[], Model]) -> KitBlock:
    """Runs along X or Z; the model is drawn along X."""
    def states():
        m = model_id(name)
        return {"variants": {"axis=x": {"model": m}, "axis=z": {"model": m, "y": 90}}}
    return KitBlock(name, english, {"axis": ("x", "z")}, states, lambda: {name: build()}, model_id(name))


# ---------------------------------------------------------------------------------------------------------------- shapes

def framed_cube(front: str, side: str, glow: str | None = None) -> Model:
    """A full block whose north face is front (with an optional glow layer over it) and whose other faces are side."""
    textures = {"front": tex(front), "side": tex(side), "particle": tex(side)}
    boxes = [Box((0, 0, 0), (16, 16, 16), {"north": "#front", "*": "#side"}, cull=True)]
    if glow:
        textures["glow"] = tex(glow)
        boxes.append(Box((0, 0, -0.01), (16, 16, 0), {"north": "#glow"}, glow=True))
    return Model(textures, boxes)


def window(glass: str, side: str, glow: str | None) -> Model:
    """A window in a wall: the glazing on the north and south faces (the outside and the inside), the frame on the other four; a
    lit window has a glow layer over both glazed faces."""
    textures = {"glass": tex(glass), "side": tex(side), "particle": tex(side)}
    boxes = [Box((0, 0, 0), (16, 16, 16), {"north": "#glass", "south": "#glass", "*": "#side"}, cull=True)]
    if glow:
        textures["glow"] = tex(glow)
        boxes.append(Box((0, 0, -0.01), (16, 16, 0), {"north": "#glow"}, glow=True))
        boxes.append(Box((0, 0, 16), (16, 16, 16.01), {"south": "#glow"}, glow=True))
    return Model(textures, boxes)


def i_beam(paint: str) -> Model:
    t = {"flange": tex(paint), "web": tex(paint + "_web"), "end": tex(paint + "_end"), "particle": tex(paint)}
    return Model(t, [
        Box((2, 0, 2), (14, 16, 4.5), {"*": "#flange", "up": "#end", "down": "#end"}, cull=True),
        Box((2, 0, 11.5), (14, 16, 14), {"*": "#flange", "up": "#end", "down": "#end"}, cull=True),
        Box((6.75, 0, 4.5), (9.25, 16, 11.5), {"*": "#web", "north": None, "south": None, "up": "#end", "down": "#end"}, cull=True),
    ])


def lattice(texture: str) -> Model:
    """A latticed box girder: four open-laced faces round a hollow core. Each wall is also drawn facing in, so the far lacing shows
    through the near one."""
    t = {"lattice": tex(texture), "end": tex(texture + "_end"), "particle": tex(texture)}
    e = 0.02
    return Model(t, [
        Box((0, 0, 0), (16, 16, 16), {"north": "#lattice", "south": "#lattice", "east": "#lattice", "west": "#lattice",
                                      "up": "#end", "down": "#end"}, cull=True),
        Box((0, 0, 16 - e), (16, 16, 16), {"north": "#lattice"}),
        Box((0, 0, 0), (16, 16, e), {"south": "#lattice"}),
        Box((16 - e, 0, 0), (16, 16, 16), {"west": "#lattice"}),
        Box((0, 0, 0), (e, 16, 16), {"east": "#lattice"}),
    ], ambient_occlusion=False)


def pipe(texture: str) -> Model:
    t = {"pipe": tex(texture), "flange": tex(texture + "_flange"), "particle": tex(texture)}
    return Model(t, [
        Box((5, 0, 5), (11, 16, 11), {"*": "#pipe", "up": "#flange", "down": "#flange"}, cull=True),
        Box((4, 0, 4), (12, 1.5, 12), {"*": "#flange"}, cull=True),
    ])


def cable() -> Model:
    t = {"cable": tex("cable"), "particle": tex("cable")}
    return Model(t, [Box((7, 0, 7), (9, 16, 9), {"*": "#cable"}, cull=True)], ambient_occlusion=False)


def brace(texture: str) -> Model:
    """A 45-degree lattice brace rising toward the north: one member along the block's diagonal, so braces set diagonally in a
    line join end to end."""
    t = {"lattice": tex(texture), "end": tex(texture + "_end"), "particle": tex(texture)}
    half = 8 * 2 ** 0.5
    return Model(t, [
        Box((3, 3, 8 - half), (13, 13, 8 + half), {"*": "#lattice", "north": "#end", "south": "#end"}, rotation=(45, 0, 0)),
    ], ambient_occlusion=False)


def roof_slope(roof: str, gable: str) -> Model:
    """A 45-degree roof falling toward the north: the sheeting runs from the top of the south edge to the foot of the north edge,
    and a wedge of 1-unit steps under it fills the gable, so the end of a roof shows a triangle of cladding, not stairs. The steps
    stay under the diagonal, and the sheeting's underside covers the notches between them."""
    t = {"roof": tex(roof), "gable": tex(gable), "particle": tex(roof)}
    half = 8 * 2 ** 0.5
    boxes = [Box((-0.5, 7.0, 8 - half - 0.3), (16.5, 8.2, 8 + half + 0.3), {"*": "#roof"}, rotation=(-45, 0, 0))]
    for k in range(1, 16):
        boxes.append(Box((0, 0, k), (16, k, k + 1), {"east": "#gable", "west": "#gable"}))
    return Model(t, boxes)


def roof_peak(roof: str, gable: str) -> Model:
    """The ridge of a 45-degree roof running along X: two half slopes meet half a block up under a ridge cap, over a triangle of
    gable built like the slope's wedge."""
    t = {"roof": tex(roof), "gable": tex(gable), "ridge": tex(roof + "_ridge"), "particle": tex(roof)}
    q = 4 * 2 ** 0.5
    boxes = [
        Box((-0.5, 3.0, 4 - q - 0.3), (16.5, 4.2, 4 + q + 0.3), {"*": "#roof"}, rotation=(-45, 0, 0), pivot=(8, 4, 4)),
        Box((-0.5, 3.0, 12 - q - 0.3), (16.5, 4.2, 12 + q + 0.3), {"*": "#roof"}, rotation=(45, 0, 0), pivot=(8, 4, 12)),
        Box((-0.6, 7.0, 6.5), (16.6, 8.8, 9.5), {"*": "#ridge"}),
    ]
    for k in range(1, 8):
        boxes.append(Box((0, k - 1, k), (16, k, 16 - k), {"east": "#gable", "west": "#gable"}))
    return Model(t, boxes)


def railing() -> Model:
    """A catwalk railing on the north edge of its block: two posts, a top rail, a knee rail and a kick plate."""
    t = {"rail": tex("railing"), "kick": tex("hazard_band"), "particle": tex("railing")}
    return Model(t, [
        Box((1, 0, 0.5), (2.5, 15, 2), {"*": "#rail"}),
        Box((13.5, 0, 0.5), (15, 15, 2), {"*": "#rail"}),
        Box((0, 14, 0.25), (16, 15.5, 2.25), {"*": "#rail"}),
        Box((0, 8, 0.75), (16, 9, 1.75), {"*": "#rail"}),
        Box((0, 0, 0.75), (16, 2, 1.5), {"*": "#kick"}),
    ], ambient_occlusion=False)


def wall_lamp() -> Model:
    """A caged sodium lamp on an arm, fixed to the wall behind it (south) and facing north."""
    t = {"iron": tex("lamp_iron"), "lit": tex("lamp_lit"), "particle": tex("lamp_iron")}
    return Model(t, [
        Box((6, 4, 13), (10, 12, 16), {"*": "#iron"}),
        Box((7, 10, 6), (9, 11.5, 13), {"*": "#iron"}),
        Box((5, 6.5, 4), (11, 10, 9), {"*": "#iron"}),
        Box((5.5, 3, 4.5), (10.5, 6.5, 8.5), {"*": "#lit", "up": None}, glow=True),
        Box((6.5, 2.5, 5.5), (9.5, 3, 7.5), {"*": "#iron"}),
    ], ambient_occlusion=False)


def floodlight() -> Model:
    """A floodlight box on a yoke, its lens tilted down to the north."""
    t = {"iron": tex("lamp_iron"), "lens": tex("flood_lens"), "particle": tex("lamp_iron")}
    return Model(t, [
        Box((7, 0, 9), (9, 6, 11), {"*": "#iron"}),
        Box((3, 5, 4), (13, 13, 12), {"*": "#iron"}, rotation=(-25, 0, 0), pivot=(8, 9, 8)),
        Box((3.5, 5.5, 3.95), (12.5, 12.5, 4), {"north": "#lens"}, rotation=(-25, 0, 0), pivot=(8, 9, 8), glow=True),
    ], ambient_occlusion=False)


def sign_tile(index: int) -> Model:
    """One tile of a Company sign: a plate 2 units thick against the wall behind it (south), its face to the north, with the lit
    letters of a lit sign drawn full-bright over it."""
    import signs
    t = {"face": tex(f"sign/tile_{index}"), "edge": tex("sign_edge"), "particle": tex("sign_edge")}
    boxes = [Box((0, 0, 14), (16, 16, 16), {"north": "#face", "*": "#edge"})]
    if signs.lit(index):
        t["glow"] = tex(f"sign/tile_{index}_glow")
        boxes.append(Box((0, 0, 13.98), (16, 16, 14), {"north": "#glow"}, glow=True))
    return Model(t, boxes)


def conveyor() -> Model:
    """A belt conveyor running north: a steel frame on two rollers, the animated belt on top."""
    t = {"belt": tex("conveyor_belt"), "frame": tex("conveyor_frame"), "particle": tex("conveyor_frame")}
    return Model(t, [
        Box((0, 0, 0), (2, 8, 16), {"*": "#frame"}),
        Box((14, 0, 0), (16, 8, 16), {"*": "#frame"}),
        Box((2, 5, 0), (14, 7, 16), {"up": "#belt", "north": "#frame", "south": "#frame", "down": "#frame"}),
    ])


# ---------------------------------------------------------------------------------------------------------------- the catalogue

def _signs() -> KitBlock:
    def states():
        variants = {}
        for i in range(SIGN_TILES):
            variants.update(facing_variants(model_id(f"sign_{i}"), f",tile={i}"))
        return {"variants": dict(sorted(variants.items()))}
    return KitBlock("enamel_sign", "Enamel Sign", {"facing": tuple(FACINGS), "tile": tuple(str(i) for i in range(SIGN_TILES))},
                    states, lambda: {f"sign_{i}": sign_tile(i) for i in range(SIGN_TILES)}, model_id("sign_0"))


def _sculpture() -> KitBlock:
    import sculptures
    pieces = sculptures.PIECES

    def states():
        return {"variants": {f"piece={p}": {"model": model_id(f"sculpture/{p}")} for p in pieces}}

    def models():
        return {f"sculpture/{p}": build() for p, build in pieces.items()}

    return KitBlock("colony_sculpture", "Colony Sculpture", {"piece": tuple(pieces)}, states, models, model_id(f"sculpture/{next(iter(pieces))}"))


CATALOGUE: dict[str, KitBlock] = {block.name: block for block in [
    cube("corrugated_cream", "Cream Corrugated Steel", WEAR),
    cube("corrugated_red", "Company Red Corrugated Steel", WEAR),
    cube("corrugated_dark", "Weathered Corrugated Steel", WEAR),
    cube("riveted_plate", "Riveted Plate", WEAR),
    cube("riveted_plate_red", "Red Riveted Plate"),
    cube("enamel_panel", "Cream Enamel Panel"),
    cube("steel_frame", "Red Steel Frame"),
    cube("hazard_band", "Hazard Band"),
    cube("concrete_footing", "Concrete Footing", (("", 6), ("_cracked", 2))),
    cube("grating", "Steel Grating"),
    cube("brass_trim", "Brass Trim"),
    cube("company_brick", "Company Brick"),
    cube("bronze", "Polished Bronze"),
    facing("window_small_lit", "Lit Mill Window", lambda: window("window_small", "corrugated_cream", "window_small_glow")),
    facing("window_small_dark", "Dark Mill Window", lambda: window("window_small_dark", "corrugated_cream", None)),
    facing("window_ribbon_lit", "Lit Ribbon Window", lambda: window("window_ribbon", "steel_frame", "window_ribbon_glow")),
    facing("window_ribbon_dark", "Dark Ribbon Window", lambda: window("window_ribbon_dark", "steel_frame", None)),
    facing("furnace_hatch", "Crusher Hatch", lambda: framed_cube("furnace_hatch", "riveted_plate", "furnace_hatch_glow")),
    facing("gauge_panel", "Gauge Panel", lambda: framed_cube("gauge_panel", "riveted_plate", "gauge_panel_glow")),
    facing("winder_door", "Riveted Door", lambda: framed_cube("winder_door", "riveted_plate")),
    facing("wall_lamp", "Company Wall Lamp", wall_lamp),
    facing("floodlight", "Company Floodlight", floodlight),
    facing("railing", "Catwalk Railing", railing),
    facing("roof_slope", "Corrugated Roof Slope", lambda: roof_slope("roof_red", "corrugated_cream")),
    facing("roof_slope_dark", "Weathered Roof Slope", lambda: roof_slope("roof_dark", "corrugated_dark")),
    facing("brace", "Lattice Brace", lambda: brace("lattice")),
    facing("brace_red", "Red Lattice Brace", lambda: brace("lattice_red")),
    facing("conveyor", "Belt Conveyor", conveyor),
    horizontal_axis("roof_peak", "Corrugated Roof Ridge", lambda: roof_peak("roof_red", "corrugated_cream")),
    horizontal_axis("roof_peak_dark", "Weathered Roof Ridge", lambda: roof_peak("roof_dark", "corrugated_dark")),
    pillar("steel_beam", "Steel Beam", lambda: i_beam("beam")),
    pillar("steel_beam_red", "Red Steel Beam", lambda: i_beam("beam_red")),
    pillar("lattice_girder", "Lattice Girder", lambda: lattice("lattice")),
    pillar("lattice_girder_red", "Red Lattice Girder", lambda: lattice("lattice_red")),
    pillar("pipe", "Steel Pipe", lambda: pipe("pipe")),
    pillar("pipe_brass", "Brass Pipe", lambda: pipe("pipe_brass")),
    pillar("cable", "Winding Cable", cable),
    _signs(),
    _sculpture(),
]}


def block(name: str) -> KitBlock:
    if name not in CATALOGUE:
        raise KeyError(f"no kit block {name!r}; the kit has {sorted(CATALOGUE)}")
    return CATALOGUE[name]
