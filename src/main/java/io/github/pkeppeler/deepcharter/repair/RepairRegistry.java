package io.github.pkeppeler.deepcharter.repair;

import io.github.pkeppeler.deepcharter.pod.TetherItem;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;

/** Registers the consumables and the repair station's actions. */
public final class RepairRegistry {
    private static final Map<Consumable, Item> ITEMS = new EnumMap<>(Consumable.class);

    static {
        for (Consumable consumable : Consumable.values()) {
            ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, consumable.itemId());
            Item item = consumable.kind() == Consumable.Kind.TOOL
                    ? new TetherItem(new Item.Properties().setId(key).stacksTo(1))
                    : new ConsumableItem(
                            consumable, new Item.Properties().setId(key).stacksTo(16));
            ITEMS.put(consumable, Registry.register(BuiltInRegistries.ITEM, key, item));
        }
    }

    private RepairRegistry() {}

    public static void register() {
        RepairStation.register();
    }

    public static Item item(Consumable consumable) {
        return Objects.requireNonNull(
                ITEMS.get(consumable),
                "no item registered for " + consumable + "; the static block registers every Consumable");
    }
}
