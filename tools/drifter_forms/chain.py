"""The chain: eight pale vertebrae on glowing filaments, 0.55 blocks across at the head and tapering to a third of that
at the tail, about 3.5 blocks long in a loose S, with a toothed, eyeless maw at its head and two hooked feelers.

Each link is a spindle of two boxes twisted on one another, an octagonal prism along the chain, between two thin
ribbed rings, so that a string of them reads as a spine and not as blobs. A faint halo, small and steep, keeps a link
a point of light at range without washing out the form. Every link is a bone of the one before, so the animation runs
down the chain as a wave.
"""
import math

from drifter_forms import kit
from drifter_forms.kit import Cube

IDENT = "chain"
SEED = 3301
SIZE = 512
BULBS = 8
HEAD_RADIUS = 4.6
TAIL_RADIUS = 1.6
SKIN = (176, 204, 232)
CORE = (206, 232, 255)
COLD = (170, 220, 255)
DARK = (14, 20, 30)
FEELER_RUNS = (9.0, 8.5, 7.0)
FEELER_WIDTHS = (1.4, 1.05, 0.8)
FEELER_TURNS = ((0.85, 0.55, 0.15), (0.55, 0.3, -0.25), (-0.15, -0.8, -0.7))


def centre(i):
    """Where link i is at rest: from the head, high and forward, back and down along a loose S."""
    s = i / (BULBS - 1)
    return (9.0 * math.sin(2 * math.pi * s), 44.0 - 36.0 * s, -16.0 + 32.0 * s)


def radius(i):
    return HEAD_RADIUS + (TAIL_RADIUS - HEAD_RADIUS) * (i / (BULBS - 1)) ** 0.85


def axis(i):
    """The direction the chain runs at link i, towards the tail."""
    a, b = centre(max(0, i - 1)), centre(min(BULBS - 1, i + 1))
    return kit.unit(kit.sub(b, a))


def length(i):
    return radius(i) * (1.9 if i == 0 else 1.5)


def paint_link(t):
    swirl = kit.fbm(t.x * 0.35, t.y * 0.35, t.z * 0.35, SEED)
    lit = 0.5 + 0.35 * t.ny + 0.25 * (swirl - 0.5)
    colour = kit.mix((80, 104, 138), SKIN, lit)
    # The ends of a spindle glow where the filament joins it.
    ends = max(abs(2 * t.fv - 1) - 0.8, 0.0) * 5
    return (*colour, 255), (*kit.mix(COLD, CORE, ends), 115 + 70 * swirl + 70 * ends)


def paint_ring(t):
    """A thin ribbed ring: an annulus with a notched outer edge, lit along its edges."""
    x, y = 2 * t.fu - 1, 2 * t.fv - 1
    d = math.hypot(x, y)
    spike = 0.84 + 0.16 * (0.5 + 0.5 * math.cos(math.atan2(y, x) * 8))
    if d > spike or d < 0.52:
        return (0, 0, 0, 0), None
    rim = d > spike - 0.2 or d < 0.66
    return (*kit.mix((110, 140, 178), SKIN, 0.7 if rim else 0.2), 255), (*kit.mix(COLD, CORE, 0.5), 230 if rim else 40)


def paint_filament(_t):
    return (150, 200, 245, 255), (*COLD, 160)


def halo_painter(strength):
    def paint(t):
        d = math.hypot(2 * t.fu - 1, 2 * t.fv - 1)
        fall = max(0.0, 1 - d) ** 2.4
        return (*COLD, 0), (*kit.mix(COLD, CORE, fall), strength * fall)

    return paint


def paint_maw(t):
    """The mouth, seen end on: a black throat ringed by teeth that interlock, and no eye anywhere."""
    x, y = 2 * t.fu - 1, 2 * t.fv - 1
    d = math.hypot(x, y)
    tooth = 0.5 + 0.5 * math.cos(math.atan2(y, x) * 7)
    if d > 0.98:
        return (0, 0, 0, 0), None
    if d < 0.5 + 0.22 * tooth:
        return (*DARK, 255), None
    if d > 0.64 + 0.34 * tooth:
        return (*kit.mix((90, 112, 150), SKIN, 0.4), 255), (*COLD, 60)
    return (*kit.mix(SKIN, CORE, 0.7), 255), (*CORE, 240)


def paint_jaw(t):
    return (*kit.mix((150, 176, 210), CORE, t.fv), 255), (*COLD, 90 + 160 * t.fv * t.fv)


def feeler_painter(k):
    def paint(t):
        if k == 2 and t.fv > 0.55:
            return (*CORE, 255), (*CORE, 255)
        return (*kit.mix((110, 140, 178), SKIN, t.fv), 255), (*COLD, 120 if t.fv < 0.1 else 70)

    return paint


def add_link(model, i):
    name = f"bulb_{i}"
    c, r, a = centre(i), radius(i), axis(i)
    model.bone(name, f"bulb_{i - 1}" if i else "root", c)
    run = length(i)
    for twist in (0.0, 45.0):
        model.add(
            name,
            Cube((c[0] - r * 0.55, c[1] - run / 2, c[2] - r * 0.55), (r * 1.1, run, r * 1.1), paint_link, kit.aim_twisted(a, twist), c, 3.0),
        )
    for side in (-1, 1):
        p = kit.add(c, kit.scale(a, side * run * 0.62))
        size = r * 3.0
        model.add(name, Cube.flat(p, size, size, kit.aim(a), paint_ring, 3.0))


def add_filament(model, i):
    a, b = centre(i), centre(i + 1)
    ra = kit.scale(axis(i), length(i) / 2 + 0.4)
    rb = kit.scale(axis(i + 1), length(i + 1) / 2 + 0.4)
    start, end = kit.add(a, ra), kit.sub(b, rb)
    direction = kit.sub(end, start)
    run = kit.length(direction)
    mid = kit.scale(kit.add(start, end), 0.5)
    model.add(f"bulb_{i}", Cube.strut(mid, run, 0.9, kit.aim(direction), paint_filament))


def add_halo(model, i):
    c, r = centre(i), radius(i)
    size = 4.0 * r
    strength = 130 - 10 * i / (BULBS - 1)
    for turn in (0.0, 90.0):
        model.add(f"bulb_{i}", Cube.plane(c, size, size, (0.0, turn, 0.0), halo_painter(strength), 3.0))


def head_frame():
    """The head's forward direction and the two directions at right angles to it, sideways and up."""
    forward = kit.unit(kit.sub(centre(0), centre(1)))
    side = kit.unit(kit.cross((0.0, 1.0, 0.0), forward))
    up = kit.cross(forward, side)
    return forward, side, up


def add_head(model):
    forward, side, up = head_frame()
    head, r = centre(0), radius(0)
    mouth = kit.add(head, kit.scale(forward, length(0) / 2 + 0.7))
    model.add("bulb_0", Cube.flat(mouth, r * 3.2, r * 3.2, kit.aim(forward), paint_maw, 4.0))
    # Two hooked jaws that close on the maw from either side, the veil of the old chain's name.
    model.bone("veil", "bulb_0", mouth)
    for sign in (-1, 1):
        base = kit.add(mouth, kit.scale(side, sign * r * 1.05))
        tip_dir = kit.unit(kit.add(kit.scale(forward, 0.8), kit.scale(side, -sign * 0.45)))
        run = r * 2.2
        centre_ = kit.add(base, kit.scale(tip_dir, run / 2))
        model.add("veil", Cube.strut(centre_, run, 1.4, kit.aim(tip_dir), paint_jaw))
    for sign in (-1, 1):
        parent, start = "bulb_0", kit.add(kit.add(head, kit.scale(forward, length(0) * 0.35)), kit.scale(side, sign * r * 1.1))
        for k, (fwd, lat, dn) in enumerate(FEELER_TURNS):
            direction = kit.unit(kit.add(kit.add(kit.scale(forward, fwd), kit.scale(side, sign * lat)), kit.scale(up, dn)))
            run, width = FEELER_RUNS[k], FEELER_WIDTHS[k]
            end = kit.add(start, kit.scale(direction, run))
            mid = kit.scale(kit.add(start, end), 0.5)
            name = f"feeler_{'lr'[(sign + 1) // 2]}_{'abc'[k]}"
            model.bone(name, parent, start)
            model.add(name, Cube.strut(mid, run, width, kit.aim(direction), feeler_painter(k)))
            parent, start = name, end


def build():
    model = kit.Model(IDENT, SIZE, (3, 4, 1.4))
    model.bone("root", None, (0, 0, 0))
    for i in range(BULBS):
        add_link(model, i)
    for i in range(BULBS - 1):
        add_filament(model, i)
    add_head(model)
    # The halos last: the translucent planes blend over what is behind them.
    for i in range(BULBS):
        add_halo(model, i)
    return model


def animations(period, bob, sway, flap):
    steps = 12
    bones = {"root": {"position": kit.loop(period, lambda a: (0, bob * math.sin(a), 0), steps=steps)}}
    for i in range(BULBS):
        # A wave runs from the head to the tail: each link is a step behind the one before and swings a little wider.
        amount = sway * (0.55 + 0.2 * i)
        bones[f"bulb_{i}"] = {
            "rotation": kit.loop(
                period, lambda a, m=amount: (m * math.sin(a), 0.4 * m * math.sin(a + 0.5), 1.2 * m * math.sin(a + 1.5)), -0.12 * i, steps=steps
            )
        }
    bones["veil"] = {"rotation": kit.loop(period, lambda a: (flap * max(0.0, math.sin(a)), 0, 0), 0.2, steps=steps)}
    for side in "lr":
        for k in range(3):
            bones[f"feeler_{side}_{'abc'[k]}"] = {
                "rotation": kit.loop(
                    period,
                    lambda a, k=k: (1.4 * flap * math.sin(a), 0, (1.6 if side == "l" else -1.6) * flap * math.sin(a + 0.8)),
                    -0.1 * k - 0.1,
                    steps=steps,
                )
            }
    return kit.animation(period, bones)


def files():
    return build(), kit.animations_json(IDENT, animations(3.6, 0.9, 2.6, 6.0), animations(1.6, 1.6, 4.4, 12.0))
