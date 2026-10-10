package io.github.pkeppeler.deepcharter.pod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.scanner.LoadedBlocks;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/** Hand lining (#313): a pilot places slag brick round the slab so lava cannot flood the bore; a pod's spoil and brick rack are one versioned attachment. */
public final class PodLining {
	public static final int VERSION = 1;

	/**
	 * What a pod keeps for lining, and what it is doing.
	 *
	 * @param spoil   waste rock in the bay, up to {@link PodLiningTuning#spoilCapacity}
	 * @param bricks  slag brick in the rack, up to {@link PodLiningTuning#brickCapacity}
	 * @param used    bricks placed by the lining in progress, or by the last one
	 * @param working the pilot is lining: the pod holds still
	 * @param dry     the last lining stopped for want of brick; shown until the pod is lining again
	 */
	public record State(int spoil, int bricks, int used, boolean working, boolean dry) {
		public static final State EMPTY = new State(0, 0, 0, false, false);
		public static final MapCodec<State> BODY = RecordCodecBuilder.mapCodec(instance -> instance.group(
				ExtraCodecs.NON_NEGATIVE_INT.fieldOf("spoil").forGetter(State::spoil),
				ExtraCodecs.NON_NEGATIVE_INT.fieldOf("bricks").forGetter(State::bricks),
				ExtraCodecs.NON_NEGATIVE_INT.fieldOf("used").forGetter(State::used),
				Codec.BOOL.fieldOf("working").forGetter(State::working),
				Codec.BOOL.fieldOf("dry").forGetter(State::dry)).apply(instance, State::new));
		public static final StreamCodec<ByteBuf, State> STREAM = StreamCodec.composite(
				ByteBufCodecs.VAR_INT, State::spoil,
				ByteBufCodecs.VAR_INT, State::bricks,
				ByteBufCodecs.VAR_INT, State::used,
				ByteBufCodecs.BOOL, State::working,
				ByteBufCodecs.BOOL, State::dry,
				State::new);

		public State {
			if (spoil < 0 || bricks < 0 || used < 0) {
				throw new IllegalArgumentException("lining counts must not be negative: " + spoil + ", " + bricks + ", " + used);
			}
		}

		public State withSpoil(int spoil) {
			return new State(spoil, bricks, used, working, dry);
		}

		/** After the processor took {@code spoilUsed} spoil and put {@code toRack} bricks in the rack: fresh stock clears the out-of-brick line. */
		public State fused(int spoilUsed, int toRack) {
			return new State(spoil - spoilUsed, bricks + toRack, used, working, false);
		}

		State begun() {
			return new State(spoil, bricks, 0, true, false);
		}

		public State placed(boolean fromRack) {
			return new State(spoil, fromRack ? bricks - 1 : bricks, used + 1, working, dry);
		}

		State stopped() {
			return new State(spoil, bricks, used, false, dry);
		}

		/** The liner used {@code bricksUsed} bricks of the rack; {@code ranOut} when the rack paid for fewer cells than the ring needed. */
		State linedByLiner(int bricksUsed, boolean ranOut) {
			return new State(spoil, bricks - bricksUsed, used, working, ranOut);
		}

		State ranDry() {
			return new State(spoil, bricks, used, false, true);
		}
	}

	public static final AttachmentType<Versioned<State>> STATE = AttachmentRegistry.create(
			Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod_lining"),
			builder -> builder
					.persistent(Versioned.codec(VERSION, State.BODY))
					.initializer(() -> Versioned.of(State.EMPTY))
					.syncWith(Versioned.streamCodec(VERSION, State.STREAM), AttachmentSyncPredicate.all()));

	/** How many slabs below the pod's feet the lining looks for lava in the footprint: the slab it will bore next, and the one under that. */
	private static final int FLOOR_DEPTH = 2;
	/** The slab below the pod's feet is the first one a hand lining's ring covers: the one the drill bores next. */
	private static final int HAND_REACH = 1;

	private PodLining() {
	}

	public static void init() {
		LiningPayload.register();
		PodEvents.AFTER_TICK.register(PodLining::afterTick);
		PodEvents.EXTRA_MASS.register(PodLining::mass);
		// A pilot at the arm holds the pod still: no drive, no climb. Read on both sides, so it answers from the synced state.
		PodStats.MODIFY.register(PodStats.CAP, (pod, stats) -> working(pod) ? stats.withHorizontalSpeed(0f).withThrustAcceleration(0f) : stats);
	}

	/** The pod's lining state, or empty when it is unreadable (logged once). Never throws. */
	public static Optional<State> readable(PodEntity pod) {
		return Versioned.readable(pod, STATE);
	}

	/** The pod's lining state, or the empty one when it is unreadable (logged once). Never throws. */
	public static State of(PodEntity pod) {
		return readable(pod).orElse(State.EMPTY);
	}

	/** True while the pilot is lining: the pod neither drives nor drills. */
	public static boolean working(PodEntity pod) {
		return of(pod).working();
	}

	/**
	 * Server only: changes the state with {@code change} and returns whether it did. A pod whose saved lining state this build cannot
	 * read is left as it is (logged once) and the answer is no.
	 */
	public static boolean modify(PodEntity pod, UnaryOperator<State> change) {
		Optional<State> state = readable(pod);
		if (state.isEmpty()) {
			return false;
		}
		pod.setAttached(STATE, Versioned.of(change.apply(state.get())));
		return true;
	}

	/** True when the pod has a spoil hopper that counts: only then does the drill keep stone. */
	public static boolean hasHopper(PodEntity pod) {
		return PodComponents.effectiveTier(pod, ComponentTrack.SPOIL_HOPPER) > 0;
	}

	/** The drill has bored one block of waste rock: a pod with a hopper keeps it as spoil if the bay has room, and loses it if not. */
	static void keepSpoil(PodEntity pod) {
		Optional<State> state = readable(pod);
		if (hasHopper(pod) && state.isPresent() && state.get().spoil() < PodLiningTuning.DEFAULT.spoilCapacity()) {
			pod.setAttached(STATE, Versioned.of(state.get().withSpoil(state.get().spoil() + 1)));
		}
	}

	private static float mass(PodEntity pod) {
		State state = of(pod);
		return state.spoil() * PodLiningTuning.DEFAULT.spoilMass() + state.bricks() * PodLiningTuning.DEFAULT.brickMass();
	}

	/**
	 * The pilot pressed the lining key: starts lining the pod's slab, or stops the lining in progress. Nothing happens for a player
	 * who does not pilot a powered pod. A press with nothing to line, or no brick to line with, says so and starts nothing.
	 */
	public static void toggle(ServerPlayer player) {
		if (!(player.getVehicle() instanceof PodEntity pod) || pod.getControllingPassenger() != player) {
			return;
		}
		State state = of(pod);
		if (state.working()) {
			stop(pod, player, state, "deepcharter.pod.lining.stopped");
			return;
		}
		if (!PodEvents.isPowered(pod)) {
			player.sendOverlayMessage(Component.translatable("deepcharter.pod.lining.no_power"));
			return;
		}
		if (cellsToLine(pod).isEmpty()) {
			player.sendOverlayMessage(Component.translatable("deepcharter.pod.lining.nothing"));
			return;
		}
		if (available(pod, player, state) == 0) {
			modify(pod, State::ranDry);
			outOfBrick(pod, player);
			return;
		}
		modify(pod, State::begun);
	}

	/** Bricks the pilot can line with now: the rack, if the pilot may use the pod's stores, and the inventory. */
	private static int available(PodEntity pod, ServerPlayer pilot, State state) {
		return (mayUseStores(pod, pilot) ? state.bricks() : 0) + carried(pilot.getInventory());
	}

	static boolean mayUseStores(PodEntity pod, ServerPlayer pilot) {
		return PodComponents.mayAccess(pod, Charters.readableCharterOf(pilot.level().getServer(), pilot.getUUID()));
	}

	static int carried(Inventory inventory) {
		int count = 0;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.is(SlagBrick.item())) {
				count += stack.getCount();
			}
		}
		return count;
	}

	static void takeCarried(Inventory inventory) {
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.is(SlagBrick.item())) {
				stack.shrink(1);
				return;
			}
		}
		throw new IllegalStateException("no slag brick carried to take");
	}

	private static void afterTick(PodEntity pod) {
		State state = of(pod);
		if (!state.working()) {
			return;
		}
		if (!(pod.getControllingPassenger() instanceof ServerPlayer pilot) || !PodEvents.isPowered(pod)) {
			modify(pod, State::stopped);
			return;
		}
		if (pod.tickCount % PodLiningTuning.DEFAULT.ticksPerBrick() == 0) {
			placeNext(pod, pilot, state);
		}
	}

	/** Places the next brick of the plan, or ends the lining: the plan is done, or the brick are. */
	private static void placeNext(PodEntity pod, ServerPlayer pilot, State state) {
		List<BlockPos> cells = cellsToLine(pod);
		if (cells.isEmpty()) {
			stop(pod, pilot, state, "deepcharter.pod.lining.done");
			return;
		}
		boolean fromRack = mayUseStores(pod, pilot) && state.bricks() > 0;
		if (!fromRack && carried(pilot.getInventory()) == 0) {
			modify(pod, State::ranDry);
			outOfBrick(pod, pilot);
			return;
		}
		if (!fromRack) {
			takeCarried(pilot.getInventory());
		}
		BlockPos cell = cells.getFirst();
		ServerLevel level = (ServerLevel) pod.level();
		setBrick(level, cell);
		level.playSound(null, cell, DeepSound.POD_LINING_PLACE.event(), SoundSource.BLOCKS);
		State after = state.placed(fromRack);
		pod.setAttached(STATE, Versioned.of(after));
		pilot.sendOverlayMessage(Component.translatable("deepcharter.pod.lining.progress", after.used(), available(pod, pilot, after)));
	}

	/** Puts a slag brick in a cell of the plan; the caller has taken the brick from its store. */
	static void setBrick(ServerLevel level, BlockPos cell) {
		level.setBlock(cell, SlagBrick.BLOCK.defaultBlockState(), Block.UPDATE_ALL);
	}

	private static void stop(PodEntity pod, ServerPlayer pilot, State state, String messageKey) {
		modify(pod, State::stopped);
		pilot.sendOverlayMessage(Component.translatable(messageKey, state.used(), available(pod, pilot, state)));
	}

	private static void outOfBrick(PodEntity pod, ServerPlayer pilot) {
		pilot.sendOverlayMessage(Component.translatable("deepcharter.pod.lining.out"));
		pod.level().playSound(null, pod.getX(), pod.getY(), pod.getZ(), DeepSound.DRILL_BLOCKED.event(), SoundSource.NEUTRAL);
	}

	/**
	 * The cells the next lining places a brick in, in the order it places them. The ring is the open cells (air or any fluid) beside the
	 * pod's footprint from the slab below the pod to the top of its box. The floor is the lava in the footprint's cells of the slab below
	 * (which the drill will not bore) and of the one under that (which the pod touches the moment it sinks into the slab below). Lava goes
	 * first, then open cells beside lava, then the rest, lowest first. Rock, ore, company rock and a cell in or beside an unloaded chunk
	 * are never in the list.
	 */
	public static List<BlockPos> cellsToLine(PodEntity pod) {
		return cellsToLine(pod, HAND_REACH);
	}

	/**
	 * As {@link #cellsToLine(PodEntity)} for a lining that reaches {@code reach} slabs below the pod's feet: the ring starts that far down
	 * (the liner lines the stretch it is about to bore), and the floor reaches that far if it is more than {@link #FLOOR_DEPTH}.
	 */
	static List<BlockPos> cellsToLine(PodEntity pod, int reach) {
		ServerLevel level = (ServerLevel) pod.level();
		LoadedBlocks blocks = new LoadedBlocks(level);
		PodFootprint foot = PodFootprint.of(pod);
		List<BlockPos> cells = new ArrayList<>();
		for (int y = foot.feetY() - Math.max(FLOOR_DEPTH, reach); y < foot.feetY() + foot.height(); y++) {
			for (int x = foot.lowX() - 1; x <= foot.lowX() + foot.width(); x++) {
				for (int z = foot.lowZ() - 1; z <= foot.lowZ() + foot.width(); z++) {
					boolean insideX = x >= foot.lowX() && x < foot.lowX() + foot.width();
					boolean insideZ = z >= foot.lowZ() && z < foot.lowZ() + foot.width();
					boolean ring = insideX != insideZ && y >= foot.feetY() - reach;
					boolean floor = insideX && insideZ && y < foot.feetY();
					BlockPos pos = new BlockPos(x, y, z);
					// A rider sits low in its cab, its feet under the pod's own (#382): the pod's riders are not in the way of its lining.
					if ((ring || floor) && !level.isOutsideBuildHeight(pos) && blocks.canChange(pos) && needsBrick(blocks.getBlockState(pos), floor)
							&& level.getEntities((Entity) null, new AABB(pos), entity -> !entity.isSpectator() && !pod.hasPassenger(entity)).isEmpty()) {
						cells.add(pos);
					}
				}
			}
		}
		cells.sort(Comparator.comparingInt((BlockPos pos) -> urgency(blocks, pos)).thenComparingInt(BlockPos::getY).thenComparingLong(BlockPos::asLong));
		return cells;
	}

	/**
	 * An open cell takes a brick: air, a pure fluid, or a block that gives way (a plant). A block that only holds a fluid, such as a
	 * waterlogged slab or chest, is not open. A floor cell takes one only when it is lava, because the drill bores the rest.
	 */
	private static boolean needsBrick(BlockState state, boolean floor) {
		if (floor) {
			return state.getBlock() instanceof LiquidBlock && state.getFluidState().is(FluidTags.LAVA);
		}
		return !state.is(Blocks.LIGHT) && (state.isAir() || state.getBlock() instanceof LiquidBlock || state.canBeReplaced());
	}

	/** 0 for lava, 1 for an open cell beside lava, 2 for any other. */
	private static int urgency(LoadedBlocks blocks, BlockPos pos) {
		if (blocks.getFluidState(pos).is(FluidTags.LAVA)) {
			return 0;
		}
		for (Direction direction : Direction.values()) {
			if (blocks.getFluidState(pos.relative(direction)).is(FluidTags.LAVA)) {
				return 1;
			}
		}
		return 2;
	}
}
