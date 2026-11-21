---
status: accepted
---

# We build our own parts catalogue, and the server sends each player only their charter's known recipes

An EMI-style recipe viewer is a mod we cannot take as it is, so we build one: the Company parts catalogue. A recipe is known per charter, and the server sends each player only the recipes their charter knows. A spec sheet is an item's catalogue page.

## Context

Research, 2026-11-21:

- Official EMI stops at 1.21.1, and its author has no porting plan.
- PF-EMI (bostonstuddard/PF-EMI, MIT) ports it to 26.3: about 35k lines, released 2026-11-19, about 14k downloads.
- EMI has no public API to hide things per player at runtime. That needs its internal reload and static filter code.
- JEI on 26.3 has public `hideRecipes` and `unhideRecipes`, but its builds there are mostly beta, it would add a mod to the modpack and it looks off-theme.
- Our item count is dozens.

## Shape

- **Viewer**: our own, closely modelled on EMI. An item panel sits beside inventory screens. R on a hovered item shows its recipes, U its uses. It has search, favourites and a "craftable now" filter. The recipe tree comes later. It is styled as a Company parts catalogue, and vanilla's recipe book is hidden.
- **Known recipes**: a recipe becomes known when a quest step that asks for its item becomes current ([ADR 0065](0065-the-handbooks-directives-are-a-quest-map-and-earlier-miners-pencil-paths-continue-it-past-layer-2.md)), or when anyone in the charter obtains the item or one of its ingredients. Every item, vanilla included, follows the rule. Unknown recipes still craft: crafting is not limited.
- **Server filter**: the server sends each player only their charter's known recipes. This fits Minecraft's recipe sync since 1.21.2.
- **Spec sheet**: each item's catalogue page. It pops up the first time a player crafts or buys the item, and can be looked up any time after.
- **Copied code**: EMI's search and recipe-tree code may be copied from the PF-EMI port (MIT, keeping EMI's notice), with an audit page in the PR that copies it. Never copy `com/unascribed/qdcss`: its licence is unclear.

## Considered Options

- **Fork PF-EMI**: rejected. 35k lines to audit and to follow through every 26.x release, mostly built for problems we do not have.
- **JEI as a dependency**: rejected, for the reasons in Context.
