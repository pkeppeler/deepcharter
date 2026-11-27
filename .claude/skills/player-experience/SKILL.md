---
name: player-experience
description: Deep Charter's player-experience checklist, the recurring themes from playtest-feedback issues (hidden numbers and causes, reinvented controls, vanilla leftovers, art drift, clipped text, onboarding, pacing, cues, lore voice, desync) each with a rule a reviewer can check and the issues it came from. Load when reviewing a gameplay PR's player-visible changes, when writing an issue or brief for a screen, HUD, control, text or texture, and when running /refresh-player-experience.
---

# Player experience

Playtesters hit the same problems again and again; code review does not see them. Each theme below is a rule a reviewer can check against a PR's diff, lang text, textures and evidence media. The first sweep read all 67 `playtest-feedback` issues; a theme with one source is a one-off, not a rule.

Learned through playtest-feedback issue: #1539

## Themes

| Theme | Rule a reviewer checks | Sources | Gate |
|---|---|---|---|
| Hidden numbers and causes | Every on-screen number has a label and a unit. Every limit a player can hit shows its value. Anything that slows, hurts or blocks says why, once when it first bites and while it holds. An action the game refuses says so. | #853 #854 #858 #859 #1272 #1425 #1432 #1436 #1438 #1440 #1449; echoed by #1538 #1541 | no |
| Reinvented controls | A control copies vanilla or an established mod (EMI, NEI, the vanilla boat) unless the PR names the deviation and why. A new key is justified. State the player left is restored. | #962 #1258 #1259 #1264 #1265 #1266 #1268 #1378 #1442; echoed by #1537 #1540 | partial: #1556 (keybind allow-list) |
| Act where you stand | A pilot reaches what the pod needs (terminals, cargo, consumables, fittings) from the seat. A screen reachable only by leaving the seat, or by a hidden gesture, is a finding. | #1270 #1447 #1456 #1457 | no |
| Vanilla leftovers | Nothing vanilla shows that the world does not have: advancements and toasts, locator bar, recipes and items outside the campaign allowlist, vanilla music, stand-in sounds, default furnace and chest looks. | #835 #1263 #1296 #1421 #1450 | partial: #1557 (banned sounds) |
| Art drift | Textures, models and digits match `docs/design/art-direction.md` and the cockpit's pixel style. A texture tiles without seams and reads at inventory size. | #856 #857 #1262 #1421 #1539 | partial: `tools/layer_concepts/seams.py` |
| Screen coverage | A HUD or screen addition states what it covers. The cockpit stays inside its coverage budget. A popup or readout never sits on another element. | #1261 #1264 #1457 #1539 | partial: `keep-clear` gate |
| Clipped text | Every lang string and name fits its panel at the widest GUI scale: no "...", no run off the page, no cut-off popup, no empty border round a map. | #962 #1258 #1413 #1429 #1452 | yes: [#1559](https://github.com/pkeppeler/deepcharter/issues/1559) |
| Onboarding and the first hour | A step is taught where it is used, in the order it is met, with words that point to the right place ("craft the scanner" in chapter 1, long before it can be fitted, #1431). The first hour is survivable: food, fuel and light are in reach. | #834 #837 #838 #963 #1260 #1414 #1415 #1423 #1425 #1431 | no |
| Pacing in the player's units | A tunable that sets pacing is measured in blocks, seconds and trips by a GameTest. A default that gets harder says so in Decisions. A refusal such as an empty tank is the player's to manage, but readable first. | #963 #1430 #1432 #1434 #1437 #1439 #1445 #1446 #1458 | partial: pacing GameTests |
| Cue at the moment | An event the player causes or suffers has a sound, motion or line at the moment it happens, and nothing flickers or snaps (a stalk that sags for 0.4 s before it falls, #1261; rotor blades that fold in and out while drilling, #1444). The first time is explained. | #854 #855 #1261 #1267 #1438 #1444 #1450 | no |
| Lore voice | Story text speaks to the whole charter ("the Director finds the Note" alienates every other player, #860). The Director is named only where the role acts. Welcome and handbook text points to the right place. | #834 #855 #860 #1415 | yes: [#1560](https://github.com/pkeppeler/deepcharter/issues/1560) |
| Desync, lag and log noise | A server-side cancel or change tells the client. A trip repeated many times does not slow the game. A playtest log has no missing-texture or force-send lines. | #858 #961 #1295 #1297 #1317 #1343 | yes: [#1561](https://github.com/pkeppeler/deepcharter/issues/1561) |
| Works in test, fails in the real client | A client test drives the real input path (SDL button numbers, char events), not a shortcut. | #1269 #1297 #1304 | partial: the input-literal gate |

## How to apply it

- Issues in an "echoed by" list carry the `gameplay` label, not `playtest-feedback`: they confirm a theme but the refresh does not count them.
- Judge what the player sees, not the code: read the diff's lang keys, textures and models, and look at the evidence media or GameTest stills.
- A finding names the theme, the file and line, and the playtest issue it echoes. Where a gate row is `yes` or `partial`, check the gate covers the PR's case before trusting it.
- A gameplay PR that adds a number, a limit, a control, a texture, a text or a cue answers the matching row in its body or evidence. Silence on a row the PR touches is a Warning.
- A new playtest issue that fits no row goes to `/refresh-player-experience`, never into a review as a rule.
