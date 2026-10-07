---
status: accepted
---

# Ore is an item with a mass, and only our ore is cargo

SPEC section 4 makes ore a physical, heavy, non-stacking item that the pod hauls. #56 builds it.

## Decisions

- **One item and one block per `OreType`.** The item ids (`deepcharter:ironium` and so on) go into saves, so renaming one later needs a data migration. The block (`<name>_ore`) is what the drill hits until #63 places ores in the world, and its loot table drops the item for hand mining.
- **Ore stacks to 1.** A stack is one ore, so the bay slot count and the ore count are the same number.
- **`PodCargo` holds `ItemStack`s with a mass beside each.** It is saved in the pod's own save data, under the same `cargo` key as before. The entry format changed from a block to a stack, so a pod saved by M1 does not load its cargo; M1 saves are not supported.
- **Vanilla ores are never cargo.** The drill destroys them, as it does with a full bay; `PodCargo` throws on a stack that is not our ore. The `pod_ore` tag, which held `#c:ores`, is gone: the block-to-ore mapping lives in one place, `OreRegistry`.
- **Mass is the original's ore mass times `OreTuning.massScale` (5).** The original's pod outweighs its ore by about 200 to 1, so its numbers would make lift irrelevant. With scale 5 and an engine of 100, four Einsteinium leave 20 lift and a full bay of them cannot take off.
- **Carried ore slows a player by a transient speed modifier.** It is `slowdownPerMass` (2%) of walking speed for each unit of mass, capped at `maxSlowdown` (80%), set each server tick from the player's inventory.

## Consequences

- No pod state is new, so no attachment is added: the bay keeps its existing save path and its synced count and mass.
- The cargo screen is a plain read-only panel. Taking ore out of a pod belongs to the terminals.
