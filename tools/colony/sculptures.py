"""The display-only pieces of the kit, drawn by block-display entities: the three Founder statue concepts (each a body and a pair
of hands, so a work order can take the hands away), the sheave wheel of a headframe and a segment of an inclined gallery.

A figure is built in figure space: the origin is the ground under its feet, Y is up, it faces +Z (south), its right hand is at -X.
One unit is 1/16 of a block of the model, and a display scales the model up (FIGURE_SCALE). FIGURE_OFFSET moves figure space into
the model's -16..32 box. Every figure keeps the arms off the line of the shoulders, so no outline is a cross: the forearms run
toward the viewer, rest on a lap, or one hand is up and the other low.
"""
import math

from models import Box, Model
from sculpt import Bone, flatten, world_points

FIGURE_OFFSET = (8.0, -16.0, 8.0)
_T = "deepcharter:block/colony/"
BRONZE = {"bronze": _T + "bronze", "dark": _T + "bronze_dark", "slot": _T + "statue_slot", "brass": _T + "brass_trim",
          "particle": _T + "bronze"}
B, D, S, P = "#bronze", "#dark", "#slot", "#brass"
HANDS = "hands"


def _limb(parent: Bone, pivot, rotation, length: float, width: float, depth: float, texture=B) -> Bone:
    """A limb hanging from pivot along its local -Y."""
    bone = parent.child(pivot, rotation)
    bone.box((-width / 2, -length, -depth / 2), (width / 2, 0.0, depth / 2), texture)
    return bone


def _open_hand(parent: Bone, pivot, rotation, side: int, scale: float) -> Bone:
    """An open hand of the hands piece: palm toward local +Z, fingers along local -Y, thumb toward local -X for the right hand
    (side -1) and +X for the left, held apart so it reads as open and empty, never a fist."""
    s = scale
    hand = parent.child(pivot, rotation)
    hand.box((-1.6 * s, 0.4 * s, -0.9 * s), (1.6 * s, 1.2 * s, 0.9 * s), D, piece=HANDS)          # the wrist
    hand.box((-1.9 * s, -3.0 * s, -0.75 * s), (1.9 * s, 0.4 * s, 0.6 * s), piece=HANDS)          # the palm
    for i, (x, length) in enumerate(((1.45, 2.7), (0.5, 3.1), (-0.45, 2.9), (-1.4, 2.2))):
        finger = hand.child((-side * x * s, -3.0 * s, 0.0), (-(5.0 + 3.0 * i), 0.0, -side * (i - 1.5) * 4.0))
        finger.box((-0.43 * s, -length * s, -0.45 * s), (0.43 * s, 0.2 * s, 0.4 * s), piece=HANDS)
    thumb = hand.child((-side * 1.9 * s, -0.6 * s, 0.1 * s), (-12.0, 0.0, -side * 50.0))
    thumb.box((-0.55 * s, -2.5 * s, -0.5 * s), (0.55 * s, 0.0, 0.5 * s), piece=HANDS)
    return hand


# Heads are drawn larger than life, as monuments are, so the face and the whiskers read from the square.
HEAD = 1.15


def _head(neck: Bone, nod: float, turn: float = 0.0) -> Bone:
    """A Victorian head: broad face, side whiskers, swept-back hair, a heavy brow; the face is toward +Z."""
    head = neck.child((0.0, 2.4, 0.2), (nod, turn, 0.0))

    def part(lo, hi, texture=B):
        head.box(tuple(v * HEAD for v in lo), tuple(v * HEAD for v in hi), texture)

    part((-2.9, 0.0, -3.0), (2.9, 6.4, 2.8))
    part((-2.4, 0.4, 2.7), (2.4, 3.2, 3.3))                 # jaw and chin
    part((-0.5, 2.7, 2.7), (0.5, 4.2, 3.8))                 # nose
    part((-2.6, 4.1, 2.5), (2.6, 4.9, 3.2), D)              # brow
    part((-3.3, 0.2, -2.0), (-2.7, 4.2, 1.9))               # whiskers
    part((2.7, 0.2, -2.0), (3.3, 4.2, 1.9))
    part((-3.1, 5.6, -3.3), (3.1, 7.2, 2.4))                # hair, swept back
    part((-3.0, 4.0, -3.5), (3.0, 5.8, -2.8))
    part((-1.2, 3.5, 2.75), (-0.45, 3.85, 2.95), D)         # eyes, in shadow
    part((0.45, 3.5, 2.75), (1.2, 3.85, 2.95), D)
    return head


def _chest_slot(torso: Bone, y: float, z: float) -> None:
    """The slot in the chest: a dark mouth in a thin brass lip, flush with the waistcoat, a small brass plate under it."""
    torso.box((-1.7, y + 0.1, z - 0.1), (1.7, y + 1.3, z + 0.12), P)
    torso.box((-1.4, y + 0.35, z + 0.05), (1.4, y + 1.05, z + 0.16), S)
    torso.box((-0.9, y - 0.9, z - 0.1), (0.9, y - 0.4, z + 0.1), P)


def _waistcoat(torso: Bone, top: float, front: float) -> None:
    for side in (-1, 1):
        lapel = torso.child((side * 2.6, top, front - 0.1), (0.0, 0.0, side * 16.0))
        lapel.box((-1.1, -6.0, -0.2), (1.1, 0.0, 0.4), D)
    for i in range(3):
        torso.box((-0.35, 1.6 + 1.5 * i, front - 0.1), (0.35, 2.2 + 1.5 * i, front + 0.25), P)
    torso.box((-3.6, 2.6, front - 0.1), (1.4, 2.9, front + 0.15), P)                      # watch chain


def _standing_coat(root: Bone, hem: float, waist: float, flare: float, stride: float = 0.0) -> None:
    """Trouser legs and boots, and a frock-coat skirt widening from the waist to the hem."""
    for side, forward in ((-1, -stride), (1, stride)):
        leg = root.child((side * 2.5, hem + 1.0, forward * 0.2), (-forward * 6.0, 0.0, 0.0))
        leg.box((-2.3, -(hem + 1.0), -2.3), (2.3, 0.0, 2.3))
        leg.box((-2.1, -(hem + 1.0), -1.4 + forward * 0.3), (2.1, -(hem - 1.6), 5.0 + forward * 0.3), D)  # boot
    steps = 4
    for i in range(steps):
        y0 = hem + (waist - hem) * i / steps
        y1 = hem + (waist - hem) * (i + 1) / steps
        w = flare - (flare - 13.0) * i / (steps - 1)
        d = 9.0 - 1.4 * i / (steps - 1)
        root.box((-w / 2, y0, -d / 2 - 0.3), (w / 2, y1, d / 2 - 0.3))
    root.box((-0.3, hem, 3.9), (0.3, waist - 1.5, 4.4), D)                                 # the coat's front edge
    root.box((-0.25, hem + 1.0, -4.9), (0.25, waist - 2.0, -4.4), D)                       # the vent behind


def _torso(root: Bone, waist: float, height: float, chest: float = 3.9) -> Bone:
    torso = root.child((0.0, waist, 0.0))
    torso.box((-6.4, 0.0, -3.9), (6.4, height, 3.5))
    torso.box((-7.3, height - 3.4, -3.5), (7.3, height, 3.1))                              # shoulders
    torso.box((-5.0, 0.8, 3.3), (5.0, height - 3.8, chest + 0.2))                          # waistcoat, a little proud
    torso.box((-5.4, 0.0, 3.0), (5.4, 3.2, chest + 0.6))                                   # the belly of a well-fed man
    return torso


def _neck_and_head(torso: Bone, height: float, nod: float, turn: float = 0.0) -> Bone:
    neck = torso.child((0.0, height, 0.2))
    neck.box((-1.8, 0.0, -1.8), (1.8, 2.6, 1.8))
    neck.box((-2.4, 0.0, 1.2), (2.4, 1.1, 2.9), D)                                         # cravat
    return _head(neck, nod, turn)


def provider() -> Bone:
    """Concept A, the Provider: standing in a frock coat, both forearms held forward at the waist, palms up, open and empty, under
    the slot in his chest. From the front the arms come toward you, so the outline stays a column; from the side it is an L."""
    body = Bone((0.0, 0.0, 0.0))
    _standing_coat(body, hem=6.5, waist=22.0, flare=17.5)
    torso = _torso(body, 22.0, 12.4)
    _waistcoat(torso, 9.0, 3.9)
    _chest_slot(torso, 6.9, 4.1)
    _neck_and_head(torso, 12.4, nod=12.0)
    for side in (-1, 1):
        upper = _limb(torso, (side * 7.0, 10.6, -0.2), (-16.0, 0.0, side * 5.0), 10.0, 3.8, 4.0)
        upper.box((-2.1, -10.6, -2.1), (2.1, -8.4, 2.1))                                      # elbow
        fore = _limb(upper, (0.0, -10.0, 0.0), (-74.0, -side * 14.0, 0.0), 8.6, 3.3, 3.4)
        fore.box((-1.95, -8.6, -1.95), (1.95, -6.8, 1.95), D)                               # cuff
        _open_hand(fore, (0.0, -8.6, 0.0), (-18.0, 0.0, side * 8.0), side, 1.45)
    return body


def patron() -> Bone:
    """Concept B, the Patron: seated on a heavy Company chair, forearms held out over the knees, palms up and open. A seated
    figure is a pyramid from every side."""
    body = Bone((0.0, 0.0, 0.0))
    # The chair: a plinth, a seat, arms with scrolled fronts and a tall back with a plain rolled top. Nothing rises above the
    # back: from the square, anything there reads as raised hands.
    body.box((-10.5, 0.0, -11.0), (10.5, 9.0, 3.5), D)
    body.box((-11.0, 9.0, -11.0), (11.0, 11.0, 4.0))
    for side in (-1, 1):
        body.box((side * 8.4 - 1.6, 11.0, -10.0), (side * 8.4 + 1.6, 18.0, 3.0))
        body.box((side * 8.4 - 1.9, 18.0, -10.5), (side * 8.4 + 1.9, 19.4, 4.2))
        body.box((side * 8.4 - 1.9, 14.0, 2.6), (side * 8.4 + 1.9, 18.0, 4.2), D)
    body.box((-9.5, 11.0, -11.5), (9.5, 36.0, -8.2))
    body.box((-10.2, 34.5, -12.0), (10.2, 37.0, -7.8), D)
    body.box((-8.8, 37.0, -11.6), (8.8, 38.6, -8.4))
    # The figure: thighs along the seat, shins down to the ground, the coat over his lap.
    for side in (-1, 1):
        body.box((side * 3.0 - 2.4, 11.0, -6.0), (side * 3.0 + 2.4, 15.6, 9.0))
        body.box((side * 3.0 - 2.2, 0.0, 5.4), (side * 3.0 + 2.2, 11.5, 9.6))
        body.box((side * 3.0 - 2.1, 0.0, 4.6), (side * 3.0 + 2.1, 1.8, 13.4), D)
    body.box((-6.8, 13.0, -7.5), (6.8, 16.4, 6.4))
    torso = body.child((0.0, 15.0, -3.0))
    torso.box((-6.4, 0.0, -3.9), (6.4, 15.0, 3.5))
    torso.box((-7.3, 11.6, -3.5), (7.3, 15.0, 3.1))
    torso.box((-5.0, 0.8, 3.3), (5.0, 11.2, 4.1))
    torso.box((-5.4, 0.0, 3.0), (5.4, 4.0, 4.7))
    _waistcoat(torso, 11.0, 4.1)
    _chest_slot(torso, 8.6, 4.2)
    _neck_and_head(torso, 15.0, nod=10.0)
    for side in (-1, 1):
        upper = _limb(torso, (side * 7.0, 13.2, -0.4), (-8.0, 0.0, side * 4.0), 9.4, 3.8, 4.0)
        upper.box((-2.1, -10.0, -2.1), (2.1, -7.8, 2.1))
        fore = _limb(upper, (0.0, -9.4, 0.0), (-82.0, -side * 6.0, 0.0), 9.6, 3.3, 3.4)
        fore.box((-1.95, -9.6, -1.95), (1.95, -7.8, 1.95), D)
        _open_hand(fore, (0.0, -9.6, 0.0), (-10.0, 0.0, side * 6.0), side, 1.5)
    return body


def host() -> Bone:
    """Concept C, the Host: mid-stride, the right hand raised beside his head in welcome, palm out, the left held low and forward,
    palm up. One hand greets, the other asks; the outline is lopsided from every side."""
    body = Bone((0.0, 0.0, 0.0))
    _standing_coat(body, hem=8.0, waist=22.5, flare=16.0, stride=1.0)
    torso = _torso(body, 22.5, 12.4)
    torso.rotation = (-4.0, 8.0, 0.0)
    _waistcoat(torso, 9.0, 3.9)
    _chest_slot(torso, 6.9, 4.1)
    _neck_and_head(torso, 12.4, nod=4.0, turn=-6.0)
    # The right arm: the elbow low and out, the forearm up, the open palm facing forward at the height of his head.
    upper = _limb(torso, (-7.0, 10.6, -0.2), (-28.0, 0.0, -32.0), 10.0, 3.8, 4.0)
    upper.box((-2.1, -10.6, -2.1), (2.1, -8.4, 2.1))
    fore = _limb(upper, (0.0, -10.0, 0.0), (-128.0, 0.0, 26.0), 8.6, 3.3, 3.4)
    fore.box((-1.95, -8.6, -1.95), (1.95, -6.8, 1.95), D)
    _open_hand(fore, (0.0, -8.6, 0.0), (8.0, 180.0, 0.0), -1, 1.45)
    # The left arm: forward and down, the palm up.
    upper = _limb(torso, (7.0, 10.6, -0.2), (-52.0, 0.0, 10.0), 10.0, 3.8, 4.0)
    upper.box((-2.1, -10.6, -2.1), (2.1, -8.4, 2.1))
    fore = _limb(upper, (0.0, -10.0, 0.0), (-14.0, -10.0, 0.0), 8.6, 3.3, 3.4)
    fore.box((-1.95, -8.6, -1.95), (1.95, -6.8, 1.95), D)
    _open_hand(fore, (0.0, -8.6, 0.0), (-24.0, 0.0, 8.0), 1, 1.45)
    return body


FIGURES = {"founder_a": provider, "founder_b": patron, "founder_c": host}


def figure_model(name: str, piece: str) -> Model:
    root = FIGURES[name]()
    boxes = flatten(root, FIGURE_OFFSET, piece)
    if not boxes:
        raise ValueError(f"{name} has no {piece}")
    return Model(dict(BRONZE), boxes, ambient_occlusion=False)


def figure_bounds(name: str) -> tuple[tuple[float, float, float], tuple[float, float, float]]:
    """The figure's extent in figure space, both pieces."""
    points = [p for box in world_points(FIGURES[name]()) for p in box]
    return tuple(min(p[i] for p in points) for i in range(3)), tuple(max(p[i] for p in points) for i in range(3))


# ---------------------------------------------------------------------------------------------------------------- machinery

_STEEL = {"steel": _T + "beam", "dark": _T + "beam_web", "brass": _T + "brass_trim", "particle": _T + "beam"}


def sheave() -> Model:
    """A headframe's sheave wheel, its axle along Z: a grooved rim of 24 chords, eight spokes and a hub, 46 units across."""
    centre = (8.0, 8.0, 8.0)
    radius = 22.0
    boxes = []
    segments = 24
    chord = 2 * radius * math.sin(math.pi / segments) + 0.4
    for i in range(segments):
        angle = 360.0 * i / segments
        for z0, z1, depth in ((5.4, 6.6, 2.6), (9.4, 10.6, 2.6), (6.6, 9.4, 1.6)):
            boxes.append(Box((centre[0] - chord / 2, centre[1] + radius - depth, z0), (centre[0] + chord / 2, centre[1] + radius, z1),
                             {"*": "#steel"} if depth > 2 else {"*": "#dark"}, rotation=(0.0, 0.0, angle), pivot=centre))
    for i in range(8):
        angle = 360.0 * i / 8 + 11.25
        boxes.append(Box((centre[0] - 0.9, centre[1] + 2.5, 6.8), (centre[0] + 0.9, centre[1] + radius - 2.0, 9.2), {"*": "#steel"},
                         rotation=(0.0, 0.0, angle), pivot=centre))
    boxes.append(Box((5.0, 5.0, 5.0), (11.0, 11.0, 11.0), {"*": "#dark"}))
    boxes.append(Box((6.5, 6.5, 2.5), (9.5, 9.5, 13.5), {"*": "#brass"}))
    return Model(dict(_STEEL), boxes, ambient_occlusion=False)


_GALLERY = {"wall": _T + "corrugated_cream", "roof": _T + "roof_red", "floor": _T + "riveted_plate", "glow": _T + "window_small_glow",
            "window": _T + "window_small", "frame": _T + "steel_frame", "particle": _T + "corrugated_cream"}


def gallery_segment() -> Model:
    """Three blocks of an inclined conveyor gallery, along Z: corrugated walls on a steel frame, a ridged roof, and a lit window
    in each wall. A display tilts it to the gallery's slope; segments set end to end make the gallery."""
    boxes = [
        Box((-12.0, -8.0, -16.0), (28.0, -6.0, 32.0), {"*": "#floor"}),
        Box((-12.0, -6.0, -16.0), (-10.0, 18.0, 32.0), {"*": "#wall"}),
        Box((26.0, -6.0, -16.0), (28.0, 18.0, 32.0), {"*": "#wall"}),
        Box((-13.0, 18.0, -16.0), (8.0, 20.0, 32.0), {"*": "#roof"}, rotation=(0.0, 0.0, 12.0), pivot=(-13.0, 18.0, 8.0)),
        Box((8.0, 18.0, -16.0), (29.0, 20.0, 32.0), {"*": "#roof"}, rotation=(0.0, 0.0, -12.0), pivot=(29.0, 18.0, 8.0)),
    ]
    for x0, x1 in ((-12.6, -12.0), (28.0, 28.6)):
        boxes.append(Box((x0, -8.5, -16.0), (x1, 18.5, -14.0), {"*": "#frame"}))
        boxes.append(Box((x0, -8.5, 7.0), (x1, 18.5, 9.0), {"*": "#frame"}))
        boxes.append(Box((x0 - 0.02, 4.0, -8.0), (x1 + 0.02, 12.0, 0.0), {"*": "#window"}))
        boxes.append(Box((x0 - 0.04, 4.0, -8.0), (x1 + 0.04, 12.0, 0.0), {"east": "#glow", "west": "#glow"}, glow=True))
        boxes.append(Box((x0 - 0.02, 4.0, 16.0), (x1 + 0.02, 12.0, 24.0), {"*": "#window"}))
        boxes.append(Box((x0 - 0.04, 4.0, 16.0), (x1 + 0.04, 12.0, 24.0), {"east": "#glow", "west": "#glow"}, glow=True))
    return Model(dict(_GALLERY), boxes)


# The pieces of colony_sculpture, in the order of its piece property (KitSculptureBlock.Piece in Java).
PIECES = {
    "founder_a": lambda: figure_model("founder_a", "body"),
    "founder_a_hands": lambda: figure_model("founder_a", HANDS),
    "founder_b": lambda: figure_model("founder_b", "body"),
    "founder_b_hands": lambda: figure_model("founder_b", HANDS),
    "founder_c": lambda: figure_model("founder_c", "body"),
    "founder_c_hands": lambda: figure_model("founder_c", HANDS),
    "sheave": sheave,
    "gallery": gallery_segment,
}

# One unit of figure space is FIGURE_SCALE / 16 blocks once displayed: 7 makes the Provider about 20 blocks tall.
FIGURE_SCALE = 7.0
