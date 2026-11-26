package io.github.pkeppeler.deepcharter.pod;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.handbook.ReadMarks;
import io.github.pkeppeler.deepcharter.ore.OreEncumbrance;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Dumping cargo from the seat (#1456, ADR 0103): a stack thrown from the pod screen's hold ({@link PodMenu}), or the
 * whole hold by the dump key held from a seat ({@link PodDumpPayload}), drops as items under the pod, with the jettison
 * sound. It is never destroyed and never goes to a pack: ore leaves the hold only to the ground, and a player seated in
 * a pod picks up no ore ({@link #keepsFromPack}), or the dump would carry it on weightless. The first time a pod is too
 * heavy to lift with a pilot aboard, that pilot is told once how to dump.
 */
public final class PodDump {
    /** The dump key's binding, which the client registers and the hint names as the player binds it. */
    public static final String KEY = "key.deepcharter.dump_cargo";

    /** The read mark of the one-time line on a pilot's first overloaded pod (the player's {@code ReadMarks}). */
    public static final Identifier HINT_MARK =
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "hint/first_overload");

    /** How far below the pod's floor a dumped item starts, when the space there is open. */
    private static final double DROP_BELOW = 0.3;
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
        List<ItemStack> dumped = pod.cargo().takeAll(pod);
        drop(pod, dumped);
        return Optional.of(Component.translatable("deepcharter.pod.dump.done", dumped.size()));
    }

    /**
     * Server only: dumps the stack in hold slot {@code index} under the pod and returns whether there was one. Whoever
     * calls this has checked the player may use the hold.
     */
    static boolean dumpSlot(PodEntity pod, int index) {
        ItemStack stack = pod.cargo().stackAt(index);
        if (stack.isEmpty()) {
            return false;
        }
        pod.cargo().setAt(pod, index, ItemStack.EMPTY);
        drop(pod, List.of(stack));
        return true;
    }

    /**
     * Whether {@code player} may not pick up {@code stack}: a player seated in a pod picks up no ore, since ore rides
     * in the hold and a pack in the seat carries it weightless. On foot anyone picks it up.
     */
    public static boolean keepsFromPack(Player player, ItemStack stack) {
        return player.getVehicle() instanceof PodEntity && OreEncumbrance.holdsOre(stack);
    }

    /**
     * Spills {@code stacks} as items under the pod: just below its floor if that space is open, else on the ground it
     * stands on, spread across its width, and sounds the jettison.
     */
    private static void drop(PodEntity pod, List<ItemStack> stacks) {
        ServerLevel level = (ServerLevel) pod.level();
        RandomSource random = level.getRandom();
        double reach = pod.getBbWidth() * SPREAD / 2;
        for (ItemStack stack : stacks) {
            double x = pod.getX() + (random.nextDouble() * 2 - 1) * reach;
            double z = pod.getZ() + (random.nextDouble() * 2 - 1) * reach;
            double below = pod.getY() - DROP_BELOW;
            double y = level.noCollision(EntityTypes.ITEM.getDimensions().makeBoundingBox(x, below, z))
                    ? below
                    : pod.getY();
            ItemEntity item = new ItemEntity(
                    level,
                    x,
                    y,
                    z,
                    stack,
                    (random.nextDouble() * 2 - 1) * SCATTER_SPEED,
                    0,
                    (random.nextDouble() * 2 - 1) * SCATTER_SPEED);
            item.setDefaultPickUpDelay();
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
     * Whether the pod is too heavy to lift and dumping would help: the load ({@link PodEntity#cargoMass} and {@link
     * PodEvents#extraMass}) reaches the engine's power, as {@link PodMovement#tick} reads it, and the engine could lift
     * something without the cargo.
     */
    private static boolean overloadedByCargo(PodEntity pod) {
        float limit = PodStats.of(pod).enginePower();
        float extra = PodEvents.extraMass(pod);
        return pod.cargoMass() > 0f && pod.cargoMass() + extra >= limit && extra < limit;
    }
}
