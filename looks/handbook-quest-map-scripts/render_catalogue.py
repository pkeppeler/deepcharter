"""Parts catalogue A (paper) and B (CRT), each beside a placeholder inventory, with the R lookup for the scanner unit open."""
import sys

sys.path.insert(0, sys.argv[1])
from handbook_parts import *
from crt import *

# ---------------------------------------------------------------- a plain inventory placeholder (simple 1 px pixel art)
IW, IH = 176, 166
IX, IY = (W - IW) // 2, (H - IH) // 2
GREY = (198, 198, 198, 255)
LIGHT = (255, 255, 255, 255)
DARK = (85, 85, 85, 255)
BLACK = (0, 0, 0, 255)
SLOT = (139, 139, 139, 255)
SLOT_DARK = (55, 55, 55, 255)
LABEL_GREY = (64, 64, 64, 255)


def slot(cv, x, y):
    cv.fill(x, y, x + 18, y + 18, SLOT)
    cv.fill(x, y, x + 17, y + 1, SLOT_DARK)
    cv.fill(x, y, x + 1, y + 17, SLOT_DARK)
    cv.fill(x + 1, y + 17, x + 18, y + 18, LIGHT)
    cv.fill(x + 17, y + 1, x + 18, y + 18, LIGHT)


def count(cv, x, y, n):
    t = str(n)
    w = MONO.width(t) - 1
    cv.text(x + 17 - w + 1, y + 10, t, (63, 63, 63, 255), italic=False)
    cv.text(x + 17 - w, y + 9, t, LIGHT, italic=False)


def inventory(cv):
    x, y = IX, IY
    cv.fill(x + 2, y, x + IW - 2, y + IH, GREY)
    cv.fill(x, y + 2, x + IW, y + IH - 2, GREY)
    cv.fill(x + 1, y + 1, x + IW - 1, y + IH - 1, GREY)
    # outline with clipped corners
    cv.fill(x + 2, y, x + IW - 3, y + 1, BLACK)
    cv.fill(x + 3, y + IH - 1, x + IW - 2, y + IH, BLACK)
    cv.fill(x, y + 2, x + 1, y + IH - 3, BLACK)
    cv.fill(x + IW - 1, y + 3, x + IW, y + IH - 2, BLACK)
    cv.put(x + 1, y + 1, BLACK); cv.put(x + IW - 2, y + IH - 2, BLACK)
    cv.put(x + IW - 3, y + 1, BLACK); cv.put(x + IW - 2, y + 2, BLACK)
    cv.put(x + 1, y + IH - 3, BLACK); cv.put(x + 2, y + IH - 2, BLACK)
    # bevel: light top-left, dark bottom-right
    cv.fill(x + 2, y + 1, x + IW - 3, y + 3, LIGHT)
    cv.fill(x + 1, y + 2, x + 3, y + IH - 3, LIGHT)
    cv.fill(x + 3, y + IH - 3, x + IW - 2, y + IH - 1, DARK)
    cv.fill(x + IW - 3, y + 3, x + IW - 1, y + IH - 2, DARK)
    for i in range(4):
        slot(cv, x + 7, y + 7 + i * 18)
    cv.fill(x + 25, y + 7, x + 77, y + 79, BLACK)  # the player's portrait
    slot(cv, x + 76, y + 61)
    cv.text(x + 97, y + 8, "Crafting", LABEL_GREY, italic=False)
    for r in range(2):
        for c in range(2):
            slot(cv, x + 97 + c * 18, y + 17 + r * 18)
    # the crafting arrow
    ax, ay = x + 135, y + 30
    cv.fill(ax, ay + 2, ax + 10, ay + 5, SLOT)
    for i in range(5):
        cv.fill(ax + 10 + i, ay - 1 + i, ax + 11 + i, ay + 8 - i, SLOT)
    slot(cv, x + 153, y + 27)
    for r in range(3):
        for c in range(9):
            slot(cv, x + 7 + c * 18, y + 83 + r * 18)
    for c in range(9):
        slot(cv, x + 7 + c * 18, y + 141)
    hot = [("handbook", 1), ("iron_ingot", 2), ("copper_ingot", 2), ("scrap_iron", 5), ("scrap_copper", 3)]
    for c, (name, n) in enumerate(hot):
        cv.paste(icon(name), x + 8 + c * 18, y + 142)
        if n > 1:
            count(cv, x + 7 + c * 18, y + 141, n)
    main = [("salvage_cloth", 4), ("biofuel", 2)]
    for c, (name, n) in enumerate(main):
        cv.paste(icon(name), x + 8 + c * 18, y + 84)
        count(cv, x + 7 + c * 18, y + 83, n)


# ---------------------------------------------------------------- what the catalogue lists
KNOWN = ["scanner_unit", "hull_unit", "crusher_gear", "socket_array", "fuel_tank_unit",
         "cargo_bay_unit", "drill_head", "drill_unit", "engine_unit", "fuel_valve",
         "fuel_injector", "pump_motor", "repair_circuit", "rotor_hub", "servo_unit",
         "smelter_coil", "tread_assembly", "upgrade_board", "welding_arm", "ore_feeder",
         "scrap_iron", "scrap_copper", "iron_ingot", "copper_ingot", "redstone"]
FAVOURITES = ["scanner_unit", "iron_ingot", "copper_ingot", "redstone", "hull_unit"]
RECIPE = ["RCR", "ICI", "I I"]
KEY = {"R": "redstone", "C": "copper_ingot", "I": "iron_ingot"}
NEEDS = [("iron_ingot", 2, 4), ("copper_ingot", 2, 2), ("redstone", 0, 2)]


# ---------------------------------------------------------------- A: paper
def paper_cell(cv, x, y, lit=False):
    if lit:
        cv.fill(x, y, x + 18, y + 18, HOVER)
        cv.border(x, y, x + 18, y + 18, INK)
    else:
        cv.border(x, y, x + 18, y + 18, EDGE)


def paper_catalogue(cv):
    # the strip, beside the inventory
    SX, SY, SW, SH = IX + IW + 3, 8, 116, 224
    sheet(cv, SX, SY, SW, SH)
    x0 = SX + T["bindingWidth"] + 5
    x1 = SX + SW - 5
    mid = (x0 + x1) // 2
    cv.centered(mid, SY + 6, "H. Colom & Co.", FAINT)
    cv.centered(mid, SY + 18, "PARTS CATALOGUE", INK)
    cv.fill(x0, SY + 29, x1, SY + 30, INK)
    # search: a blank to fill in
    y = SY + 34
    cv.text(x0, y, "Search", FAINT)
    sx = x0 + MONO.width("Search") + 3
    cv.fill(sx, y + 8, x1, y + 9, INK)
    cv.fill(sx + 1, y - 1, sx + 2, y + 8, INK)  # the caret
    # craftable now: a stamped checkbox
    y += 14
    cv.border(x0, y, x0 + 11, y + 11, STAMP)
    cv.border(x0 + 2, y + 2, x0 + 9, y + 9, STAMP)
    cv.text(x0 + 15, y + 2, "Craftable now", INK)
    # the known items
    y += 16
    for i, name in enumerate(KNOWN):
        cx, cy = x0 + (i % 5) * 20, y + (i // 5) * 20
        paper_cell(cv, cx, cy)
        cv.paste(icon(name), cx + 1, cy + 1)
    y += 5 * 20 + 1
    button(cv, x0, y, 13, 13, "<", active=False)
    button(cv, x1 - 13, y, 13, 13, ">")
    cv.centered(mid, y + 3, "1 / 2", FAINT)
    y += 18
    cv.text(x0, y, "Favourites", FAINT)
    y += 11
    for i, name in enumerate(FAVOURITES):
        cx = x0 + i * 20
        paper_cell(cv, cx, y, lit=(i == 0))
        cv.paste(icon(name), cx + 1, y + 1)

    # the R lookup: an index card with the recipe, a SPEC SHEET tab beside it
    CX, CY, CW = 6, 46, 114
    tab_y = CY - T["tabHeight"] + 1
    lines = 0
    CH = 140
    card(cv, CX, CY, CW, CH)
    button(cv, CX + 4, tab_y, 44, T["tabHeight"], "Recipe", selected=True, joined="bottom")
    button(cv, CX + 50, tab_y, 62, T["tabHeight"], "SPEC SHEET")
    cx0, cx1 = CX + 4, CX + CW - 4
    cv.text(cx0, CY + 4, "Scanner Unit", INK)
    cv.fill(cx0, CY + 14, cx1, CY + 15, STAMP)
    cv.text(cx0, CY + 18, "Crafting Table", FAINT)
    gx, gy = cx0 + 1, CY + 30
    for r, row in enumerate(RECIPE):
        for c, ch in enumerate(row):
            paper_cell(cv, gx + c * 18, gy + r * 18)
            if ch != " ":
                cv.paste(icon(KEY[ch]), gx + c * 18 + 1, gy + r * 18 + 1)
    ax, ay = gx + 58, gy + 26
    arrow(cv, [(ax, ay), (ax + 10, ay)], INK)
    rx = ax + 14
    cv.border(rx - 2, gy + 16, rx + 20, gy + 38, INK)
    cv.border(rx, gy + 18, rx + 18, gy + 36, INK)
    cv.paste(icon("scanner_unit"), rx + 1, gy + 19)
    ty = gy + 58
    for k, (name, have, need) in enumerate(NEEDS):
        tally_icon(cv, cx0 + k * 36, ty, name, have, need)
    cv.text(cx0, ty + 22, "R: recipes  U: uses", FAINT)


# ---------------------------------------------------------------- B: CRT
def crt_catalogue(cv):
    # the strip, beside the inventory
    SX, SY, SW, SH = IX + IW + 2, 4, W - (IX + IW + 2) - 1, 232
    gx0, gy0, gx1, gy1 = crt_panel_back(cv, SX, SY, SW, SH)
    x0, x1 = gx0 + 5, gx1 - 5
    y = header(cv, x0, gy0 + 5, x1, "PARTS CATALOGUE") + 4
    # search field
    cv.fill(x0, y, x1, y + 13, BG)
    cv.border(x0, y, x1, y + 13, DIM)
    term_text(cv, x0 + 3, y + 3, "SEARCH", DIM_TEXT, glow=False)
    term_text(cv, x0 + 3 + term_width("SEARCH "), y + 3, "_", PHOS)
    y += 17
    # the known items
    for i, name in enumerate(KNOWN):
        cx, cy = x0 + (i % 5) * 19, y + (i // 5) * 19
        crt_slot(cv, cx, cy)
        cv.paste(icon(name), cx + 1, cy + 1)
    y += 5 * 19 + 3
    term_text(cv, x0, y, "FAVOURITES", DIM_TEXT, glow=False)
    y += 11
    for i, name in enumerate(FAVOURITES):
        cx = x0 + i * 19
        crt_slot(cv, cx, y, lit=(i == 0))
        cv.paste(icon(name), cx + 1, y + 1)
    crt_panel_front(cv, SX, SY, SW, SH)
    # metal buttons go over the glass
    by = gy1 - 18
    panel_button(cv, gx0, by - 16, gx1 - gx0, 14, "CRAFTABLE NOW", lit=False)
    panel_button(cv, x0 - 2, by, 20, 14, "<", active=False, pip=False)
    panel_button(cv, x1 - 18, by, 20, 14, ">", pip=False)
    term_text(cv, (x0 + x1) // 2 - term_width("1/2") // 2, by + 3, "1/2", DIM_TEXT, glow=False)

    # the R lookup, on a second screen
    LX, LY, LW, LH = 1, 22, IX - 2, 196
    gx0, gy0, gx1, gy1 = crt_panel_back(cv, LX, LY, LW, LH)
    x0, x1 = gx0 + 5, gx1 - 5
    y = header(cv, x0, gy0 + 5, x1, "SCANNER UNIT") + 4
    term_text(cv, x0, y, "CRAFTING TABLE", DIM_TEXT, glow=False)
    y += 12
    gx, gy = x0, y
    for r, row in enumerate(RECIPE):
        for c, ch in enumerate(row):
            crt_slot(cv, gx + c * 18, gy + r * 18)
            if ch != " ":
                cv.paste(icon(KEY[ch]), gx + c * 18 + 1, gy + r * 18 + 1)
    term_text(cv, gx + 57, gy + 23, "->", PHOS)
    rx = gx + 72
    crt_slot(cv, rx, gy + 18, lit=True)
    cv.paste(icon("scanner_unit"), rx + 1, gy + 19)
    y = gy + 58
    for name, have, need in NEEDS:
        cv.paste(icon(name), x0, y)
        t = f"{have}/{need}"
        term_text(cv, x0 + 20, y + 4, t, PHOS if have < need else DIM_TEXT, glow=have < need)
        y += 18
    crt_panel_front(cv, LX, LY, LW, LH)
    by = gy1 - 18
    panel_button(cv, gx0, by, gx1 - gx0, 14, "SPEC SHEET")


if __name__ == "__main__":
    for theme in ("a", "b"):
        cv = Canvas()
        backdrop(cv)
        inventory(cv)
        if theme == "a":
            paper_catalogue(cv)
        else:
            crt_catalogue(cv)
        cv.save(sys.argv[1] + f"/out/catalogue-{theme}.png")
