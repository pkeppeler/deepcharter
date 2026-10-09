package io.github.pkeppeler.deepcharter.pod;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.scanner.LoadedBlocks;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * The seep sounder (#373): a part that hears the gas pockets the drill is about to open. Each tick it marks the nearest pocket of the pod's
 * footprint within its tier's slabs below, and (tier 2) the sides a sidestep would bore or land on, and the pod hisses faster as the pocket
 * nears. A sounder that bleeds (tier 2) makes the drill wait before it bores a slab with a pocket in it, and the blast then costs
 * at most a share of the pod's hull ({@link #bleedPauseTicks}, {@link #drilledBlast}; the drill and {@code GasHazard} read them).
 */
public final class PodSounder {
	public static final int VERSION = 1;

	/**
	 * What the sounder marks, as the pilot's HUD shows it.
	 *
	 * @param down   slabs between the pod's feet and the nearest pocket of its footprint within the tier's reach, 0 for none
	 * @param beside the sides (a bit for each horizontal direction, by {@link Direction#get2DDataValue}) with a pocket in the cells a sidestep would bore or land on
	 */
	public record State(int down, int beside) {
		public static final State EMPTY = new State(0, 0);
		public static final MapCodec<State> BODY = RecordCodecBuilder.mapCodec(instance -> instance.group(
				Codec.INT.fieldOf("down").forGetter(State::down),
				Codec.INT.fieldOf("beside").forGetter(State::beside)).apply(instance, State::new));
		public static final StreamCodec<ByteBuf, State> STREAM = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, State::down,
				ByteBufCodecs.VAR_INT, State::beside,
				State::new);

		/** Whether a pocket lies in the cells a sidestep to {@code side} would bore or land on. */
		public boolean marks(Direction side) {
			return (beside & (1 << side.get2DDataValue())) != 0;
		}

		public boolean isClear() {
			return down == 0 && beside == 0;
		}
	}

	public static final AttachmentType<Versioned<State>> STATE = AttachmentRegistry.create(
			Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod_sounder"),
			builder -> builder
					.persistent(Versioned.codec(VERSION, State.BODY))
					.initializer(() -> Versioned.of(State.EMPTY))
					.syncWith(Versioned.streamCodec(VERSION, State.STREAM), AttachmentSyncPredicate.all()));

	private PodSounder() {
	}

	public static void init() {
		PodEvents.AFTER_TICK.register(PodSounder::afterTick);
		PodStats.MODIFY.register(PodStats.BASE, (pod, stats) -> {
			int tier = tier(pod);
			return tier == 0 ? stats : stats.withTicksPerHardness(PodSounderTuning.DEFAULT.tier(tier).slowedDrill(stats.ticksPerHardness()));
		});
	}

	/** The tier the pod's sounder works at: 0 for none, a void part or an unreadable one. */
	public static int tier(PodEntity pod) {
		return PodComponents.effectiveTier(pod, ComponentTrack.SOUNDER);
	}

	/** What the sounder marks now, or empty for a pod with no sounder. Works on both sides, from the synced state. */
	public static Optional<State> reading(PodEntity pod) {
		return tier(pod) == 0 ? Optional.empty() : Versioned.readable(pod, STATE);
	}

	/** Ticks between two hisses when the nearest pocket is {@code slabsAway} slabs below the pod (1 or more): the nearer the pocket, the quicker. */
	public static int hissEveryTicks(int slabsAway) {
		if (slabsAway < 1) {
			throw new IllegalArgumentException("a pocket is 1 slab or more below the pod, got " + slabsAway);
		}
		return PodSounderTuning.DEFAULT.hissTicksPerSlab() * slabsAway;
	}

	/** Ticks the drill waits before it bores a slab with a gas pocket in it: 0 for a pod whose sounder does not bleed. */
	public static int bleedPauseTicks(PodEntity pod) {
		int tier = tier(pod);
		return tier == 0 ? 0 : PodSounderTuning.DEFAULT.tier(tier).bleedPauseTicks();
	}

	/**
	 * The hull a gas blast of {@code blast} costs a pod whose own drill opened the pocket: the whole blast, unless the pod's sounder bleeds, in which
	 * case it costs at most the tier's share of the pod's most hull.
	 */
	public static float drilledBlast(PodEntity pod, float blast) {
		int tier = tier(pod);
		if (tier == 0 || !PodSounderTuning.DEFAULT.tier(tier).bleeds()) {
			return blast;
		}
		return Math.min(blast, PodSounderTuning.DEFAULT.tier(tier).bleedHullShare() * pod.maxHull());
	}

	private static void afterTick(PodEntity pod) {
		int tier = tier(pod);
		Optional<State> read = Versioned.readable(pod, STATE);
		if (tier == 0 || read.isEmpty()) {
			return;
		}
		State now = PodEvents.isPowered(pod) ? scan(pod, PodSounderTuning.DEFAULT.tier(tier)) : State.EMPTY;
		if (!now.equals(read.get())) {
			pod.setAttached(STATE, Versioned.of(now));
		}
		if (now.down() > 0 && pod.tickCount % hissEveryTicks(now.down()) == 0) {
			pod.level().playSound(null, pod.getX(), pod.getY(), pod.getZ(), DeepSound.POD_SEEP_HISS.event(), SoundSource.NEUTRAL);
		}
	}

	/**
	 * Reads the blocks the sounder hears. The footprint is read for {@code slabsBelow} slabs under the pod's feet. A side is the strip of
	 * {@code sideReach} columns beyond the footprint's edge, the cells a sidestep bores (the pod's height) and the slab it lands on.
	 */
	private static State scan(PodEntity pod, PodSounderTuning.Tier spec) {
		PodFootprint foot = PodFootprint.of(pod);
		LoadedBlocks blocks = new LoadedBlocks(pod.level());
		int down = 0;
		for (int slabs = 1; slabs <= spec.slabsBelow() && down == 0; slabs++) {
			if (hasPocket(blocks, foot.lowX(), foot.lowZ(), foot.width(), foot.width(), foot.feetY() - slabs, foot.feetY() - slabs)) {
				down = slabs;
			}
		}
		int beside = 0;
		int rise = foot.feetY() + foot.height() - 1;
		int landing = foot.feetY() - 1;
		for (Direction side : Direction.Plane.HORIZONTAL) {
			if (spec.sideReach() == 0) {
				break;
			}
			int reach = spec.sideReach();
			int lowX = side.getStepX() > 0 ? foot.lowX() + foot.width() : side.getStepX() < 0 ? foot.lowX() - reach : foot.lowX();
			int lowZ = side.getStepZ() > 0 ? foot.lowZ() + foot.width() : side.getStepZ() < 0 ? foot.lowZ() - reach : foot.lowZ();
			int sizeX = side.getStepX() != 0 ? reach : foot.width();
			int sizeZ = side.getStepZ() != 0 ? reach : foot.width();
			if (hasPocket(blocks, lowX, lowZ, sizeX, sizeZ, landing, rise)) {
				beside |= 1 << side.get2DDataValue();
			}
		}
		return new State(down, beside);
	}

	private static boolean hasPocket(LoadedBlocks blocks, int lowX, int lowZ, int sizeX, int sizeZ, int lowY, int highY) {
		for (int x = lowX; x < lowX + sizeX; x++) {
			for (int z = lowZ; z < lowZ + sizeZ; z++) {
				for (int y = lowY; y <= highY; y++) {
					if (blocks.getBlockState(new BlockPos(x, y, z)).is(HazardBlocks.GAS_POCKET)) {
						return true;
					}
				}
			}
		}
		return false;
	}
}
