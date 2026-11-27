package io.github.pkeppeler.deepcharter.ore;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.handbook.ReadMarks;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import java.util.Objects;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * Carried ore slows a player on foot (SPEC section 4), so that the pod is the hauler, and so does the solid fuel in a
 * pack (a {@link CargoWeight}, #1280). Ore in a shulker box or a bundle counts.
 * This class is the one source of the slowdown: the Overburdened status ({@link OreRegistry#OVERBURDENED}) and the one-time line on a
 * player's first ore only show it (#853), and are set here from the same number, on the same tick.
 */
public final class OreEncumbrance {
    private static final Identifier LOAD = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "ore_load");

    /** The read mark that records the one-time line on a player's first ore (the player's {@code ReadMarks}, which also holds one-time hints). */
    public static final Identifier FIRST_PICKUP_MARK =
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "hint/first_ore");

    /** The read mark of the one-time line on a player's first load of solid fuel with no ore in it. */
    public static final Identifier FIRST_FUEL_MARK =
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "hint/first_fuel_load");

    /**
     * Mass that weighs on a player that is not ore: a Retained's grip (#916). Summed with the carried ore on the same tick, so the
     * slowdown, its cap and the Overburdened status stay one thing. A listener returns 0 for a player it does not weigh on, and never
     * throws: it runs for every player on every tick. It does not count toward the first-ore line.
     */
    public static final Event<ExtraMass> EXTRA_MASS =
            EventFactory.createArrayBacked(ExtraMass.class, listeners -> player -> {
                float total = 0f;
                for (ExtraMass listener : listeners) {
                    total += listener.of(player);
                }
                return total;
            });

    private OreEncumbrance() {}

    /** One source of extra weight. */
    @FunctionalInterface
    public interface ExtraMass {
        float of(ServerPlayer player);
    }

    public static void init() {
        // Every rider's pack rides in a seat, so all it holds weighs on the pod's lift: ore, fuel and containers
        // (#1446). The bay holds only loose ore, never a pack's items, so nothing is counted twice.
        PodEvents.EXTRA_MASS.register(pod -> {
            float mass = 0f;
            for (Entity rider : pod.getPassengers()) {
                if (rider instanceof ServerPlayer player) {
                    mass += carriedMass(player);
                }
            }
            return mass;
        });
        ServerTickEvents.END_SERVER_TICK.register(
                server -> server.getPlayerList().getPlayers().forEach(OreEncumbrance::apply));
    }

    /**
     * Total mass the player carries, ore and every {@link CargoWeight}: every inventory slot, the offhand and armor included, and the
     * contents of containers.
     */
    public static float carriedMass(ServerPlayer player) {
        float mass = 0f;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            mass += massOf(player.getInventory().getItem(slot));
        }
        return mass;
    }

    /**
     * The mass of a stack's own ore (at its {@link DrilledMass} when it has one) and weighted items, and of everything
     * inside it.
     */
    public static float massOf(ItemStack stack) {
        float mass = CargoWeight.of(stack)
                + OreRegistry.typeOf(stack)
                                .map(type -> DrilledMass.each(stack, type))
                                .orElse(0f)
                        * stack.getCount();
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container != null) {
            mass += (float) container
                    .nonEmptyItemCopyStream()
                    .mapToDouble(OreEncumbrance::massOf)
                    .sum();
        }
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            mass += (float)
                    bundle.itemCopies().mapToDouble(OreEncumbrance::massOf).sum();
        }
        return mass;
    }

    private static void apply(ServerPlayer player) {
        OreTuning tuning = OreTuning.DEFAULT;
        float mass = carriedMass(player);
        double slowdown =
                Math.min(tuning.maxSlowdown(), (mass + EXTRA_MASS.invoker().of(player)) * tuning.slowdownPerMass());
        AttributeInstance speed = Objects.requireNonNull(
                player.getAttribute(Attributes.MOVEMENT_SPEED), "a player has no movement speed attribute");
        // Setting a modifier marks the attribute dirty, which sends a packet, even when the value is the same.
        AttributeModifier current = speed.getModifier(LOAD);
        if (slowdown <= 0) {
            if (current != null) {
                speed.removeModifier(LOAD);
            }
        } else if (current == null || current.amount() != -slowdown) {
            speed.addOrUpdateTransientModifier(
                    new AttributeModifier(LOAD, -slowdown, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
        // A pod carries its pilot at its own speed, so a rider is not slowed and the status would say otherwise.
        showStatus(player, player.isPassenger() ? 0 : slowdown);
        if (mass > 0) {
            explainFirstLoad(player);
        }
    }

    /**
     * Shows the weight as an endless status whose level is the units of ore carried, one level for each {@code massScale} of mass and no
     * more than the cap allows. It shows its icon and no swirl particles (the icon says it). It is set only when it differs, because every change is a packet, and it is put back on the next tick
     * if milk or a command cleared it.
     */
    private static void showStatus(ServerPlayer player, double slowdown) {
        MobEffectInstance shown = player.getEffect(OreRegistry.OVERBURDENED);
        if (slowdown <= 0) {
            if (shown != null) {
                player.removeEffect(OreRegistry.OVERBURDENED);
            }
            return;
        }
        OreTuning tuning = OreTuning.DEFAULT;
        int level = Math.max(1, (int) Math.round(slowdown / (tuning.slowdownPerMass() * tuning.massScale())));
        if (shown == null || shown.getAmplifier() != level - 1) {
            player.addEffect(new MobEffectInstance(
                    OreRegistry.OVERBURDENED, MobEffectInstance.INFINITE_DURATION, level - 1, false, false, true));
        }
    }

    /** Whether the player carries any ore, in a slot or inside a container or a bundle. */
    private static boolean carriesOre(Player player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (holdsOre(player.getInventory().getItem(slot))) {
                return true;
            }
        }
        return false;
    }

    private static boolean holdsOre(ItemStack stack) {
        if (OreRegistry.typeOf(stack).isPresent()) {
            return true;
        }
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        return (container != null && container.nonEmptyItemCopyStream().anyMatch(OreEncumbrance::holdsOre))
                || (bundle != null && bundle.itemCopies().anyMatch(OreEncumbrance::holdsOre));
    }

    /**
     * Says once per player, and keeps that as a read mark, why they now move slowly: the ore line for a first ore, the
     * fuel line for a first load that has no ore in it. Says nothing when the marks cannot be read.
     */
    private static void explainFirstLoad(ServerPlayer player) {
        if (!ReadMarks.isReadable(player)) {
            return;
        }
        boolean carriesOre = carriesOre(player);
        Identifier mark = carriesOre ? FIRST_PICKUP_MARK : FIRST_FUEL_MARK;
        if (ReadMarks.isRead(player, mark)) {
            return;
        }
        ReadMarks.mark(player, mark);
        player.sendSystemMessage(
                Component.translatable(carriesOre ? "deepcharter.ore.first_pickup" : "deepcharter.ore.first_fuel"));
    }
}
