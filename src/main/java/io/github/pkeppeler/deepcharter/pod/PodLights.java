package io.github.pkeppeler.deepcharter.pod;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.stream.Collectors;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTuning;

/**
 * The pod's lights (SPEC section 7, the lights track): a powered pod with a lights part holds one vanilla {@code light}
 * block in its own column, at the part's level, and moves it with the pod. A stranded or wrecked pod is unpowered
 * ({@link PodEvents#isPowered}), so it is dark.
 *
 * <p>A light block is world state and outlives the pod, so it is never left behind. The pod takes its block away when it
 * moves, loses power, loses the part, is removed (which covers unloading, a breach crossing and a wreck being removed), and
 * when the server stops. A crash skips all of that, so every block is recorded in the {@link PodLightLedger} before it is
 * placed, and each dimension sweeps the recorded blocks that no pod holds, as soon as their chunk is loaded.
 *
 * <p>The pod never replaces a block that is not air, and takes away only a block that is still a {@code light} block at a
 * position it recorded: a builder's light block is not touched. The light always sits in the pod's own column, so it is in the
 * pod's chunk and goes with the pod.
 *
 * <p>The listeners run on every tick, so they never throw: a ledger this build cannot read is logged once and the pod
 * places no light.
 */
public final class PodLights {
	/** Where a pod's light is, and in what dimension. */
	private record Lit(ServerLevel level, BlockPos pos, int lightLevel) {
		GlobalPos global() {
			return GlobalPos.of(level.dimension(), pos);
		}
	}

	/** The light each pod holds now. Server thread only; weak, so a pod that vanished without an event does not leak. */
	private static final Map<PodEntity, Lit> LIT = Collections.synchronizedMap(new WeakHashMap<>());
	private static volatile boolean ledgerUnreadableLogged;

	private PodLights() {
	}

	public static void init() {
		PodEvents.AFTER_TICK.register(PodLights::afterTick);
		ServerEntityEvents.ENTITY_UNLOAD.register(PodLights::onUnload);
		ServerTickEvents.END_LEVEL_TICK.register(PodLights::sweep);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> releaseAll());
	}

	private static void afterTick(PodEntity pod) {
		if (!(pod.level() instanceof ServerLevel level)) {
			return;
		}
		int lightLevel = lightLevel(pod);
		Lit held = LIT.get(pod);
		if (held != null && lightLevel > 0 && stillHolds(held, pod, lightLevel)) {
			return;
		}
		if (held != null) {
			release(held);
			LIT.remove(pod);
		}
		if (lightLevel > 0) {
			place(pod, level, lightLevel);
		}
	}

	/** 0 when the pod is dark: unpowered, or no lights part that counts. */
	private static int lightLevel(PodEntity pod) {
		if (!PodEvents.isPowered(pod)) {
			return 0;
		}
		int tier = PodComponents.effectiveTier(pod, ComponentTrack.LIGHTS);
		return Math.round(UpgradeTuning.DEFAULT.value(ComponentTrack.LIGHTS, tier));
	}

	/** True while the held block is still the pod's light, at the right level, in the pod's column and height. */
	private static boolean stillHolds(Lit held, PodEntity pod, int lightLevel) {
		BlockPos pos = held.pos();
		if (held.level() != pod.level() || held.lightLevel() != lightLevel
				|| pos.getX() != pod.getBlockX() || pos.getZ() != pod.getBlockZ()
				|| pos.getY() < Math.floor(pod.getBoundingBox().minY) || pos.getY() > Math.floor(pod.getBoundingBox().maxY)) {
			return false;
		}
		BlockState state = held.level().getBlockState(pos);
		return state.is(Blocks.LIGHT) && state.getValue(LightBlock.LEVEL) == lightLevel;
	}

	private static void place(PodEntity pod, ServerLevel level, int lightLevel) {
		PodLightLedger ledger = PodLightLedger.get(level.getServer());
		if (!ledger.isReadable()) {
			if (!ledgerUnreadableLogged) {
				ledgerUnreadableLogged = true;
				DeepCharter.LOGGER.error("Pod lights are off: the saved light ledger has version {}, which this build cannot read, and a light it cannot record it could not clean up",
						ledger.unreadableVersion());
			}
			return;
		}
		BlockPos pos = freeCell(pod, level);
		if (pos == null) {
			return;
		}
		Lit lit = new Lit(level, pos, lightLevel);
		// Recorded first: a save between the two steps holds the entry and not the block, which the sweep reads as a block already gone.
		ledger.record(lit.global());
		level.setBlock(pos, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, lightLevel), Block.UPDATE_ALL);
		LIT.put(pod, lit);
	}

	/** The first air cell of the pod's column, from its middle upward and then down; null when the column is solid. */
	private static BlockPos freeCell(PodEntity pod, ServerLevel level) {
		int low = (int) Math.floor(pod.getBoundingBox().minY);
		int high = (int) Math.floor(pod.getBoundingBox().maxY);
		int middle = Math.clamp((int) Math.floor(pod.getY() + pod.getBbHeight() / 2.0), low, high);
		BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos(pod.getBlockX(), 0, pod.getBlockZ());
		for (int y = middle; y <= high; y++) {
			if (level.getBlockState(cell.setY(y)).isAir()) {
				return cell.immutable();
			}
		}
		for (int y = middle - 1; y >= low; y--) {
			if (level.getBlockState(cell.setY(y)).isAir()) {
				return cell.immutable();
			}
		}
		return null;
	}

	/**
	 * Takes the block away if it is still a light block and its chunk is loaded, and forgets it in the ledger. A block in
	 * an unloaded chunk stays recorded, and the sweep takes it away when the chunk loads.
	 */
	private static void release(Lit held) {
		ServerLevel level = held.level();
		if (!level.hasChunkAt(held.pos())) {
			return;
		}
		clear(level, held.pos());
		PodLightLedger ledger = PodLightLedger.get(level.getServer());
		if (ledger.isReadable()) {
			ledger.forget(held.global());
		}
	}

	private static void clear(ServerLevel level, BlockPos pos) {
		if (level.getBlockState(pos).is(Blocks.LIGHT)) {
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
		}
	}

	private static void onUnload(Entity entity, ServerLevel level) {
		if (entity instanceof PodEntity pod) {
			Lit held = LIT.remove(pod);
			if (held != null) {
				release(held);
			}
		}
	}

	private static void releaseAll() {
		synchronized (LIT) {
			for (Lit held : LIT.values()) {
				release(held);
			}
			LIT.clear();
		}
	}

	/** Takes away the recorded light blocks of this dimension that no pod holds and whose chunk is loaded. */
	private static void sweep(ServerLevel level) {
		if (level.getGameTime() % PodLightsTuning.DEFAULT.sweepIntervalTicks() != 0) {
			return;
		}
		PodLightLedger ledger = PodLightLedger.get(level.getServer());
		if (!ledger.isReadable()) {
			return;
		}
		Set<GlobalPos> recorded = ledger.entries();
		if (recorded.isEmpty()) {
			return;
		}
		Set<GlobalPos> held = heldIn(level);
		for (GlobalPos entry : recorded) {
			if (entry.dimension().equals(level.dimension()) && !held.contains(entry) && level.hasChunkAt(entry.pos())) {
				clear(level, entry.pos());
				ledger.forget(entry);
			}
		}
	}

	private static Set<GlobalPos> heldIn(ServerLevel level) {
		synchronized (LIT) {
			return LIT.values().stream().filter(lit -> lit.level() == level).map(Lit::global).collect(Collectors.toSet());
		}
	}
}
