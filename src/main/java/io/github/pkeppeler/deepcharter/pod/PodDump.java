package io.github.pkeppeler.deepcharter.pod;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.handbook.ReadMarks;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import java.util.List;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Dumping cargo from the seat (#1456, ADR 0103, ADR 0110): Q on a stack in the pod screen's hold ({@link PodMenu})
 * drops it as items under the pod, with the jettison sound. It goes to the ground, never straight back to a pack: a
 * player seated in a pod picks up nothing a pod dumped ({@link #keepsFromPack}), or the dump would ride on in the
 * seat. Ore the rider picks up from the ground another way is loose ore, which costs the pod ({@link PodLooseOre}). On
 * the ground it is an item like any other: it despawns in time, and over lava it burns. The first time a pod's cargo
 * keeps it from climbing with a pilot aboard, that pilot is told once how to dump.
 */
public final class PodDump {
    /** The read mark of the one-time line on a pilot's first overloaded pod (the player's {@code ReadMarks}). */
    public static final Identifier HINT_MARK =
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "hint/first_overload");

    public static final int VERSION = 1;

    /** Whether an item entity was dumped by a pod, so no seated player takes it back. */
    public record Dumped(boolean dumped) {
        public static final Dumped NO = new Dumped(false);
        public static final MapCodec<Dumped> BODY = RecordCodecBuilder.mapCodec(
                instance -> instance.group(Codec.BOOL.fieldOf("dumped").forGetter(Dumped::dumped))
                        .apply(instance, Dumped::new));
    }

    /** The mark on every item a pod dumps, kept with the item through a save. */
    public static final AttachmentType<Versioned<Dumped>> DUMPED = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "dumped_by_pod"),
            builder -> builder.persistent(Versioned.codec(VERSION, Dumped.BODY))
                    .initializer(() -> Versioned.of(Dumped.NO)));

    /** The share of the pod's width the dumped items spread over, centred under it. */
    private static final double SPREAD = 0.8;
    /** The most sideways speed a dumped item starts with, blocks a tick. */
    private static final double SCATTER_SPEED = 0.05;

    private PodDump() {}

    public static void init() {
        PodEvents.AFTER_TICK.register(PodEvents.OBSERVE, PodDump::explainFirstOverload);
    }

    /**
     * Server only: dumps up to {@code count} of the stack in hold slot {@code index} under the pod. Whoever calls this
     * has checked the player may use the hold.
     */
    static void dumpSlot(PodEntity pod, int index, int count) {
        ItemStack taken = pod.cargo().removeAt(pod, index, count);
        if (!taken.isEmpty()) {
            drop(pod, List.of(taken));
        }
    }

    /**
     * Whether {@code player} may not pick up {@code item}: a player seated in a pod picks up nothing a pod dumped,
     * which would land back in the seat. An item whose mark cannot be read counts as dumped. On foot anyone picks it
     * up.
     */
    public static boolean keepsFromPack(Player player, ItemEntity item) {
        return player.getVehicle() instanceof PodEntity
                && Versioned.readable(item, DUMPED).map(Dumped::dumped).orElse(true);
    }

    /**
     * Spills {@code stacks} as items at the pod's floor, spread across its width and marked {@link #DUMPED}, and
     * sounds the jettison.
     */
    private static void drop(PodEntity pod, List<ItemStack> stacks) {
        ServerLevel level = (ServerLevel) pod.level();
        RandomSource random = level.getRandom();
        double reach = pod.getBbWidth() * SPREAD / 2;
        for (ItemStack stack : stacks) {
            double x = pod.getX() + (random.nextDouble() * 2 - 1) * reach;
            double z = pod.getZ() + (random.nextDouble() * 2 - 1) * reach;
            ItemEntity item = new ItemEntity(
                    level,
                    x,
                    pod.getY(),
                    z,
                    stack,
                    (random.nextDouble() * 2 - 1) * SCATTER_SPEED,
                    0,
                    (random.nextDouble() * 2 - 1) * SCATTER_SPEED);
            item.setDefaultPickUpDelay();
            item.setAttached(DUMPED, Versioned.of(new Dumped(true)));
            level.addFreshEntity(item);
        }
        level.playSound(null, pod, DeepSound.CARGO_JETTISON.event(), SoundSource.NEUTRAL, 1f, 1f);
    }

    /**
     * Says once per player, kept as a read mark, that a pod too heavy to lift can dump its cargo from the pod screen,
     * naming the inventory and drop keys as the pilot binds them. Only when the cargo is what holds it down: a pod that towing alone overloads says nothing.
     * Says nothing when the marks cannot be read.
     */
    private static void explainFirstOverload(PodEntity pod) {
        if (!(pod.getControllingPassenger() instanceof ServerPlayer pilot) || !overloadedByCargo(pod)) {
            return;
        }
        if (ReadMarks.isReadable(pilot) && !ReadMarks.isRead(pilot, HINT_MARK)) {
            ReadMarks.mark(pilot, HINT_MARK);
            pilot.sendSystemMessage(Component.translatable(
                    "deepcharter.pod.dump.hint", Component.keybind("key.inventory"), Component.keybind("key.drop")));
        }
    }

    /**
     * Whether the pod cannot climb and dumping would help: the load ({@link PodLoad}) reaches its climb limit, and without
     * the cargo it would not (which also means the hold holds something). {@link PodCues} names how to dump only then.
     */
    static boolean overloadedByCargo(PodEntity pod) {
        PodLoad load = PodLoad.of(pod);
        return load.cannotClimb() && load.load() - pod.cargoMass() < load.climbLimit();
    }
}
