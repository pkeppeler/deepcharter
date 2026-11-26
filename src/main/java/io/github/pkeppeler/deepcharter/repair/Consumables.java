package io.github.pkeppeler.deepcharter.repair;

import io.github.pkeppeler.deepcharter.layer.CaveIns;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.pod.PodCharge;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodFootprint;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.suit.SuitReserve;
import io.github.pkeppeler.deepcharter.suit.SuitTuning;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * What each item does (SPEC sections 10 and 11). All of them but the thrown kit work on the pod the player is piloting, and
 * on no other: an item used on foot, or in a wreck, is refused and kept. The thrown kit (#985) is the other way round: it is
 * thrown on foot and refused from a seat, and {@link ThrownKit} does it. The air canister (#1194) is neither: it tops up the suit,
 * on foot or in a seat that does not refill it.
 */
final class Consumables {
    private Consumables() {}

    /** Server only: applies the item for {@code player}. Empty when it worked (the caller uses it up), or the reason it did not. */
    static Optional<Component> use(ServerPlayer player, Consumable consumable) {
        return switch (consumable.use()) {
            case THROWN -> ThrownKit.throwFrom(player, consumable, ThrownKitTuning.DEFAULT);
            case SUIT -> SuitReserve.topUp(player, SuitTuning.DEFAULT.canisterSeconds());
            case PILOT_SEAT -> pilot(player, consumable);
            case OWN -> throw new IllegalStateException(consumable + " handles its own use");
        };
    }

    private static Optional<Component> pilot(ServerPlayer player, Consumable consumable) {
        if (!(player.getVehicle() instanceof PodEntity pod) || pod.getControllingPassenger() != player) {
            return refusal("not_piloting");
        }
        if (pod.hull() <= 0f || Wrecks.isWreck(pod)) {
            return refusal("wreck");
        }
        RepairTuning tuning = RepairTuning.DEFAULT;
        return switch (consumable) {
            case RESERVE_FUEL_TANK -> refuel(pod, tuning);
            case HULL_NANOBOTS -> nanobots(pod, tuning);
            case DYNAMITE -> blast(pod, tuning.dynamiteRadius(), DeepSound.DRILL_DYNAMITE);
            case PLASTIC_EXPLOSIVES -> blast(pod, tuning.plasticRadius(), DeepSound.DRILL_PLASTIC);
            case BLASTING_CHARGE -> PodCharge.set(pod, tuning, PodCharge.Kind.BLASTING);
            case SHAPED_CHARGE -> PodCharge.set(pod, tuning, PodCharge.Kind.SHAPED);
            case QUANTUM_TELEPORTER -> Teleports.send(pod, tuning.quantumScatter());
            case MATTER_TRANSMITTER -> Teleports.send(pod, 0.0);
            case THROWN_FLARE, THROWN_DECOY, THROWN_CHARGE, TETHER, BOLT_DRIVER, BOLT, AIR_CANISTER ->
                throw new IllegalStateException(consumable + " is not used from the pilot's seat");
        };
    }

    static Optional<Component> refusal(String reason) {
        return Optional.of(Component.translatable("message.deepcharter.repair." + reason));
    }

    private static Optional<Component> refuel(PodEntity pod, RepairTuning tuning) {
        float full = PodTuning.DEFAULT.shell().fullFuel();
        if (pod.fuel() >= full) {
            return refusal("tank_full");
        }
        float percent = tuning.reserveLitres() / PodStats.of(pod).tankLitres() * full;
        pod.setFuel(Math.min(full, pod.fuel() + percent));
        pod.setStranded(false);
        play(pod, DeepSound.FUEL_REFUEL);
        return Optional.empty();
    }

    private static Optional<Component> nanobots(PodEntity pod, RepairTuning tuning) {
        if (pod.hull() >= pod.maxHull()) {
            return refusal("hull_full");
        }
        pod.setHull(pod.hull() + tuning.nanobotHp());
        play(pod, DeepSound.REPAIR_NANOBOTS);
        return Optional.empty();
    }

    /**
     * Clears the natural rock within {@code radius} of the pod's feet on each axis, as a gas blast does. Company rock, lava and anything built
     * stay. The cube sits on the row the pod's feet are in, not on the middle of its box (#1478): a pod is 2.9 blocks tall, so a cube round its
     * middle reached no row under it, and the slab under the pod is what a pilot spends a stick on (a gas pocket the sounder marks, a slab the
     * drill refuses). So it clears {@code radius} slabs under the pod and {@code radius} rows up from its feet row, short of the roof over a
     * 3-row cab, which stands. Across, the cube is centred on the footprint's middle cell; a 2-wide Mole has no middle cell, so its extra
     * column is always on the +x and +z side, whichever way the pod sits within the drill's alignment slack.
     */
    private static Optional<Component> blast(PodEntity pod, int radius, DeepSound sound) {
        ServerLevel level = (ServerLevel) pod.level();
        PodFootprint foot = PodFootprint.of(pod);
        BlockPos middle = new BlockPos(foot.lowX() + foot.width() / 2, foot.feetY(), foot.lowZ() + foot.width() / 2);
        BlockPos low = middle.offset(-radius, -radius, -radius);
        BlockPos high = middle.offset(radius, radius, radius);
        for (BlockPos cell : BlockPos.betweenClosed(low, high)) {
            if (level.getBlockState(cell).is(HazardBlocks.NATURAL_ROCK)) {
                level.destroyBlock(cell.immutable(), false);
            }
        }
        play(pod, sound);
        // The blast shakes the roof over anyone on foot near it (#987), measured from the middle of the cube it
        // cleared.
        CaveIns.blasted(level, Vec3.atCenterOf(middle), PodCharge.blasterOf(pod));
        return Optional.empty();
    }

    static void play(PodEntity pod, DeepSound sound) {
        pod.level().playSound(null, pod.getX(), pod.getY(), pod.getZ(), sound.event(), SoundSource.PLAYERS);
    }
}
