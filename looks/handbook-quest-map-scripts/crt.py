"""The colony terminals' CRT look: crt.json and panel.json colours, the pack's own panel sprites, the terminal font (unscii-8)."""
from mc import *

SPR = ASSETS + "/textures/gui/sprites/panel/"
BG = rgba(CRT["backgroundColor"])
PHOS = rgba(CRT["phosphorColor"])
DIM = rgba(CRT["dimColor"])
DIM_TEXT = rgba(PANEL["dimTextColor"])
HOVER_CRT = rgba(CRT["hoverFillColor"])
GLOW = rgba(CRT["glowColor"])
BLOOM = rgba(CRT["bloomColor"])
SCAN = rgba(CRT["scanlineColor"])
LABEL = rgba(PANEL["buttonLabelColor"])
LABEL_HOT = rgba(PANEL["buttonLabelHotColor"])
LABEL_OFF = rgba(PANEL["buttonLabelOffColor"])
UNDER = rgba(PANEL["buttonUnderColor"])

# font/terminal.json: unscii-8 at size 6.0 GUI px. At GUI scale 3 that is 18 screen px, so it is drawn at screen resolution,
# glyph pixels 2.25 screen px, as the game draws it.
TERM = ImageFont.truetype(HERE + "/font/unscii-8.ttf", 18)
TERM_ADV = 6  # GUI px per character


def sprite(name):
    return Image.open(SPR + name + ".png").convert("RGBA")


def nine_slice(img, w, h, l, t, r, b):
    """Minecraft's nine_slice GUI scaling: corners as they are, edges and middle tiled."""
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    sw, sh = img.size
    cw, ch = sw - l - r, sh - t - b

    def tile(src, x, y, tw, th):
        if tw <= 0 or th <= 0 or src.size[0] == 0 or src.size[1] == 0:
            return
        for yy in range(0, th, src.size[1]):
            for xx in range(0, tw, src.size[0]):
                piece = src.crop((0, 0, min(src.size[0], tw - xx), min(src.size[1], th - yy)))
                out.paste(piece, (x + xx, y + yy))

    out.paste(img.crop((0, 0, l, t)), (0, 0))
    out.paste(img.crop((sw - r, 0, sw, t)), (w - r, 0))
    out.paste(img.crop((0, sh - b, l, sh)), (0, h - b))
    out.paste(img.crop((sw - r, sh - b, sw, sh)), (w - r, h - b))
    tile(img.crop((l, 0, sw - r, t)), l, 0, w - l - r, t)
    tile(img.crop((l, sh - b, sw - r, sh)), l, h - b, w - l - r, b)
    tile(img.crop((0, t, l, sh - b)), 0, t, l, h - t - b)
    tile(img.crop((sw - r, t, sw, sh - b)), w - r, t, r, h - t - b)
    tile(img.crop((l, t, sw - r, sh - b)), l, t, w - l - r, h - t - b)
    return out


# A slim bezel cut from the pack's own frame sprite (panel/frame.png): its rounded glass ring and a few pixels of the plate
# round it, corners as drawn, straight runs taken from clean one-pixel slices (clear of the rivets, switches and rail).
_FRAME = sprite("frame")
BEZEL_IN = (12, 11, 10, 11)  # glass inset: left, top, right, bottom


def bezel(w, h):
    f = _FRAME
    C = 24
    tl = f.crop((36, 3, 36 + C, 3 + C))
    tr = f.crop((114, 3, 114 + C, 3 + C))
    bl = f.crop((36, 77, 36 + C, 77 + C))
    br = f.crop((114, 77, 114 + C, 77 + C))
    top = f.crop((62, 3, 63, 3 + C))
    bot = f.crop((62, 77, 63, 77 + C))
    left = f.crop((36, 80, 36 + C, 81))
    right = f.crop((114, 80, 114 + C, 81))
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    for x in range(C, w - C):
        out.paste(top, (x, 0))
        out.paste(bot, (x, h - C))
    for y in range(C, h - C):
        out.paste(left, (0, y))
        out.paste(right, (w - C, y))
    out.paste(tl, (0, 0))
    out.paste(tr, (w - C, 0))
    out.paste(bl, (0, h - C))
    out.paste(br, (w - C, h - C))
    return out


def backdrop_crt(cv, x0, y0, x1, y1):
    """CrtDraw.backdrop: near-black, with a phosphor bloom fading in from the top and bottom edges (one flat fill per row)."""
    cv.fill(x0, y0, x1, y1, BG)
    bh = min(CRT["bloomHeight"], (y1 - y0) // 2)
    for i in range(bh):
        a = round(BLOOM[3] * (1 - (i + 0.5) / bh))
        cv.fill(x0, y0 + i, x1, y0 + i + 1, with_alpha(BLOOM, a))
        cv.fill(x0, y1 - 1 - i, x1, y1 - i, with_alpha(BLOOM, a))


def scanlines(cv, x0, y0, x1, y1):
    y = y0
    while y < y1:
        cv.fill(x0, y, x1, y + 1, SCAN)
        y += CRT["scanlineSpacing"]


def glass(cv, x0, y0, x1, y1):
    cv.paste(nine_slice(sprite("glass"), x1 - x0, y1 - y0, 10, 10, 10, 10), x0, y0)


def crt_panel_back(cv, x, y, w, h):
    """Behind the content: the phosphor backdrop in the glass, then the bezel round it."""
    l, t, r, b = BEZEL_IN
    backdrop_crt(cv, x + l, y + t, x + w - r, y + h - b)
    cv.paste(bezel(w, h), x, y)
    return x + l, y + t, x + w - r, y + h - b


def crt_panel_front(cv, x, y, w, h):
    """Over the content: the glass sprite and the scanlines, inside the glass only."""
    l, t, r, b = BEZEL_IN
    glass(cv, x + l, y + t, x + w - r, y + h - b)
    scanlines(cv, x + l, y + t, x + w - r, y + h - b)


def _term_mask(s):
    m = Image.new("1", (len(s) * 18 + 12, 30), 0)
    d = ImageDraw.Draw(m)
    d.fontmode = "1"
    d.text((0, 21), s, font=TERM, fill=1, anchor="ls")
    return m


def term_text(cv, x, y, s, color, glow=True):
    """Terminal text at GUI (x, y): CrtDraw.glowText draws a halo copy one GUI px out each way, then the text."""
    m = _term_mask(s)
    X, Y = x * cv.s, y * cv.s - 0
    if glow:
        for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            cv.big.paste(tuple(GLOW[:3]), (X + dx * cv.s, Y + dy * cv.s), _alpha_mask(m, GLOW[3]))
    cv.big.paste(tuple(color[:3]), (X, Y), _alpha_mask(m, color[3] if len(color) == 4 else 255))
    return len(s) * TERM_ADV


def _alpha_mask(m, a):
    return m.convert("L").point(lambda v: a if v else 0)


def term_width(s):
    return len(s) * TERM_ADV


def header(cv, x, y, right, title):
    """CrtDraw.header: the title in bright phosphor, the dim rule under it."""
    term_text(cv, x, y, title, PHOS)
    ry = y + LINE + CRT["headerRuleGap"]
    cv.fill(x - CRT["headerRuleInset"], ry, right + CRT["headerRuleInset"], ry + 1, DIM)
    return ry + 1


def panel_button(cv, x, y, w, h, label, active=True, lit=False, pip=True):
    """CrtDraw.panelButtonFace with the pack's button and pip sprites, and the label in the panel's label colours."""
    cv.fill(x, y, x + w, y + h, UNDER)
    name = "button_off" if not active else "button_hover" if lit else "button"
    cv.paste(nine_slice(sprite(name), w, h, 3, 3, 3, 3), x, y)
    lx = x + PANEL["buttonPad"]
    if pip and PANEL["pipSize"] > 0:
        pn = "pip_off" if not active else "pip_hot" if lit else "pip"
        cv.paste(sprite(pn), x + PANEL["pipX"], y + (h - PANEL["pipSize"]) // 2 + PANEL["pipY"])
        lx = x + PANEL["pipX"] + PANEL["pipSize"] + PANEL["buttonPad"]
    ink = LABEL_OFF if not active else LABEL_HOT if lit else LABEL
    term_text(cv, lx, y + (h - LINE) // 2 + CRT["buttonLabelOffset"], label, ink, glow=False)


def crt_slot(cv, x, y, lit=False):
    if lit:
        cv.fill(x, y, x + 18, y + 18, HOVER_CRT)
    cv.border(x, y, x + 18, y + 18, PHOS if lit else DIM)
