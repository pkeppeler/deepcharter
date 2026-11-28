package io.github.pkeppeler.deepcharter.hangar;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonyEvents;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.Serials;
import io.github.pkeppeler.deepcharter.scanner.LoadedBlocks;
import io.github.pkeppeler.deepcharter.terminal.TerminalBlock;
import io.github.pkeppeler.deepcharter.terminal.TerminalEvents;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.transmission.Transmissions;
import io.github.pkeppeler.deepcharter.wreck.WreckEvents;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * The colony hangar. When the colony is built it gets a console (the {@link HangarTerminal}) and the derelict founding Mole,
 * a wreck that nobody owns. The first charter to put in the last of the four {@link HangarParts} repairs the Mole and owns it
 * as {@code MOLE-0001}, because every other way to get a registered Mole needs the console to be repaired first.
 *
 * <p>Every method here is safe on a callback path: unreadable hangar data is logged once and skipped.
 */
public final class Hangar {
    /**
     * T06, "The Mole is flying again!": the founding Mole handed to its charter, or the first Mole a charter crafts.
     */
    public static final Identifier MOLE_TRANSMISSION = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "t06");
    /** The console stands this far from the hangar anchor, at its east side facing the bay. */
    private static final BlockPos CONSOLE_OFFSET = new BlockPos(5, 0, -3);
    /** The write-offs are checked this often, in ticks: a second. */
    private static final int WRITE_OFF_CHECK_TICKS = 20;

    private static final Identifier AFTER_UNLOADS =
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "hangar_after_unloads");

    private Hangar() {}

    static void register() {
        ColonyEvents.BUILT.register(Hangar::onBuilt);
        TerminalEvents.REPAIRED.register(Hangar::onRepaired);
        ServerEntityEvents.ENTITY_LOAD.register(Hangar::onLoad);
        PodEvents.REGISTERED.register(Hangar::onRegistered);
        WreckEvents.WRECKED.register(pod -> markWrecked(pod, true));
        WreckEvents.RESTORED.register(Hangar::onRestored);
        // Last: a removal listener that throws then leaves the place held.
        ServerEntityEvents.ENTITY_UNLOAD.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER_UNLOADS);
        ServerEntityEvents.ENTITY_UNLOAD.register(AFTER_UNLOADS, Hangar::onUnload);
        ServerTickEvents.END_SERVER_TICK.register(Hangar::onTick);
    }

    /**
     * A wreck was made a working pod again: its place says so, and a write-off filed for it is cancelled and refunded.
     */
    private static void onRestored(PodEntity pod) {
        markWrecked(pod, false);
        if (pod.level() instanceof ServerLevel level) {
            HangarData.readable(level.getServer())
                    .ifPresent(data -> cancelWriteOff(level.getServer(), data, pod.getUUID()));
        }
    }

    /**
     * Takes back the write-off filed for the pod, if one is, and gives its price back to the charter that paid it (when
     * that charter still exists). Used when the wreck is restored or scrapped before the day is out.
     */
    static void cancelWriteOff(MinecraftServer server, HangarData data, UUID pod) {
        Optional<HangarData.WriteOff> filed = data.slotHolding(pod).flatMap(HangarData.Slot::writeOff);
        if (filed.isEmpty()) {
            return;
        }
        data.cancelWriteOff(pod);
        Optional<CharterRefusal> refused = Charters.readableFind(
                        server, filed.get().payer())
                .flatMap(charter ->
                        Charters.deposit(server, charter.id(), filed.get().paid()));
        if (refused.isPresent()) {
            DeepCharter.LOGGER.error("Could not refund the write-off of pod {}: {}", pod, refused.get());
        }
    }

    /**
     * A pod that is removed for good (killed or discarded, not unloaded with its chunk or moved across a seam) frees
     * the place it held, so no way of losing a Mole can lock its owner out of another.
     */
    private static void onUnload(Entity entity, ServerLevel level) {
        if (!(entity instanceof PodEntity pod)) {
            return;
        }
        Entity.RemovalReason reason = pod.getRemovalReason();
        if (reason != Entity.RemovalReason.KILLED && reason != Entity.RemovalReason.DISCARDED) {
            return;
        }
        HangarData.readable(level.getServer()).ifPresent(data -> data.release(pod.getUUID()));
    }

    /** Records on the pod's Mole place, if it holds one, whether the pod is a wreck. Never throws. */
    private static void markWrecked(PodEntity pod, boolean wrecked) {
        if (pod.level() instanceof ServerLevel level) {
            HangarData.readable(level.getServer()).ifPresent(data -> data.markWrecked(pod.getUUID(), wrecked));
        }
    }

    /**
     * Checks the write-offs once a second, against the game time, not the time of day: sleeping must not shorten the
     * day one takes.
     */
    private static void onTick(MinecraftServer server) {
        if (server.getTickCount() % WRITE_OFF_CHECK_TICKS == 0) {
            settleWriteOffs(server, server.overworld().getGameTime());
        }
    }

    /**
     * Takes the write-offs that are due at game time {@code now}: frees the place, and destroys the wreck with its
     * parts and cargo, without a drop. A wreck that is not loaded is condemned and destroyed when it loads ({@link
     * #onLoad}). One bad record never stops the rest.
     */
    public static void settleWriteOffs(MinecraftServer server, long now) {
        Optional<HangarData> data = HangarData.readable(server);
        if (data.isEmpty()) {
            return;
        }
        for (HangarData.Slot slot : data.get().writeOffs()) {
            // A wreck restored since it was filed is no longer a wreck: its filing was cancelled, and a stale one never
            // destroys it.
            if (slot.writeOffDue().orElseThrow() > now || !slot.wrecked()) {
                continue;
            }
            try {
                // Destroy first and free the place after, so a step that fails leaves the place held and the write-off
                // due again.
                Optional<PodEntity> pod = podOf(server, slot.pod());
                if (pod.isPresent()) {
                    pod.get().discard();
                } else {
                    data.get().condemn(slot.pod());
                }
                data.get().release(slot.pod());
                DeepCharter.LOGGER.info("Wrote off the wreck {} of player {}", slot.pod(), slot.player());
            } catch (RuntimeException e) {
                DeepCharter.LOGGER.error("Could not take the write-off of pod {}: it is tried again", slot.pod(), e);
            }
        }
    }

    /**
     * Places the console on every build, since a rebuild clears the pad (its repair lives in {@code RepairState}), and the
     * derelict Mole once. A colony built before the hangar existed is not given one.
     */
    public static void onBuilt(MinecraftServer server, ColonySite.Placed colony) {
        ServerLevel level = server.overworld();
        BlockPos anchor = ColonyAnchor.HANGAR.in(colony.anchors());
        placeConsole(level, anchor);
        Optional<HangarData> data = HangarData.readable(server);
        if (data.isEmpty() || data.get().state().derelict().isPresent()) {
            return;
        }
        PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.STRUCTURE);
        if (pod == null) {
            DeepCharter.LOGGER.error("Could not make the derelict Mole for the hangar: the pod was not created");
            return;
        }
        pod.setPos(Vec3.atBottomCenterOf(anchor));
        pod.setHull(0f);
        if (!level.addFreshEntity(pod)) {
            DeepCharter.LOGGER.error(
                    "Could not place the derelict Mole at {}: the world refused the entity", anchor.toShortString());
            return;
        }
        data.get().placeDerelict(pod.getUUID());
        DeepCharter.LOGGER.info(
                "Placed the derelict Mole {} in the hangar at {}", pod.getUUID(), anchor.toShortString());
    }

    private static void placeConsole(ServerLevel level, BlockPos anchor) {
        BlockPos console = anchor.offset(CONSOLE_OFFSET);
        if (!level.getBlockState(console).is(HangarTerminal.TYPE.block())) {
            BlockState state = HangarTerminal.TYPE
                    .block()
                    .defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST);
            TerminalBlock.place(level, console, state, 3);
        }
    }

    private static Optional<PodEntity> podOf(MinecraftServer server, UUID id) {
        // entity-query: a miss means the Mole is not loaded, which both callers handle: completeFounding waits for its
        // ENTITY_LOAD and derelict reports it unloaded
        return Optional.ofNullable(server.overworld().getEntity(id))
                .filter(PodEntity.class::isInstance)
                .map(PodEntity.class::cast);
    }

    /** True for the founding Mole until it is repaired: it is repaired with its four parts and is not for sale as a wreck. */
    static boolean isUnrepairedDerelict(HangarData data, PodEntity pod) {
        HangarData.State state = data.state();
        return !state.founded() && state.derelict().equals(Optional.of(pod.getUUID()));
    }

    /**
     * A free place in the bay for a new pod of {@code chassis}, the nearest to the anchor first; empty when the bay is full. The
     * places are a bore width and {@link HangarTuning#slotGap()} apart, as many as fit within {@link HangarTuning#bayRadius()} of the anchor, and
     * a place is free when a pod's box fits there clear of blocks and of other pods.
     */
    public static Optional<Vec3> freeSlot(ServerLevel level, BlockPos anchor, Chassis chassis) {
        HangarTuning tuning = HangarTuning.DEFAULT;
        int spacing = chassis.boreWidth() + tuning.slotGap();
        int reach = tuning.bayRadius() / spacing * spacing;
        List<Vec3> slots = new ArrayList<>();
        for (int dx = -reach; dx <= reach; dx += spacing) {
            for (int dz = -reach; dz <= reach; dz += spacing) {
                slots.add(Vec3.atBottomCenterOf(anchor.offset(dx, 0, dz)));
            }
        }
        slots.sort(Comparator.comparingDouble(slot -> slot.distanceToSqr(Vec3.atBottomCenterOf(anchor))));
        EntityType<PodEntity> type = PodRegistry.typeOf(chassis);
        return slots.stream()
                .filter(slot -> {
                    AABB box = type.getDimensions().makeBoundingBox(slot);
                    BlockPos slotPos = BlockPos.containing(slot);
                    return LoadedBlocks.isLoaded(level, slotPos)
                            && level.noCollision(box)
                            // entity-query: the bay is within a few blocks of the player at the console (a terminal's
                            // reach), so its chunks are entity-ticking
                            && level.getEntitiesOfClass(PodEntity.class, box).isEmpty();
                })
                .findFirst();
    }

    private static void onRepaired(MinecraftServer server, TerminalType type, Charter charter, ServerPlayer player) {
        if (type != HangarTerminal.TYPE) {
            return;
        }
        Optional<HangarData> data = HangarData.readable(server);
        if (data.isEmpty()) {
            return;
        }
        if (data.get().state().founder().isEmpty()) {
            data.get().setFounder(charter.id());
        }
        // The founding Mole is the one Mole of the player who repaired it.
        data.get().state().derelict().ifPresent(derelict -> {
            if (data.get().slotOf(player.getUUID()).isEmpty()
                    && data.get().slotHolding(derelict).isEmpty()) {
                data.get().claim(player.getUUID(), derelict, false);
            }
        });
        completeFounding(server, data.get());
    }

    /**
     * Records a pod that is in the world as its owner's the moment it is registered: the price of the next pod counts from saved data, not
     * from the pods that happen to be loaded. A pod registered before it is added is recorded when it loads ({@link #onLoad}), so a pod
     * that is never added (a refused purchase) is never counted.
     */
    private static void onRegistered(PodEntity pod, CharterId owner) {
        // entity-query: asks only whether the pod in hand is in its level yet: a miss means "not added", and onLoad
        // records it when it is
        if (pod.level() instanceof ServerLevel level && level.getEntity(pod.getUUID()) == pod) {
            HangarData.readable(level.getServer()).ifPresent(data -> data.hold(owner, pod.getUUID()));
        }
    }

    private static void onLoad(Entity entity, ServerLevel level) {
        if (!(entity instanceof PodEntity pod)) {
            return;
        }
        Optional<HangarData> data = HangarData.readable(level.getServer());
        if (data.isEmpty()) {
            return;
        }
        // A pod registered before the hangar recorded every registration is recorded when it loads.
        PodComponents.registration(pod).ifPresent(registration -> data.get().hold(registration.owner(), pod.getUUID()));
        if (data.get().isCondemned(pod.getUUID())) {
            pod.discard();
            data.get().forgetCondemned(pod.getUUID());
            return;
        }
        if (data.get().state().derelict().equals(Optional.of(pod.getUUID()))) {
            giveLegacyFoundingPlace(level.getServer(), data.get(), pod);
            completeFounding(level.getServer(), data.get());
        }
    }

    /**
     * A founding Mole repaired before the one-Mole rule (data of version 1) has no place on record, because the repair
     * kept only the charter. It is given to the Director of the founding charter, once, unless they have a place
     * already. Other Moles of an older world hold no place: the rule counts only what is crafted after it (ADR 0117).
     * Never throws.
     */
    private static void giveLegacyFoundingPlace(MinecraftServer server, HangarData data, PodEntity pod) {
        HangarData.State state = data.state();
        if (!state.legacyFoundingPlace() || !state.founded() || state.founder().isEmpty()) {
            return;
        }
        Optional<Charter> founder =
                Charters.readableFind(server, state.founder().get());
        if (founder.isEmpty()) {
            // The charters cannot be read, or the founder is gone: try again at the next load.
            return;
        }
        founder.flatMap(Charter::director)
                .filter(director -> data.slotOf(director).isEmpty()
                        && data.slotHolding(pod.getUUID()).isEmpty())
                .ifPresent(director -> data.claim(director, pod.getUUID(), Wrecks.isWreck(pod)));
        data.settleFoundingPlace();
    }

    /**
     * Gives the founding Mole to the charter that repaired it: registers it (which gives it its serial) and restores it. A Mole that
     * is not loaded now is finished when it loads. Never throws.
     */
    private static void completeFounding(MinecraftServer server, HangarData data) {
        HangarData.State state = data.state();
        if (state.founded() || state.founder().isEmpty() || state.derelict().isEmpty()) {
            return;
        }
        Optional<PodEntity> pod = podOf(server, state.derelict().get());
        if (pod.isEmpty()) {
            return;
        }
        CharterId founder = state.founder().get();
        try {
            if (PodComponents.registration(pod.get()).isEmpty()) {
                if (!Serials.get(server).isReadable()) {
                    // Logged once by the check; the hangar tries again when the Mole loads.
                    return;
                }
                PodComponents.register(pod.get(), founder);
            }
            if (Wrecks.isWreck(pod.get())) {
                Wrecks.restore(pod.get(), pod.get().maxHull());
            }
            data.hold(founder, pod.get().getUUID());
            data.markFounded();
            String serial = PodComponents.registration(pod.get()).orElseThrow().serial();
            if (!serial.equals("MOLE-0001")) {
                DeepCharter.LOGGER.warn(
                        "The founding Mole got the serial {}, not MOLE-0001: another Mole was registered before the hangar console was repaired",
                        serial);
            }
        } catch (RuntimeException e) {
            DeepCharter.LOGGER.error(
                    "Could not give the founding Mole to charter {}: it stays as it is, and the hangar tries again when it loads",
                    founder,
                    e);
            return;
        }
        // The Mole is the founder's now: Personnel says so (T06), to a charter that still exists.
        if (Charters.readableFind(server, founder).isPresent()) {
            Transmissions.fire(server, founder, MOLE_TRANSMISSION);
        }
    }
}
