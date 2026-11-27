package io.github.pkeppeler.deepcharter.pod;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.handbook.ReadMarks;
import io.github.pkeppeler.deepcharter.ore.LooseOre;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * Ore carried loose in a rider's pack, which the bay's size does not limit (ADR 0110, #1540). It costs the pod three
 * ways, all from {@link LooseOreTuning}: it weighs double against the lift ({@link PodLoad}), it cramps the cab so
 * the drill and the steering slow in proportion to its mass, and each ore adds to a hard landing's hull damage
 * ({@link HardLanding}). The cockpit shows the cause as LOOSE, and a rider's first loose ore says so once.
 *
 * <p>The server measures the riders' packs each tick ({@link #measure}) and copies the result to {@link #LOOSE} for
 * the stats and the client, as {@link PodLoad#EXTRA} does, so a stats listener, which runs often, reads a number and
 * scans no inventory. The weight and the landing read the packs themselves, since each runs once a tick or less.
 */
public final class PodLooseOre {
    public static final int VERSION = 1;

    /** The read mark of the one-time line on a rider's first loose ore (the player's {@code ReadMarks}). */
    public static final Identifier FIRST_MARK = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "hint/first_loose");

    /**
     * The riders' loose ore as the server last measured it. Synced, not saved: the server writes it on the pod's first
     * tick. Unreadable (a newer build's version on the wire) reads as no loose ore.
     */
    public static final AttachmentType<Versioned<LooseOre>> LOOSE = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod_loose_ore"),
            builder -> builder.initializer(() -> Versioned.of(LooseOre.NONE))
                    .syncWith(Versioned.streamCodec(VERSION, LooseOre.STREAM), AttachmentSyncPredicate.all()));

    private PodLooseOre() {}

    static void init() {
        PodEvents.EXTRA_MASS.register(PodLooseOre::extraMass);
        PodEvents.AFTER_TICK.register(PodEvents.OBSERVE, PodLooseOre::share);
        PodStats.MODIFY.register(PodLooseOre::cramped);
    }

    /** The ore in the packs of the players seated in {@code pod} now. Server only: it reads the packs. */
    public static LooseOre measure(PodEntity pod) {
        LooseOre total = LooseOre.NONE;
        for (Entity rider : pod.getPassengers()) {
            if (rider instanceof ServerPlayer player) {
                total = total.plus(LooseOre.inPack(player));
            }
        }
        return total;
    }

    /** The loose ore as last measured, on either side. */
    public static LooseOre of(PodEntity pod) {
        return Versioned.readable(pod, LOOSE).orElse(LooseOre.NONE);
    }

    /** The share of the drill's speed and steering the loose ore takes, from 0 up to but not including 1. */
    public static float cramp(PodEntity pod) {
        return LooseOreTuning.DEFAULT.cramp(of(pod).mass());
    }

    /** The hull points the loose ore in the packs now adds to a hard landing. Server only. */
    static float rattle(PodEntity pod) {
        return measure(pod).ore() * LooseOreTuning.DEFAULT.rattlePerOre();
    }

    /** The mass loose ore adds past its pack mass, which {@code OreEncumbrance} already counts once. */
    private static float extraMass(PodEntity pod) {
        return measure(pod).mass() * (LooseOreTuning.DEFAULT.weightFactor() - 1f);
    }

    /** The drill takes longer for every unit of loose ore: its ticks for each point of hardness grow by the cramp. */
    private static PodStats cramped(PodEntity pod, PodStats stats) {
        float cramp = cramp(pod);
        return cramp == 0f ? stats : stats.withTicksPerHardness(stats.ticksPerHardness() / (1f - cramp));
    }

    /** Copies the measure to the attachment when it changed, and tells each rider of their first loose ore once. */
    private static void share(PodEntity pod) {
        LooseOre now = measure(pod);
        if (!of(pod).equals(now) && Versioned.readable(pod, LOOSE).isPresent()) {
            pod.setAttached(LOOSE, Versioned.of(now));
        }
        if (!now.any()) {
            return;
        }
        for (Entity rider : pod.getPassengers()) {
            if (rider instanceof ServerPlayer player
                    && LooseOre.inPack(player).any()
                    && ReadMarks.isReadable(player)
                    && !ReadMarks.isRead(player, FIRST_MARK)) {
                ReadMarks.mark(player, FIRST_MARK);
                player.sendSystemMessage(Component.translatable("deepcharter.pod.loose.first"));
            }
        }
    }
}
