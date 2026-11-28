package io.github.pkeppeler.deepcharter.hangar;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.Serials;
import io.github.pkeppeler.deepcharter.terminal.TerminalAction;
import io.github.pkeppeler.deepcharter.terminal.TerminalActions;
import io.github.pkeppeler.deepcharter.terminal.TerminalFeatures;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.transmission.Transmissions;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The hangar console: a terminal of its own, repaired by the four {@link HangarParts} (which repairs the founding Mole, see
 * {@link Hangar}), and then the place to assemble a player's one Mole ({@link #ASSEMBLE_MOLE}), to restore a wreck
 * ({@link #RESTORE_WRECK}) or scrap one ({@link #SCRAP_WRECK}), and to write off a wreck that cannot be reached
 * ({@link #WRITE_OFF}). No action takes arguments, so the client sends nothing the server must trust.
 *
 * <p>A restore that takes catalysts may be paid in part from the Company's advance ({@link HangarTuning.RestoreCost#advance}): the
 * Company lends each charter the Cicatrium of its first Prospector against its contract, because the ore does not pay that out in
 * time (issue 209). The pack's Cicatrium is spent first.
 *
 * <p>An action does every check, and everything that can throw or refuse, before it takes money or the catalyst: a refusal
 * changes nothing. The catalyst and money go last.
 */
public final class HangarTerminal {
    public static final Identifier ASSEMBLE_MOLE = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "assemble_mole");
    public static final Identifier RESTORE_WRECK = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "restore_wreck");
    public static final Identifier SCRAP_WRECK = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "scrap_wreck");
    public static final Identifier WRITE_OFF = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "write_off");
    /**
     * The console's id is not {@code hangar}: the colony stands a terminal on the plinth of the anchor with the type's name, and the
     * hangar anchor is the bay, not a plinth. {@link Hangar} places the console beside the bay.
     */
    public static final TerminalType TYPE = TerminalTypes.register(
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "hangar_console"), HangarParts.ALL);

    private static final int TICKS_PER_MINUTE = 1200;
    private static final int TICKS_PER_SECOND = 20;
    /** How long a first press on Write Off stays armed for the second, in ticks: thirty seconds. */
    private static final int ARM_WINDOW_TICKS = 600;
    /**
     * Players who pressed Write Off once, by the game time they did. Forgotten when the server stops; a lapsed one is
     * ignored.
     */
    private static final Map<UUID, Long> ARMED = new HashMap<>();

    private HangarTerminal() {}

    static void register() {
        TerminalActions.register(TYPE, ASSEMBLE_MOLE, HangarTerminal::assembleMole);
        TerminalActions.register(TYPE, RESTORE_WRECK, HangarTerminal::restoreWreck);
        TerminalActions.register(TYPE, SCRAP_WRECK, HangarTerminal::scrapWreck);
        TerminalActions.register(TYPE, WRITE_OFF, HangarTerminal::writeOff);
        TerminalFeatures.register(TYPE, HangarView.STREAM_CODEC, HangarTerminal::view);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ARMED.clear());
    }

    /**
     * What the Prospector's advance has left for the viewing charter, and the state of the viewing player's Mole. Never
     * throws: unreadable records show none.
     */
    public static HangarView view(
            MinecraftServer server, ServerPlayer player, Optional<Charter> charter, BlockPos pos) {
        Optional<HangarData> data = HangarData.readable(server);
        if (data.isEmpty() || charter.isEmpty()) {
            return new HangarView(0, HangarView.MoleState.NONE, 0, 0);
        }
        int advanceLeft = data.get()
                .advanceLeft(
                        charter.get().id(),
                        HangarTuning.DEFAULT.restoreCost(Chassis.PROSPECTOR).advance());
        Optional<HangarData.Slot> slot = data.get().slotOf(player.getUUID());
        long now = server.overworld().getGameTime();
        return new HangarView(
                advanceLeft,
                slot.map(held -> held.writeOffDue().isPresent()
                                ? HangarView.MoleState.WRITING_OFF
                                : held.wrecked() ? HangarView.MoleState.WRECKED : HangarView.MoleState.WORKING)
                        .orElse(HangarView.MoleState.NONE),
                slot.flatMap(HangarData.Slot::writeOffDue)
                        .map(due -> (int) Math.max(0, Math.min(Integer.MAX_VALUE, due - now)))
                        .orElse(0),
                armedTicksLeft(player, slot, now));
    }

    /**
     * The write-off's wait in real minutes, the one unit every line about it uses: a Minecraft day is 24,000 ticks, 20
     * minutes.
     */
    static long writeOffMinutes(HangarTuning tuning) {
        return tuning.writeOffTicks() / TICKS_PER_MINUTE;
    }

    /**
     * The ticks left of the player's first press on Write Off, 0 unless their Mole is a wreck with no write-off filed.
     */
    private static int armedTicksLeft(ServerPlayer player, Optional<HangarData.Slot> slot, long now) {
        Long armedAt = ARMED.get(player.getUUID());
        boolean wreckedUnfiled =
                slot.map(held -> held.wrecked() && held.writeOff().isEmpty()).orElse(false);
        return armedAt == null || !wreckedUnfiled ? 0 : (int) Math.max(0, ARM_WINDOW_TICKS - (now - armedAt));
    }

    private static Optional<Component> refuse(String reason, Object... args) {
        return Optional.of(Component.translatable("deepcharter.hangar.refusal." + reason, args));
    }

    /**
     * Assembles the player's one Mole from the four {@link HangarParts} in their pack, with no fee. It stands in a free
     * place in the bay, registered to the player's charter, and holds the player's place until it is scrapped or
     * written off ({@link HangarData.Slot}).
     */
    private static Optional<Component> assembleMole(TerminalAction.Context context) {
        MinecraftServer server = context.server();
        Optional<HangarData> data = HangarData.readable(server);
        if (data.isEmpty()) {
            return refuse("unreadable");
        }
        if (!data.get().state().founded()) {
            return refuse("founding_pending");
        }
        Optional<BlockPos> anchor = Colony.anchor(server, ColonyAnchor.HANGAR);
        if (anchor.isEmpty()) {
            return refuse("no_bay");
        }
        Charter charter = context.charter()
                .orElseThrow(() -> new IllegalStateException("the hangar console is for charters only"));
        ServerPlayer player = context.player();
        Optional<HangarData.Slot> held = data.get().slotOf(player.getUUID());
        if (held.isPresent()) {
            if (held.get().writeOffDue().isPresent()) {
                long left = held.get().writeOffDue().get() - server.overworld().getGameTime();
                return refuse("mole_writing_off", Math.max(1, (left + TICKS_PER_MINUTE - 1) / TICKS_PER_MINUTE));
            }
            return refuse(held.get().wrecked() ? "mole_wrecked" : "mole_held");
        }
        for (Item part : HangarParts.ALL) {
            if (count(player.getInventory(), part) < 1) {
                return refuse("missing_parts");
            }
        }
        ServerLevel level = server.overworld();
        Optional<Vec3> slot = Hangar.freeSlot(level, anchor.get(), Chassis.MOLE);
        if (slot.isEmpty()) {
            return refuse("bay_full");
        }
        PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.TRIGGERED);
        if (pod == null) {
            return refuse("pod_failed");
        }
        pod.setPos(slot.get());
        if (!Serials.get(server).isReadable()) {
            // No pod is registered without a serial: nothing has been taken yet.
            return refuse("serials_unreadable");
        }
        PodComponents.register(pod, charter.id());
        String serial = PodComponents.registration(pod).orElseThrow().serial();
        // The pod enters the world before the parts leave the pack, so a world that refuses it takes nothing.
        if (!level.addFreshEntity(pod)) {
            return refuse("pod_failed");
        }
        data.get().claim(player.getUUID(), pod.getUUID(), false);
        for (Item part : HangarParts.ALL) {
            take(player.getInventory(), part, 1);
        }
        player.sendOverlayMessage(Component.translatable(
                "deepcharter.hangar.assembled", pod.chassis().id().toUpperCase(Locale.ROOT), serial));
        return Optional.empty();
    }

    /**
     * Every wreck in reach of the console, the nearest first, except the founding Mole before it is repaired. Scrap and
     * restore both take the first one the charter may use: the player names no wreck, so the client sends nothing the
     * server must trust.
     */
    private static List<PodEntity> wrecksInReach(TerminalAction.Context context, HangarData data, HangarTuning tuning) {
        Vec3 console = Vec3.atCenterOf(context.pos());
        // entity-query: wreckRadius round the console the player stands within reach of is within 30 blocks of the
        // player, which tick at the lowest simulation distance (EntityQueryAuditTest measures it); a miss refuses
        // before anything is spent
        return context
                .player()
                .level()
                .getEntitiesOfClass(PodEntity.class, new AABB(context.pos()).inflate(tuning.wreckRadius()))
                .stream()
                .filter(pod -> Wrecks.isWreck(pod)
                        && !Hangar.isUnrepairedDerelict(data, pod)
                        && pod.position().distanceTo(console) <= tuning.wreckRadius())
                .sorted(Comparator.comparingDouble(pod -> pod.position().distanceToSqr(console)))
                .toList();
    }

    /** The reason, as a refusal, that none of {@code inReach} is the charter's to use; empty when one is. */
    private static Optional<Component> unusable(List<PodEntity> inReach, Optional<Charter> charter) {
        if (inReach.isEmpty()) {
            return refuse("no_wreck");
        }
        if (inReach.stream().noneMatch(pod -> PodComponents.mayAccess(pod, charter))) {
            return PodComponents.registration(inReach.getFirst())
                    .map(registration -> refuse("not_your_wreck", registration.serial()))
                    .orElseGet(() -> refuse("wreck_unreadable"));
        }
        return Optional.empty();
    }

    /**
     * Scraps the nearest wreck of the charter in reach of the console, for no money: the hold's contents go to the
     * player's pack (what does not fit drops at their feet), the fitted parts drop as items at the wreck, and the pod
     * is gone. If it held a player's Mole place, the place is free. Only a wreck the charter owns can be scrapped, so a
     * wreck nobody owns is restored first.
     */
    private static Optional<Component> scrapWreck(TerminalAction.Context context) {
        MinecraftServer server = context.server();
        Optional<HangarData> data = HangarData.readable(server);
        if (data.isEmpty()) {
            return refuse("unreadable");
        }
        Charter charter = context.charter()
                .orElseThrow(() -> new IllegalStateException("the hangar console is for charters only"));
        List<PodEntity> inReach = wrecksInReach(context, data.get(), HangarTuning.DEFAULT);
        Optional<Component> refusal = unusable(inReach, Optional.of(charter));
        if (refusal.isPresent()) {
            return refusal;
        }
        // Only registered wrecks the charter may use count, so a nearer wreck nobody owns never blocks the scrap of one
        // that is owned.
        List<PodEntity> owned = inReach.stream()
                .filter(pod -> PodComponents.registration(pod).isPresent()
                        && PodComponents.mayAccess(pod, Optional.of(charter)))
                .toList();
        if (owned.isEmpty()) {
            return refuse("no_scrappable_wreck");
        }
        UUID caller = context.player().getUUID();
        // The caller's own Mole first, wherever it lies among the wrecks in reach; otherwise the nearest wreck that
        // holds no member's place, or that the Director may scrap as a fallback.
        PodEntity wreck = owned.stream()
                .filter(pod -> data.get()
                        .slotHolding(pod.getUUID())
                        .map(held -> held.player().equals(caller))
                        .orElse(false))
                .findFirst()
                .or(() -> owned.stream()
                        .filter(pod -> data.get()
                                .slotHolding(pod.getUUID())
                                .map(held -> charter.isDirector(caller))
                                .orElse(true))
                        .findFirst())
                .orElse(null);
        if (wreck == null) {
            return refuse("not_your_place");
        }
        if (!wreck.cargo().isReadable()) {
            return refuse("wreck_unreadable");
        }
        for (ComponentTrack track : ComponentTrack.values()) {
            PodComponents.partOf(wreck, track)
                    .ifPresent(label -> wreck.level()
                            .addFreshEntity(new ItemEntity(
                                    wreck.level(),
                                    wreck.getX(),
                                    wreck.getY() + wreck.getBbHeight(),
                                    wreck.getZ(),
                                    ComponentItems.stackOf(track, label))));
        }
        handBackPack(server, data.get(), wreck);
        Wrecks.dropPack(wreck);
        Wrecks.salvage(context.player(), wreck);
        Hangar.cancelWriteOff(server, data.get(), wreck.getUUID());
        data.get().release(wreck.getUUID());
        wreck.discard();
        context.player().sendOverlayMessage(Component.translatable("deepcharter.hangar.scrapped"));
        return Optional.empty();
    }

    /**
     * Files a write-off of the player's own wreck, wherever it lies: for a wreck that cannot be reached (lava, the
     * void). It costs {@link HangarTuning#writeOffCost} from the charter's account now. One in-game day later the place
     * is free and the wreck is destroyed with its parts and cargo ({@code Hangar.settleWriteOffs}). Only a recorded
     * wreck can be written off: a working Mole that is merely stuck cannot, so no one writes a pod off to skip the way
     * home.
     */
    private static Optional<Component> writeOff(TerminalAction.Context context) {
        MinecraftServer server = context.server();
        Optional<HangarData> data = HangarData.readable(server);
        if (data.isEmpty()) {
            return refuse("unreadable");
        }
        Charter charter = context.charter()
                .orElseThrow(() -> new IllegalStateException("the hangar console is for charters only"));
        Optional<HangarData.Slot> held = data.get().slotOf(context.player().getUUID());
        if (held.isEmpty()) {
            return refuse("no_mole");
        }
        if (held.get().writeOffDue().isPresent()) {
            return refuse("mole_writing_off");
        }
        if (!held.get().wrecked()) {
            return refuse("mole_not_wrecked");
        }
        long cost = HangarTuning.DEFAULT.writeOffCost();
        if (charter.account() < cost) {
            return refuse("insufficient_funds", cost);
        }
        // Press once to arm, with the terms in full; press again within the window to file.
        long now = server.overworld().getGameTime();
        Long armedAt = ARMED.get(context.player().getUUID());
        if (armedAt == null || now - armedAt > ARM_WINDOW_TICKS) {
            ARMED.put(context.player().getUUID(), now);
            // The terms are on the console's screen while it is armed; this line only says it is.
            context.player()
                    .sendOverlayMessage(Component.translatable(
                            "deepcharter.hangar.write_off_armed", ARM_WINDOW_TICKS / TICKS_PER_SECOND));
            return Optional.empty();
        }
        ARMED.remove(context.player().getUUID());
        Optional<CharterRefusal> refusal = Charters.spend(server, charter.id(), cost);
        if (refusal.isPresent()) {
            throw new IllegalStateException("a write-off that passed the funds check was refused: " + refusal.get());
        }
        data.get()
                .fileWriteOff(
                        held.get().pod(),
                        new HangarData.WriteOff(
                                now + HangarTuning.DEFAULT.writeOffTicks(), Optional.of(charter.id()), cost));
        context.player()
                .sendOverlayMessage(Component.translatable(
                        "deepcharter.hangar.write_off_filed", cost, writeOffMinutes(HangarTuning.DEFAULT)));
        return Optional.empty();
    }

    /**
     * Gives the wreck's pack back to its pilots, or the place holder for a pilot who is away, and tells each player who
     * got stacks.
     */
    private static void handBackPack(MinecraftServer server, HangarData data, PodEntity wreck) {
        Wrecks.returnPack(server, wreck, data.slotHolding(wreck.getUUID()).map(HangarData.Slot::player))
                .forEach((id, count) -> {
                    ServerPlayer recipient = server.getPlayerList().getPlayer(id);
                    if (recipient != null) {
                        recipient.sendSystemMessage(Component.translatable("deepcharter.hangar.pack_returned", count));
                    }
                });
    }

    /** Restores the nearest wreck in reach of the console that the charter may access, for money and the catalyst. */
    private static Optional<Component> restoreWreck(TerminalAction.Context context) {
        MinecraftServer server = context.server();
        HangarTuning tuning = HangarTuning.DEFAULT;
        Optional<HangarData> data = HangarData.readable(server);
        if (data.isEmpty()) {
            return refuse("unreadable");
        }
        Charter charter = context.charter()
                .orElseThrow(() -> new IllegalStateException("the hangar console is for charters only"));
        ServerPlayer player = context.player();
        List<PodEntity> inReach = wrecksInReach(context, data.get(), tuning);
        Optional<Component> unusable = unusable(inReach, Optional.of(charter));
        if (unusable.isPresent()) {
            return unusable;
        }
        PodEntity wreck = inReach.stream()
                .filter(pod -> PodComponents.mayAccess(pod, Optional.of(charter)))
                .findFirst()
                .orElseThrow();
        HangarTuning.RestoreCost cost = tuning.restoreCost(wreck.chassis());
        // A wreck nobody owns is registered to the charter that restores it. The lore wreck keeps its own serial; any
        // other takes one.
        boolean unowned = PodComponents.registration(wreck).isEmpty();
        boolean lore = PodComponents.isFamousProspector(wreck);
        if (unowned && !Serials.get(server).isReadable()) {
            return refuse("serials_unreadable");
        }
        if (unowned && lore && Serials.get(server).isFamousProspectorHeld()) {
            return refuse("serial_held", Serials.FAMOUS_PROSPECTOR);
        }
        if (charter.account() < cost.money()) {
            return refuse("insufficient_funds", cost.money());
        }
        // A restore ends the wreck: a pack stack goes to its pilot or the holder, so wait for one of them.
        if (Wrecks.packHasNoRecipient(
                server, wreck, data.get().slotHolding(wreck.getUUID()).map(HangarData.Slot::player))) {
            return refuse("pack_owner_away");
        }
        Item catalyst = OreRegistry.item(tuning.catalyst());
        int fromPack = Math.min(count(player.getInventory(), catalyst), cost.catalysts());
        int advanced = Math.min(cost.catalysts() - fromPack, data.get().advanceLeft(charter.id(), cost.advance()));
        if (fromPack + advanced < cost.catalysts()) {
            return refuse(
                    "missing_catalyst",
                    cost.catalysts(),
                    new ItemStack(catalyst).getHoverName().getString());
        }
        try {
            Wrecks.restore(wreck, wreck.maxHull());
        } catch (IllegalStateException unreadable) {
            DeepCharter.LOGGER.error("Could not restore pod {}: {}", wreck.getUUID(), unreadable.getMessage());
            return refuse("restore_failed");
        }
        if (unowned) {
            if (lore) {
                PodComponents.registerLoreWreck(wreck, charter.id());
            } else {
                PodComponents.register(wreck, charter.id());
                // The name a wreck was given is the wreck's: the registration names the pod now.
                wreck.setCustomName(null);
            }
        }
        handBackPack(server, data.get(), wreck);
        take(player.getInventory(), catalyst, fromPack);
        if (advanced > 0) {
            data.get().spendAdvance(charter.id(), advanced);
        }
        Optional<CharterRefusal> refusal = Charters.spend(server, charter.id(), cost.money());
        if (refusal.isPresent()) {
            throw new IllegalStateException("a restore that passed the funds check was refused: " + refusal.get());
        }
        player.sendOverlayMessage(
                advanced > 0
                        ? Component.translatable(
                                "deepcharter.hangar.restored_advance",
                                cost.money(),
                                advanced,
                                new ItemStack(catalyst).getHoverName().getString())
                        : Component.translatable("deepcharter.hangar.restored", cost.money()));
        cost.transmission().ifPresent(transmission -> Transmissions.fire(server, charter.id(), transmission));
        HangarEvents.RESTORED.invoker().onRestored(server, player, wreck);
        return Optional.empty();
    }

    private static int count(Inventory inventory, Item item) {
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(item)) {
                total += inventory.getItem(slot).getCount();
            }
        }
        return total;
    }

    /** Takes {@code amount} of {@code item}; the caller has checked that the inventory holds that many. */
    private static void take(Inventory inventory, Item item, int amount) {
        int left = amount;
        for (int slot = 0; slot < inventory.getContainerSize() && left > 0; slot++) {
            if (inventory.getItem(slot).is(item)) {
                left -= inventory.removeItem(slot, left).getCount();
            }
        }
        if (left > 0) {
            throw new IllegalStateException("the inventory held fewer than the " + amount + " that were counted");
        }
    }
}
