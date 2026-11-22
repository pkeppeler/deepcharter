package io.github.pkeppeler.deepcharter.handbook;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.repair.RepairEvents;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeEvents;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.Item;

/**
 * Shows a player the spec sheet of a Company item the first time they craft or buy it ({@link SpecSheets}). First time is per player,
 * not per charter: a sheet is read by the one who holds the part, and a crewmate's first craft teaches you nothing. The mark is one
 * entry in the player's {@link ReadMarks}, so it is versioned, kept on death and sent to its owner like every other one-time line, and
 * data of another version shows no sheet and never throws.
 *
 * <p>A buy is the repair station's shop ({@link RepairEvents#BOUGHT}) and a part bought at the upgrade terminal
 * ({@link UpgradeEvents#BOUGHT}), the two places that hand out or fit an item for money. A craft is read from the player's crafted
 * statistic, which vanilla raises each time a result is taken from a crafting grid, so no mixin is needed: a rise since the last
 * tick is a craft. The first look at a player takes their running totals as they are, so what they crafted before this existed (or
 * before they joined) pops nothing.
 */
public final class SpecSheetPopups {
    /** Each online player's crafted counts of the Company items, as of the last tick. */
    private static final Map<UUID, Map<Item, Integer>> CRAFTED = new HashMap<>();

    private static List<Item> companyItems = List.of();

    private SpecSheetPopups() {}

    static void init() {
        SpecSheetPayload.register();
        RepairEvents.BOUGHT.register((server, player, item) -> offer(player, RepairRegistry.item(item)));
        UpgradeEvents.BOUGHT.register((server, player, pod, track, tier) -> offer(player, ComponentItems.item(track)));
        ServerTickEvents.END_SERVER_TICK.register(
                server -> server.getPlayerList().getPlayers().forEach(SpecSheetPopups::pollCrafts));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> CRAFTED.clear());
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> CRAFTED.remove(handler.getPlayer().getUUID()));
    }

    /** The read mark that records that {@code item}'s sheet has been shown, one for each item. */
    public static Identifier markOf(Item item) {
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
        return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "spec_sheet/" + id.getPath());
    }

    /**
     * Shows {@code player} the sheet of {@code item} if it is Company issue and they have not seen it. Does nothing, and never
     * throws, for another item, a sheet already shown, marks that cannot be read, or a client that cannot be sent it. Returns whether
     * the sheet was sent.
     */
    public static boolean offer(ServerPlayer player, Item item) {
        if (!SpecSheets.isCompany(item) || !ReadMarks.isReadable(player) || !SpecSheetPayload.canSend(player)) {
            return false;
        }
        Identifier mark = markOf(item);
        if (ReadMarks.isRead(player, mark)) {
            return false;
        }
        ReadMarks.mark(player, mark);
        SpecSheetPayload.send(player, new SpecSheetPayload(BuiltInRegistries.ITEM.getKey(item)));
        return true;
    }

    private static List<Item> companyItems() {
        if (companyItems.isEmpty()) {
            companyItems = BuiltInRegistries.ITEM.stream()
                    .filter(SpecSheets::isCompany)
                    .toList();
        }
        return companyItems;
    }

    /** Offers each Company item whose crafted count rose since the last tick. */
    private static void pollCrafts(ServerPlayer player) {
        ServerStatsCounter stats = player.getStats();
        Map<Item, Integer> known = CRAFTED.get(player.getUUID());
        Map<Item, Integer> seen = known == null ? new HashMap<>() : known;
        CRAFTED.put(player.getUUID(), seen);
        for (Item item : companyItems()) {
            int crafted = stats.getValue(Stats.ITEM_CRAFTED.get(item));
            Integer before = seen.put(item, crafted);
            if (known != null && crafted > (before == null ? 0 : before)) {
                offer(player, item);
            }
        }
    }
}
