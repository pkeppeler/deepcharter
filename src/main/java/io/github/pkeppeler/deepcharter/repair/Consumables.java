package io.github.pkeppeler.deepcharter.repair;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;

import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;

/**
 * What each of the six items does (SPEC sections 10 and 11). All of them work on the pod the player is piloting, and
 * on no other: an item used on foot, or in a wreck, is refused and kept.
 */
final class Consumables {
	private Consumables() {
	}

	/** Server only: applies the item for {@code player}. Empty when it worked (the caller uses it up), or the reason it did not. */
	static Optional<Component> use(ServerPlayer player, Consumable consumable) {
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
			case QUANTUM_TELEPORTER -> Teleports.send(pod, tuning.quantumScatter());
			case MATTER_TRANSMITTER -> Teleports.send(pod, 0.0);
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

	/** Clears the natural rock within {@code radius} of the pod's middle on each axis, as a gas blast does. Company rock, lava and anything built stay. */
	private static Optional<Component> blast(PodEntity pod, int radius, DeepSound sound) {
		ServerLevel level = (ServerLevel) pod.level();
		BlockPos middle = BlockPos.containing(pod.getBoundingBox().getCenter());
		for (BlockPos cell : BlockPos.betweenClosed(middle.offset(-radius, -radius, -radius), middle.offset(radius, radius, radius))) {
			if (level.getBlockState(cell).is(HazardBlocks.NATURAL_ROCK)) {
				level.destroyBlock(cell.immutable(), false);
			}
		}
		play(pod, sound);
		return Optional.empty();
	}

	private static void play(PodEntity pod, DeepSound sound) {
		pod.level().playSound(null, pod.getX(), pod.getY(), pod.getZ(), sound.event(), SoundSource.PLAYERS);
	}
}
