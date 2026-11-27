package io.github.pkeppeler.deepcharter.colony;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import java.util.Optional;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Company rations, so a new player does not starve before the first crop: the tins every player starts with, the ones the ore cars
 * still hold ({@link OreCarBlock}), the canteen's one on every use ({@link CanteenBlock}), and the poor meal a fungal stalk cooks
 * into ({@code recipe/stalk_meal_campfire.json} and {@code stalk_meal_smoking.json}; a furnace still turns a stalk into charcoal).
 *
 * <p>What a player has been handed is the attachment {@link #LEDGER}. A crate may hold a ration too
 * ({@code loot_table/blocks/salvage_crate.json}).
 */
public final class ColonyRations {
    /** Per player, persistent and kept on death: a player who died is not given the starting tins again. Never sent to a client. */
    public static final AttachmentType<Versioned<RationLedger>> LEDGER = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "rations"),
            builder -> builder.persistent(RationLedger.CODEC)
                    .copyOnDeath()
                    .initializer(() -> Versioned.of(RationLedger.DEFAULT)));

    /** A sealed Company tin: it fills about as much as bread, and keeps. */
    public static final Item COMPANY_RATION = item("company_ration", new FoodProperties(5, 4.0F, false));
    /** What a stalk cooks into: it fills, but poorly. */
    public static final Item STALK_MEAL = item("stalk_meal", new FoodProperties(3, 1.0F, false));

    /** The tins every player is given once, with the handbook. */
    static final int STARTING_RATIONS = 3;

    private ColonyRations() {}

    /** Loads the class, which registers the items and the ledger. */
    static void register() {}

    /** Gives a joining player the starting tins once. */
    static void init() {
        ServerPlayerEvents.JOIN.register(ColonyRations::giveStartingRations);
    }

    /** Gives {@code player} the starting rations if the ledger says they have none yet; a second call gives nothing. */
    static void giveStartingRations(ServerPlayer player) {
        // A new player's ledger comes from the initializer and is always readable: only data another version wrote
        // is skipped.
        Optional<RationLedger> ledger = Versioned.readable(player, LEDGER);
        if (ledger.isEmpty() || ledger.get().starterGiven()) {
            return;
        }
        player.setAttached(LEDGER, Versioned.of(new RationLedger(true)));
        give(player, STARTING_RATIONS);
    }

    /** Puts {@code count} rations in the player's inventory, and drops what does not fit at their feet. */
    static void give(ServerPlayer player, int count) {
        ItemStack stack = new ItemStack(COMPANY_RATION, count);
        player.getInventory().add(stack);
        if (!stack.isEmpty()) {
            player.spawnAtLocation(player.level(), stack);
        }
    }

    private static Item item(String path, FoodProperties food) {
        ResourceKey<Item> key =
                ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path));
        return Registry.register(
                BuiltInRegistries.ITEM,
                key,
                new Item(new Item.Properties().setId(key).food(food)));
    }
}
