package io.github.pkeppeler.deepcharter.pod;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.WeakHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.layer.BreachService;
import io.github.pkeppeler.deepcharter.layer.Depth;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain;

/**
 * Pod drilling (SPEC section 7). Sprint on the ground bores down; pushing into a wall on the ground bores
 * sideways; there is no way to bore up. The bore is the pod's footprint, one slab at a time: the pod slides to
 * centre itself on the nearest 2 x 2 (sized by the chassis), waits out the slab's drill time, then breaks the
 * whole slab and moves into it (gravity does so when going down, the pilot's push when going sideways).
 *
 * <p>A slab is refused as a whole, so the bore is never ragged, when any cell of it is unbreakable, is
 * outside the world, is in the top row of the level (a layer's ceiling), or is breach crust in the last layer.
 * Ore goes to the cargo bay; with a full bay it is destroyed, because a drill that refused would trap a pod
 * in its own tunnel (SPEC: only ore is kept, everything else is destroyed). Ore mass is the placeholder
 * {@link PodTuning.Cargo#defaultOreMass()} until ores have weights.
 */
public final class PodDrill {
	/** Ore the cargo bay keeps; it includes the convention tag {@code c:ores}. */
	public static final TagKey<Block> POD_ORE = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod_ore"));

	private static final double EPSILON = 1e-3;
	private static final double ALIGNED = 1e-6;

	/** The slab being bored: its first cell identifies it, so a different slab or direction starts over. Server only. */
	private record Progress(Direction direction, BlockPos slabOrigin, int ticks) {
	}

	private static final Map<PodEntity, Progress> PROGRESS = new WeakHashMap<>();

	private PodDrill() {
	}

	public static void init() {
	}

	/** Ticks to bore one slab whose hardest block has this hardness, at this depth. No faster than at sea level. */
	public static int drillTicks(float hardness, int depthFeet, boolean crust) {
		PodTuning.Drill tuning = PodTuning.DEFAULT.drill();
		double ticks = hardness * tuning.ticksPerHardness() * (1 + Math.max(0, depthFeet) / 1000.0) * (crust ? tuning.crustTimeFactor() : 1f);
		return Math.max(1, (int) Math.ceil(ticks));
	}

	/** Called every pod tick, on both sides; only the server drills. */
	public static void tick(PodEntity pod) {
		if (pod.level().isClientSide()) {
			return;
		}
		Direction wanted = pod.stranded() ? null : wantedDirection(pod);
		if (wanted == null) {
			stop(pod);
			return;
		}
		Slab slab = Slab.of(pod, wanted);
		if (!slab.hasWork() || !slab.allowed()) {
			stop(pod);
			return;
		}
		pod.setDrilling(true);
		pod.setDrillDirection(wanted);
		if (!centre(pod, slab)) {
			return;
		}
		Progress progress = PROGRESS.get(pod);
		int ticks = progress != null && progress.direction == wanted && progress.slabOrigin.equals(slab.origin()) ? progress.ticks + 1 : 1;
		if (ticks < slab.drillTicks()) {
			PROGRESS.put(pod, new Progress(wanted, slab.origin(), ticks));
			return;
		}
		PROGRESS.remove(pod);
		slab.bore(pod);
	}

	private static void stop(PodEntity pod) {
		pod.setDrilling(false);
		PROGRESS.remove(pod);
	}

	/** Down on sprint, a horizontal direction on a push into a wall; null when the pod is not on the ground or is not asked to drill. */
	private static Direction wantedDirection(PodEntity pod) {
		if (!pod.onGround() || !(pod.getControllingPassenger() instanceof ServerPlayer pilot)) {
			return null;
		}
		Input input = pilot.getLastClientInput();
		Direction drive = driveDirection(input, pilot.getYRot());
		if (drive != null) {
			return pod.horizontalCollision ? drive : null;
		}
		return input.sprint() ? Direction.DOWN : null;
	}

	/** The same snap as PodMovement's drive: forward and back win over strafing, one axis only. Null when no direction key is held. */
	private static Direction driveDirection(Input input, float pilotYaw) {
		Direction facing = Direction.fromYRot(pilotYaw);
		if (input.forward() != input.backward()) {
			return input.forward() ? facing : facing.getOpposite();
		}
		if (input.left() != input.right()) {
			return input.right() ? facing.getClockWise() : facing.getCounterClockWise();
		}
		return null;
	}

	/**
	 * Slides the pod toward the centre of its bore, {@link PodTuning.Drill#alignSpeed()} a tick. Returns true once
	 * it is there. The centre lies inside the blocks the pod already overlaps, so this never moves it into a wall.
	 */
	private static boolean centre(PodEntity pod, Slab slab) {
		double speed = PodTuning.DEFAULT.drill().alignSpeed();
		double dx = slab.centreX() - pod.getX();
		double dz = slab.centreZ() - pod.getZ();
		pod.setPos(pod.getX() + Mth.clamp(dx, -speed, speed), pod.getY(), pod.getZ() + Mth.clamp(dz, -speed, speed));
		return Math.abs(dx) <= speed + ALIGNED && Math.abs(dz) <= speed + ALIGNED;
	}

	/** The cells one bore step removes, and what is in them. */
	private record Slab(ServerLevel level, List<BlockPos> cells, double centreX, double centreZ) {
		/** The slab in front of the pod in {@code direction}; sized by its chassis. */
		static Slab of(PodEntity pod, Direction direction) {
			Chassis chassis = pod.chassis();
			int width = Mth.ceil(chassis.width());
			int height = Mth.ceil(chassis.height());
			// The nearest block-aligned footprint: its low corner, and the centre the pod slides to.
			int lowX = Mth.floor(pod.getX() - width / 2.0 + 0.5);
			int lowZ = Mth.floor(pod.getZ() - width / 2.0 + 0.5);
			int feetY = Mth.floor(pod.getY() + EPSILON);
			List<BlockPos> cells = new ArrayList<>();
			if (direction == Direction.DOWN) {
				int y = Mth.ceil(pod.getY() - EPSILON) - 1;
				for (int x = lowX; x < lowX + width; x++) {
					for (int z = lowZ; z < lowZ + width; z++) {
						cells.add(new BlockPos(x, y, z));
					}
				}
			} else {
				boolean alongX = direction.getAxis() == Direction.Axis.X;
				int along = alongX ? lowX : lowZ;
				int front = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? along + width : along - 1;
				for (int lateral = alongX ? lowZ : lowX; lateral < (alongX ? lowZ : lowX) + width; lateral++) {
					for (int y = feetY; y < feetY + height; y++) {
						cells.add(alongX ? new BlockPos(front, y, lateral) : new BlockPos(lateral, y, front));
					}
				}
			}
			return new Slab((ServerLevel) pod.level(), cells, lowX + width / 2.0, lowZ + width / 2.0);
		}

		BlockPos origin() {
			return cells.getFirst();
		}

		/** True when at least one cell holds something to break. */
		boolean hasWork() {
			return cells.stream().anyMatch(pos -> breakable(state(pos)));
		}

		/** False when any cell would make the bore ragged or break what must not break. */
		boolean allowed() {
			for (BlockPos pos : cells) {
				BlockState state = state(pos);
				if (level.isOutsideBuildHeight(pos) || pos.getY() >= level.getMaxY()) {
					return false;
				}
				if (breakable(state) && (state.getDestroySpeed(level, pos) < 0 || state.is(LayerBlocks.BREACH_CRUST) && !crustLeadsOn())) {
					return false;
				}
			}
			return true;
		}

		int drillTicks() {
			int depth = Depth.feet(Depth.of(level, cells.stream().mapToInt(BlockPos::getY).min().orElseThrow()));
			int ticks = 1;
			for (BlockPos pos : cells) {
				BlockState state = state(pos);
				if (breakable(state)) {
					ticks = Math.max(ticks, PodDrill.drillTicks(state.getDestroySpeed(level, pos), depth, state.is(LayerBlocks.BREACH_CRUST)));
				}
			}
			return ticks;
		}

		/** Breaks every breakable cell: ore into the bay (or lost when it is full), crust through the breach service, with hull damage. */
		void bore(PodEntity pod) {
			boolean crust = false;
			for (BlockPos pos : cells) {
				BlockState state = state(pos);
				if (!breakable(state)) {
					continue;
				}
				if (state.is(LayerBlocks.BREACH_CRUST)) {
					crust = true;
					BreachService.breakCrust(level, pos);
				} else {
					if (state.is(POD_ORE)) {
						pod.cargo().tryAdd(pod, state.getBlock());
					}
					level.destroyBlock(pos, false);
				}
			}
			if (crust) {
				pod.setHull(Math.max(0f, pod.hull() - PodTuning.DEFAULT.drill().crustHullDamage()));
			}
		}

		private BlockState state(BlockPos pos) {
			return level.getBlockState(pos);
		}

		/** Air and fluid are not drilled: the bore goes through them. */
		private static boolean breakable(BlockState state) {
			return !state.isAir() && !(state.getBlock() instanceof LiquidBlock);
		}

		/** The crust of the last layer has nothing under it, so a hole through it would only drop the pod into the void. */
		private boolean crustLeadsOn() {
			OptionalInt layer = LayerChain.layerOf(level.dimensionTypeRegistration().unwrapKey().orElseThrow().identifier());
			return layer.isEmpty() || layer.getAsInt() < LayerChain.count(level.registryAccess());
		}
	}
}
