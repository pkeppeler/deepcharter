package io.github.pkeppeler.deepcharter.pod;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.repair.RepairTuning;
import io.github.pkeppeler.deepcharter.scanner.LoadedBlocks;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;

/**
 * The blasting charge (#509, ADR 0015): set from the seat on the slab under the pod, a fuse, then a blast that clears
 * {@code deepcharter:undiggable} (Company rock) and the natural rock round it. It is the one thing that does. The blast
 * volume is the pod's bore square, {@code chargeDepth} slab rows deep.
 *
 * <p>Rules: only blocks of {@link HazardBlocks#UNDIGGABLE} and {@link HazardBlocks#NATURAL_ROCK} go (so bricks, lining,
 * liner rings, structures, bedrock, crust and every fluid stay), and only in loaded chunks. A gas pocket in the volume
 * goes with the blast and vents no more, as in a gas blast. Lava next to the hole is not touched; the hole lets it
 * flow, as a bore does. Every pod within {@code chargeReach} of the volume takes {@code chargeDamage} less its distance share, the setter's too
 * unless it climbs clear (a wreck takes nothing; another charter's pod in reach is hurt, the risk of a shared shaft). A pod with no more hull
 * than {@code chargeDamage} cannot set a charge.
 * The fuse belongs to the pod and is neither saved nor synced: a pod that is discarded or unloaded before the blast loses it.
 */
public final class PodCharge {
	private static final int TICKS_PER_SECOND = 20;
	private static final int SPARK_EVERY_TICKS = 4;

	/** A burning fuse: the volume it will clear (inclusive corners) and the numbers of the tuning it was set with. */
	record Fuse(BlockPos low, BlockPos high, int ticksLeft, int startTicks, float damage, double reach) {
		Fuse burned() {
			return new Fuse(low, high, ticksLeft - 1, startTicks, damage, reach);
		}

		AABB volume() {
			return new AABB(low.getX(), low.getY(), low.getZ(), high.getX() + 1, high.getY() + 1, high.getZ() + 1);
		}
	}

	private PodCharge() {
	}

	/** Server only: sets a charge under {@code pod}. Empty when it is set (the caller uses the item up), or the reason it was not. */
	public static Optional<Component> set(PodEntity pod, RepairTuning tuning) {
		if (pod.fuse() != null) {
			return refusal("charge_burning");
		}
		if (!pod.onGround()) {
			return refusal("charge_not_resting");
		}
		if (pod.hull() <= tuning.chargeDamage()) {
			return refusal("charge_hull_low");
		}
		PodFootprint foot = PodFootprint.of(pod);
		int top = Mth.ceil(pod.getY() - PodFootprint.EPSILON) - 1;
		BlockPos low = new BlockPos(foot.lowX(), top - tuning.chargeDepth() + 1, foot.lowZ());
		BlockPos high = new BlockPos(foot.lowX() + foot.width() - 1, top, foot.lowZ() + foot.width() - 1);
		ServerLevel level = (ServerLevel) pod.level();
		if (BlockPos.betweenClosedStream(low, high).noneMatch(cell -> new LoadedBlocks(level).getBlockState(cell).is(HazardBlocks.UNDIGGABLE))) {
			return refusal("charge_no_company_rock");
		}
		Fuse fuse = new Fuse(low, high, tuning.chargeFuseTicks(), tuning.chargeFuseTicks(), tuning.chargeDamage(), tuning.chargeReach());
		pod.setFuse(fuse);
		level.playSound(null, pod.getX(), pod.getY(), pod.getZ(), DeepSound.DRILL_DYNAMITE.event(), SoundSource.PLAYERS);
		count(pod, fuse, "charge_set");
		return Optional.empty();
	}

	/** Called every pod tick, on the server only. */
	static void tick(PodEntity pod) {
		Fuse fuse = pod.fuse();
		if (fuse == null) {
			return;
		}
		ServerLevel level = (ServerLevel) pod.level();
		if (fuse.ticksLeft() <= 0) {
			pod.setFuse(null);
			blast(level, fuse);
			return;
		}
		if (fuse.ticksLeft() % TICKS_PER_SECOND == 0 && fuse.ticksLeft() < fuse.startTicks()) {
			count(pod, fuse, "charge_count");
		}
		if (fuse.ticksLeft() % SPARK_EVERY_TICKS == 0) {
			AABB volume = fuse.volume();
			level.sendParticles(ParticleTypes.FLAME, volume.getCenter().x, volume.maxY, volume.getCenter().z, 3, 0.4, 0.1, 0.4, 0.01);
			level.sendParticles(ParticleTypes.SMOKE, volume.getCenter().x, volume.maxY + 0.2, volume.getCenter().z, 2, 0.3, 0.1, 0.3, 0.01);
		}
		pod.setFuse(fuse.burned());
	}

	/** The seconds left, on the pilot's action bar. */
	private static void count(PodEntity pod, Fuse fuse, String key) {
		if (pod.getControllingPassenger() instanceof ServerPlayer pilot) {
			pilot.sendOverlayMessage(Component.translatable("message.deepcharter.repair." + key, Mth.ceil(fuse.ticksLeft() / (double) TICKS_PER_SECOND)));
		}
	}

	private static void blast(ServerLevel level, Fuse fuse) {
		AABB volume = fuse.volume();
		Vec3 middle = volume.getCenter();
		// entity-query: the blast reaches chargeReach round a charge that a ticking pod set, so its chunks are entity-ticking
		for (PodEntity pod : level.getEntitiesOfClass(PodEntity.class, volume.inflate(fuse.reach()))) {
			if (Wrecks.isWreck(pod)) {
				continue;
			}
			double share = 1 - Math.sqrt(distanceSqr(volume, pod.getBoundingBox())) / fuse.reach();
			if (share > 0) {
				pod.damageHull((float) (fuse.damage() * share));
			}
		}
		LoadedBlocks blocks = new LoadedBlocks(level);
		for (BlockPos cell : BlockPos.betweenClosed(fuse.low(), fuse.high())) {
			if (clears(level, blocks, cell)) {
				level.destroyBlock(cell.immutable(), false);
			}
		}
		level.sendParticles(ParticleTypes.EXPLOSION, middle.x, middle.y, middle.z, 6, 1.0, 0.5, 1.0, 0.0);
		level.playSound(null, middle.x, middle.y, middle.z, DeepSound.DRILL_PLASTIC.event(), SoundSource.PLAYERS);
	}

	private static boolean clears(ServerLevel level, LoadedBlocks blocks, BlockPos cell) {
		if (level.isOutsideBuildHeight(cell) || cell.getY() >= level.getMaxY() || !blocks.canChange(cell)) {
			return false;
		}
		var state = blocks.getBlockState(cell);
		return state.is(HazardBlocks.UNDIGGABLE) || state.is(HazardBlocks.NATURAL_ROCK);
	}

	/** Squared distance between two boxes, 0 when they touch or overlap. */
	private static double distanceSqr(AABB a, AABB b) {
		double dx = Math.max(0, Math.max(a.minX - b.maxX, b.minX - a.maxX));
		double dy = Math.max(0, Math.max(a.minY - b.maxY, b.minY - a.maxY));
		double dz = Math.max(0, Math.max(a.minZ - b.maxZ, b.minZ - a.maxZ));
		return dx * dx + dy * dy + dz * dz;
	}

	private static Optional<Component> refusal(String reason) {
		return Optional.of(Component.translatable("message.deepcharter.repair." + reason));
	}
}
