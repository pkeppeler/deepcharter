package io.github.pkeppeler.deepcharter.pod;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.handbook.ReadMarks;
import io.github.pkeppeler.deepcharter.ore.OreEncumbrance;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import java.util.List;
import java.util.Optional;
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
 * Dumping cargo from the seat (#1456, ADR 0103): Q on a stack in the pod screen's hold ({@link PodMenu}), or the
 * dump key held from a seat for the whole hold ({@link PodDumpPayload}), drops it as items under the pod, with the
 * jettison sound. It goes to the ground, never to a pack: a player seated in a pod picks up no ore and nothing a pod
 * dumped ({@link #keepsFromPack}), or the dump would ride on in the seat, ore weightless. On the ground it is an item
 * like any other: it despawns in time, and over lava it burns. The first time a pod's cargo keeps it from climbing
 * with a pilot aboard, that pilot is told once how to dump.
 */
public final class PodDump {
    /** The dump key's binding, which the client registers and the hint names as the player binds it. */
    public static final String KEY = "key.deepcharter.dump_cargo";

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
        PodDumpPayload.register();
        PodEvents.AFTER_TICK.register(PodEvents.OBSERVE, PodDump::explainFirstOverload);
    }

    /**
     * Server only: dumps the whole hold of the pod {@code player} sits in, or says why not. Only a player seated in the
     * pod who may open its screen dumps it ({@link PodMenu#mayOpen}).
     */
    static Optional<Component> dumpBay(ServerPlayer player) {
        if (!(player.getVehicle() instanceof PodEntity pod) || player.isSpectator() || !pod.isAlive()) {
            return Optional.empty();
        }
        if (!PodMenu.mayOpen(player, pod)) {
            return Optional.of(Component.translatable("deepcharter.pod.screen.refusal.not_yours"));
        }
        if (!pod.cargo().isReadable()) {
            return Optional.of(Component.translatable("deepcharter.pod.screen.refusal.hold_unreadable"));
        }
        if (pod.cargo().occupied() == 0) {
            return Optional.of(Component.translatable("deepcharter.pod.dump.empty"));
        }
        drop(pod, pod.cargo().takeAll(pod));
        return Optional.of(Component.translatable("deepcharter.pod.dump.done"));
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
     * Whether {@code player} may not pick up {@code item}: a player seated in a pod picks up no ore, since a pack in
     * the seat carries it weightless, and nothing a pod dumped, which would land back in the seat. An item whose mark
     * cannot be read counts as dumped. On foot anyone picks either up.
     */
    public static boolean keepsFromPack(Player player, ItemEntity item) {
        return player.getVehicle() instanceof PodEntity
                && (OreEncumbrance.holdsOre(item.getItem())
                        || Versioned.readable(item, DUMPED).map(Dumped::dumped).orElse(true));
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
     * Says once per player, kept as a read mark, that a pod too heavy to lift can dump its cargo, naming the dump key
     * as the pilot binds it. Only when the cargo is what holds it down: a pod that towing alone overloads says nothing.
     * Says nothing when the marks cannot be read.
     */
    private static void explainFirstOverload(PodEntity pod) {
        if (!(pod.getControllingPassenger() instanceof ServerPlayer pilot) || !overloadedByCargo(pod)) {
            return;
        }
        if (ReadMarks.isReadable(pilot) && !ReadMarks.isRead(pilot, HINT_MARK)) {
            ReadMarks.mark(pilot, HINT_MARK);
            pilot.sendSystemMessage(Component.translatable("deepcharter.pod.dump.hint", Component.keybind(KEY)));
        }
    }

    /**
     * Whether the pod cannot climb and dumping would help: the load ({@link PodEntity#cargoMass} and {@link
     * PodEvents#extraMass}) reaches {@link #climbLimit}, and without the cargo it would not (which also means the hold
     * holds something). {@link PodCues} names the dump key only then.
     */
    static boolean overloadedByCargo(PodEntity pod) {
        double limit = climbLimit(pod);
        float extra = PodEvents.extraMass(pod);
        return pod.cargoMass() + extra >= limit && extra < limit;
    }

    /**
     * The load at which a held jump no longer climbs. {@link PodMovement#tick} thrusts by {@code thrustAcceleration *
     * lift / enginePower} against the pod's gravity, with lift the power the load leaves, so from {@code enginePower *
     * (1 - gravity / thrustAcceleration)} up the thrust cannot beat gravity. {@link PodCues} reads it too. Goes into
     * #1449's {@code PodLoad} when that lands, as does the tests' {@code PodClimb}.
     */
    static double climbLimit(PodEntity pod) {
        PodStats stats = PodStats.of(pod);
        double gravity = pod.gravityPull(PodTuning.DEFAULT.movement().gravity());
        return stats.enginePower() * (1 - gravity / stats.thrustAcceleration());
    }
}
