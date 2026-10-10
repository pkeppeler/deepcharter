package io.github.pkeppeler.deepcharter.pod;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

import io.github.pkeppeler.deepcharter.layer.BreachService;
import io.github.pkeppeler.deepcharter.layer.Depth;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.GasHazard;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.scanner.LoadedBlocks;

/**
 * Pod drilling (SPEC section 7): sprint bores down, pushing into a wall bores sideways, never up. The bore is the
 * pod's footprint, one slab at a time.
 */
public final class PodDrill {
	private static final double ALIGNED = 1e-6;

	/** Ticks spent on one slab; a different slab or direction starts over, as soon as the pod faces it. */
	record Progress(Direction direction, BlockPos slabOrigin, int ticks) {
		boolean continues(Direction direction, BlockPos slabOrigin) {
			return this.direction == direction && this.slabOrigin.equals(slabOrigin);
		}
	}

	private PodDrill() {
	}

	public static void init() {
	}

	/** Above sea level the depth is negative; the drill is no faster for it. */
	public static int drillTicks(PodStats stats, float hardness, int depthFeet) {
		double ticks = hardness * stats.ticksPerHardness() * (1 + Math.max(0, depthFeet) / 1000.0);
		return Math.max(1, (int) Math.ceil(ticks));
	}

	/** Called every pod tick, on the server only. */
	static void tick(PodEntity pod, PodStats stats) {
		Direction wanted = PodEvents.isPowered(pod) && !PodLining.working(pod) ? wantedDirection(pod) : null;
		if (wanted == null) {
			stop(pod);
			return;
		}
		Slab slab = Slab.of(pod, wanted);
		// Progress belongs to the slab it was made on. Judge that before the pod is centred: a pod that was moved off its slab (teleport,
		// push, tow) and is still sliding onto the next one must not hold the old slab's ticks, or it could slide back and resume them.
		Progress progress = pod.drillProgress();
		if (progress != null && !progress.continues(wanted, slab.origin())) {
			pod.setDrillProgress(null);
			progress = null;
		}
		// Centre before judging the slab: a pod straddling a third column can see an all-air footprint, and
		// sliding onto it (off a ledge, past a wall's edge) is how it reaches something to drill.
		boolean centred = centre(pod, slab, stats);
		if (!slab.hasWork() || !slab.allowed()) {
			if (slab.hasCompanyRock() && pod.getControllingPassenger() instanceof ServerPlayer pilot && pod.tickCount % 40 == 0) {
				pilot.sendOverlayMessage(Component.translatable("deepcharter.ore.company_rock_refused"));
			}
			stop(pod);
			return;
		}
		pod.setDrilling(true);
		pod.setDrillDirection(wanted);
		if (!centred) {
			return;
		}
		int ticks = progress != null ? progress.ticks() + 1 : 1;
		// A sounder that bleeds makes the drill wait before it bores a pocket; the blast then costs the pod at most a share of its hull (PodSounder).
		if (ticks < slab.drillTicks(stats) + (slab.hasGasPocket() ? PodSounder.bleedPauseTicks(pod) : 0)) {
			pod.setDrillProgress(new Progress(wanted, slab.origin(), ticks));
			return;
		}
		pod.setDrillProgress(null);
		slab.bore(pod, stats);
	}

	private static void stop(PodEntity pod) {
		pod.setDrilling(false);
		pod.setDrillProgress(null);
	}

	/** Down on sprint, a horizontal direction on a push into a wall; null when the pod is not on the ground or is not asked to drill. */
	private static Direction wantedDirection(PodEntity pod) {
		if (!pod.onGround() || !(pod.getControllingPassenger() instanceof ServerPlayer pilot)) {
			return null;
		}
		Input input = pilot.getLastClientInput();
		Direction drive = PodMovement.driveDirection(input, pilot.getYRot());
		if (drive != null) {
			return pod.horizontalCollision ? drive : null;
		}
		return input.sprint() ? Direction.DOWN : null;
	}

	/** Slides the pod toward the centre of its bore; true once there. The centre is inside blocks the pod already overlaps. */
	private static boolean centre(PodEntity pod, Slab slab, PodStats stats) {
		double speed = stats.alignSpeed();
		double dx = slab.centreX() - pod.getX();
		double dz = slab.centreZ() - pod.getZ();
		pod.setPos(pod.getX() + Mth.clamp(dx, -speed, speed), pod.getY(), pod.getZ() + Mth.clamp(dz, -speed, speed));
		return Math.abs(dx) <= speed + ALIGNED && Math.abs(dz) <= speed + ALIGNED;
	}

	/** The cells one bore step removes, and what is in them. */
	private record Slab(ServerLevel level, List<BlockPos> cells, double centreX, double centreZ) {
		static Slab of(PodEntity pod, Direction direction) {
			PodFootprint foot = PodFootprint.of(pod);
			int width = foot.width();
			int height = foot.height();
			int lowX = foot.lowX();
			int lowZ = foot.lowZ();
			int feetY = foot.feetY();
			List<BlockPos> cells = new ArrayList<>();
			if (direction == Direction.DOWN) {
				int y = Mth.ceil(pod.getY() - PodFootprint.EPSILON) - 1;
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

		boolean hasCompanyRock() {
			return cells.stream().anyMatch(pos -> state(pos).is(HazardBlocks.UNDIGGABLE));
		}

		boolean hasGasPocket() {
			return cells.stream().anyMatch(pos -> state(pos).is(HazardBlocks.GAS_POCKET));
		}

		boolean hasWork() {
			return cells.stream().anyMatch(pos -> breakable(state(pos)));
		}

		/**
		 * A slab is refused whole, so the bore is never ragged: unbreakable, outside the world, the ceiling row, last-layer crust, or
		 * a cell that cannot change without loading a chunk (breaking a block tells its four sides, so a bore at the edge of the
		 * loaded chunks waits until the next one is loaded; a pilot's view keeps the chunks around a bore loaded).
		 */
		boolean allowed() {
			LoadedBlocks blocks = blocks();
			for (BlockPos pos : cells) {
				BlockState state = blocks.getBlockState(pos);
				if (level.isOutsideBuildHeight(pos) || pos.getY() >= level.getMaxY() || !blocks.canChange(pos)) {
					return false;
				}
				if (breakable(state) && (state.getDestroySpeed(level, pos) < 0 || state.is(HazardBlocks.UNDIGGABLE)
						|| state.is(LayerBlocks.BREACH_CRUST) && !crustLeadsOn())) {
					return false;
				}
			}
			return true;
		}

		int drillTicks(PodStats stats) {
			int depth = Depth.feet(Depth.of(level, cells.stream().mapToInt(BlockPos::getY).min().orElseThrow()));
			int ticks = 1;
			for (BlockPos pos : cells) {
				BlockState state = state(pos);
				if (breakable(state)) {
					ticks = Math.max(ticks, PodDrill.drillTicks(stats, state.getDestroySpeed(level, pos), depth));
				}
			}
			return ticks;
		}

		/** A full bay, or cargo that cannot be read, loses the ore: a drill that refused would trap the pod in its own tunnel (SPEC: only ore is kept). */
		void bore(PodEntity pod, PodStats stats) {
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
					OreRegistry.typeOf(state.getBlock()).ifPresent(ore -> {
						if (pod.cargo().isReadable()) {
							pod.cargo().tryAdd(pod, OreRegistry.stack(ore));
						} else {
							pod.cargo().logDiscardedOre(pod);
						}
					});
					if (state.is(SlagBrick.WASTE_ROCK)) {
						PodLining.keepSpoil(pod);
					}
					level.destroyBlock(pos, false);
					if (state.is(HazardBlocks.GAS_POCKET)) {
						GasHazard.vent(level, pos, pod);
					}
				}
			}
			if (crust) {
				pod.damageHull(stats.crustHullDamage());
			}
		}

		private BlockState state(BlockPos pos) {
			return blocks().getBlockState(pos);
		}

		/** A cell in an unloaded chunk reads as air, so the plain read never loads the chunk next door at the edge of a bore. */
		private LoadedBlocks blocks() {
			return new LoadedBlocks(level);
		}

		private static boolean breakable(BlockState state) {
			return !state.isAir() && !(state.getBlock() instanceof LiquidBlock);
		}

		/** The last layer's crust has nothing under it: a hole would only drop the pod into the void. */
		private boolean crustLeadsOn() {
			OptionalInt layer = LayerChain.layerOf(level.dimensionTypeRegistration().unwrapKey().orElseThrow().identifier());
			return layer.isEmpty() || layer.getAsInt() < LayerChain.count(level.registryAccess());
		}
	}
}
