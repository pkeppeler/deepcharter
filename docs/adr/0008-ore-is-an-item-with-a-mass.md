---
status: accepted
---

# Ore is an item with a mass, and only our ore is cargo

SPEC section 4 makes ore a physical, heavy, non-stacking item that the pod hauls. #56 builds it.

## Decisions

- **One item and one block per `OreType`.** The item ids (`deepcharter:ironium` and so on) go into saves, so renaming one later needs a data migration. The block (`<name>_ore`) is what the drill hits until #63 places ores in the world, and its loot table drops the item for hand mining.
- **Ore stacks to 1.** A stack is one ore, so the bay slot count and the ore count are the same number.
- **`PodCargo` holds `ItemStack`s with a mass beside each.** It is saved in the pod's own save data, under the `cargo` key, with a `cargo_version` (1). The entry format changed from a block to a stack. A save with no version is from M1: its entries were vanilla ores, so they are dropped and logged at ERROR with the pod's UUID and the count. A save of a newer version, or with an entry that does not decode, is kept and written back unchanged; `tryAdd` and `dump` then throw, naming the pod.
- **Vanilla ores are never cargo.** The drill destroys them, as it does with a full bay; `PodCargo` throws on a stack that is not our ore. The `pod_ore` tag, which held `#c:ores`, is gone: the block-to-ore mapping lives in one place, `OreRegistry`.
- **Mass is the original's ore mass times `OreTuning.massScale` (5).** The original's pod outweighs its ore by about 200 to 1, so its numbers would make lift irrelevant. With scale 5 and an engine of 100, four Einsteinium leave 20 lift and a full bay of them cannot take off.
- **Carried ore slows a player by a transient speed modifier.** It is `slowdownPerMass` (2%) of walking speed for each unit of mass, capped at `maxSlowdown` (80%), set from the player's inventory when the load changes.
- **Ore in a container counts.** The mass of ore inside a shulker box or a bundle is added to the load, so a container is not a way to carry ore for free.

## Consequences

- No pod state is new, so no attachment is added: the bay keeps its existing save path and its synced count and mass.
- The cargo screen is a plain read-only panel. Taking ore out of a pod belongs to the terminals.
