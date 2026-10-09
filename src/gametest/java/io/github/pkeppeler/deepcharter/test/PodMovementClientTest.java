package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

/**
 * Client GameTest: with server-authoritative movement the client's pod must keep up with the
 * server's, and the client must see the mock pilot's pod move.
 */
public class PodMovementClientTest implements FabricClientGameTest {
	/** How far the client's pod may trail the server's while driving (SPEC section 7 risk: input lag). */
	static final double MAX_LAG_BLOCKS = 0.5;
	static final int DRIVE_TICKS = 40;
	/** The pad's corner: far enough from the world spawn that no colony or other structure of it reaches. */
	private static final int PAD_X = 6000;
	private static final int PAD_Z = 0;
	/** The pad's margin behind and beside the riders, and ahead of them along the drive, in blocks. */
	private static final int PAD_BEHIND = 4;
	private static final int PAD_SIDE = 16;
	private static final int PAD_AHEAD = 70;
	private static final int PAD_HEADROOM = 10;
	private static final Logger LOGGER = LoggerFactory.getLogger(PodMovementClientTest.class);
	private static final double MOCK_OFFSET_BLOCKS = 8;
	private static final Input MOCK_FORWARD = new Input(true, false, false, false, false, false, false);

	/** Seat the real player in a new pod, and the mock in another pod beside it. Returns the mock pod's entity id. */
	public static int mountBoth(TwoPlayerServer two) {
		return two.server().computeOnServer(server -> {
			ServerPlayer real = realPlayer(server, two.mock().player().getUUID());
			PodEntity realPod = spawnPod(real);
			mount(real, realPod);
			two.mock().teleportTo(real.level(), real.position().add(MOCK_OFFSET_BLOCKS, 0, 0), real.getYRot(), 0f);
			PodEntity mockPod = spawnPod(two.mock().player());
			mount(two.mock().player(), mockPod);
			return mockPod.getId();
		});
	}

	/**
	 * Moves the real player and the mock to a flat pad of generated ground far from the colony, so the drive meets nothing a world's
	 * spawn happens to build. The pods are spawned at the riders, so call this before {@link #mountBoth}. Waits for the pad's chunks
	 * to tick on the server and for the real client to hold the pad.
	 */
	public static void moveToOpenGround(ClientGameTestContext context, TwoPlayerServer two) {
		List<ChunkPos> chunks = new ArrayList<>();
		for (int chunkX = (PAD_X - PAD_BEHIND) >> 4; chunkX <= (PAD_X + PAD_SIDE) >> 4; chunkX++) {
			for (int chunkZ = (PAD_Z - PAD_BEHIND) >> 4; chunkZ <= (PAD_Z + PAD_AHEAD) >> 4; chunkZ++) {
				chunks.add(new ChunkPos(chunkX, chunkZ));
			}
		}
		two.server().runOnServer(server -> chunks.forEach(chunk -> server.overworld().setChunkForced(chunk.x(), chunk.z(), true)));
		ClientWait.until(context, "the pad's chunks entity-ticking far from the colony",
				() -> two.server().computeOnServer(server -> chunks.stream()
						.allMatch(chunk -> server.overworld().isPositionEntityTicking(chunk.getMiddleBlockPosition(64)))),
				() -> "chunks " + chunks);
		BlockPos floor = two.server().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			int groundY = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(PAD_X, 64, PAD_Z)).getY();
			BlockState stone = Blocks.STONE.defaultBlockState();
			BlockState air = Blocks.AIR.defaultBlockState();
			for (int x = PAD_X - PAD_BEHIND; x <= PAD_X + PAD_SIDE; x++) {
				for (int z = PAD_Z - PAD_BEHIND; z <= PAD_Z + PAD_AHEAD; z++) {
					level.setBlock(new BlockPos(x, groundY - 1, z), stone, Block.UPDATE_CLIENTS);
					for (int y = groundY; y < groundY + PAD_HEADROOM; y++) {
						level.setBlock(new BlockPos(x, y, z), air, Block.UPDATE_CLIENTS);
					}
				}
			}
			ServerPlayer real = realPlayer(server, two.mock().player().getUUID());
			real.teleportTo(level, PAD_X + 0.5, groundY, PAD_Z + 0.5, Set.of(), 0f, 0f, true);
			return new BlockPos(PAD_X, groundY - 1, PAD_Z);
		});
		ClientWait.until(context, "the client holding the flat pad", client -> client.level.getBlockState(floor).is(Blocks.STONE)
				&& client.level.getBlockState(floor.above()).isAir() && client.player.distanceToSqr(floor.getX() + 0.5, floor.getY() + 1, floor.getZ() + 0.5) < 4);
	}

	public static void driveMock(TwoPlayerServer two) {
		two.server().runOnServer(server -> two.mock().setInput(MOCK_FORWARD));
	}

	private static ServerPlayer realPlayer(MinecraftServer server, UUID mockId) {
		return server.getPlayerList().getPlayers().stream()
				.filter(player -> !player.getUUID().equals(mockId))
				.findFirst()
				.orElseThrow(() -> new AssertionError("the real player is not on the server"));
	}

	private static PodEntity spawnPod(ServerPlayer rider) {
		PodEntity pod = PodRegistry.POD.create(rider.level(), EntitySpawnReason.COMMAND);
		pod.setPos(rider.position());
		rider.level().addFreshEntity(pod);
		return pod;
	}

	private static void mount(ServerPlayer rider, PodEntity pod) {
		require(rider.startRiding(pod), "the player could not mount the new pod");
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
			moveToOpenGround(context, two);
			int mockPodId = mountBoth(two);
			ClientWait.until(context, "the client riding the mock pod", client -> client.player.getVehicle() instanceof PodEntity && client.level.getEntity(mockPodId) != null);
			context.waitTicks(10);

			int ownPodId = context.computeOnClient(client -> client.player.getVehicle().getId());
			Vec3 mockStart = context.computeOnClient(client -> client.level.getEntity(mockPodId).position());
			Vec3 ownStart = context.computeOnClient(client -> client.player.getVehicle().position());
			context.getInput().holdKey(options -> options.keyUp);
			driveMock(two);

			double lag = 0;
			double worstLag = 0;
			for (int tick = 0; tick < DRIVE_TICKS; tick++) {
				context.waitTick();
				lag = lagBlocks(context, two, ownPodId);
				worstLag = Math.max(worstLag, lag);
			}
			LOGGER.info("PodMovementClientTest: client pod trailed the server pod by {} blocks at tick {}, worst {}",
					lag, DRIVE_TICKS, worstLag);
			context.getInput().releaseKey(options -> options.keyUp);

			Vec3 ownEnd = context.computeOnClient(client -> client.player.getVehicle().position());
			Vec3 mockEnd = context.computeOnClient(client -> client.level.getEntity(mockPodId).position());
			require(!(ownEnd.subtract(ownStart).horizontalDistance() < 2), "Holding W should drive the client's own pod, it moved " + ownStart.distanceTo(ownEnd));
			require(!(mockEnd.subtract(mockStart).horizontalDistance() < 2), "The client should see the mock pilot's pod move, it moved " + mockStart.distanceTo(mockEnd));
			require(!(lag > MAX_LAG_BLOCKS), "After " + DRIVE_TICKS + " ticks the client's pod trailed the server's by " + lag
						+ " blocks, over the " + MAX_LAG_BLOCKS + " bound (worst during the drive: " + worstLag + ")");
		}
	}

	/** Distance between the client's pod and the server's pod now. The server side runs between two client ticks. */
	private static double lagBlocks(ClientGameTestContext context, TwoPlayerServer two, int podId) {
		Vec3 onClient = context.computeOnClient(client -> client.level.getEntity(podId).position());
		Vec3 onServer = two.server().computeOnServer(server -> {
			Entity pod = server.overworld().getEntity(podId);
			require(pod != null, "the server has no pod with id " + podId);
			return pod.position();
		});
		return onClient.distanceTo(onServer);
	}
}
