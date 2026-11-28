package io.github.pkeppeler.deepcharter.wreck;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.handbook.HandbookItems;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerChain.Band;
import io.github.pkeppeler.deepcharter.pod.PodCargo;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;

/**
 * A pod whose hull reached 0 is a wreck (#67): unpowered (so dark, with no light for #75 to give it), not mountable,
 * never removed, and its cargo stays in the bay for anyone to {@link #salvage}. The state is the pod's
 * {@link WreckRegistry#STATE} attachment; {@link #restore} is the hook the hangar (#77) uses to repair one.
 *
 * <p>Saved wreck state this build cannot read is never guessed at and never overwritten: {@link #isWreck} says no, the
 * event and load paths log an error and carry on, and only {@link #restore} throws.
 */
public final class Wrecks {
    private Wrecks() {}

    /** True when the pod is a wreck. Safe on either side; the client sees the synced state. Unreadable state is not a wreck. */
    public static boolean isWreck(PodEntity pod) {
        return Versioned.readable(pod, WreckRegistry.STATE)
                .map(WreckState::wrecked)
                .orElse(false);
    }

    /**
     * Turns a wreck back into a working pod with {@code hull} points of hull, which must be above 0.
     *
     * @throws IllegalArgumentException if {@code hull} is not above 0
     * @throws IllegalStateException    if the pod is not a wreck, or its saved wreck state is unreadable
     */
    public static void restore(PodEntity pod, float hull) {
        if (!(hull > 0f)) {
            throw new IllegalArgumentException("a restored pod needs hull above 0, got " + hull);
        }
        if (!Versioned.orThrow(pod, WreckRegistry.STATE).wrecked()) {
            throw new IllegalStateException("pod " + pod.getUUID() + " is not a wreck");
        }
        Versioned.modifyOrThrow(pod, WreckRegistry.STATE, state -> WreckState.INTACT);
        pod.setHull(hull);
        WreckEvents.RESTORED.invoker().onRestored(pod);
    }

    /**
     * Server only: moves everything in the wreck's hold to {@code player}, ore and the crew's stores alike, dropping
     * what does not fit, and the stacks that {@code player} themselves carried when the hull ran out. Anyone may take
     * the hold; no one takes another player's pack. Tells the player what happened, and returns how many stacks moved.
     * Unreadable cargo is left alone and reported, not thrown.
     */
    public static int salvage(ServerPlayer player, PodEntity pod) {
        if (!isWreck(pod)) {
            throw new IllegalStateException("pod " + pod.getUUID() + " is not a wreck");
        }
        PodCargo cargo = pod.cargo();
        List<ItemStack> held = cargo.isReadable() ? cargo.takeAll(pod) : List.of();
        List<ItemStack> own = takePack(pod, pilot -> pilot.equals(player.getUUID())).stream()
                .map(WreckPack.Entry::stack)
                .toList();
        if (held.isEmpty() && own.isEmpty()) {
            player.sendSystemMessage(Component.translatable(
                    cargo.isReadable() ? "deepcharter.wreck.salvage.empty" : "deepcharter.wreck.salvage.unreadable"));
            return 0;
        }
        List<ItemStack> all = new ArrayList<>(held);
        all.addAll(own);
        give(player, all);
        player.sendSystemMessage(Component.translatable("deepcharter.wreck.salvage.success", all.size()));
        return all.size();
    }

    /**
     * Server only: gives the pack back, which the hangar restore and the scrap do. Each stack goes to the player who
     * carried it, if they are online and alive. A stack whose pilot is offline, dead or gone goes to {@code holder}
     * (the Mole's owner) if they are online and alive; with neither it stays in the wreck. The hold is not
     * touched. Unreadable saved pack is left alone. Returns how many stacks each player was given.
     */
    public static Map<UUID, Integer> returnPack(MinecraftServer server, PodEntity pod, Optional<UUID> holder) {
        Optional<ServerPlayer> fallback = holder.flatMap(id -> livePlayer(server, id));
        Map<ServerPlayer, List<ItemStack>> given = new LinkedHashMap<>();
        for (WreckPack.Entry entry :
                takePack(pod, pilot -> recipient(server, pilot, fallback).isPresent())) {
            given.computeIfAbsent(recipient(server, entry.pilot(), fallback).orElseThrow(), player -> new ArrayList<>())
                    .add(entry.stack());
        }
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        given.forEach((player, stacks) -> {
            give(player, stacks);
            counts.put(player.getUUID(), stacks.size());
        });
        return counts;
    }

    /**
     * True when the wreck's pack holds a stack that {@link #returnPack} could not give anyone now: its pilot and the
     * Mole's owner are both offline or dead.
     */
    public static boolean packHasNoRecipient(MinecraftServer server, PodEntity pod, Optional<UUID> holder) {
        Optional<ServerPlayer> fallback = holder.flatMap(id -> livePlayer(server, id));
        return Versioned.readable(pod, WreckRegistry.PACK).stream()
                .flatMap(pack -> pack.entries().stream())
                .anyMatch(entry -> recipient(server, entry.pilot(), fallback).isEmpty());
    }

    /** The player who gets a pilot's stacks: the pilot if online and alive, else the fallback. */
    private static Optional<ServerPlayer> recipient(
            MinecraftServer server, UUID pilot, Optional<ServerPlayer> fallback) {
        return livePlayer(server, pilot).or(() -> fallback);
    }

    /** A dead player has no pack to fill: what they were given would be lost at their respawn. */
    private static Optional<ServerPlayer> livePlayer(MinecraftServer server, UUID id) {
        return Optional.ofNullable(server.getPlayerList().getPlayer(id)).filter(ServerPlayer::isAlive);
    }

    private static void give(ServerPlayer player, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            // A free slot takes any whole stack. Checked first because a creative player's add() discards what does
            // not fit.
            if (player.getInventory().getFreeSlot() < 0) {
                player.spawnAtLocation(player.level(), stack);
            } else {
                player.getInventory().add(stack);
            }
        }
    }

    /**
     * Takes out of the wreck's saved pack the entries whose pilot {@code pilots} accepts, and returns them; the rest
     * stay. Unreadable saved pack is skipped (logged once) and left as it is.
     */
    private static List<WreckPack.Entry> takePack(PodEntity pod, Predicate<UUID> pilots) {
        Optional<WreckPack> pack = Versioned.readable(pod, WreckRegistry.PACK);
        if (pack.isEmpty()) {
            return List.of();
        }
        List<WreckPack.Entry> taken = pack.get().entries().stream()
                .filter(entry -> pilots.test(entry.pilot()))
                .toList();
        if (taken.isEmpty()) {
            return List.of();
        }
        List<WreckPack.Entry> kept = pack.get().entries().stream()
                .filter(entry -> !pilots.test(entry.pilot()))
                .toList();
        Versioned.modifyOrThrow(pod, WreckRegistry.PACK, old -> new WreckPack(kept));
        return taken;
    }

    /**
     * Moves the carried stacks of each player in the crew into the wreck, so the crew's death drops nothing. A wreck
     * whose pack state is unreadable keeps the vanilla drops (logged once): nothing is overwritten.
     */
    private static void stashCrewPacks(PodEntity pod, List<Entity> crew) {
        Optional<WreckPack> saved = Versioned.readable(pod, WreckRegistry.PACK);
        if (saved.isEmpty()) {
            return;
        }
        List<WreckPack.Entry> stashed = new ArrayList<>(saved.get().entries());
        for (Entity member : crew) {
            if (member instanceof ServerPlayer player) {
                Inventory inventory = player.getInventory();
                for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                    // The handbook is bound to its holder and never enters the world: it stays, and the player is given
                    // one again.
                    if (HandbookItems.isHandbook(inventory.getItem(slot))) {
                        continue;
                    }
                    ItemStack stack = inventory.removeItemNoUpdate(slot);
                    if (!stack.isEmpty()) {
                        stashed.add(new WreckPack.Entry(player.getUUID(), stack));
                    }
                }
            }
        }
        Versioned.modifyOrThrow(pod, WreckRegistry.PACK, old -> new WreckPack(stashed));
    }

    static void onHullDepleted(PodEntity pod) {
        if (!isReadable(pod)) {
            return;
        }
        Versioned.modifyOrThrow(pod, WreckRegistry.STATE, state -> WreckState.WRECKED);
        WreckEvents.WRECKED.invoker().onWrecked(pod);
        ServerLevel level = (ServerLevel) pod.level();
        List<Entity> crew = List.copyOf(pod.getPassengers());
        Map<CharterId, Charter> charters = chartersToTell(level.getServer(), pod, crew);
        pod.ejectPassengers();
        stashCrewPacks(pod, crew);
        crew.forEach(member -> CrewFate.die(member, level));
        for (Charter charter : charters.values()) {
            try {
                report(level, charter, pod);
            } catch (RuntimeException e) {
                DeepCharter.LOGGER.error(
                        "Could not report the wreck of pod {} to charter {}: the other charters are still told",
                        pod.getUUID(),
                        charter.id(),
                        e);
            }
        }
    }

    /** A pod that comes into the world at hull 0 (saved before wrecks, or by {@code setHull} elsewhere) is a wreck too. */
    static void onLoad(Entity entity, ServerLevel level) {
        if (!(entity instanceof PodEntity pod) || pod.hull() > 0f || isWreck(pod)) {
            return;
        }
        if (!isReadable(pod)) {
            return;
        }
        DeepCharter.LOGGER.warn(
                "Pod {} loaded with hull {} and is now a wreck (a corrupt saved hull loads as 0)",
                pod.getUUID(),
                pod.hull());
        Versioned.modifyOrThrow(pod, WreckRegistry.STATE, state -> WreckState.WRECKED);
        WreckEvents.WRECKED.invoker().onWrecked(pod);
    }

    /** Using a wreck, without sneaking, salvages its cargo. Sneaking still looks into the bay, and a working pod is not touched. */
    static InteractionResult onUse(
            Player player, Level level, InteractionHand hand, Entity entity, EntityHitResult hit) {
        if (!(entity instanceof PodEntity pod)
                || !isWreck(pod)
                || hand != InteractionHand.MAIN_HAND
                || player.isSecondaryUseActive()
                || player.isSpectator()) {
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            salvage(serverPlayer, pod);
        }
        return InteractionResult.SUCCESS;
    }

    /** False only for saved state of a version this build cannot read. A pod that never had the state is readable. */
    private static boolean isReadable(PodEntity pod) {
        return Versioned.readable(pod, WreckRegistry.STATE).isPresent();
    }

    /**
     * Who is told: the pod's owner charter, and only it, even when nobody was riding. A pod with no live owner (unowned, or its
     * owner gone or dormant) tells the charter of each player in the crew, once each. Offline members are not told later: the
     * notice is a message for now, and is not kept.
     */
    private static Map<CharterId, Charter> chartersToTell(MinecraftServer server, PodEntity pod, List<Entity> crew) {
        Map<CharterId, Charter> charters = new LinkedHashMap<>();
        Optional<Charter> owner = PodComponents.ownerCharter(pod);
        if (owner.isPresent()) {
            charters.put(owner.get().id(), owner.get());
            return charters;
        }
        if (!Charters.isReadable(server)) {
            DeepCharter.LOGGER.error(
                    "The wreck of pod {} is reported to nobody, because the saved charters cannot be read; the first"
                            + " charters error logged carries the cause",
                    pod.getUUID());
            return charters;
        }
        for (Entity member : crew) {
            Charters.readableCharterOf(server, member.getUUID())
                    .ifPresent(charter -> charters.putIfAbsent(charter.id(), charter));
        }
        return charters;
    }

    /**
     * Tells the charter's online members where the pod went down, and in a layer that a tow cable brings it home,
     * then fires {@link WreckEvents#REPORTED}.
     */
    private static void report(ServerLevel level, Charter charter, PodEntity pod) {
        MinecraftServer server = level.getServer();
        BlockPos pos = pod.blockPosition();
        OptionalInt layer = LayerChain.layerAt(level, pos.getY()).stream()
                .mapToInt(Band::layer)
                .findFirst();
        Component message = layer.isPresent()
                ? Component.translatable(
                        "deepcharter.wreck.report.layer", layer.getAsInt(), pos.getX(), pos.getY(), pos.getZ())
                : Component.translatable("deepcharter.wreck.report.surface", pos.getX(), pos.getY(), pos.getZ());
        for (var member : charter.roster()) {
            ServerPlayer player = server.getPlayerList().getPlayer(member);
            if (player != null) {
                player.sendSystemMessage(message);
                if (layer.isPresent()) {
                    player.sendSystemMessage(Component.translatable("deepcharter.wreck.tow_hint"));
                }
            }
        }
        WreckEvents.REPORTED.invoker().onReported(server, charter, pod, layer, pos);
    }
}
