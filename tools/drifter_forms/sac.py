"""The spore sac: a heavy puffball 1.8 blocks across, flattened underneath and dimpled on top, netted with raised veins, a ring of gills hanging
under it like a mushroom cap and three stubby threads.

The body is a stack of thin 16-sided slabs (four boxes turned 22.5 degrees apart) whose texture is shaded as the sphere it stands for, so
the steps do not show. The skin is mostly opaque bone-white with thin translucent patches over a dim nucleus; the veins, the gills and the
thread tips glow pale green-white.
"""
import math

from drifter_forms import kit
from drifter_forms.kit import Cube

IDENT = "sac"
SIZE = 512
SEED = 7041
RADIUS = 14.4
CENTRE = 22.0
TOP_REACH = 14.2
BOTTOM_REACH = 17.0
FLOOR = 9.6
SLAB = 1.8
SHOULDER = 11.0
BONE = (236, 231, 214)
SHADE = (132, 150, 148)
VEIN = (196, 240, 212)
COLD = (200, 255, 225)
MERIDIANS = 8
GILLS = 16
INNER_GILLS = 8
THREADS = 3


def profile(y):
    """The radius of the sac's body at height y."""
    if y >= CENTRE:
        return RADIUS * math.sqrt(max(0.02, 1 - ((y - CENTRE) / TOP_REACH) ** 2))
    return RADIUS * math.sqrt(max(0.02, 1 - ((CENTRE - y) / BOTTOM_REACH) ** 2))


def surface_normal(t):
    """The way the sphere the slabs stand for faces at a place on it."""
    reach = TOP_REACH if t.y >= CENTRE else BOTTOM_REACH
    return kit.unit((t.x / RADIUS**2, (t.y - CENTRE) / reach**2, t.z / RADIUS**2))


def paint_skin(t):
    nx, ny, nz = surface_normal(t)
    lit = max(0.0, min(1.0, 0.5 + 0.5 * (0.3 * nx + 0.85 * ny + 0.3 * nz)))
    mottle = kit.fbm(t.x * 0.16, t.y * 0.16, t.z * 0.16, SEED, 3)
    colour = kit.mix(SHADE, BONE, lit * 0.85 + (mottle - 0.5) * 0.35)
    speckle = kit.noise(t.x * 1.9, t.y * 1.9, t.z * 1.9, SEED + 3)
    if speckle > 0.8:
        colour = kit.mix(colour, (150, 160, 140), 0.45)
    alpha = 255.0
    glow = None
    patch = kit.fbm(t.x * 0.11 + 4, t.y * 0.11, t.z * 0.11, SEED + 8, 2)
    if patch > 0.6:
        fade = kit.smooth((patch - 0.6) / 0.05)
        colour = kit.mix(colour, (190, 224, 208), fade * 0.7)
        alpha = 255 - 150 * fade
    near, far = kit.cells(t.x * 0.085, t.y * 0.085, t.z * 0.085, SEED + 11)
    web = far - near
    if web < 0.07:
        colour = kit.mix(colour, VEIN, 0.85)
        alpha = max(alpha, 235)
        glow = (*COLD, 190 if web < 0.04 else 80)
    elif web < 0.12:
        colour = kit.mix(colour, (176, 206, 186), 0.4)
    if t.y < FLOOR + 0.3 and t.ny < -0.5:
        spokes = abs(math.sin(math.atan2(t.x, t.z) * GILLS / 2))
        colour = kit.mix((88, 104, 100), (150, 172, 160), spokes * 0.6)
        alpha = 255
        glow = (*COLD, 40) if math.hypot(t.x, t.z) < 3.2 else None
    return (*colour, alpha), glow


def paint_dimple(t):
    r = math.hypot(t.x, t.z)
    colour = kit.mix((96, 112, 104), (170, 188, 170), r / 8)
    ring = abs(r - 4.2) < 0.55
    return (*kit.mix(colour, VEIN, 0.8 if ring else 0.0), 255), ((*COLD, 150) if ring else None)


def paint_ridge(t):
    glint = 0.5 + 0.5 * kit.noise(t.x * 0.4, t.y * 0.4, t.z * 0.4, SEED + 21)
    return (*kit.mix((170, 214, 188), VEIN, glint), 255), (*COLD, 70 + 60 * glint)


def paint_nucleus(t):
    swirl = kit.fbm(t.x * 0.3, t.y * 0.3, t.z * 0.3, SEED + 31)
    return (*kit.mix((90, 150, 120), (190, 245, 205), swirl), 255), (*COLD, 80 + 60 * swirl)


def gill_painter(length):
    def paint(t):
        # A lamella: a long fan edge below, rounded at both ends, striped along its length.
        edge = 0.62 + 0.38 * math.sin(math.pi * min(1.0, max(0.0, t.fu)))
        if t.fv > edge:
            return (0, 0, 0, 0), None
        stripe = 0.5 + 0.5 * math.sin(t.fu * length * 3.1)
        colour = kit.mix((150, 188, 170), VEIN, stripe * 0.5 + t.fv * 0.4)
        lower = edge - t.fv < 0.16
        return (*colour, 200 if lower else 140), (*COLD, 235 if lower else 40)

    return paint


def thread_painter(tip):
    def paint(t):
        colour = kit.mix((176, 196, 176), (120, 140, 126), t.fv)
        if tip and t.fv > 0.7:
            return (*VEIN, 255), (*COLD, 255)
        return (*colour, 255), None

    return paint


def add_slabs(model):
    y = FLOOR
    while y < CENTRE + SHOULDER:
        height = min(SLAB, CENTRE + SHOULDER - y)
        for cube in kit.disc((0.0, 0.0), y, height, profile(y + height / 2), 8, paint_skin):
            model.add("body", cube)
        y += height
    top = CENTRE + SHOULDER
    for i in range(8):
        heading = 45.0 * i
        centre = (7.0 * math.sin(math.radians(heading)), top + 1.0, 7.0 * math.cos(math.radians(heading)))
        model.add(
            "body",
            Cube((centre[0] - 3.2, centre[1] - 1.3, centre[2] - 1.5), (6.4, 2.6, 3.0), paint_skin, (0.0, heading, 0.0), centre, 2.0, 0.5),
        )
    floor_apothem = 6.4
    model.add("body", Cube((-floor_apothem, top - 0.3, -floor_apothem), (2 * floor_apothem, 0.6, 2 * floor_apothem), paint_dimple, density=2.0, cap_density=2.0))
    model.add("body", Cube((-floor_apothem, top - 0.3, -floor_apothem), (2 * floor_apothem, 0.6, 2 * floor_apothem), paint_dimple, (0.0, 45.0, 0.0), density=2.0))


def add_veins(model):
    """Raised veins that wander: meridians that drift a few degrees at every step and break off now and then, and two rings that do the same."""
    heights = [FLOOR + 1.6 + k * (CENTRE + SHOULDER - FLOOR - 2.4) / 6 for k in range(7)]
    for m in range(MERIDIANS):
        for k in range(6):
            if kit.noise(m * 3.1, k * 1.7, 0, SEED + 41) > 0.82:
                continue
            drift = 7.0 * (kit.noise(m * 1.3, k * 0.6, 0, SEED + 43) - 0.5)
            heading = math.radians(360.0 / MERIDIANS * m + 22.5 + drift * k / 3)
            y0, y1 = heights[k], heights[k + 1]
            r0, r1 = profile(y0) + 0.4, profile(y1) + 0.4
            rm = (r0 + r1) / 2
            centre = (rm * math.sin(heading), (y0 + y1) / 2, rm * math.cos(heading))
            axis = (math.sin(heading) * (r1 - r0), y1 - y0, math.cos(heading) * (r1 - r0))
            run = kit.length(axis)
            model.add(
                "body",
                Cube((centre[0] - 0.7, centre[1] - run / 2, centre[2] - 0.5), (1.4, run, 1.0), paint_ridge, kit.aim(axis), centre, 2.0),
            )
    for ring, y in enumerate((CENTRE - 4.0, CENTRE + 4.5)):
        for i in range(16):
            if kit.noise(i * 1.9, ring * 5.0, 0, SEED + 47) > 0.7:
                continue
            r = profile(y) + 0.4
            chord = 2 * r * math.tan(math.pi / 16) * 1.08
            heading = math.radians(22.5 * i)
            centre = (r * math.sin(heading), y + 0.8 * math.sin(i * 0.9 + ring), r * math.cos(heading))
            model.add(
                "body",
                Cube((centre[0] - chord / 2, centre[1] - 0.5, centre[2] - 0.5), (chord, 1.0, 1.0), paint_ridge, (0.0, 22.5 * i, 0.0), centre, 2.0),
            )


def add_gills(model):
    for count, near, far, height, offset in ((GILLS, 2.8, 10.0, 3.6, 0.0), (INNER_GILLS, 1.2, 5.4, 2.4, 11.25)):
        for i in range(count):
            heading = math.radians(360.0 / count * i + offset)
            reach = (near + far) / 2
            centre = (reach * math.sin(heading), FLOOR - height / 2 - 0.1, reach * math.cos(heading))
            model.add(
                "gills",
                Cube.plane(centre, far - near, height, (0.0, math.degrees(heading) - 90.0, 0.0), gill_painter(far - near), 3.0),
            )


def add_threads(model):
    for i in range(THREADS):
        heading = math.radians(120.0 * i + 30)
        foot = (3.3 * math.sin(heading), FLOOR - 0.2, 3.3 * math.cos(heading))
        parent = "body"
        for k, (width, run) in enumerate(((2.6, 4.6), (1.9, 4.4))):
            name = f"thread_{i}_{'ab'[k]}"
            model.bone(name, parent, foot)
            model.add(
                name,
                Cube((foot[0] - width / 2, foot[1] - run, foot[2] - width / 2), (width, run, width), thread_painter(k == 1), density=3.0),
            )
            parent, foot = name, (foot[0], foot[1] - run, foot[2])


def build():
    model = kit.Model(IDENT, SIZE, (3, 3, 0.6))
    model.bone("root", None, (0, 0, 0))
    model.bone("body", "root", (0, CENTRE, 0))
    for turn in (0.0, 45.0):
        model.add("body", Cube((-3.2, CENTRE - 3.2, -3.2), (6.4, 6.4, 6.4), paint_nucleus, (0.0, turn, 0.0), density=2.0))
    add_slabs(model)
    add_veins(model)
    model.bone("gills", "body", (0, FLOOR, 0))
    add_gills(model)
    add_threads(model)
    return model


def animations(period, swell, flutter, sway):
    bones = {
        "body": {"scale": kit.loop(period, lambda a: (1 + swell * math.sin(a),) * 3)},
        "gills": {"rotation": kit.loop(period, lambda a: (flutter * math.sin(2 * a), 0, flutter * math.sin(2 * a + 1.0)), 0.1)},
    }
    for i in range(THREADS):
        for k in range(2):
            bones[f"thread_{i}_{'ab'[k]}"] = {
                "rotation": kit.loop(period, lambda a, k=k: (sway * (1 + k) * math.sin(a), 0, sway * (1 + k) * math.sin(a + 1.7)), i / THREADS + 0.07 * k)
            }
    return kit.animation(period, bones)


def files():
    model = build()
    return model, kit.animations_json(IDENT, animations(4.0, 0.035, 5.0, 4.0), animations(1.8, 0.07, 9.0, 7.0))
