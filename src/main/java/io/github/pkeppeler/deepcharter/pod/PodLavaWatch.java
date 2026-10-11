package io.github.pkeppeler.deepcharter.pod;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

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
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.scanner.LoadedBlocks;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;

/**
 * The lava watch (#454): the pod warns its pilot of flowing lava that is joined to the pod's box by open cells, the lava the liner's view
 * (3 beside, 4 over the box) never sees. Every pod with a pilot has it; it is no part. It reads through {@link LoadedBlocks} only, so it
 * never loads a chunk, once every {@link PodLavaWatchTuning#scanEveryTicks} ticks, over at most {@link PodLavaWatchTuning#reach} blocks of open cells. Lava
 * that rock or lining cuts off from the pod does not warn. Still lava (a source) does not warn until it flows, which it does at once when a bore opens it.
 * Lava that stays where it is warns only within {@link PodLavaWatchTuning#nearBand}; farther off it warns while it comes nearer. Lava inside the box
 * itself is not read: the pod is burning, which its own cue shows. When the nearest lava's path leaves the box upward, the warning says so, and hand lining
 * then caps the shaft ({@link PodLining#cellsToLine}).
 */
public final class PodLavaWatch {
	public static final int VERSION = 1;

	/** The nearest flowing lava: the open cells to it, counting its own cell, and whether its path leaves the box upward. */
	private record Sighting(int blocks, boolean above) {
	}

	/**
	 * What the watch has seen, as the pilot's HUD shows it and as the next look judges it.
	 *
	 * @param blocksOff open cells between the pod's box and the nearest flowing lava, counting the lava's own cell, so lava beside the box is 1; 0 while the watch is quiet
	 * @param above     the path to that lava leaves the box upward: lava falls down the shaft the pod bored
	 * @param nearest   the nearest lava at the last look, 0 for none, quiet or not
	 * @param still     looks in a row at which the lava came no nearer, up to {@link PodLavaWatchTuning#quietScans}
	 */
	public record State(int blocksOff, boolean above, int nearest, int still) {
		public static final State CLEAR = new State(0, false, 0, 0);
		public static final MapCodec<State> BODY = RecordCodecBuilder.mapCodec(instance -> instance.group(
				Codec.INT.fieldOf("blocksOff").forGetter(State::blocksOff),
				Codec.BOOL.fieldOf("above").forGetter(State::above),
				Codec.INT.fieldOf("nearest").forGetter(State::nearest),
				Codec.INT.fieldOf("still").forGetter(State::still)).apply(instance, State::new));
		public static final StreamCodec<ByteBuf, State> STREAM = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, State::blocksOff,
				ByteBufCodecs.BOOL, State::above,
				ByteBufCodecs.VAR_INT, State::nearest,
				ByteBufCodecs.VAR_INT, State::still,
				State::new);

		public State {
			if (blocksOff < 0 || nearest < 0 || still < 0) {
				throw new IllegalArgumentException("the lava watch counts are not negative, got " + blocksOff + ", " + nearest + " and " + still);
			}
		}

		public boolean isQuiet() {
			return blocksOff == 0;
		}

		/** The state after a look that saw {@code seen}: lava that came nearer, or that is within the near band, warns; lava that stays beyond it falls quiet after a while. */
		private State after(Optional<Sighting> seen) {
			if (seen.isEmpty()) {
				return CLEAR;
			}
			PodLavaWatchTuning tuning = PodLavaWatchTuning.DEFAULT;
			Sighting sighting = seen.get();
			boolean nearer = nearest == 0 || sighting.blocks() < nearest;
			int stillNow = nearer ? 0 : Math.min(still + 1, tuning.quietScans());
			boolean warns = sighting.blocks() <= tuning.nearBand() || stillNow < tuning.quietScans();
			return new State(warns ? sighting.blocks() : 0, sighting.above(), sighting.blocks(), stillNow);
		}
	}

	public static final AttachmentType<Versioned<State>> STATE = AttachmentRegistry.create(
			Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod_lava_watch"),
			builder -> builder
					.persistent(Versioned.codec(VERSION, State.BODY))
					.initializer(() -> Versioned.of(State.CLEAR))
					.syncWith(Versioned.streamCodec(VERSION, State.STREAM), AttachmentSyncPredicate.all()));

	private PodLavaWatch() {
	}

	public static void init() {
		PodEvents.AFTER_TICK.register(PodLavaWatch::afterTick);
	}

	/** What the watch warns of now, or empty when it is quiet or the state is unreadable. Works on both sides, from the synced state. */
	public static Optional<State> warning(PodEntity pod) {
		return Versioned.readable(pod, STATE).filter(state -> !state.isQuiet());
	}

	/** Blocks of open cells between the pod and the nearest flowing lava it warns of, or empty. */
	public static Optional<Integer> blocksOff(PodEntity pod) {
		return warning(pod).map(State::blocksOff);
	}

	/** Whether the pod warns of lava that comes down the shaft from above. */
	static boolean lavaAbove(PodEntity pod) {
		return warning(pod).filter(State::above).isPresent();
	}

	private static void afterTick(PodEntity pod) {
		Optional<State> read = Versioned.readable(pod, STATE);
		if (read.isEmpty()) {
			return;
		}
		boolean watching = pod.getControllingPassenger() != null && !Wrecks.isWreck(pod);
		State now = !watching ? State.CLEAR
				: pod.tickCount % PodLavaWatchTuning.DEFAULT.scanEveryTicks() == 0 ? read.get().after(scan(pod)) : read.get();
		if (!now.equals(read.get())) {
			pod.setAttached(STATE, Versioned.of(now));
		}
	}

	/** A cell of the walk and the way its path left the box; {@code first} is null for a cell of the box itself. */
	private record Step(BlockPos pos, Direction first) {
	}

	/** A breadth-first walk from the pod's box through open cells ({@link PodLining#open}), a block at a time, to the first flowing lava. */
	private static Optional<Sighting> scan(PodEntity pod) {
		LoadedBlocks blocks = new LoadedBlocks(pod.level());
		PodFootprint foot = PodFootprint.of(pod);
		Set<BlockPos> seen = new HashSet<>();
		ArrayDeque<Step> frontier = new ArrayDeque<>();
		for (BlockPos pos : BlockPos.betweenClosed(foot.lowX(), foot.feetY(), foot.lowZ(),
				foot.lowX() + foot.width() - 1, foot.feetY() + foot.height() - 1, foot.lowZ() + foot.width() - 1)) {
			seen.add(pos.immutable());
			frontier.add(new Step(pos.immutable(), null));
		}
		for (int distance = 1; distance <= PodLavaWatchTuning.DEFAULT.reach() && !frontier.isEmpty(); distance++) {
			ArrayDeque<Step> next = new ArrayDeque<>();
			for (Step from : frontier) {
				for (Direction direction : Direction.values()) {
					BlockPos to = from.pos().relative(direction);
					if (!seen.add(to)) {
						continue;
					}
					Direction first = from.first() == null ? direction : from.first();
					FluidState fluid = blocks.getFluidState(to);
					if (fluid.is(FluidTags.LAVA) && !fluid.isSource()) {
						return Optional.of(new Sighting(distance, first == Direction.UP));
					}
					if (PodLining.open(blocks.getBlockState(to))) {
						next.add(new Step(to, first));
					}
				}
			}
			frontier = next;
		}
		return Optional.empty();
	}
}
