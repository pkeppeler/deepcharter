"""The jellyfish: a domed bell, 1.6 blocks across and 1 high, with a faint organ glowing inside it, six tapering
tendrils that curl and trail, and four thin frilled oral arms.

The bell is a lantern of flat membranes: 8 panels round and 7 rings up, each a plane that leans in toward the apex,
so it is a dome and not a stack of blocks, and light passes through it. Two rings of eight ragged lobes hang from the
rim, and the torn outline is the alpha of their texture. The skin is pale teal at about 35 per cent alpha, with faint
radial canals. Inside it is no solid block but an organ: a small stomach and four tilted horseshoe gonads, softly
lit. The lights round the rim and the tips of the tendrils glow.
"""
import math

from drifter_forms import kit
from drifter_forms.kit import Cube

IDENT = "jellyfish"
SIZE = 512
SEED = 1204
RIM = 22.0
HEIGHT = 16.0
APOTHEM = 12.4
PANELS = 8
RING_HEIGHTS = (0.0, 2.0, 4.4, 7.0, 9.8, 12.4, 14.6, 16.0)
ARMS = 4
ARM_SEGMENTS = (9.5, 8.5)
ARM_WIDTHS = (4.4, 3.2)
TENDRILS = 6
# Whole lengths in pixels, and how thick and how tightly curled each tendril is, so that no two are alike.
TENDRIL_LENGTHS = (36.0, 49.0, 41.0, 50.0, 34.0, 45.0)
TENDRIL_THICK = (1.0, 0.72, 1.15, 0.62, 0.9, 0.8)
TENDRIL_CURL = (1.0, 1.35, 0.75, 1.2, 0.6, 1.1)
# Each tendril has five segments: its share of the length, its width, and how far it has bent from straight down by its
# end.
SEGMENT_SHARE = (0.26, 0.24, 0.20, 0.17, 0.13)
SEGMENT_WIDTH = (1.8, 1.45, 1.15, 0.9, 0.65)
SEGMENT_BEND = (5.0, 13.0, 25.0, 42.0, 64.0)
SKIN = (150, 226, 216)
CANAL = (196, 252, 246)
COLD = (150, 255, 235)
ORGAN_CENTRE = 28.0
STOMACH_AXIAL = 3.0
STOMACH_EQUATOR = 2.0
STOMACH_SLAB = 1.5
GONADS = 4
GONAD_SIZE = 5.4
GONAD_REACH = 3.9
LOBE_WIDTH = 10.6
LOBE_HEIGHT = 7.0
TEAL_ALPHA = 92


def ring_apothem(h):
    return APOTHEM * math.sqrt(max(0.04, 1 - (h / 17.0) ** 2.2))


def lobe_edge(fu):
    """How far down a lobe (0 top, 1 bottom) its rounded bottom edge is, at a place across it."""
    return 0.5 + 0.5 * math.sqrt(max(0.0, 1 - (2 * fu - 1) ** 2))


def ragged_edge(fu, k):
    """The torn bottom edge of lobe k: its round edge bitten by a few notches and a fine fringe."""
    notch = max(0.0, kit.noise(fu * 5.5, k * 1.9, 0, SEED + 61) - 0.45) * 0.55
    fringe = 0.05 * abs(math.sin(fu * math.pi * (7 + k % 3) + k))
    return lobe_edge(fu) - notch - fringe


def paint_bell(t):
    h = (t.y - RIM) / HEIGHT
    r = math.hypot(t.x, t.z)
    azimuth = math.atan2(t.x, t.z)
    haze = kit.fbm(t.x * 0.22, t.y * 0.22, t.z * 0.22, SEED)
    colour = kit.mix(SKIN, (205, 245, 240), h * 0.6 + haze * 0.3)
    alpha = TEAL_ALPHA + 26 * (haze - 0.5) + 18 * h
    glow = (*COLD, 62 + 34 * haze)
    # Sixteen canals run from the apex to the rim and fork once near it, so they are thin, faint and wander a little.
    wander = (kit.noise(h * 6, azimuth * 2, 0, SEED + 5) - 0.5) * 0.08
    for count, reach in ((16, 1.0), (32, 0.45)):
        if h > reach:
            continue
        step = 2 * math.pi / count
        off = (azimuth + wander + step / 2) % step - step / 2
        if abs(off) * max(r, 1.0) < 0.5 - 0.25 * h:
            colour = kit.mix(colour, CANAL, 0.8)
            alpha += 70
            glow = (*COLD, 46)
    if h < 0.1:
        colour = kit.mix(colour, (70, 190, 190), 0.5)
        alpha += 55
        glow = (*COLD, 62)
    return (*colour, 0), glow


def paint_cap(_t):
    return (*SKIN, 0), (*COLD, 70)


def lobe_painter(k, lights):
    def paint(t):
        edge = ragged_edge(t.fu, k)
        if t.fv > edge:
            return (0, 0, 0, 0), None
        colour = kit.mix(SKIN, (205, 245, 240), 0.3 + 0.3 * t.fv)
        alpha = TEAL_ALPHA + 40 * t.fv
        glow = (*COLD, 40 + 14 * kit.noise(t.fu * 9, t.fv * 5, k, SEED + 62))
        if edge - t.fv < 0.09:
            colour = kit.mix(colour, CANAL, 0.7)
            alpha = 190
            glow = (*COLD, 100)
        if lights:
            for cu in (0.5, 0.17, 0.83):
                cv = ragged_edge(cu, k) - 0.12
                if math.hypot((t.fu - cu) * LOBE_WIDTH, (t.fv - cv) * LOBE_HEIGHT) < (0.55 if cu == 0.5 else 0.38):
                    return (210, 255, 255, 255), (200, 255, 255, 255)
        return (*colour, 0), glow

    return paint


def paint_stomach(t):
    swirl = kit.fbm(t.x * 0.4, t.y * 0.35, t.z * 0.4, SEED + 9)
    lit = 0.35 + 0.4 * swirl
    colour = kit.mix((30, 110, 170), (110, 200, 235), lit)
    return (*colour, 255), (*kit.mix((40, 150, 215), (140, 225, 250), lit), 105 + 70 * swirl)


def paint_gonad(t):
    """A horseshoe ring, open on the side that faces the stomach, brighter along its inner edge, mottled."""
    x, y = 2 * t.fu - 1, 2 * t.fv - 1
    d = math.hypot(x, y)
    if d > 0.98 or d < 0.5 or (y > 0.25 and abs(x) < 0.3):
        return (0, 0, 0, 0), None
    inner = (d - 0.5) / 0.48
    mottle = kit.fbm(t.x * 0.5, t.y * 0.5, t.z * 0.5, SEED + 12)
    colour = kit.mix((60, 120, 200), (150, 190, 255), 1 - inner)
    return (*colour, 255), (*kit.mix((90, 150, 245), (170, 215, 255), 1 - inner), 70 + 80 * (1 - inner) * (0.6 + 0.6 * mottle))


def paint_arm(phase):
    def paint(t):
        # A frill: the half-width ripples fast down the arm and narrows to its tip.
        ripple = 0.55 + 0.22 * math.sin(t.fv * 31 + phase) + 0.12 * math.sin(t.fv * 11 + phase * 2.3)
        reach = ripple * (1 - 0.4 * t.fv)
        if abs(t.fu - 0.5) > reach * 0.5 or t.fv > 0.97:
            return (0, 0, 0, 0), None
        edge = abs(t.fu - 0.5) > reach * 0.5 - 0.07
        colour = kit.mix(SKIN, CANAL, 0.6 if edge else 0.2)
        return (*colour, 0), (*COLD, 125 if edge else 46)

    return paint


def tendril_painter(k, last):
    def paint(t):
        colour = kit.mix((84, 170, 170), (150, 230, 225), t.fv * 0.5)
        glow = None
        if last and t.fv > 0.7:
            return (210, 255, 250, 255), (*COLD, 255)
        if t.fv < 0.09 or (0.46 < t.fv < 0.54):
            glow = (*COLD, 150 - 20 * k)
            colour = kit.mix(colour, CANAL, 0.6)
        return (*colour, 205), glow or (*COLD, 60)

    return paint


def paint_ribbon(t):
    ripple = 0.5 + 0.25 * math.sin(t.fv * 22)
    if abs(t.fu - 0.5) > ripple * 0.9:
        return (0, 0, 0, 0), None
    edge = abs(t.fu - 0.5) > ripple * 0.9 - 0.12
    return (*kit.mix(SKIN, CANAL, 0.5), 0), (*COLD, 90 if edge else 34)


def build():
    model = kit.Model(IDENT, SIZE, (4, 5, 0.3))
    model.bone("root", None, (0, 0, 0))
    model.bone("body", "root", (0, RIM, 0))
    model.bone("core", "body", (0, ORGAN_CENTRE, 0))
    add_organ(model)
    for i in range(ARMS):
        add_arm(model, i)
    for i in range(TENDRILS):
        add_tendril(model, i)
    # Last, so that what is inside the translucent bell is drawn before it.
    model.bone("bell", "body", (0, RIM, 0))
    add_bell(model)
    return model


def add_organ(model):
    y = ORGAN_CENTRE - STOMACH_AXIAL
    while y < ORGAN_CENTRE + STOMACH_AXIAL - 0.6:
        mid = y + STOMACH_SLAB / 2
        radius = STOMACH_EQUATOR * math.sqrt(max(0.1, 1 - ((mid - ORGAN_CENTRE) / STOMACH_AXIAL) ** 2))
        for cube in kit.disc((0.0, 0.0), y, STOMACH_SLAB, radius, 4, paint_stomach, 3.0, 1.0):
            model.add("core", cube)
        y += STOMACH_SLAB
    for i in range(GONADS):
        heading = math.radians(360.0 / GONADS * i + 45.0)
        lean = math.radians(52)
        normal = (math.sin(heading) * math.sin(lean), math.cos(lean), math.cos(heading) * math.sin(lean))
        centre = (GONAD_REACH * math.sin(heading), ORGAN_CENTRE + 0.5, GONAD_REACH * math.cos(heading))
        # The horseshoe's gap is the side nearest the stomach: turn the plane about its own up axis to face it.
        model.add("core", Cube.flat(centre, GONAD_SIZE, GONAD_SIZE, kit.aim(normal), paint_gonad, 3.0))


def add_bell(model):
    for k in range(len(RING_HEIGHTS) - 1):
        h0, h1 = RING_HEIGHTS[k], RING_HEIGHTS[k + 1]
        r0, r1 = ring_apothem(h0), ring_apothem(h1)
        mid = (r0 + r1) / 2
        tilt = math.atan2(r0 - r1, h1 - h0)
        slant = math.hypot(r0 - r1, h1 - h0)
        width = 2 * mid * math.tan(math.pi / PANELS) * 1.05
        for i in range(PANELS):
            yaw = 360.0 / PANELS * i
            heading = math.radians(yaw)
            normal = (math.cos(tilt) * math.sin(heading), math.sin(tilt), math.cos(tilt) * math.cos(heading))
            centre = (mid * math.sin(heading), RIM + (h0 + h1) / 2, mid * math.cos(heading))
            model.add("bell", Cube.plane(centre, width, slant * 1.02, kit.face_on(normal), paint_bell, density=3.0))
    top = ring_apothem(RING_HEIGHTS[-1]) * 2
    for turn in (0.0, 45.0):
        model.add("bell", Cube.flat((0, RIM + HEIGHT, 0), top, top, (0.0, turn, 0.0), paint_cap))
    # The rim hangs two rings of lobes, the second shorter, flared further and half a step round, so that the edge is
    # ragged.
    for ring, (height, flare, inset, offset) in enumerate(((LOBE_HEIGHT, 16.0, 0.0, 0.0), (5.0, 26.0, 1.4, 22.5))):
        for i in range(PANELS):
            k = i + ring * PANELS
            tall = height * (0.85 + 0.3 * kit.noise(k * 1.7, 0, 0, SEED + 63))
            heading = math.radians(360.0 / PANELS * i + offset)
            lean = math.radians(flare + 4.0 * (kit.noise(k * 2.3, 1, 0, SEED + 64) - 0.5))
            reach = ring_apothem(0) - inset + tall / 2 * math.sin(lean)
            centre = (reach * math.sin(heading), RIM + 0.4 - tall / 2 * math.cos(lean), reach * math.cos(heading))
            normal = (math.cos(lean) * math.sin(heading), math.sin(lean), math.cos(lean) * math.cos(heading))
            model.add(
                "bell",
                Cube.plane(centre, LOBE_WIDTH, tall, kit.face_on(normal), lobe_painter(k, ring == 0), density=4.0),
            )


def add_arm(model, i):
    heading = math.radians(45 + 90 * i)
    foot = (2.4 * math.sin(heading), 0.0, 2.4 * math.cos(heading))
    top, parent = 20.5, "body"
    for k, (run, width) in enumerate(zip(ARM_SEGMENTS, ARM_WIDTHS)):
        name = f"arm_{i}" if k == 0 else f"arm_{i}_{'ab'[k]}"
        model.bone(name, parent, (foot[0], top, foot[2]))
        centre = (foot[0], top - run / 2, foot[2])
        for quarter in (0.0, 90.0):
            model.add(
                name,
                Cube.plane(centre, width, run, (0.0, math.degrees(heading) + quarter, 0.0), paint_arm(i + 2.1 * k), 3.0),
            )
        parent, top = name, top - run


def tendril_direction(i, k):
    """The unit direction of segment k of tendril i: down, bent a little further out and round at each step, so that it
    curls."""
    heading = math.radians(30 + 60 * i)
    radial = (math.sin(heading), 0.0, math.cos(heading))
    tangent = (math.cos(heading), 0.0, -math.sin(heading))
    swing = 1 if i % 2 == 0 else -1
    # The bend starts straight out and turns about the tendril as it goes: a spiral.
    return kit.bent_down(radial, tangent, SEGMENT_BEND[k] * TENDRIL_CURL[i], (0.0, 20.0, 50.0, 90.0, 140.0)[k] * swing)


def add_tendril(model, i):
    heading = math.radians(30 + 60 * i)
    start = (8.8 * math.sin(heading), RIM - 0.6, 8.8 * math.cos(heading))
    parent = "body"
    for k in range(len(SEGMENT_SHARE)):
        reach = TENDRIL_LENGTHS[i] * SEGMENT_SHARE[k]
        down = tendril_direction(i, k)
        end = kit.add(start, kit.scale(down, reach))
        centre = kit.scale(kit.add(start, end), 0.5)
        name = f"tendril_{i}_{'abcde'[k]}"
        model.bone(name, parent, start)
        width = SEGMENT_WIDTH[k] * TENDRIL_THICK[i]
        turn = kit.aim(kit.scale(down, -1))
        model.add(name, Cube.strut(centre, reach, width, turn, tendril_painter(k, k == len(SEGMENT_SHARE) - 1)))
        if k < 3:
            model.add(name, Cube.plane(centre, (4.4 - 1.2 * k) * TENDRIL_THICK[i], reach, turn, paint_ribbon, 3.0))
        parent, start = name, end


def swaying(period, bone_amounts, phase):
    """Rotation keyframes: each bone leans about x and z by its amount, a little later than the one it hangs from."""
    return {
        bone: {
            "rotation": kit.loop(
                period,
                lambda a, m=m: (m[0] * math.sin(a) + 0.35 * m[0] * math.sin(2 * a + 0.6), 0, m[1] * math.sin(a + 1.3)),
                phase - lag,
                steps=12,
            )
        }
        for bone, m, lag in bone_amounts
    }


def animations(period, breath, bob, sway):
    bones = {
        "body": {"position": kit.loop(period, lambda a: (0, bob * math.sin(a), 0), steps=12)},
        "bell": {
            "scale": kit.loop(
                period, lambda a: (1 + breath * math.sin(a), 1 - 1.5 * breath * math.sin(a), 1 + breath * math.sin(a)), steps=12
            )
        },
        "core": {"scale": kit.loop(period, lambda a: (1 + 1.8 * breath * math.sin(a + 0.8),) * 3, steps=12)},
    }
    for i in range(ARMS):
        for k in range(2):
            name = f"arm_{i}" if k == 0 else f"arm_{i}_b"
            bones.update(swaying(period, [(name, (sway * (1 + k), sway * 0.9 * (1 + k)), 0.11 * k)], i * 0.25 + 0.18))
    for i in range(TENDRILS):
        # The tendrils trail the bell: each segment is later than the one above it, and swings wider.
        chain = [
            (f"tendril_{i}_{'abcde'[k]}", (sway * (0.4 + 0.35 * k), sway * (0.5 + 0.35 * k)), 0.08 * k + 0.12)
            for k in range(len(SEGMENT_SHARE))
        ]
        bones.update(swaying(period, chain, i / TENDRILS))
    return kit.animation(period, bones)


def files():
    model = build()
    idle = animations(3.2, 0.035, 0.9, 3.2)
    drawn = animations(1.4, 0.08, 1.6, 5.6)
    return model, kit.animations_json(IDENT, idle, drawn)
