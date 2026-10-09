---
status: accepted
---

# Pod models are item models a pack can replace, and the lampless figure keeps the vanilla model

Builds on [ADR 0030](0030-art-direction-decisions.md) (the art direction) and [ADR 0027](0027-a-chassis-is-an-entity-type-and-an-unowned-wreck-is-registered-when-restored.md) (a chassis is an entity type). Issue #258, part of the drop-in skins work (#226). **The pod part of this record is superseded by [ADR 0040](0040-a-pod-look-is-a-file-per-chassis-and-the-drill-tier-picks-the-cutter.md)** (#243): the pods are GeckoLib models with a look file, and `PodSkins`, `PodRenderer` and the pod item models are gone. The lampless figure's part stands.

## Decision

- **A pod look is three item model definitions.** Per chassis there is `pod/<chassis>` (the hull), `pod/<chassis>_wreck` and `pod/<chassis>_drill`. Each has `assets/deepcharter/items/pod/<name>.json` and a Java block/item model `assets/deepcharter/models/pod/<name>.json`, which Blockbench exports. A pack that replaces those files, or the textures they name, changes the look after F3+T with no Java. `PodSkins` holds the ids; code names slots and nothing else.
- **The renderer asks for the model the way vanilla draws a held item.** `PodRenderer` makes an `ItemStack` with the `item_model` component set to the id and resolves it through `ItemModelResolver`, then submits the `ItemStackRenderState`. Models are authored at true size in block space (the Mole's hull is 30.4 x 14.4 x 30.4 pixels, centred on 8), so the renderer does not scale them.
- **The default skin is today's look.** A slab model whose six faces carry one texture each, from vanilla's `raw_copper_block`, `iron_block` and `coal_block` (a placeholder reference by id; the repo ships no vanilla texture).
- **A moving part is its own model, moved by transform.** The drill is `pod/<chassis>_drill`. The renderer turns it about its axis while the pod drills and points it along the drill direction. The default drill is a small block inside the hull, so nothing new shows. #243's rotor, treads and the rest follow this pattern: a model file each, a transform in code.
- **The tow cable is a mod particle type**, `deepcharter:tow_cable`, with `particles/tow_cable.json` and `textures/particle/tow_cable.png`. It keeps the end rod's motion (it uses vanilla's provider); the sprite is ours.
- **The lampless figure keeps the vanilla zombie model; its texture is the skin.** `textures/entity/lampless_figure.png` is already a pack file. Vanilla entity models are built in code from `ModelLayers`, so a data-driven figure needs a bone rig with per-limb poses. That is what GeckoLib gives the pods and the figure under ADR 0030 (#243), so building a second one now would be thrown away. This is the documented exception until then.
- **`SkinAssetsTest` guards it.** Every pod, wreck and drill model has its item definition, model file and the textures it names in this mod's namespace; every mod particle has its JSON and texture; the figure has its texture.

## Considered Options

- **Extra models through Fabric's model-loading API, drawn with the block-model renderer.** Rejected: it needs a plugin and a manual model key for each part, and the item route gives the same F3+T reload with vanilla code only. It also breaks less on a Minecraft bump, because the resolver is the path vanilla's own item displays use.
- **Scaling one unit-cube model in code, as before.** Rejected: a skin could then change the texture but not the shape or its proportions.
- **A block-model-based figure.** Rejected for now, see above.

## Consequences

- ADR 0030's GeckoLib pods replace these models later; the ids, the F3+T reload and the asset test stay as the floor for any skin, and a GeckoLib skin is another file set that the same test then covers.
- The item `Items.STONE` is a stand-in that only carries the component. It is never in a world or an inventory.
- Item rendering centres a model by moving it half a block on each axis. `PodRenderer` moves it back, so a model's origin is the block corner. A model past 32 pixels from its centre cannot be exported by Blockbench (its limit is -16 to 32), which caps a chassis at 3 blocks wide for the models as authored here. A larger chassis scales its model in code or splits it into parts.
