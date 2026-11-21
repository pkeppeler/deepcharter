"""The real handbook screen's frame (HandbookScreen.init at 427x240) and the quest-map pieces the mockups share."""
from mc import *

# HandbookScreen.init at a 427x240 GUI
PW = min(T["paperWidth"], W - 12)                          # 320
PH = max(100, min(H - T["tabHeight"] - 16, T["paperMaxHeight"]))  # 200
PL = (W - PW) // 2                                          # 53
PT = (H - PH + T["tabHeight"]) // 2                         # 26
TL = PL + T["bindingWidth"] + T["padding"]                  # 69
TR = PL + PW - T["padding"]                                 # 365 (no margin column)
TR_MARGIN = TR - (T["marginWidth"] + T["marginGap"])        # 291 (with the margin column)
ML = TR_MARGIN + T["marginGap"]                             # 295
TAB_Y = PT - T["tabHeight"] + 1                             # 14
TAB_X = PL + T["bindingWidth"] + 4                          # 65
BTN_Y = PT + PH - T["buttonHeight"] - 4                     # 209
BOTTOM = PT + PH - T["buttonHeight"] - 10                   # 203
CENTER_X = PL + T["bindingWidth"] + (PW - T["bindingWidth"]) // 2  # 217

CH1_DIRECTIVES = [
    "Gather wood from a salvage crate or a fungal stalk.",
    "Craft a crafting table.",
    "Craft stone tools.",
    "Gather scrap metal from a salvage crate.",
    "Smelt scrap into iron.",
    "Craft a scanner unit. Keep it: you fit it to your Mole later.",
]
CH2_DIRECTIVES = ["Repair the fuel pump.", "Repair the ore processor.", "Repair the upgrade terminal."]
CH1_TITLE = "Welcome to Prosperity"
CH2_TITLE = "Bringing Prosperity Back Online"
CH1_MARGIN = "Every terminal in town went dark tonight except the contract terminal. Think about that. —R."
REDACTIONS_MARGIN = "They'll unredact these when you're too deep to quit. —R."


def top_tabs(cv, x=TAB_X, y=TAB_Y, extra=()):
    """The Handbook and Notes tabs, as HandbookScreen.init places them, Handbook selected."""
    button(cv, x, y, T["tabWidth"], T["tabHeight"], "Handbook", selected=True)
    button(cv, x + T["tabWidth"] + 2, y, T["tabWidth"], T["tabHeight"], "Notes")


def footer(cv, left=TL, right=TR, y=BTN_Y, page="8 / 22", back_active=True):
    button(cv, left, y, T["buttonWidth"], T["buttonHeight"], "< Back", active=back_active)
    nx = right - T["buttonWidth"]
    button(cv, nx, y, T["buttonWidth"], T["buttonHeight"], "Next >")
    mid = (nx + left + T["buttonWidth"]) // 2
    w = MONO.width(page)
    cv.text(mid - w // 2, y + 3, page, FAINT)


def clip_lines(text, width, max_lines=2):
    lines = MONO.wrap(text, width)
    if len(lines) <= max_lines:
        return lines
    rest = " ".join(lines[max_lines - 1:])
    cut = rest
    while MONO.width(cut + "...") - 1 > width and cut:
        cut = cut[:-1]
    return lines[: max_lines - 1] + [cut.rstrip() + "..."]


def step_box(cv, x, y, w, text, color=INK, max_lines=2, h=None):
    """A flowchart step: a 1 px ink box with the directive text, 2 px in."""
    lines = clip_lines(text, w - 6, max_lines)
    if h is None:
        h = len(lines) * (LINE + 1) + 2
    cv.border(x, y, x + w, y + h, color)
    for i, ln in enumerate(lines):
        cv.text(x + 3, y + 2 + i * (LINE + 1), ln, color)
    return h


def ring(cv, x0, y0, x1, y1, color=INK):
    """A hand-drawn circle round a step: two loose ellipses, the second offset so the ends overshoot."""
    cv.ellipse(x0, y0, x1, y1, color, 200, 520)
    cv.ellipse(x0 + 2, y0 - 1, x1 + 1, y1 - 1, color, 150, 250)


def tally_icon(cv, x, y, name, have, need):
    cv.paste(icon(name), x, y)
    t = f"{have}/{need}"
    cv.text(x + 18, y + 5, t, INK if have < need else FAINT)
    if have >= need:
        cv.fill(x + 17, y + 8, x + 18 + MONO.width(t), y + 9, FAINT)  # struck through: done


def link(cv, x, y, text):
    w = cv.text(x, y, text, INK)
    cv.fill(x, y + 9, x + w - 1, y + 10, INK)
    return w


def loop(cv, x0, y0, x1, y1, color=INK):
    """The current step circled in ink: a loose rounded loop round the box, the pen running on past where it closed."""
    m = Image.new("1", (x1 - x0 + 1, y1 - y0 + 1), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, x1 - x0, y1 - y0), radius=9, outline=1, width=1)
    mp = m.load()
    for j in range(m.size[1]):
        for i in range(m.size[0]):
            if mp[i, j]:
                cv.put(x0 + i, y0 + j, color)
    # the closing stroke overshoots at the top-right
    cv.line(x1 - 14, y0 + 1, x1 - 6, y0 - 1, color)
    cv.line(x1 - 6, y0 - 1, x1 - 2, y0 - 2, color)


def page_sheet(cv, left, top, width, height, bind="left", bind_w=T["bindingWidth"], shadow=False):
    """PaperDraw.sheet with the binding band on either edge: a right-hand page binds on its left, a left-hand page on its right."""
    s = T["shadowOffset"]
    if shadow:
        cv.fill(left + s, top + s, left + width + s, top + height + s, SHADOW)
    cv.fill(left, top, left + width, top + height, PAPER)
    x0 = left + (bind_w if bind == "left" else 1)
    x1 = left + width - (bind_w if bind == "right" else 1)
    y = top + T["ruleSpacing"] * 2
    while y < top + height - T["ruleSpacing"]:
        cv.fill(x0, y, x1, y + 1, RULE)
        y += T["ruleSpacing"]
    if bind == "left":
        cv.fill(left, top, left + bind_w, top + height, BINDING)
    else:
        cv.fill(left + width - bind_w, top, left + width, top + height, BINDING)
    cv.border(left, top, left + width, top + height, EDGE)


def spine(cv, x, top, height, width=T["bindingWidth"]):
    """Where two pages meet, the binding runs on through: no paper edge down the middle of it."""
    cv.fill(x, top + 1, x + width, top + height - 1, BINDING)


def side_tabs(cv, x, y0, current=1, visible=2, width=14, count=9, pitch=None):
    """Chapter tabs standing out of a sheet's right edge, one under the other; the open chapter's tab joins the sheet."""
    pitch = pitch or T["tabHeight"] + 1
    for i in range(count):
        n = i + 1
        y = y0 + i * pitch
        cur = n == current
        cv.fill(x, y, x + width, y + T["tabHeight"], PAPER if cur else EDGE)
        cv.border(x, y, x + width, y + T["tabHeight"], EDGE)
        if cur:
            cv.fill(x - 1, y + 1, x + 1, y + T["tabHeight"] - 1, PAPER)
        lab = str(n)
        cv.text(x + (width - MONO.width(lab)) // 2 + 1, y + 3, lab, INK if n <= visible else DISABLED)
