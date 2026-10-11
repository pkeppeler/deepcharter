package io.github.pkeppeler.deepcharter.test.support;

import java.util.Optional;
import java.util.stream.Stream;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;

/**
 * Compares the light a client reads with the light the server holds (issue 427). A client that is sent a chunk while the server's light
 * engine still works on it keeps the light it was sent, so a sealed room can read sky light 15 on the client and 0 on the server. The
 * comparison is the one the design tour used for its dark room (PR 422), shared by every carved evidence scene and by the tests that
 * place blocks as the mod does.
 */
public final class ClientLight {
	/** Ticks the client gets, after the server's light engine is idle, to receive the light packets that follow. */
	private static final int PACKET_TICKS = 10;

	private ClientLight() {
	}

	/** A box of block positions, both corners inclusive. */
	public record Box(BlockPos min, BlockPos max) {
		/** The box {@code radius} blocks round {@code centre} in X and Z, from {@code below} blocks under it to {@code above} over it. */
		public static Box around(BlockPos centre, int radius, int below, int above) {
			return new Box(centre.offset(-radius, -below, -radius), centre.offset(radius, above, radius));
		}

		Stream<BlockPos> cells() {
			return BlockPos.betweenClosedStream(min, max);
		}
	}

	/** One number for the sky and block light of every cell of the box, so that two levels with the same light give the same number. */
	public static int fingerprint(Level level, Box box) {
		return box.cells()
				.mapToInt(pos -> level.getBrightness(LightLayer.SKY, pos) * 16 + level.getBrightness(LightLayer.BLOCK, pos))
				.reduce(1, (hash, light) -> 31 * hash + light);
	}

	/** The highest sky light in the box: 15 in a sealed room is the stale light of issue 427. */
	public static int maxSky(Level level, Box box) {
		return box.cells().mapToInt(pos -> level.getBrightness(LightLayer.SKY, pos)).max().orElse(0);
	}

	/** The highest block light in the box. */
	public static int maxBlock(Level level, Box box) {
		return box.cells().mapToInt(pos -> level.getBrightness(LightLayer.BLOCK, pos)).max().orElse(0);
	}

	/** True when the client reads, in every cell of the box, the sky and block light that the server holds. The client must be in {@code dimension}. */
	public static boolean matches(ClientGameTestContext context, TestServerContext server, ResourceKey<Level> dimension, Box box) {
		return describeMismatch(context, server, dimension, box).isEmpty();
	}

	/**
	 * Empty when the client reads the server's light in the box; otherwise what each side reads, for a failure message. A client that is in
	 * another dimension, or lacks a chunk of the box, does not read the server's light.
	 */
	public static Optional<String> describeMismatch(ClientGameTestContext context, TestServerContext server, ResourceKey<Level> dimension, Box box) {
		Optional<String> absent = context.computeOnClient(client -> {
			if (!client.level.dimension().equals(dimension)) {
				return Optional.of("the client is in " + client.level.dimension().identifier() + ", not " + dimension.identifier());
			}
			if (!chunksPresent(client.level, box)) {
				return Optional.of("the client lacks a chunk of " + box);
			}
			return Optional.<String>empty();
		});
		if (absent.isPresent()) {
			return absent;
		}
		int serverPrint = server.computeOnServer(minecraftServer -> fingerprint(minecraftServer.getLevel(dimension), box));
		int clientPrint = context.computeOnClient(client -> fingerprint(client.level, box));
		if (serverPrint == clientPrint) {
			return Optional.empty();
		}
		int serverSky = server.computeOnServer(minecraftServer -> maxSky(minecraftServer.getLevel(dimension), box));
		int serverBlock = server.computeOnServer(minecraftServer -> maxBlock(minecraftServer.getLevel(dimension), box));
		return Optional.of(context.computeOnClient(client -> "client maxSky=" + maxSky(client.level, box) + " maxBlock=" + maxBlock(client.level, box)
				+ " in " + client.level.dimension().identifier() + ", server maxSky=" + serverSky + " maxBlock=" + serverBlock));
	}

	/** True when the client is in {@code dimension} and holds every chunk the box touches. */
	public static boolean clientHolds(ClientGameTestContext context, ResourceKey<Level> dimension, Box box) {
		return context.computeOnClient(client -> client.level.dimension().equals(dimension) && chunksPresent(client.level, box));
	}

	/** True when the level holds every chunk the box touches. */
	public static boolean chunksPresent(Level level, Box box) {
		return ChunkPos.rangeClosed(ChunkPos.containing(box.min()), ChunkPos.containing(box.max())).allMatch(chunk -> level.getChunkSource().hasChunk(chunk.x(), chunk.z()));
	}

	/** True when no level of the server has light work pending. */
	public static boolean serverLightIdle(ClientGameTestContext context, TestServerContext server) {
		return server.computeOnServer(minecraftServer -> {
			for (ServerLevel level : minecraftServer.getAllLevels()) {
				if (level.getLightEngine().hasLightWork()) {
					return false;
				}
			}
			return true;
		});
	}

	/**
	 * Waits, on the wall clock, until the server's light engine is idle and the client has had {@link #PACKET_TICKS} ticks for the light
	 * packets after it, then returns what a comparison of the box finds. Sends nothing: a mismatch is a light the client will keep.
	 */
	public static Optional<String> settledMismatch(ClientGameTestContext context, TestServerContext server, ResourceKey<Level> dimension, Box box) {
		ClientWait.until(context, "the server's light engine to be idle", () -> serverLightIdle(context, server), () -> "light work pending");
		// tick-wait: the packets that follow the last light job arrive within a few ticks; the comparison after is the check
		context.waitTicks(PACKET_TICKS);
		return describeMismatch(context, server, dimension, box);
	}
}
