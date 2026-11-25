package io.github.pkeppeler.deepcharter.fuel;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.ore.CargoWeight;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import net.fabricmc.fabric.api.item.v1.DefaultItemComponentEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Registers biofuel and the reserve tank. The pump itself is a terminal, registered by the terminal feature. */
public final class FuelRegistry {
    /** Fuel: its litres are in {@code data/deepcharter/pod_fuel/biofuel.json} and it is in the {@code pod_fuel} tag. */
    public static final Item BIOFUEL = item("biofuel", FuelTuning.DEFAULT.solidStack());
    /** Used on a pod, it fits a {@link ReserveTank}. */
    public static final Item RESERVE_TANK = item("reserve_tank", 16);

    private FuelRegistry() {}

    /** Loads the class, which registers the items, and gives the solid fuels their stack size and weight (#1280). */
    public static void register() {
        FuelTuning.Weight weight = FuelTuning.DEFAULT.weight();
        DefaultItemComponentEvents.MODIFY.register(context -> {
            context.modify(Items.COAL, builder -> solid(builder, weight.coal()));
            context.modify(Items.CHARCOAL, builder -> solid(builder, weight.charcoal()));
            context.modify(
                    BIOFUEL, builder -> builder.set(OreRegistry.CARGO_WEIGHT, new CargoWeight(weight.biofuel())));
        });
    }

    private static void solid(DataComponentMap.Builder builder, float mass) {
        builder.set(DataComponents.MAX_STACK_SIZE, FuelTuning.DEFAULT.solidStack())
                .set(OreRegistry.CARGO_WEIGHT, new CargoWeight(mass));
    }

    private static Item item(String path, int stackSize) {
        ResourceKey<Item> key =
                ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
        return Registry.register(
                BuiltInRegistries.ITEM,
                key,
                new Item(new Item.Properties().setId(key).stacksTo(stackSize)));
    }
}
