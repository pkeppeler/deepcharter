"""The display-only pieces of the kit, drawn by block-display entities: the Founder statue the user picked in #335, the Host (a
body and a pair of hands, so a work order can take the hands away), the sheave wheel of a headframe and a segment of a trussed
conveyor.

A figure is built in figure space: the origin is the ground under its feet, Y is up, it faces +Z (south), its right hand is at -X.
One unit is 1/16 of a block of the model, and a display scales the model up to the height the square wants (scale_for).
FIGURE_OFFSET moves figure space into the model's -16..32 box. No figure holds its arms out from the shoulders, and no hand is
held out in front of the body (from below in the square it would rise to the height of the head): one hand is raised beside the
head, the other hangs at the side.
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


def host() -> Bone:
    """The Host (statue C of #335, the user's pick): mid-stride, the right hand raised beside his head in welcome, palm out, the
    left lowered at his side and turned out, open. One hand greets, the other shows it holds nothing."""
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
    # The left arm: lowered at his side, a little out and forward, the open palm turned out.
    upper = _limb(torso, (7.0, 10.6, -0.2), (-6.0, 0.0, 8.0), 10.0, 3.8, 4.0)
    upper.box((-2.1, -10.6, -2.1), (2.1, -8.4, 2.1))
    fore = _limb(upper, (0.0, -10.0, 0.0), (-24.0, 0.0, 3.0), 8.6, 3.3, 3.4)
    fore.box((-1.95, -8.6, -1.95), (1.95, -6.8, 1.95), D)
    _open_hand(fore, (0.0, -8.6, 0.0), (-6.0, 34.0, 4.0), -1, 1.45)
    return body


FIGURES = {"founder_c": host}


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


def scale_for(name: str, height: float) -> float:
    """The display scale that makes the figure height blocks tall, from its soles to its highest point."""
    lo, hi = figure_bounds(name)
    return height * 16 / (hi[1] - lo[1])


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


_TRUSS = {"steel": _T + "beam_web", "end": _T + "beam_end", "floor": _T + "grating", "belt": _T + "conveyor_belt",
          "frame": _T + "conveyor_frame", "lamp": _T + "lamp_lit", "particle": _T + "beam_web"}
# The truss's cross-section, in model units: its sides' X, its chords' Y, the member's thickness.
_TRUSS_SIDES = ((-8.0, -5.5), (21.5, 24.0))
_TRUSS_BOTTOM, _TRUSS_TOP, _TRUSS_MEMBER = (-8.0, -5.5), (21.5, 24.0), 2.5


def truss_segment() -> Model:
    """Three blocks of a trussed conveyor along Z, two wide and two high: two black iron trusses (chords, a post at each panel
    point and a diagonal in each panel, so the sides read as N-trusses), struts across the top, a grating floor, and the belt on
    its frame down the west half. A display tilts it to the conveyor's slope; segments set end to end make the conveyor."""
    steel = {"*": "#steel", "north": "#end", "south": "#end"}
    boxes = []
    bottom, top, m = _TRUSS_BOTTOM, _TRUSS_TOP, _TRUSS_MEMBER
    for x0, x1 in _TRUSS_SIDES:
        for z0 in (-16.0, 0.0, 16.0):
            boxes.append(Box((x0, bottom[0], z0), (x1, bottom[1], z0 + 16.0), steel))
            boxes.append(Box((x0, top[0], z0), (x1, top[1], z0 + 16.0), steel))
        for z0 in (-16.0, 8.0):
            boxes.append(Box((x0, bottom[1], z0), (x1, top[0], z0 + m), {"*": "#steel"}))
            # The panel's diagonal, from the foot of this post to the head of the next.
            dz, dy = 24.0 - m, top[0] - bottom[1]
            length = math.hypot(dz, dy)
            centre = (8.0, (bottom[1] + top[0]) / 2, z0 + m + dz / 2)
            angle = math.degrees(math.atan2(dz, dy))
            boxes.append(Box((x0, centre[1] - length / 2, centre[2] - m / 2), (x1, centre[1] + length / 2, centre[2] + m / 2),
                             {"*": "#steel"}, rotation=(angle, 0.0, 0.0), pivot=((x0 + x1) / 2, centre[1], centre[2])))
            boxes.append(Box((_TRUSS_SIDES[0][1], top[0], z0), (_TRUSS_SIDES[1][0], top[0] + 2.0, z0 + m), {"*": "#steel"}))
    for z0 in (-16.0, 0.0, 16.0):
        boxes.append(Box((_TRUSS_SIDES[0][1], -7.5, z0), (_TRUSS_SIDES[1][0], -6.5, z0 + 16.0), {"*": "#floor"}))
        boxes.append(Box((-4.0, -6.5, z0), (8.0, -3.5, z0 + 16.0), {"*": "#frame", "up": "#belt"}))
    # A lamp under the struts over the walkway, one a segment.
    boxes.append(Box((13.0, top[0] - 1.5, 7.0), (16.0, top[0], 10.0), {"*": "#lamp"}, glow=True))
    return Model(dict(_TRUSS), boxes, ambient_occlusion=False)


# The pieces of colony_sculpture, in the order of its piece property (KitSculptureBlock.Piece in Java).
PIECES = {
    "founder_c": lambda: figure_model("founder_c", "body"),
    "founder_c_hands": lambda: figure_model("founder_c", HANDS),
    "sheave": sheave,
    "truss": truss_segment,
}
