package io.github.pkeppeler.deepcharter.surface;

import io.github.pkeppeler.deepcharter.DeepCharter;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

/**
 * What a salvage crate holds besides planks and wiring (art direction section 11): scrap iron and scrap copper, which a furnace
 * melts into ingots ({@code recipe/scrap_iron.json} and {@code scrap_copper.json}), and cloth, which unpicks into string
 * ({@code recipe/string_from_cloth.json}). The crate's loot table is {@code loot_table/blocks/salvage_crate.json}.
 */
final class SurfaceItems {
    private SurfaceItems() {}

    /** Registers the items. */
    static void register() {
        item("scrap_iron");
        item("scrap_copper");
        item("salvage_cloth");
    }

    private static void item(String path) {
        ResourceKey<Item> key =
                ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
        Registry.register(BuiltInRegistries.ITEM, key, new Item(new Item.Properties().setId(key)));
    }
}
