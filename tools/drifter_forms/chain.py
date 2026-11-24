"""The chain: eight small glowing bulbs, 0.5 blocks at the head tapering to 0.25 at the tail, on thin emissive filaments, about 3.5 blocks long in
a loose S, with a faint veil trailing from the head.

Each bulb is a rounded capsule of thin slabs with a blue-white core inside a translucent skin, and a halo of crossed planes that soft-lights
the air round it, so a string of them reads at range as points of light on a line. Every bulb is a bone of the one before, so the animation
runs down the chain as a wave.
"""
import math

from drifter_forms import kit
from drifter_forms.kit import Cube

IDENT = "chain"
SIZE = 512
SEED = 3301
BULBS = 8
HEAD_RADIUS = 4.0
TAIL_RADIUS = 2.0
SKIN = (140, 190, 235)
CORE = (206, 232, 255)
COLD = (170, 220, 255)


def centre(i):
    """Where bulb i is at rest: from the head, high and forward, back and down along a loose S."""
    s = i / (BULBS - 1)
    return (9.0 * math.sin(2 * math.pi * s), 44.0 - 36.0 * s, 16.0 - 32.0 * s)


def radius(i):
    return HEAD_RADIUS + (TAIL_RADIUS - HEAD_RADIUS) * i / (BULBS - 1)


def paint_skin(t):
    swirl = kit.fbm(t.x * 0.3, t.y * 0.3, t.z * 0.3, SEED)
    colour = kit.mix(SKIN, (190, 225, 250), swirl * 0.6 + 0.2 * t.ny)
    return (*colour, 0), (*COLD, 62 + 40 * swirl)


def paint_core(t):
    glint = 0.6 + 0.4 * kit.noise(t.x * 0.9, t.y * 0.9, t.z * 0.9, SEED + 5)
    return (*kit.mix((150, 200, 255), CORE, glint), 255), (*kit.mix((150, 200, 255), (230, 245, 255), glint), 255)


def paint_filament(_t):
    return (150, 200, 245, 255), (*COLD, 220)


def halo_painter(strength):
    def paint(t):
        d = math.hypot(2 * t.fu - 1, 2 * t.fv - 1)
        fall = max(0.0, 1 - d) ** 1.3
        return (*COLD, 0), (*kit.mix(COLD, CORE, fall), strength * fall)

    return paint


def paint_veil(t):
    ripple = 0.5 + 0.12 * math.sin(t.fv * 7 + t.fu * 3)
    reach = ripple * (0.35 + 0.65 * t.fv)
    if abs(t.fu - 0.5) > reach or t.fv > 0.98:
        return (0, 0, 0, 0), None
    edge = abs(t.fu - 0.5) > reach - 0.1
    return (*kit.mix(SKIN, CORE, 0.4), 0), ((*COLD, 120) if edge else (*COLD, 36))


def add_bulb(model, i):
    name = f"bulb_{i}"
    c, r = centre(i), radius(i)
    model.bone(name, f"bulb_{i - 1}" if i else "root", c)
    for turn in (0.0, 45.0):
        model.add(name, Cube((c[0] - r * 0.5, c[1] - r * 0.5, c[2] - r * 0.5), (r,) * 3, paint_core, (0.0, turn, 0.0), density=3.0))
    return name


def add_skin(model, i):
    name = f"bulb_{i}"
    c, r = centre(i), radius(i)
    for dy, height, ring in ((-0.78, 0.7, 0.62), (-0.2, 0.95, 1.0), (0.55, 0.7, 0.7)):
        for cube in kit.disc((c[0], c[2]), c[1] + dy * r - height * r / 2 + 0.2 * r, height * r, ring * r, 4, paint_skin, 3.0, 1.0):
            model.add(name, cube)


def add_filament(model, i):
    a, b = centre(i), centre(i + 1)
    axis = kit.sub(b, a)
    run = kit.length(axis)
    mid = kit.scale(kit.add(a, b), 0.5)
    model.add(f"bulb_{i}", Cube((mid[0] - 0.55, mid[1] - run / 2, mid[2] - 0.55), (1.1, run, 1.1), paint_filament, kit.aim(axis), mid, 3.0))


def add_halo(model, i):
    c, r = centre(i), radius(i)
    size = 6.0 * r
    strength = 255 - 30 * i / (BULBS - 1)
    for turn in (0.0, 90.0):
        model.add(f"bulb_{i}", Cube.plane(c, size, size, (0.0, turn, 0.0), halo_painter(strength), 4.0))
    model.add(f"bulb_{i}", Cube.flat(c, size, size, (0.0, 0.0, 0.0), halo_painter(strength * 0.7), 4.0))


def build():
    model = kit.Model(IDENT, SIZE, (3, 4, 1.4))
    model.bone("root", None, (0, 0, 0))
    for i in range(BULBS):
        add_bulb(model, i)
    for i in range(BULBS):
        add_skin(model, i)
    for i in range(BULBS - 1):
        add_filament(model, i)
    head = centre(0)
    model.bone("veil", "bulb_0", head)
    for turn in (0.0, 90.0):
        model.add("veil", Cube.plane((head[0], head[1] + 5.0, head[2] - 8.0), 14.0, 22.0, (-50.0, turn, 0.0), paint_veil, 3.0))
    # The halos last: the translucent planes blend over what is behind them.
    for i in range(BULBS):
        add_halo(model, i)
    return model


def animations(period, bob, sway, flap):
    bones = {"root": {"position": kit.loop(period, lambda a: (0, bob * math.sin(a), 0))}}
    for i in range(BULBS):
        bones[f"bulb_{i}"] = {
            "rotation": kit.loop(period, lambda a: (sway * math.sin(a), 0, 1.4 * sway * math.sin(a + 0.9)), -0.09 * i)
        }
    bones["veil"] = {"rotation": kit.loop(period, lambda a: (flap * math.sin(2 * a), 0, 0.6 * flap * math.sin(a)), 0.2)}
    return kit.animation(period, bones)


def files():
    return build(), kit.animations_json(IDENT, animations(3.6, 0.9, 4.0, 9.0), animations(1.6, 1.6, 7.0, 16.0))
