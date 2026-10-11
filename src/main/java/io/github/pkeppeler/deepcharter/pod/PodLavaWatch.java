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
 */
public final class PodLavaWatch {
	public static final int VERSION = 1;

	/**
	 * What the watch sees, as the pilot's HUD shows it.
	 *
	 * @param blocksOff open cells between the pod's box and the nearest flowing lava, counting the lava's own cell, so lava beside the box is 1; 0 for none
	 */
	public record State(int blocksOff) {
		public static final State CLEAR = new State(0);
		public static final MapCodec<State> BODY = RecordCodecBuilder.mapCodec(instance -> instance.group(
				Codec.INT.fieldOf("blocksOff").forGetter(State::blocksOff)).apply(instance, State::new));
		public static final StreamCodec<ByteBuf, State> STREAM = StreamCodec.composite(ByteBufCodecs.VAR_INT, State::blocksOff, State::new);

		public State {
			if (blocksOff < 0) {
				throw new IllegalArgumentException("the lava is not a negative number of blocks off, got " + blocksOff);
			}
		}

		public boolean isClear() {
			return blocksOff == 0;
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

	/** Blocks of open cells between the pod and the nearest flowing lava, or empty when the watch sees none. Works on both sides, from the synced state. */
	public static Optional<Integer> blocksOff(PodEntity pod) {
		return Versioned.readable(pod, STATE).filter(state -> !state.isClear()).map(State::blocksOff);
	}

	private static void afterTick(PodEntity pod) {
		Optional<State> read = Versioned.readable(pod, STATE);
		if (read.isEmpty()) {
			return;
		}
		boolean watching = pod.getControllingPassenger() != null && !Wrecks.isWreck(pod);
		State now = !watching ? State.CLEAR
				: pod.tickCount % PodLavaWatchTuning.DEFAULT.scanEveryTicks() == 0 ? scan(pod) : read.get();
		if (!now.equals(read.get())) {
			pod.setAttached(STATE, Versioned.of(now));
		}
	}

	/** A breadth-first walk from the pod's box through open cells ({@link PodLining#open}), a block at a time, to the first flowing lava. */
	private static State scan(PodEntity pod) {
		LoadedBlocks blocks = new LoadedBlocks(pod.level());
		PodFootprint foot = PodFootprint.of(pod);
		Set<BlockPos> seen = new HashSet<>();
		ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
		for (BlockPos pos : BlockPos.betweenClosed(foot.lowX(), foot.feetY(), foot.lowZ(),
				foot.lowX() + foot.width() - 1, foot.feetY() + foot.height() - 1, foot.lowZ() + foot.width() - 1)) {
			seen.add(pos.immutable());
			frontier.add(pos.immutable());
		}
		for (int distance = 1; distance <= PodLavaWatchTuning.DEFAULT.reach() && !frontier.isEmpty(); distance++) {
			ArrayDeque<BlockPos> next = new ArrayDeque<>();
			for (BlockPos from : frontier) {
				for (Direction direction : Direction.values()) {
					BlockPos to = from.relative(direction);
					if (!seen.add(to)) {
						continue;
					}
					FluidState fluid = blocks.getFluidState(to);
					if (fluid.is(FluidTags.LAVA) && !fluid.isSource()) {
						return new State(distance);
					}
					if (PodLining.open(blocks.getBlockState(to))) {
						next.add(to);
					}
				}
			}
			frontier = next;
		}
		return State.CLEAR;
	}
}
