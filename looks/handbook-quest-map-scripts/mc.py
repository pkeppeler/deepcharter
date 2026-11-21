"""Pixel-exact drawing of Deep Charter's handbook look at native GUI resolution (427x240 = 1280x720 at GUI scale 3).

Every size and colour comes from the theme files in the repo. No anti-aliasing: integer fills, 1-bit glyphs, nearest-neighbour.
"""
import json
import math
import os

from PIL import Image, ImageDraw, ImageFont, ImageFilter

REPO = "/home/user/deepcharter"
HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = REPO + "/src/main/resources/assets/deepcharter"
T = json.load(open(ASSETS + "/theme/handbook.json"))
CRT = json.load(open(ASSETS + "/theme/crt.json"))
PANEL = json.load(open(ASSETS + "/theme/panel.json"))

W, H = 427, 240
SCALE = 3


def rgba(s):
    s = s.lstrip("#")
    if len(s) == 8:
        return (int(s[2:4], 16), int(s[4:6], 16), int(s[6:8], 16), int(s[0:2], 16))
    return (int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16), 255)


def with_alpha(c, a):
    return (c[0], c[1], c[2], a)


PAPER = rgba(T["paperColor"])
EDGE = rgba(T["paperEdgeColor"])
BINDING = rgba(T["bindingColor"])
INK = rgba(T["inkColor"])
MARGIN = rgba(T["marginInkColor"])
STAMP = rgba(T["stampColor"])
REDACT = rgba(T["redactionColor"])
FAINT = rgba(T["faintInkColor"])
HOVER = rgba(T["hoverFillColor"])
DISABLED = rgba(T["disabledInkColor"])
SHADOW = rgba(T["shadowColor"])
RULE = with_alpha(EDGE, T["ruleAlpha"])
MARGIN_RULE = with_alpha(MARGIN, T["marginRuleAlpha"])

LINE = 9  # font.lineHeight


# ---------------------------------------------------------------- font


class PixelFont:
    """Monocraft at its native 9 px em: 7 px caps on a 1 px grid, the same glyph grid as Minecraft's font.

    Glyphs are trimmed to their ink and advance by ink width + 1, as Minecraft's own bitmap font does; a space is 4.
    """

    def __init__(self, path, size=9, index=0, space=4):
        self.font = ImageFont.truetype(path, size, index=index)
        self.space = space
        self.cache = {}

    def glyph(self, ch):
        if ch in self.cache:
            return self.cache[ch]
        if ch == " ":
            g = (self.space, [])
        else:
            im = Image.new("1", (32, 32), 0)
            d = ImageDraw.Draw(im)
            d.fontmode = "1"
            base = 20
            d.text((6, base), ch, font=self.font, fill=1, anchor="ls")
            box = im.getbbox()
            if box is None:
                g = (self.space, [])
            else:
                px = im.load()
                pix = []
                for y in range(box[1], box[3]):
                    for x in range(box[0], box[2]):
                        if px[x, y]:
                            pix.append((x - box[0], y - (base - 7)))
                g = (box[2] - box[0] + 1, pix)
        self.cache[ch] = g
        return g

    def width(self, text, bold=False):
        return sum(self.glyph(c)[0] + (1 if bold else 0) for c in text)

    def wrap(self, text, width, bold=False):
        lines = []
        for para in text.split("\n"):
            line = ""
            for word in para.split(" "):
                trial = word if not line else line + " " + word
                if self.width(trial, bold) - 1 <= width or not line:
                    line = trial
                else:
                    lines.append(line)
                    line = word
            lines.append(line)
        return lines


MONO = PixelFont(HERE + "/font/Monocraft.ttc", 9, 0)


def italic_shift(row):
    # Vanilla italic leans each glyph 1 px right at the top and 1 px left at the bottom (1 - 0.25 * y over 8 rows).
    if row <= 1:
        return 1
    if row >= 6:
        return -1
    return 0


# ---------------------------------------------------------------- canvas


class Canvas:
    """Coordinates are GUI pixels. Every GUI pixel is a 3x3 block of the 1280x720 output (nearest-neighbour), so fills, lines,
    sprites and upright text are exactly the native 427x240 drawing upscaled x3. Two things the game rasterises at screen
    resolution are done at screen resolution too: the italic lean of the ink (vanilla shears each glyph per screen row) and
    the rotation of a rubber stamp."""

    def __init__(self, w=W, h=H, s=SCALE, rgba=False):
        self.w, self.h, self.s = w, h, s
        mode = "RGBA" if rgba else "RGB"
        self.big = Image.new(mode, (w * s, h * s), (0, 0, 0, 0) if rgba else (0, 0, 0))
        self.d = ImageDraw.Draw(self.big)

    # screen-pixel primitives
    def _rect(self, X0, Y0, X1, Y1, c):
        """Screen pixels [X0, X1) x [Y0, Y1), colour with GL-style alpha blending."""
        X0, Y0 = max(0, X0), max(0, Y0)
        X1, Y1 = min(self.big.size[0], X1), min(self.big.size[1], Y1)
        if X1 <= X0 or Y1 <= Y0:
            return
        if len(c) == 3 or c[3] == 255 or self.big.mode == "RGBA":
            self.d.rectangle((X0, Y0, X1 - 1, Y1 - 1), fill=tuple(c[:3]) + ((c[3],) if self.big.mode == "RGBA" else ()))
        elif c[3] > 0:
            mask = Image.new("L", (X1 - X0, Y1 - Y0), c[3])
            self.big.paste(tuple(c[:3]), (X0, Y0, X1, Y1), mask)

    # GUI-pixel primitives
    def put(self, x, y, c):
        s = self.s
        self._rect(x * s, y * s, x * s + s, y * s + s, c)

    def fill(self, x0, y0, x1, y1, c):
        """GuiGraphics.fill: [x0, x1) x [y0, y1) in GUI pixels."""
        s = self.s
        self._rect(x0 * s, y0 * s, x1 * s, y1 * s, c)

    def border(self, x0, y0, x1, y1, c):
        self.fill(x0, y0, x1, y0 + 1, c)
        self.fill(x0, y1 - 1, x1, y1, c)
        self.fill(x0, y0 + 1, x0 + 1, y1 - 1, c)
        self.fill(x1 - 1, y0 + 1, x1, y1 - 1, c)

    def line(self, x0, y0, x1, y1, c, dash=None):
        """Bresenham in GUI pixels. dash=(on, off) for a broken pencil line."""
        dx, dy = abs(x1 - x0), -abs(y1 - y0)
        sx, sy = (1 if x0 < x1 else -1), (1 if y0 < y1 else -1)
        err = dx + dy
        n = 0
        while True:
            if dash is None or (n % (dash[0] + dash[1])) < dash[0]:
                self.put(x0, y0, c)
            n += 1
            if x0 == x1 and y0 == y1:
                break
            e2 = 2 * err
            if e2 >= dy:
                err += dy
                x0 += sx
            if e2 <= dx:
                err += dx
                y0 += sy

    def poly(self, pts, c, dash=None):
        for a, b in zip(pts, pts[1:]):
            self.line(a[0], a[1], b[0], b[1], c, dash)

    def ellipse(self, x0, y0, x1, y1, c, start=0, end=360):
        """A 1 GUI px ellipse (or arc, degrees clockwise from 3 o'clock) in [x0, x1] x [y0, y1], from a 1-bit mask."""
        m = Image.new("1", (x1 - x0 + 1, y1 - y0 + 1), 0)
        d = ImageDraw.Draw(m)
        if (start, end) == (0, 360):
            d.ellipse((0, 0, x1 - x0, y1 - y0), outline=1, width=1)
        else:
            d.arc((0, 0, x1 - x0, y1 - y0), start, end, fill=1, width=1)
        mp = m.load()
        for j in range(m.size[1]):
            for i in range(m.size[0]):
                if mp[i, j]:
                    self.put(x0 + i, y0 + j, c)

    def paste(self, img, x, y):
        """A native-size RGBA sprite (an item icon) at GUI (x, y), each texel a 3x3 block."""
        s = self.s
        big = img.convert("RGBA").resize((img.size[0] * s, img.size[1] * s), Image.NEAREST)
        if self.big.mode == "RGBA":
            self.big.alpha_composite(big, (x * s, y * s))
        else:
            self.big.paste(big, (x * s, y * s), big)

    def paste_screen(self, img, X, Y):
        """An RGBA image already at screen resolution, at screen pixel (X, Y)."""
        if self.big.mode == "RGBA":
            self.big.alpha_composite(img, (X, Y))
        else:
            self.big.paste(img, (X, Y), img)

    # text: Monocraft glyph pixels, 3x3 each; italic leans per screen row as vanilla does (1 - 0.25 * y over the 8 rows)
    def text(self, x, y, txt, c, italic=True, bold=False, font=MONO):
        s = self.s
        cx = x
        for ch in txt:
            adv, pix = font.glyph(ch)
            for gx, gy in pix:
                for b in ((0, 1) if bold else (0,)):
                    if not italic:
                        self._rect((cx + gx + b) * s, (y + gy) * s, (cx + gx + b + 1) * s, (y + gy + 1) * s, c)
                        continue
                    for r in range(s):
                        yy = gy + (r + 0.5) / s  # GUI y inside the glyph
                        shift = (1.0 - 0.25 * yy) * s
                        left = (cx + gx + b) * s + shift
                        X0 = math.ceil(left - 0.5)
                        X1 = math.ceil(left + s - 0.5)
                        Y = (y + gy) * s + r
                        self._rect(X0, Y, X1, Y + 1, c)
            cx += adv + (1 if bold else 0)
        return cx - x

    def centered(self, cx, y, txt, c, italic=True, bold=False):
        w = MONO.width(txt, bold)
        self.text(cx - w // 2, y, txt, c, italic, bold)
        return y + LINE + 2

    def wrapped(self, x, y, txt, width, c, italic=True, pitch=LINE + 1):
        for ln in MONO.wrap(txt, width):
            self.text(x, y, ln, c, italic)
            y += pitch
        return y

    # output
    def save(self, path):
        out = self.big.crop((0, 0, 1280, 720))
        out.save(path)
        return out


# ---------------------------------------------------------------- the handbook's parts (PaperDraw, PaperButton)


def sheet(cv, left, top, width, height, binding=True, rules=True, shadow=True):
    """PaperDraw.sheet: drop shadow, paper, faint rules every 10 px from the third, binding band, 1 px edge."""
    s = T["shadowOffset"]
    if shadow:
        cv.fill(left + s, top + s, left + width + s, top + height + s, SHADOW)
    cv.fill(left, top, left + width, top + height, PAPER)
    bw = T["bindingWidth"] if binding else 0
    if rules:
        y = top + T["ruleSpacing"] * 2
        while y < top + height - T["ruleSpacing"]:
            cv.fill(left + bw, y, left + width - 1, y + 1, RULE)
            y += T["ruleSpacing"]
    if binding:
        cv.fill(left, top, left + bw, top + height, BINDING)
    cv.border(left, top, left + width, top + height, EDGE)


def button(cv, x, y, w, h, label, selected=False, hover=False, active=True, joined=None):
    """PaperButton: a paper slip with an italic ink label. joined='bottom'|'left' leaves that edge open onto the sheet."""
    cv.fill(x, y, x + w, y + h, PAPER if selected else HOVER if (hover and active) else EDGE)
    cv.border(x, y, x + w, y + h, EDGE)
    if joined == "bottom":
        cv.fill(x + 1, y + h - 1, x + w - 1, y + h + 1, PAPER)
    elif joined == "left":
        cv.fill(x - 1, y + 1, x + 1, y + h - 1, PAPER)
    ink = INK if active else DISABLED
    tw = MONO.width(label)
    cv.text(x + (w - tw) // 2, y + (h - LINE) // 2 + 1, label, ink)


def stamp(cv, text, cx, cy, deg, color=STAMP):
    """PaperDraw.stamp: bold capitals in a double frame, centred on (cx, cy) and turned by deg (clockwise on screen).
    Drawn upright on GUI pixels, then turned at screen resolution, nearest-neighbour, as the pose rotation does in game."""
    tw = MONO.width(text, bold=True)
    hw = tw // 2 + 5
    hh = LINE // 2 + 3
    S = 2 * max(hw, hh) + 8
    lay = Canvas(S, S, rgba=True)
    o = S // 2
    lay.border(o - hw, o - hh, o + hw, o + hh, color)
    lay.border(o - hw + 2, o - hh + 2, o + hw - 2, o + hh - 2, color)
    lay.text(o - tw // 2, o - LINE // 2 + 1, text, color, italic=False, bold=True)
    rot = lay.big.rotate(-deg, resample=Image.NEAREST, center=(o * cv.s, o * cv.s))
    cv.paste_screen(rot, (cx - o) * cv.s, (cy - o) * cv.s)


def redaction(cv, x, y, w, h):
    cv.fill(x, y, x + w, y + h, REDACT)


def margin_note(cv, x, y, width, text, rule_bottom, rule=True):
    if rule:
        cv.fill(x - 4, y - 2, x - 3, rule_bottom, MARGIN_RULE)
    return cv.wrapped(x, y, text, width, MARGIN)


def card(cv, x, y, w, h):
    """An index card laid on the page: the same paper, edge and drop shadow, no binding, one red head rule."""
    s = T["shadowOffset"]
    cv.fill(x + s, y + s, x + w + s, y + h + s, SHADOW)
    cv.fill(x, y, x + w, y + h, PAPER)
    yy = y + 20
    while yy < y + h - 4:
        cv.fill(x + 1, yy, x + w - 1, yy + 1, RULE)
        yy += T["ruleSpacing"]
    cv.border(x, y, x + w, y + h, EDGE)


def arrow(cv, pts, c, head=True, dash=None):
    cv.poly(pts, c, dash)
    if head:
        (x0, y0), (x1, y1) = pts[-2], pts[-1]
        if x1 > x0:
            cv.put(x1 - 1, y1 - 1, c); cv.put(x1 - 1, y1 + 1, c); cv.put(x1 - 2, y1 - 2, c); cv.put(x1 - 2, y1 + 2, c)
        elif x1 < x0:
            cv.put(x1 + 1, y1 - 1, c); cv.put(x1 + 1, y1 + 1, c); cv.put(x1 + 2, y1 - 2, c); cv.put(x1 + 2, y1 + 2, c)
        elif y1 > y0:
            cv.put(x1 - 1, y1 - 1, c); cv.put(x1 + 1, y1 - 1, c); cv.put(x1 - 2, y1 - 2, c); cv.put(x1 + 2, y1 - 2, c)
        else:
            cv.put(x1 - 1, y1 + 1, c); cv.put(x1 + 1, y1 + 1, c); cv.put(x1 - 2, y1 + 2, c); cv.put(x1 + 2, y1 + 2, c)


# ---------------------------------------------------------------- the world behind the screen


def backdrop(cv, seed=3):
    """Our own regolith blocks, out of focus, under a flat dark tint (vanilla darkens and blurs the world behind a screen)."""
    tex = ASSETS + "/textures/block/"
    names = {
        "rock": ["regolith_rock_0", "regolith_rock_1"],
        "packed": ["regolith_packed_0", "regolith_packed_1"],
        "rust": ["regolith_0", "regolith_1", "regolith_2", "regolith_3"],
        "ochre": ["ochre_regolith_0", "ochre_regolith_1", "ochre_regolith_2"],
    }
    imgs = {k: [Image.open(tex + n + ".png").convert("RGB") for n in v] for k, v in names.items()}
    world = Image.new("RGB", (W, H))
    B = 40  # one block, near the camera
    rows = [
        "rock rock packed rock rock packed rock rock packed rock rock",
        "packed rust rust packed rust rust rust packed rust rust packed",
        "rust rust ochre rust rust packed rust ochre rust rust rust",
        "rust ochre ochre rust ochre rust rust ochre ochre rust rust",
        "ochre ochre rust ochre ochre ochre rust ochre ochre ochre rust",
        "ochre ochre ochre ochre rust ochre ochre ochre ochre rust ochre",
        "ochre ochre ochre ochre ochre ochre ochre ochre ochre ochre ochre",
    ]
    k = seed
    for j, row in enumerate(rows):
        for i, kind in enumerate(row.split()):
            k = (k * 1103515245 + 12345) & 0x7FFFFFFF
            t = imgs[kind][k % len(imgs[kind])].resize((B, B), Image.NEAREST)
            world.paste(t, (i * B - 12, j * B - 22))
    # shade: the upper wall is darker, the floor catches lamp light
    shade = Image.new("L", (W, H))
    sp = shade.load()
    for y in range(H):
        v = int(150 + 70 * (y / H))
        for x in range(W):
            sp[x, y] = v
    world = Image.composite(world, Image.new("RGB", (W, H), (0, 0, 0)), shade)
    world = world.filter(ImageFilter.BoxBlur(4)).filter(ImageFilter.BoxBlur(3))
    cv.big.paste(world.resize((W * cv.s, H * cv.s), Image.NEAREST), (0, 0))
    cv.fill(0, 0, W, H, (16, 16, 16, 0x70))


# ---------------------------------------------------------------- items


ITEM_DIR = ASSETS + "/textures/item/"


def item(name):
    return Image.open(ITEM_DIR + name + ".png").convert("RGBA")


def _art(rows, pal):
    im = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    p = im.load()
    for y, r in enumerate(rows):
        for x, ch in enumerate(r):
            if ch != ".":
                p[x, y] = pal[ch]
    return im


INGOT = [
    "................",
    "................",
    "................",
    "................",
    "......oooooooo..",
    ".....ohhhhhhhho.",
    "....ohhhhhhhhmo.",
    "...ohhhhhhhhmmo.",
    "..oooooooooommo.",
    "..offfffffffomo.",
    "..offfffffffmmo.",
    "..odddddddddmo..",
    "..oooooooooooo..",
    "................",
    "................",
    "................",
]


def iron_ingot():
    # stand-in, not Mojang's art: a pale grey bar
    return _art(INGOT, {"o": (58, 58, 64, 255), "h": (238, 238, 238, 255), "m": (150, 150, 156, 255),
                        "f": (206, 206, 210, 255), "d": (168, 168, 174, 255)})


def copper_ingot():
    return _art(INGOT, {"o": (92, 42, 26, 255), "h": (246, 160, 112, 255), "m": (170, 82, 52, 255),
                        "f": (226, 124, 78, 255), "d": (190, 98, 62, 255)})


def redstone():
    rows = [
        "................",
        "................",
        "................",
        "................",
        "................",
        "......r.........",
        ".........r......",
        "....r.rRr...r...",
        "......RRRr......",
        "...r.rRHRRr.r...",
        ".....RRRRRRr....",
        "..r.rRRHRRRRr...",
        "...dRRRRRRRRRd..",
        "...ddddddddddd..",
        "................",
        "................",
    ]
    return _art(rows, {"r": (150, 0, 0, 255), "R": (214, 24, 18, 255), "H": (255, 92, 72, 255),
                       "d": (102, 0, 0, 255)})


def icon(name):
    if name == "iron_ingot":
        return iron_ingot()
    if name == "copper_ingot":
        return copper_ingot()
    if name == "redstone":
        return redstone()
    return item(name)
