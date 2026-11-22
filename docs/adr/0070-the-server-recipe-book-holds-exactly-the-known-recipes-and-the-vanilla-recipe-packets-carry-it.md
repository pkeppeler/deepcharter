---
status: accepted
---

# The server recipe book holds exactly the known recipes, and the vanilla recipe packets carry it

Builds on [ADR 0066](0066-we-build-our-own-parts-catalogue-and-the-server-sends-each-player-only-their-charters-known-recipes.md) (known recipes per charter) and [ADR 0065](0065-the-handbooks-directives-are-a-quest-map-and-earlier-miners-pencil-paths-continue-it-past-layer-2.md) (progress carries into a charter). ADR 0066 inferred that known recipes fit Minecraft's recipe sync since 1.21.2. This records how 26.3 does it, and what we do with it.

## How recipe sync works in 26.3 (read from the jar)

- The recipes themselves are not sent. `ClientboundUpdateRecipesPacket` carries only the item sets the client needs to validate inputs (furnace, smithing, brewing) and the stonecutter recipes, to every player. It holds no recipe-book content and the catalogue does not read it.
- A player's recipe book is the recipes they can see. The server keeps it in `ServerPlayer.getRecipeBook()` (`ServerRecipeBook`: a `known` and a `highlight` set of recipe keys, saved in the player's own data). The client keeps `ClientRecipeBook`, a map of `RecipeDisplayId` to `RecipeDisplayEntry`: the display (what the grid and result look like), its category and its crafting requirements. `LocalPlayer.getRecipeBook().getCollections()` is how a client reads them.
- Packets: `ClientboundRecipeBookAddPacket(entries, replace)`, whose entries carry flags for a toast (`notification`) and a highlight; `ClientboundRecipeBookRemovePacket(displayIds)`; `ClientboundRecipeBookSettingsPacket`. `ServerRecipeBook.sendInitialRecipeBook` sends the whole book with `replace` set and no flags, at login.
- Vanilla unlocks go through `ServerRecipeBook.addRecipes(recipes, player)`: advancement rewards, `/recipe give` and crafting. It adds to `known` and `highlight`, sends an add packet that toasts when the recipe says so, and fires the recipe-unlocked trigger. Special recipes are never added.
- Crafting never asks the recipe book, unless the `limited_crafting` game rule is on (default off). We never turn it on: unknown recipes still craft.

## Decision

- **Data**: `KnownRecipesData`, one versioned `SavedData`: `{version, charters: [{charter, recipes}], players: [{player, recipes}]}`, recipe ids. It follows ADR 0007: another version loads as unreadable, is written back unchanged, and every explicit use throws. A recipe id that no recipe has any more stays saved and is skipped when sent.
- **Known recipes**: the server's recipe book for a player holds exactly the recipes their charter knows (their own set on no charter), by the add and remove packets above. `KnownRecipes` makes it so when a charter is founded, joined or revived (every member, since the set grew), when a player leaves (their own set again), and when the set grows.
- **Our adds are quiet**: we add to the book with `ServerRecipeBook.add` and send add packets with no toast and no highlight, not through `addRecipes`. A step can teach a dozen recipes at once; the catalogue, not a toast, is how the player learns of them.
- **Triggers**: a poll at `HandbookTuning.progressPollTicks` (like directive progress) adds, for each online player, the recipes of every item in their inventory or on their cursor whose result or ingredient it is (every item an ingredient tag names), and, for the charter's current step, the recipes that make each item the step `needs`. Every recipe follows the rule, vanilla's too. The step the charter is on is the first directive, in handbook order, that it has not completed, which is the one the quest map circles (`HandbookChapters.current`). A step's `needs` are data in its chapter; a step with none teaches nothing by itself. Held items count wherever they sit in the inventory, not inside a bundle or shulker box.
- **Before a charter**: a player on no charter keeps their own set, as #843 keeps directives and Notes. `ProgressCarry.carry` adds it to the charter on founding, joining and reviving. It stays after, so leaving gives it back, and never the charter's.
- **Vanilla's unlocks do not leak**: `handbook/mixin/ServerRecipeBookMixin` targets `ServerRecipeBook.addRecipes` and `sendInitialRecipeBook`. The first passes on only the recipes the player knows, so `/recipe give`, an advancement reward or another mod cannot put an unknown one in the book; one the player knows still goes through. The second makes the book match what the player knows before the whole book is sent, so a recipe saved in the player's data is not sent. A recipe the player knows may still be unlocked by vanilla, which adds nothing the player was not already shown.
- **Unreadable**: while the saved charters or known recipes are unreadable, no recipe is known and the book is emptied, so the privacy rule holds. The saved data is kept, and a build that can read it gets the recipes back.
- **The client**: the client's known set is its `ClientRecipeBook`. The parts catalogue (#846) reads `getCollections()` and needs nothing else from us; the vanilla recipe-book button is hidden there.

## Considered Options

- **Our own packet, as with `CharterView`**: rejected. The client already has a book, a display model and add and remove packets, and the catalogue wants exactly those displays.
- **Polling the book and removing strays, no mixin**: rejected. A vanilla unlock would reach the client for a poll interval, and the book saved in player data would be sent in full at login before any poll.
- **Turning on `limited_crafting`**: rejected. Unknown recipes still craft (SPEC).
- **Advancement-driven discovery** (recipe advancements, as vanilla does): rejected. It has no per-charter set, and ADR 0067's removal of vanilla advancements would take it away.
- **A client-side filter over a full book**: rejected. The server must not send what the player does not know.

## Consequences

- A new recipe, including a datapack's, follows the rule with no change here. A recipe with no ingredient and no result item (a special recipe) is never known and never sent.
- A tag ingredient makes every recipe that uses any item of the tag known when one item of it is held: holding oak planks teaches every recipe that takes any planks.
- The poll also reconciles each player's book, so a recipe taken out of it from outside (`/recipe take`) comes back while the charter knows it.
- A recipe a datapack removes on `/reload` leaves no display to remove, so its key stays in the server book until the next resend, which the mixin conforms. Every learnable recipe in 26.3 has at least one ingredient, so each can be known by an ingredient as well as by its result.
- The index is dropped when the server stops, so it never keeps a stopped server's recipes alive.
