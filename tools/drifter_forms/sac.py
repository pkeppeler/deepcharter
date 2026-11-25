"""The spore sac: a bloated membrane 1.7 blocks across that sags to one side, flatter on top and heavy underneath,
netted with raised veins, with dark shapes curled inside it that press out against the skin, a seam in front that
parts, and a long fringe of gills, ribbons and feeding threads hanging under it.

The body is a stack of thin 16-sided slabs, each a little off the one below it, whose texture is shaded as the sag it
stands for, so the steps do not show. The skin is mostly opaque bone-white with windows of thin translucent skin
where the dark shapes lie; the veins, the gills, the seam's lips and the thread tips glow pale green-white.
"""
import math

from drifter_forms import kit
from drifter_forms.kit import Cube

IDENT = "sac"
SIZE = 512
SEED = 7041
RADIUS = 13.6
CENTRE = 22.0
TOP_REACH = 13.0
BOTTOM_REACH = 18.0
FLOOR = 8.6
SLAB = 1.6
SHOULDER = 11.0
LEAN = (5.2, 1.6)
BONE = (236, 231, 214)
SHADE = (132, 150, 148)
VEIN = (196, 240, 212)
COLD = (200, 255, 225)
DARK = (22, 30, 28)
MERIDIANS = 6
GILLS = 16
INNER_GILLS = 8
THREADS = 6
THREAD_RUNS = (11.0, 9.5, 8.5)
THREAD_WIDTHS = (1.5, 1.05, 0.7)
THREAD_LENGTHS = (34.0, 26.0, 40.0, 30.0, 24.0, 36.0)
# The dark shapes curled inside, as they show through the skin: a hook on the front wall, with a smaller one on each
# flank. Each is (arc across the wall in pixels from the front, height, the hook's radius, its turn in degrees at its
# start).
HOOKS = ((-1.0, 24.5, 4.0, 25.0), (-14.5, 18.0, 2.6, 100.0), (13.0, 27.0, 2.9, 200.0))


def sag(y):
    """How far down the body a height is, from 0 at its top shoulder to 1 at its floor."""
    return max(0.0, min(1.0, (CENTRE + 4.0 - y) / (CENTRE + 4.0 - FLOOR)))


def body_centre(y):
    """Where the middle of the body is at height y: the lower it hangs, the further it leans to one side, with a lump or
    two."""
    u = sag(y) ** 1.5
    # Above the middle the top slumps the other way, so that the whole bag is bent.
    crumple = max(0.0, (y - CENTRE - 3.0) / (SHOULDER - 3.0))
    return (
        LEAN[0] * u - 3.2 * crumple + 1.1 * math.sin(y * 0.33),
        LEAN[1] * u - 1.0 * crumple + 0.8 * math.sin(y * 0.21 + 1.0),
    )


def profile(y):
    """The radius of the sac's body at height y: flat above, bulging and heavy below, a little uneven."""
    reach = TOP_REACH if y >= CENTRE else BOTTOM_REACH
    base = RADIUS * math.sqrt(max(0.02, 1 - (abs(y - CENTRE) / reach) ** 2))
    return base * (1 + 0.1 * sag(y) + 0.07 * (kit.noise(y * 0.19, 0, 0, SEED + 2) - 0.5))


def hook_points(hook):
    """The spine of a curled dark shape, as (arc, height, thickness) points on the wall: a hook that thins towards its
    tip."""
    arc, height, size, start = hook
    points = []
    for k in range(14):
        along = k / 13
        angle = math.radians(start + 280 * along)
        points.append((arc + size * math.cos(angle), height + size * math.sin(angle), size * (0.42 - 0.25 * along)))
    return points


HOOK_POINTS = tuple(hook_points(hook) for hook in HOOKS)


def shadow(t):
    """How a dark shape curled behind the skin shows at a place on it: its depth, 1 over the shape and fading
    to 0 over a few pixels, its darkest core along the thick of its spine, and a rim where it presses the skin."""
    cx, cz = body_centre(t.y)
    arc = math.atan2(t.x - cx, -(t.z - cz)) * RADIUS
    near = min(math.hypot(arc - u, t.y - v) - r for points in HOOK_POINTS for u, v, r in points)
    # A ragged edge, as through cloudy skin.
    near += 1.6 * (kit.fbm(t.x * 0.35, t.y * 0.35, t.z * 0.35, SEED + 29, 2) - 0.5)
    depth = kit.smooth(1.0 - near / 3.4)
    core = kit.smooth(0.4 - near / 1.8)
    rim = kit.smooth(1.0 - abs(near - 1.5) / 0.9) * (1 - depth**4)
    return depth, core, rim


def surface_normal(t):
    """The way the sag the slabs stand for faces at a place on it."""
    cx, cz = body_centre(t.y)
    reach = TOP_REACH if t.y >= CENTRE else BOTTOM_REACH
    return kit.unit(((t.x - cx) / RADIUS**2, (t.y - CENTRE) / reach**2, (t.z - cz) / RADIUS**2))


def seam_x(y):
    return body_centre(y)[0] + 0.9 * math.sin(y * 0.28)


def seam_half(y):
    """Half the seam's gap at height y: shut at its top, widest low down."""
    return 0.25 + 1.5 * sag(y) ** 1.3


def paint_skin(t):
    nx, ny, nz = surface_normal(t)
    lit = max(0.0, min(1.0, 0.5 + 0.5 * (0.3 * nx + 0.85 * ny + 0.3 * nz)))
    mottle = kit.fbm(t.x * 0.16, t.y * 0.16, t.z * 0.16, SEED, 3)
    colour = kit.mix(SHADE, BONE, lit * 0.85 + (mottle - 0.5) * 0.35)
    speckle = kit.noise(t.x * 1.9, t.y * 1.9, t.z * 1.9, SEED + 3)
    if speckle > 0.8:
        colour = kit.mix(colour, (150, 160, 140), 0.45)
    # Slack folds across the heavy lower half.
    fold = math.sin(t.y * 1.15 + 3 * kit.noise(t.x * 0.15, t.y * 0.1, t.z * 0.15, SEED + 17))
    if fold > 0.82 and t.y < CENTRE + 3:
        colour = kit.mix(colour, SHADE, 0.55 * sag(t.y) + 0.2)
    glow = (*kit.mix(COLD, (235, 252, 240), lit), 70 + 50 * lit + 40 * mottle)
    patch = kit.fbm(t.x * 0.11 + 4, t.y * 0.11, t.z * 0.11, SEED + 8, 2)
    if patch > 0.68:
        colour = kit.mix(colour, (170, 206, 190), kit.smooth((patch - 0.68) / 0.05) * 0.5)
    depth, core, rim = shadow(t)
    # The shape behind the skin: dim and murky where it is deep, darkest only along its spine, with the skin's own
    # milk laid over it, and a pale rim where it presses.
    murk = kit.mix((84, 102, 98), (26, 36, 34), core)
    colour = kit.mix(colour, kit.mix(murk, BONE, 0.1), 0.9 * depth)
    colour = kit.mix(colour, (176, 214, 196), 0.45 * rim)
    if depth > 0.05 or rim > 0.05:
        glow = (*COLD, max(glow[3] * (1 - depth), 22 * depth + 95 * rim))
    near, far = kit.cells(t.x * 0.085, t.y * 0.085, t.z * 0.085, SEED + 11)
    web = far - near
    if web < 0.06:
        colour = kit.mix(colour, VEIN, 0.7)
        glow = (*COLD, (200 if web < 0.035 else 90) * (1 - 0.3 * depth))
    elif web < 0.11:
        colour = kit.mix(colour, (176, 206, 186), 0.4)
    if t.z < 0 and t.y > FLOOR - 0.4:
        gap = abs(t.x - seam_x(t.y))
        if t.y < CENTRE + 3 and gap < seam_half(t.y):
            return (*DARK, 255), (*COLD, 36)
        if t.y < CENTRE + 3 and gap < seam_half(t.y) + 0.7:
            return (*kit.mix(colour, VEIN, 0.5), 255), (*COLD, 200)
    if t.y < FLOOR + 0.3 and t.ny < -0.5:
        cx, cz = body_centre(FLOOR)
        spokes = abs(math.sin(math.atan2(t.x - cx, t.z - cz) * GILLS / 2))
        colour = kit.mix((88, 104, 100), (150, 172, 160), spokes * 0.6)
        glow = (*COLD, 40) if math.hypot(t.x - cx, t.z - cz) < 3.2 else None
    return (*colour, 255), glow


def paint_dimple(t):
    cx, cz = body_centre(t.y)
    r = math.hypot(t.x - cx, t.z - cz)
    colour = kit.mix((96, 112, 104), (170, 188, 170), r / 8)
    ring = abs(r - 4.2) < 0.55
    return (*kit.mix(colour, VEIN, 0.8 if ring else 0.0), 255), ((*COLD, 150) if ring else None)


def paint_ridge(t):
    glint = 0.5 + 0.5 * kit.noise(t.x * 0.4, t.y * 0.4, t.z * 0.4, SEED + 21)
    return (*kit.mix((170, 214, 188), VEIN, glint), 255), (*COLD, 50 + 50 * glint)


def paint_lip(t):
    ridge = 0.5 + 0.5 * math.sin(t.fv * 40)
    return (*kit.mix((150, 188, 166), VEIN, 0.3 + 0.5 * ridge), 255), (*COLD, 130 + 80 * ridge)


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


def thread_painter(k, tip):
    def paint(t):
        colour = kit.mix((190, 212, 190), (140, 164, 148), t.fv)
        if tip and t.fv > 0.6:
            return (*VEIN, 255), (*COLD, 255)
        # A glowing bead where one segment ends, so the thread reads as a string of them in the dark.
        if t.fv > 0.88:
            return (*VEIN, 255), (*COLD, 200 - 40 * k)
        return (*colour, 255), (*COLD, 70)

    return paint


def paint_ribbon(t):
    ripple = 0.5 + 0.3 * math.sin(t.fv * 17 + 2 * t.fu)
    if abs(t.fu - 0.5) > ripple * (1 - 0.45 * t.fv) * 0.9 or t.fv > 0.97:
        return (0, 0, 0, 0), None
    edge = abs(t.fu - 0.5) > ripple * (1 - 0.45 * t.fv) * 0.9 - 0.1
    return (*kit.mix((150, 188, 170), VEIN, 0.5 if edge else 0.15), 150), (*COLD, 150 if edge else 30)


def add_slabs(model):
    y = FLOOR
    top = CENTRE + SHOULDER
    while y < top:
        # Thinner slabs where the body closes in, so that the steps up the shoulder stay small.
        height = min(SLAB if y < CENTRE + 4.0 else 0.9, top - y)
        mid = y + height / 2
        for cube in kit.disc(body_centre(mid), y, height, profile(mid), 8, paint_skin, 2.0, 1.0):
            model.add("body", cube)
        y += height
    ring = body_centre(top)
    for i in range(8):
        heading = 45.0 * i
        centre = (ring[0] + 4.1 * math.sin(math.radians(heading)), top + 0.9, ring[1] + 4.1 * math.cos(math.radians(heading)))
        model.add(
            "body",
            Cube((centre[0] - 2.0, centre[1] - 1.1, centre[2] - 1.1), (4.0, 2.2, 2.2), paint_skin, (0.0, heading, 0.0), centre, 2.0, 0.5),
        )
    for cube in kit.disc(ring, top - 0.5, 0.9, 3.9, 4, paint_dimple, 2.0, 2.0):
        model.add("body", cube)


def add_veins(model):
    """Raised veins that wander: meridians that drift a few degrees at every step and break off now and then."""
    top = CENTRE + SHOULDER
    heights = [FLOOR + 1.6 + k * (top - FLOOR - 2.4) / 6 for k in range(7)]
    for m in range(MERIDIANS):
        for k in range(6):
            if kit.noise(m * 3.1, k * 1.7, 0, SEED + 41) > 0.82:
                continue
            drift = 7.0 * (kit.noise(m * 1.3, k * 0.6, 0, SEED + 43) - 0.5)
            heading = math.radians(360.0 / MERIDIANS * m + 22.5 + drift * k / 3)
            y0, y1 = heights[k], heights[k + 1]
            r0, r1 = profile(y0) + 0.4, profile(y1) + 0.4
            rm = (r0 + r1) / 2
            c0, c1 = body_centre(y0), body_centre(y1)
            cm = ((c0[0] + c1[0]) / 2, (c0[1] + c1[1]) / 2)
            centre = (cm[0] + rm * math.sin(heading), (y0 + y1) / 2, cm[1] + rm * math.cos(heading))
            axis = (c1[0] - c0[0] + math.sin(heading) * (r1 - r0), y1 - y0, c1[1] - c0[1] + math.cos(heading) * (r1 - r0))
            run = kit.length(axis)
            model.add(
                "body",
                Cube((centre[0] - 0.5, centre[1] - run / 2, centre[2] - 0.4), (1.0, run, 0.8), paint_ridge, kit.aim(axis), centre, 2.0),
            )


def add_gills(model):
    cx, cz = body_centre(FLOOR)
    for count, near, far, height, offset in ((GILLS, 2.8, 10.0, 4.6, 0.0), (INNER_GILLS, 1.2, 5.4, 3.0, 11.25)):
        for i in range(count):
            heading = math.radians(360.0 / count * i + offset)
            reach = (near + far) / 2
            centre = (cx + reach * math.sin(heading), FLOOR - height / 2 - 0.1, cz + reach * math.cos(heading))
            model.add(
                "gills",
                Cube.plane(centre, far - near, height, (0.0, math.degrees(heading) - 90.0, 0.0), gill_painter(far - near), 3.0),
            )


def add_lips(model):
    """Two lips either side of the seam, a hair apart at rest, that the animation draws apart."""
    y0, y1 = FLOOR + 2.0, CENTRE + 2.0
    ym = (y0 + y1) / 2
    for name, side in (("lip_l", -1), ("lip_r", 1)):
        x = seam_x(ym) + side * (seam_half(ym) + 1.0)
        reach = body_centre(ym)[1] - profile(ym) * 0.93
        model.bone(name, "body", (x, ym, reach))
        run = y1 - y0
        model.add(name, Cube((x - 0.7, ym - run / 2, reach - 1.5), (1.4, run, 2.4), paint_lip, (6.0, 0.0, -3.0 * side), (x, ym, reach), 3.0))


def add_threads(model):
    cx, cz = body_centre(FLOOR)
    for i in range(THREADS):
        heading = math.radians(60.0 * i + 20)
        radial = (math.sin(heading), 0.0, math.cos(heading))
        tangent = (math.cos(heading), 0.0, -math.sin(heading))
        swing = 1 if i % 2 == 0 else -1
        reach = (3.4, 7.8)[i % 2] + 0.6 * i
        foot = (cx + reach * radial[0], FLOOR - 0.2, cz + reach * radial[2])
        parent = "body"
        scale = THREAD_LENGTHS[i] / sum(THREAD_RUNS)
        for k, (width, run) in enumerate(zip(THREAD_WIDTHS, THREAD_RUNS)):
            run *= scale
            down = kit.bent_down(radial, tangent, (7.0, 22.0, 42.0)[k], (0.0, 50.0, 115.0)[k] * swing)
            end = kit.add(foot, kit.scale(down, run))
            centre = kit.scale(kit.add(foot, end), 0.5)
            name = f"thread_{i}_{'abc'[k]}"
            model.bone(name, parent, foot)
            turn = kit.aim(kit.scale(down, -1))
            model.add(name, Cube.strut(centre, run, width, turn, thread_painter(k, k == 2)))
            if k < 2:
                model.add(name, Cube.plane(centre, 5.0 - 2.0 * k, run, turn, paint_ribbon, 3.0))
            parent, foot = name, end


def build():
    model = kit.Model(IDENT, SIZE, (4, 4, 0.5), engine_uv=True)
    model.bone("root", None, (0, 0, 0))
    model.bone("body", "root", (0, CENTRE, 0))
    add_slabs(model)
    add_veins(model)
    add_lips(model)
    model.bone("gills", "body", (body_centre(FLOOR)[0], FLOOR, body_centre(FLOOR)[1]))
    add_gills(model)
    add_threads(model)
    return model


def pulse(a):
    """One slow, uneven beat: a swell that comes fast and ebbs slowly, with a second smaller one behind it."""
    return math.sin(a) + 0.5 * math.sin(2 * a + 1.2) + 0.2 * math.sin(3 * a)


def animations(period, swell, flutter, sway):
    steps = 16
    bones = {
        "body": {
            "scale": kit.loop(
                period,
                lambda a: (1 + swell * pulse(a), 1 - 0.7 * swell * pulse(a + 0.5), 1 + 0.8 * swell * pulse(a + 1.3)),
                steps=steps,
            )
        },
        "gills": {"rotation": kit.loop(period, lambda a: (flutter * math.sin(2 * a), 0, flutter * math.sin(2 * a + 1.0)), 0.1, steps=steps)},
        # The seam parts as the body swells and shuts as it ebbs.
        "lip_l": {"position": kit.loop(period, lambda a: (-0.9 * max(0.0, pulse(a - 0.6)), 0, 0), steps=steps)},
        "lip_r": {"position": kit.loop(period, lambda a: (0.9 * max(0.0, pulse(a - 0.6)), 0, 0), steps=steps)},
    }
    for i in range(THREADS):
        for k in range(3):
            # Each segment trails the one above it, so the fringe drifts after the body.
            bones[f"thread_{i}_{'abc'[k]}"] = {
                "rotation": kit.loop(
                    period,
                    lambda a, k=k: (sway * (1 + k) * math.sin(a), 0, sway * (1 + k) * math.sin(a + 1.7) + 0.4 * sway * math.sin(2 * a)),
                    i / THREADS - 0.1 * k - 0.1,
                    steps=steps,
                )
            }
    return kit.animation(period, bones)


def files():
    model = build()
    return model, kit.animations_json(IDENT, animations(5.3, 0.03, 5.0, 3.0), animations(2.2, 0.06, 9.0, 5.0))
