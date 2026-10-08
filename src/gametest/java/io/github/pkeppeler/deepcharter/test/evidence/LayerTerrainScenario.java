package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.layer.LayerChain;

/**
 * Evidence scenario "m2-layer-terrain": a fly-through of the noise caves of layer 1 and then layer 2. The route is
 * found on the server from the generated terrain: it picks the roomiest cave near the start, follows the longest
 * open way through it, and the camera glides along that way, looking ahead. The player has night vision, because the
 * layers are dark by design and the point here is the shape of the ground.
 */
public class LayerTerrainScenario extends EvidenceScenario {
	private static final int START_X = 2000;
	private static final int START_Z = 2000;
	private static final int SEARCH_RADIUS = 40;
	private static final int MIN_CLEARANCE = 3;
	private static final int CLEARANCE_PROBE = 8;
	private static final int CANDIDATES = 8;
	private static final int MAX_CELLS = 60_000;
	private static final int MIN_ROUTE = 40;
	private static final int SMOOTHING = 4;
	private static final int LOOK_AHEAD = 5;
	private static final double EYE_HEIGHT = 1.62;
	private static final int[] FRAMES_PER_LAYER = {42, 24};
	private static final int TICKS_PER_FRAME = 2;
	private static final Direction[] AXES = {Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH};

	@Override
	protected String name() {
		return "m2-layer-terrain";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitFor(client -> client.player != null && client.level != null);
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.setGameMode(GameType.CREATIVE);
				player.getAbilities().flying = true;
				player.onUpdateAbilities();
				player.setPermanentlyInvulnerable(true);
				player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false));
			});
			for (int layer = 1; layer <= 2; layer++) {
				flyThrough(context, singleplayer, layer, FRAMES_PER_LAYER[layer - 1]);
				screenshot(context, "layer-" + layer + "-cave");
			}
		}
	}

	private void flyThrough(ClientGameTestContext context, TestSingleplayerContext singleplayer, int layer, int frames) {
		List<Vec3> route = singleplayer.getServer().computeOnServer(server -> {
			ServerLevel level = server.getLevel(LayerChain.dimension(layer));
			return smooth(findRoute(level));
		});
		int stride = Math.max(1, (int) Math.ceil(route.size() / (double) frames));
		float yaw = 0f;
		boolean first = true;
		for (int i = 0; i < route.size() - 1; i += stride) {
			Vec3 at = route.get(i);
			Vec3 ahead = route.get(Math.min(route.size() - 1, i + LOOK_AHEAD * stride));
			Vec3 look = ahead.subtract(at);
			if (look.horizontalDistanceSqr() > 1e-4) {
				float target = (float) (Mth.atan2(-look.x, look.z) * Mth.RAD_TO_DEG);
				yaw = first ? target : Mth.rotLerp(0.3f, yaw, target);
			}
			float pitch = (float) (-Math.atan2(look.y, Math.sqrt(look.horizontalDistanceSqr())) * Mth.RAD_TO_DEG);
			float shownYaw = yaw;
			float shownPitch = Mth.clamp(pitch, -60f, 60f);
			singleplayer.getServer().runOnServer(server -> {
				ServerLevel level = server.getLevel(LayerChain.dimension(layer));
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.teleportTo(level, at.x, at.y - EYE_HEIGHT, at.z, Set.of(), shownYaw, shownPitch, true);
			});
			if (first) {
				context.waitFor(client -> client.level.dimension().equals(LayerChain.dimension(layer)));
				context.waitTicks(40);
				first = false;
			}
			context.waitTicks(TICKS_PER_FRAME);
			// Vanilla covers the screen with "Loading terrain" while the client waits for chunks; leave those frames out.
			if (context.computeOnClient(client -> client.gui.screen() == null)) {
				frame(context);
			}
		}
	}

	/** The longest open way out of the roomiest of the few best caves near the start, as cell centres. */
	private static List<Vec3> findRoute(ServerLevel level) {
		List<BlockPos> starts = new ArrayList<>();
		Map<BlockPos, Integer> room = new HashMap<>();
		int top = level.getMinY() + 24;
		int bottom = level.getMaxY() - 40;
		for (int x = START_X - SEARCH_RADIUS; x <= START_X + SEARCH_RADIUS; x += 2) {
			for (int z = START_Z - SEARCH_RADIUS; z <= START_Z + SEARCH_RADIUS; z += 2) {
				for (int y = top; y <= bottom; y += 2) {
					BlockPos pos = new BlockPos(x, y, z);
					int clearance = clearance(level, pos);
					if (clearance >= MIN_CLEARANCE) {
						starts.add(pos);
						room.put(pos, clearance);
					}
				}
			}
		}
		starts.sort(Comparator.comparing((BlockPos pos) -> room.get(pos)).reversed());
		List<BlockPos> best = List.of();
		for (BlockPos start : starts.stream().limit(CANDIDATES).toList()) {
			List<BlockPos> route = longestWay(level, start);
			if (route.size() > best.size()) {
				best = route;
			}
		}
		if (best.size() < MIN_ROUTE) {
			throw new AssertionError("No cave route of " + MIN_ROUTE + " cells near " + START_X + ", " + START_Z + " in " + level.dimension().identifier());
		}
		return best.stream().map(Vec3::atCenterOf).toList();
	}

	/** Breadth-first through cells with a clear 3 x 3 x 3 around them; the path to the cell farthest from the start. */
	private static List<BlockPos> longestWay(ServerLevel level, BlockPos start) {
		Map<Long, Long> parent = new HashMap<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		parent.put(start.asLong(), start.asLong());
		queue.add(start);
		BlockPos last = start;
		while (!queue.isEmpty() && parent.size() < MAX_CELLS) {
			BlockPos cell = queue.poll();
			last = cell;
			for (Direction direction : AXES) {
				BlockPos next = cell.relative(direction);
				if (!parent.containsKey(next.asLong()) && clearance(level, next) >= 1) {
					parent.put(next.asLong(), cell.asLong());
					queue.add(next);
				}
			}
		}
		List<BlockPos> route = new ArrayList<>();
		long at = last.asLong();
		while (at != start.asLong()) {
			route.add(BlockPos.of(at));
			at = parent.get(at);
		}
		route.add(start);
		return route.reversed();
	}

	/** How many blocks of air there are from {@code pos} in the least open axis direction, up to the probe length. */
	private static int clearance(ServerLevel level, BlockPos pos) {
		if (level.isOutsideBuildHeight(pos) || !level.getBlockState(pos).isAir()) {
			return 0;
		}
		int least = CLEARANCE_PROBE;
		for (Direction direction : AXES) {
			int free = 0;
			while (free < least && level.getBlockState(pos.relative(direction, free + 1)).isAir()) {
				free++;
			}
			least = Math.min(least, free);
		}
		return least;
	}

	private static List<Vec3> smooth(List<Vec3> route) {
		List<Vec3> smoothed = new ArrayList<>();
		for (int i = 0; i < route.size(); i++) {
			Vec3 sum = Vec3.ZERO;
			int count = 0;
			for (int j = Math.max(0, i - SMOOTHING); j <= Math.min(route.size() - 1, i + SMOOTHING); j++) {
				sum = sum.add(route.get(j));
				count++;
			}
			smoothed.add(sum.scale(1.0 / count));
		}
		return smoothed;
	}
}
