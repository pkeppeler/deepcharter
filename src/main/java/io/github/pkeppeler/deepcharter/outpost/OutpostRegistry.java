package io.github.pkeppeler.deepcharter.outpost;

import io.github.pkeppeler.deepcharter.DeepCharter;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

/** Registers the outpost kit. The outpost's pump and sell terminal are the colony's own terminals ({@code TerminalTypes}). */
public final class OutpostRegistry {
    /** Used on the floor of layer 1, it builds the charter's outpost there ({@link OutpostBuilder}). */
    public static final Item KIT = kit("outpost_kit");

    private OutpostRegistry() {}

    /** Loads the class, which registers the item. */
    public static void register() {}

    private static Item kit(String path) {
        ResourceKey<Item> key =
                ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
        return Registry.register(
                BuiltInRegistries.ITEM,
                key,
                new OutpostKit(new Item.Properties().setId(key).stacksTo(4)));
    }
}
