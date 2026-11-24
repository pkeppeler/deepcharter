"""The jellyfish: a domed bell, 1.6 blocks across and 1 high, around a glowing cyan core, with six long tendrils and four short frilled arms.

The bell is a lantern of flat membranes: 8 panels round and 7 rings up, each a plane that leans in toward the apex, so it is a dome and not a
stack of blocks, and light passes through it. Eight soft lobes hang from the rim, and the scalloped outline is the alpha of their texture.
The skin is pale teal at about 35 per cent alpha, with faint radial canals; the core, the lights round the rim and the tips of the tendrils
glow.
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
TENDRILS = 6
TENDRIL_LENGTHS = (40.0, 46.0, 43.0, 48.0, 41.0, 45.0)
TENDRIL_WIDTHS = (1.9, 1.6, 1.3)
SKIN = (150, 226, 216)
CANAL = (196, 252, 246)
COLD = (150, 255, 235)
CORE_LIGHT = (170, 250, 255)
CORE_CENTRE = 28.2
CORE_AXIAL = 5.8
CORE_EQUATOR = 4.0
CORE_SLAB = 1.9
LOBE_WIDTH = 10.6
LOBE_HEIGHT = 7.0
TEAL_ALPHA = 92


def ring_apothem(h):
    return APOTHEM * math.sqrt(max(0.04, 1 - (h / 17.0) ** 2.2))


def lobe_edge(fu):
    """How far down a lobe (0 top, 1 bottom) its rounded bottom edge is, at a place across it."""
    return 0.5 + 0.5 * math.sqrt(max(0.0, 1 - (2 * fu - 1) ** 2))


def paint_bell(t):
    h = (t.y - RIM) / HEIGHT
    r = math.hypot(t.x, t.z)
    azimuth = math.atan2(t.x, t.z)
    haze = kit.fbm(t.x * 0.22, t.y * 0.22, t.z * 0.22, SEED)
    colour = kit.mix(SKIN, (205, 245, 240), h * 0.6 + haze * 0.3)
    alpha = TEAL_ALPHA + 26 * (haze - 0.5) + 18 * h
    glow = (*COLD, 56 + 30 * haze)
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


def lobe_painter():
    def paint(t):
        edge = lobe_edge(t.fu)
        if t.fv > edge:
            return (0, 0, 0, 0), None
        colour = kit.mix(SKIN, (205, 245, 240), 0.3 + 0.3 * t.fv)
        alpha = TEAL_ALPHA + 40 * t.fv
        glow = (*COLD, 44)
        if edge - t.fv < 0.1:
            colour = kit.mix(colour, CANAL, 0.7)
            alpha = 190
            glow = (*COLD, 90)
        for cu in (0.5, 0.17, 0.83):
            cv = lobe_edge(cu) - 0.12
            if math.hypot((t.fu - cu) * LOBE_WIDTH, (t.fv - cv) * LOBE_HEIGHT) < (0.55 if cu == 0.5 else 0.38):
                return (210, 255, 255, 255), (200, 255, 255, 255)
        return (*colour, 0), glow

    return paint


def paint_core(t):
    nx, ny, nz = kit.unit((t.x / CORE_EQUATOR**2, (t.y - CORE_CENTRE) / CORE_AXIAL**2, t.z / CORE_EQUATOR**2))
    swirl = kit.fbm(t.x * 0.35, t.y * 0.3, t.z * 0.35, SEED + 9)
    lit = 0.45 + 0.3 * ny + 0.25 * swirl
    colour = kit.mix((30, 150, 225), (150, 235, 255), lit - 0.1)
    return (*colour, 255), (*kit.mix((40, 185, 240), CORE_LIGHT, lit - 0.1), 255)


def paint_arm(t):
    frill = 0.5 + 0.18 * math.sin(t.fv * 38 + t.fu * 5)
    if abs(t.fu - 0.5) > frill * (1 - 0.35 * t.fv) or t.fv > 0.97:
        return (0, 0, 0, 0), None
    edge = abs(t.fu - 0.5) > frill * (1 - 0.35 * t.fv) - 0.09
    colour = kit.mix(SKIN, CANAL, 0.5 if edge else 0.2)
    return (*colour, 0), (*COLD, 120 if edge else 50)


def tendril_painter(last):
    def paint(t):
        colour = kit.mix((84, 170, 170), (150, 230, 225), t.fv * 0.5)
        glow = None
        if last and t.fv > 0.78:
            return (210, 255, 250, 255), (*COLD, 255)
        if t.fv < 0.09 or (0.46 < t.fv < 0.54):
            glow = (*COLD, 150)
            colour = kit.mix(colour, CANAL, 0.6)
        return (*colour, 205), glow or (*COLD, 34)

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
    model.bone("core", "body", (0, 28.0, 0))
    y = CORE_CENTRE - CORE_AXIAL + 0.2
    while y < CORE_CENTRE + CORE_AXIAL - 0.8:
        mid = y + CORE_SLAB / 2
        radius = CORE_EQUATOR * math.sqrt(max(0.05, 1 - ((mid - CORE_CENTRE) / CORE_AXIAL) ** 2))
        for cube in kit.disc((0.0, 0.0), y, CORE_SLAB, radius, 4, paint_core, 3.0, 1.0):
            model.add("core", cube)
        y += CORE_SLAB
    model.add("core", Cube((-0.9, 34.6, -0.9), (1.8, 3.4, 1.8), paint_core, density=2.0))
    for i in range(ARMS):
        add_arm(model, i)
    for i in range(TENDRILS):
        add_tendril(model, i)
    # Last, so that what is inside the translucent bell is drawn before it.
    model.bone("bell", "body", (0, RIM, 0))
    add_bell(model)
    return model



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
    for i in range(PANELS):
        heading = math.radians(360.0 / PANELS * i)
        flare = math.radians(16)
        reach = ring_apothem(0) + LOBE_HEIGHT / 2 * math.sin(flare)
        centre = (reach * math.sin(heading), RIM + 0.4 - LOBE_HEIGHT / 2 * math.cos(flare), reach * math.cos(heading))
        normal = (math.cos(flare) * math.sin(heading), math.sin(flare), math.cos(flare) * math.cos(heading))
        model.add("bell", Cube.plane(centre, LOBE_WIDTH, LOBE_HEIGHT, kit.face_on(normal), lobe_painter(), density=4.0))


def add_arm(model, i):
    name = f"arm_{i}"
    heading = math.radians(45 + 90 * i)
    foot = (2.4 * math.sin(heading), 0.0, 2.4 * math.cos(heading))
    model.bone(name, "body", (foot[0], 20.5, foot[2]))
    centre = (foot[0], 13.5, foot[2])
    for quarter in (0.0, 90.0):
        model.add(name, Cube.plane(centre, 6.0, 14.0, (0.0, math.degrees(heading) + quarter, 0.0), paint_arm, density=3.0))


def add_tendril(model, i):
    heading = math.radians(30 + 60 * i)
    radial = (math.sin(heading), 0.0, math.cos(heading))
    tangent = (math.cos(heading), 0.0, -math.sin(heading))
    swing = 1 if i % 2 == 0 else -1
    reach = TENDRIL_LENGTHS[i] / 3
    start = (8.8 * radial[0], RIM - 0.6, 8.8 * radial[2])
    parent = "body"
    for k, (outward, aside) in enumerate(((14.0, 0.0), (3.0, 9.0 * swing), (-8.0, -7.0 * swing))):
        beta, gamma = math.radians(outward), math.radians(aside)
        flat = (
            radial[0] * math.sin(beta) + tangent[0] * math.sin(gamma),
            0.0,
            radial[2] * math.sin(beta) + tangent[2] * math.sin(gamma),
        )
        down = (flat[0], -math.sqrt(max(0.0, 1 - flat[0] ** 2 - flat[2] ** 2)), flat[2])
        end = kit.add(start, kit.scale(down, reach))
        centre = kit.scale(kit.add(start, end), 0.5)
        name = f"tendril_{i}_{'abc'[k]}"
        model.bone(name, parent, start)
        width = TENDRIL_WIDTHS[k]
        turn = kit.aim(kit.scale(down, -1))
        model.add(
            name,
            Cube(
                (centre[0] - width / 2, centre[1] - reach / 2, centre[2] - width / 2),
                (width, reach, width),
                tendril_painter(k == 2),
                turn,
                centre,
                density=3.0,
            ),
        )
        model.add(
            name,
            Cube.plane(centre, 5.0 - k, reach, turn, paint_ribbon, 3.0),
        )
        parent, start = name, end


def swaying(period, bone_amounts, phase):
    """Rotation keyframes: each bone leans about x and z by its amount, a little later down the chain."""
    return {
        bone: {"rotation": kit.loop(period, lambda a, m=m: (m[0] * math.sin(a), 0, m[1] * math.sin(a + 1.3)), phase + lag)}
        for bone, m, lag in bone_amounts
    }


def animations(period, breath, bob, sway):
    bones = {
        "body": {"position": kit.loop(period, lambda a: (0, bob * math.sin(a), 0))},
        "bell": {"scale": kit.loop(period, lambda a: (1 + breath * math.sin(a), 1 - 1.5 * breath * math.sin(a), 1 + breath * math.sin(a)))},
        "core": {"scale": kit.loop(period, lambda a: (1 + 1.8 * breath * math.sin(a + 0.8),) * 3)},
    }
    for i in range(ARMS):
        bones.update(swaying(period, [(f"arm_{i}", (sway, sway * 0.8), 0.0)], i * 0.25))
    for i in range(TENDRILS):
        chain = [(f"tendril_{i}_{'abc'[k]}", (sway * (0.5 + 0.5 * k), sway * (0.6 + 0.5 * k)), 0.06 * k) for k in range(3)]
        bones.update(swaying(period, chain, i / TENDRILS))
    return kit.animation(period, bones)


def files():
    model = build()
    idle = animations(3.2, 0.035, 0.9, 4.0)
    drawn = animations(1.4, 0.08, 1.6, 7.0)
    return model, kit.animations_json(IDENT, idle, drawn)
