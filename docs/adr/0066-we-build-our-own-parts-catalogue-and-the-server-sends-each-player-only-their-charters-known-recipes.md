---
status: accepted
---

# We build our own parts catalogue, and the server sends each player only their charter's known recipes

An EMI-style recipe viewer is a mod we cannot take as it is, so we build one: the Company parts catalogue. A recipe is known per charter, and the server sends each player only the recipes their charter knows. A spec sheet is an item's catalogue page.

## Context

Research, 2026-11-21:

- Official EMI stops at 1.21.1, and its author has no porting plan.
- PF-EMI (bostonstuddard/PF-EMI, MIT) ports it to 26.3: about 35k lines.
- EMI has no public API to hide things per player at runtime. That needs its internal reload and static filter code.
- JEI on 26.3 has public `hideRecipes` and `unhideRecipes`, but its builds there are mostly beta, it would add a mod to the modpack and it looks off-theme.
- Our item count is dozens.

## Decision

- **Viewer and known recipes**: our own, closely modelled on EMI, with a recipe known per charter. Rules in [SPEC §5](../SPEC.md#parts-catalogue). A quest step makes a recipe known ([ADR 0065](0065-the-handbooks-directives-are-a-quest-map-and-earlier-miners-pencil-paths-continue-it-past-layer-2.md)).
- **Server filter**: the server sends each player only their charter's known recipes. It uses Minecraft's recipe book and its add and remove packets ([ADR 0070](0070-the-server-recipe-book-holds-exactly-the-known-recipes-and-the-vanilla-recipe-packets-carry-it.md)).
- **Spec sheet**: each item's catalogue page ([SPEC](../SPEC.md#the-world-is-a-mystery-the-rules-never-are)).
- **Copied code**: EMI's search and recipe-tree code may be copied from the PF-EMI port (MIT, keeping EMI's notice), with an audit page in the PR that copies it. Never copy `com/unascribed/qdcss`: it has no licence header, and its source licence could not be confirmed.

## Considered Options

- **Fork PF-EMI**: rejected. 35k lines to audit and to follow through every 26.x release, mostly built for problems we do not have: thousands of items, many loaders and no per-player hiding.
- **JEI as a dependency**: rejected, for the reasons in Context.
