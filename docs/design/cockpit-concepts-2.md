# Cockpit HUD concepts, round 2

[Round 1](cockpit-concepts.md) drew three looks. The user liked Consoles best and wrote ([#448](https://github.com/pkeppeler/deepcharter/issues/448)): every status panel must take far less of the screen (only the scanner may be large), the pod should show as the form around a clear window, and a front camera upgrade should turn that window into a video feed so the pod becomes invisible. This round draws **four compact looks** built on Consoles, designs the **front camera** as a mechanic ([mechanics.md](mechanics.md#front-camera-448-a)) and builds just enough of it, behind a test pack, to be seen in the game. The build of the one you pick comes in a later pull request.

**Status: concepts.** The mod ships `style` 0 and no camera, so a player sees no change: the plain text HUD and the pod's cab as they were.

## What every option shares (already decided)

1. **Round 1's rules still hold:** the vanilla HUD goes while seated, the gauges read the pod's real values, heat is the burning of the hull eased, the options differ in shape and not in colour, and everything is resource-pack data ([cockpit-concepts.md](cockpit-concepts.md)).
2. **No frame over the screen.** The pod's own cab is the frame: the pillars, the roof bar and the cutter you see from the seat are the 3D model. Each option is a few small housings and nothing else. (Round 1's frame was a full-screen sprite.)
3. **Five gauges and four lamps.** Fuel, hull, depth, heat and now **cargo** (the ore aboard, a share of the bay's slots), each a short segmented bar in its own colour, and a row of four **warning lamps**: low fuel, low hull, hull burning, and any other warning of the readout (a crust or seep warning, a hard landing, stranded, out of brick).
4. **A one line plate.** The four values the old readout listed as lines (hull, fuel, cargo, depth) are one row of numbers, each in its gauge's colour: `100/100  77%  0  Y150`. The account line is a second row. Every other line of the plain readout (the slag, the liner, the seep and crust warnings, the lining, "HULL BURNING", a hard landing, stranded) is a row of its own, over the numbers when the plate hangs from the bottom of the screen and under them when it hangs from the top, so the numbers do not jump when a warning comes and goes. The plate is as wide as its widest row.
5. **The altimeter stays at the top centre,** in a recess in the ribbon for B and plain text for the others.
6. **Colour is state.** A full hull is green and turns red at the low-hull mark (30 %), as fuel turns red at the low-fuel beep: a full bar never reads as a warning.
7. **The scanner stays big.** The same 98 x 82 window as round 1: tiers 1 to 3 whole, tier 4 cut at its edge.

## The four options

| | Shape | Status housings | Scanner | Status | Scanner | Total |
|---|---|---|---|---|---|---|
| **A. Corners** | A small housing in each corner. | Fuel, hull and cargo bars (6 x 24, 12 steps) bottom left, 52 x 44; depth and heat bars bottom right, 36 x 44; the four lamps, each with a letter under it (F, H, T, W), top left, 36 x 20. Plate at the bottom centre. | 112 x 112 bezel, top right. | 7.4 % | 12.2 % | 19.6 % |
| **B. Ribbon** | One slim ribbon along the top edge. | Every gauge as a 26 x 5 bar (13 steps) with a letter, the altimeter in a recess at the middle, the lamps at the end: 320 x 14. Plate under the scanner. | 112 x 112 bezel hung from the ribbon's right end. | 7.4 % | 12.2 % | 19.5 % |
| **C. Posts** | A post up each side of the view, a sill along the bottom. | Two bars (5 x 80, 40 steps) in each of two posts of 20 x 106, fuel and hull left, depth and heat right; the sill, 120 x 12, has the cargo bar and the lamps. Plate over the sill. | 112 x 112 bezel, top left, over the left post. | 8.5 % | 12.2 % | 20.7 % |
| **D. Cab** | Nothing but a small instrument sill. | One slab of 144 x 28 at the bottom centre: fuel, hull and cargo bars over depth, heat and the lamps (32 x 6, 16 steps). Plate over it. | A slim 104 x 104 bezel, middle of the right edge. | 6.9 % | 10.3 % | 17.2 % |
| *Round 1, Consoles* | A rim and two consoles. | 76 x 108 and 124 x 178. | In the right console. | 29.0 % | 13.5 % | 42.4 % |
| *The plain HUD* | Text and a scanner. | None. | 147 x 123, top right. | text only | 17.6 % | 17.6 % |

**Coverage** is the share of a 427 x 240 GUI (an 854 x 480 window at GUI scale 2) that the option's sprites make opaque in the calm state, a pixel under two pieces counted once: **status** is every housing, the frame and the plate (the plate at the width of its calm rows), **scanner** is its bezel and window. `python3 tools/cockpit_concepts.py --coverage` prints it, and `tools/tests/test_cockpit_concepts.py` fails a compact look whose status goes over 9 % or is as big as its scanner. The four options cut the status of Consoles by three quarters or more (29.0 % to 6.6 to 8.5 %); the total is 17 to 21 %, about the plain HUD's scanner alone.

## How they are drawn, and how to see them

- **`theme/cockpit.json`** `style`: 0 none (the default), 1 Consoles (round 1), 2 Corners, 3 Ribbon, 4 Posts, 5 Cab. **A pick is a data change:** a pack with `{"style": 4}` is Posts, and F3+T applies it. The test packs `cockpit_consoles`, `cockpit_corners`, `cockpit_ribbon`, `cockpit_posts` and `cockpit_cab` do exactly that, and `cockpit_camera` (and `cockpit_camera_hold`, whose static does not fade, for a still) sets `cameraInstalled` to 1. Only the evidence scenario and the tests turn them on.
- **`tools/cockpit_concepts.py`** writes the sprites and the theme file with the Python standard library only; `--check` (and `tools/tests/test_cockpit_concepts.py`) fails when a committed file differs from what it makes, `--preview DIR` writes a mock of each look, `--coverage` prints the table above. Every coordinate is a whole number or goes through `nearest`, and a test fails on a bare `round(`: #445 failed CI on Linux for a pixel that `round` put on the other side of a tie.
- **`client/pod/CockpitHud`** draws the style as the first HUD element; `CockpitLook` reads it; the plate's rows are `CockpitHud.rows`. **`client/pod/FrontCameraFeed`** is the camera.
- **The stills** are in the PR that carries this page ([#453](https://github.com/pkeppeler/deepcharter/pull/453)) and in [pr-media/453](https://github.com/pkeppeler/deepcharter/tree/pr-media/453), named `<option>-<state>.png`: the options `current` (the plain HUD), `consoles`, `corners`, `ribbon`, `posts` and `cab`; the states `calm`, `low-fuel`, `burning` (the hull burning in lava), `deep` and `on-foot`; each option also `-calm-scale1` (an 854 x 480 GUI) and `-camera-calm` (the same seat through the front camera), and `current` and `cab` also `-camera-haze`, `-camera-dead` and `-camera-static`. `<option>-<state>-vs-current.png` puts the plain HUD beside the option and `<option>-<state>-vs-consoles.png` round 1's Consoles. A still that is not there (an option on foot, which is the vanilla HUD again) did not differ from what it is compared with. `tools/record-evidence.sh cockpit-concepts` makes them; `DEEPCHARTER_COCKPIT_OPTIONS=cab,posts` shoots only those. Every still came from one run on one renderer (Vulkan), in a sealed stone room.

## From the seat today

The first-person view from the Mole's seat ([#382](https://github.com/pkeppeler/deepcharter/issues/382), the pilot upright in the canopy) is the inside of the pod's own model: two cab pillars, the roof bar and the dark cutter cone in front, the cab sill below. Taking the pod out of a still changes 51 % of its pixels (76 % of the middle of the view). That is the "form of the actual pod" the user wants to see, and it is what the four options are drawn over. The only change this round makes to it is the front camera.

## The four, one by one

### A. Corners

![A, calm](https://github.com/pkeppeler/deepcharter/blob/pr-media/453/corners-calm.png?raw=true)

**Shape.** Four small housings, one in each corner: bars bottom left and bottom right on the pod's dark lower cab, the lamps top left, the scanner top right. Nothing on the top or the sides between them.

**From the seat.** The bars are tall (24 pixels, 12 steps of 8 %), so a low tank is one red step and reads at the corner of the eye, and the cab's pillars and cutter keep the middle and both sides. The cost is spread: the pilot looks at three corners, and the lamps at top left are small, labelled F, H, T and W. Status is 7.4 %.

### B. Ribbon

![B, calm](https://github.com/pkeppeler/deepcharter/blob/pr-media/453/ribbon-calm.png?raw=true)

**Shape.** One 14 pixel ribbon across the top, the altimeter at its middle, the scanner hung from its end.

**From the seat.** Everything is one glance along one line, and the ribbon is a roof beam over a view that is otherwise clear. The bars are the weakest of the four, 26 x 5 pixels in 13 steps, small to read at a glance, and the plate is under the scanner, far from the bars it explains, and close to the cab's right pillar (it has to sit clear of a long crust warning at the smallest GUI, which the scanner's end of the ribbon does not leave room for). Status is 7.4 %.

### C. Posts

![C, calm](https://github.com/pkeppeler/deepcharter/blob/pr-media/453/posts-calm.png?raw=true)

**Shape.** A post up each side with two bars set in it, a sill with the cargo bar and the lamps, the scanner at top left.

**From the seat.** The finest gauges: 80 pixels tall in 40 steps (2.5 %), in the pilot's side vision where the cab's own side pillars are, so they read as set into the pillars, which is the nearest to the user's idea of gauges in the real cab. The cost is the edges: 20 pixels a side for the posts and a 112 pixel bezel over the left one. At 8.5 % it is the heaviest of the four.

### D. Cab

![D, calm](https://github.com/pkeppeler/deepcharter/blob/pr-media/453/cab-calm.png?raw=true)

**Shape.** A single instrument sill at the bottom centre, the numbers over it and a slim scanner window at the right edge. No frame, no posts.

**From the seat.** The clearest view and the smallest status (6.9 %): the pod's cab is the frame and the screen has nothing else on it. It is also the hardest to read, with bars 6 pixels tall, so it relies on the lamps and the numbers. It is the option for the see-the-pod view, and the one that does the most with the front camera: with the cab gone, a sill and a window are all that is left.

## The front camera

The pod's body is not drawn from the seat while the camera's feed is live, so the view is clear; the camera and its price and hazards are designed in [mechanics.md](mechanics.md#front-camera-448-a). In short: one tier, $350 (about three early layer 1 runs, or one layer 2 run with tier 2 parts), a share of the idle fuel (not built yet), and a feed that a hull hit covers with static, that lava heats until it fails (the cab is back and the badge reads NO FEED) and that drops out in bursts when the hull is nearly gone.

Without the camera (left) and with it (right), on the plain HUD and on the Cab option:

![plain HUD, without and with the camera](https://github.com/pkeppeler/deepcharter/blob/pr-media/453/current-camera-off-vs-on.png?raw=true)

![cab, without and with the camera](https://github.com/pkeppeler/deepcharter/blob/pr-media/453/cab-camera-off-vs-on.png?raw=true)

Under hazards (heat haze while the hull burns, then the dead feed once the lens has cooked, then static after a hit):

![cab, haze and dead feed](https://github.com/pkeppeler/deepcharter/blob/pr-media/453/cab-camera-haze-vs-dead.png?raw=true)

![cab, static](https://github.com/pkeppeler/deepcharter/blob/pr-media/453/cab-camera-static.png?raw=true)

## What none of them does yet, and what the pick has to settle

- **The purchase flow.** The camera is a stand-in (`cameraInstalled` in the theme): while the purchase is deferred any resource pack turns it on for free and sets the lens, static and flicker numbers too. The build adds the component track `camera`, its price in `UpgradeTuning`, the part item and the terminal's row, an `EconomyAffordabilityTest` pin of the price, and the idle fuel draw.
- **Small and large GUIs.** The layouts hold at 320 x 240 and at 427 x 240 (the layout tests check housings, gauges, lamps, scanner window and the plate in every state and with the longest warning line). Under 320 x 240 they would overlap, so, like the terminal panel, the build should draw the plain HUD when the GUI is smaller than the style needs.
- **Scanner tier 4** is cut at the window's edge: the pick needs a rule (clip, zoom out, or a larger window).
- **The navigator.** A second seat gets the housings and the scanner and no gauges or plate, as it gets no readout today, and loses the vanilla hotbar under a style. The build must decide if that is right.
- **Heat** is the burning of the hull, eased (+0.08 and -0.02 a tick). If heat becomes a pod value, the gauge reads that. The easing still has no test.
- **Depth** is a share of the floor of the last layer. A new layer moves every reading.

## Pick one

[#247](https://github.com/pkeppeler/deepcharter/issues/247) is built from the option you pick: its `style` number becomes the mod's, and the other styles' sprites and keys are removed, as round 2 removed Dials and Strip. Round 1's Consoles is kept only to compare against and goes with the pick.
