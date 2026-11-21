"""Quest map A: one page. Chapter 1's flowchart on the ruled sheet, chapter tabs down the right edge, a step's index card on a corner."""
import sys

sys.path.insert(0, sys.argv[1])
from handbook_parts import *

cv = Canvas()
backdrop(cv)
sheet(cv, PL, PT, PW, PH)

# chapter tabs, stepped down the right edge; chapter 1 is open and its tab joins the sheet
TAB_W = 20
for i in range(9):
    n = i + 1
    y = PT + 4 + i * (T["tabHeight"] + 1)
    current = n == 1
    x = PL + PW - 1
    cv.fill(x, y, x + TAB_W, y + T["tabHeight"], PAPER if current else EDGE)
    cv.border(x, y, x + TAB_W, y + T["tabHeight"], EDGE)
    if current:
        cv.fill(x - 1, y + 1, x + 1, y + T["tabHeight"] - 1, PAPER)
    ink = INK if n <= 2 else DISABLED
    lab = str(n)
    cv.text(x + (TAB_W - MONO.width(lab)) // 2 + 1, y + 3, lab, ink)

top_tabs(cv)

# heading, as the real chapter page
y = cv.wrapped(TL, PT + T["topMargin"], "Chapter 1", 200, FAINT)
y = cv.wrapped(TL, y + 3, CH1_TITLE, 200, INK)
cv.fill(TL, y + 1, TR, y + 2, INK)

# the flowchart: three columns, two lanes of chapter 1, then chapter 2 in faint ink, then CLASSIFIED
BW, GAP, h, PITCH = 92, 10, 22, 28
cols = [TL, TL + BW + GAP, TL + 2 * (BW + GAP)]
r1 = 75
r2 = r1 + PITCH
done = [f"[X] {d}" for d in CH1_DIRECTIVES[:5]]
step_box(cv, cols[0], r1, BW, done[0], h=h)
step_box(cv, cols[1], r1, BW, done[1], h=h)
step_box(cv, cols[2], r1, BW, done[2], h=h)
step_box(cv, cols[0], r2, BW, done[3], h=h)
step_box(cv, cols[1], r2, BW, done[4], h=h)
step_box(cv, cols[2], r2, BW, "[  ] Craft a scanner unit.", h=h)
mid1, mid2 = r1 + h // 2, r2 + h // 2
for r in (mid1, mid2):
    arrow(cv, [(cols[0] + BW, r), (cols[1] - 1, r)], INK)
    arrow(cv, [(cols[1] + BW, r), (cols[2] - 1, r)], INK)
cx2 = cols[2] + BW // 2
arrow(cv, [(cx2, r1 + h), (cx2, r2 - 1)], INK)
loop(cv, cols[2] - 4, r2 - 3, cols[2] + BW + 3, r2 + h + 2)

# chapter 2, the road just ahead: its title and directives, in faint ink
y2 = r2 + h + 6
cv.text(TL, y2, "Chapter 2: " + CH2_TITLE, FAINT)
r3 = y2 + 11
for i, d in enumerate(CH2_DIRECTIVES):
    step_box(cv, cols[i], r3, BW, d, FAINT, h=h)
mid3 = r3 + h // 2
arrow(cv, [(cols[0] + BW, mid3), (cols[1] - 1, mid3)], FAINT)
arrow(cv, [(cols[1] + BW, mid3), (cols[2] - 1, mid3)], FAINT)
arrow(cv, [(cols[2] + BW - 12, r2 + h + 3), (cols[2] + BW - 12, r3 - 1)], FAINT, dash=(2, 1))

# further ahead: redacted shapes and the stamp
r4 = r3 + h + 6
bars = [(cols[0], 78), (cols[1], 64), (cols[2], 70)]
for bx, bw in bars:
    redaction(cv, bx, r4, bw, LINE)
for (ax, aw), (bx, bw) in zip(bars, bars[1:]):
    cv.fill(ax + aw, r4 + 4, bx, r4 + 5, REDACT)
cv.fill(cols[2] + 30, r3 + h, cols[2] + 31, r4, REDACT)
stamp(cv, "CLASSIFIED", cols[0] + 70, r4 + 5, -12)

# an earlier miner's pencil path: off the end of chapter 2, past the redactions, to her note
ex = cols[2] + BW
path = [(ex + 1, mid3), (ex + 3, mid3 + 2), (ex + 3, r4 + 4), (ex + 2, r4 + 12), (ex - 1, r4 + 20)]
cv.poly(path, MARGIN, dash=(2, 1))
xm, ym = ex - 5, r4 + 21
cv.line(xm - 2, ym - 2, xm + 2, ym + 2, MARGIN)
cv.line(xm + 2, ym - 2, xm - 2, ym + 2, MARGIN)
lines = MONO.wrap(REDACTIONS_MARGIN, 160)
for i, ln in enumerate(lines):
    cv.text(xm - 6 - MONO.width(ln), r4 + 12 + i * 10, ln, MARGIN)

# the open step's index card, laid over the top-right corner
CX, CY, CW = 242, 2, 126
yy = CY + 16
lines = MONO.wrap(CH1_DIRECTIVES[5], CW - 10)
CH = 16 + len(lines) * 10 + 22
card(cv, CX, CY, CW, CH)
cv.text(CX + 4, CY + 3, "Step 6", FAINT)
lw = MONO.width("see p. 4")
link(cv, CX + CW - 6 - lw, CY + 3, "see p. 4")
cv.fill(CX + 1, CY + 13, CX + CW - 1, CY + 14, STAMP)
yy = cv.wrapped(CX + 4, CY + 16, CH1_DIRECTIVES[5], CW - 10, INK)
tally_icon(cv, CX + 5, yy + 1, "iron_ingot", 2, 4)
tally_icon(cv, CX + 46, yy + 1, "copper_ingot", 2, 2)
tally_icon(cv, CX + 87, yy + 1, "redstone", 0, 2)
print("card", CX, CY, CW, CH, "rows", r1, r2, r3, r4)

footer(cv)
cv.save(sys.argv[1] + "/out/questmap-a.png")
