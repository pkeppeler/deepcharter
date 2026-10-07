package io.github.pkeppeler.deepcharter.test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import io.github.pkeppeler.deepcharter.client.layer.BreachEffects;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.scanner.ScannerTuning;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;

/**
 * Client GameTest on a real dedicated server: the harness works (the real client sees itself and the mock player), and
 * the M1 integration scenario (#33), where the real client and the mock player each pilot a Mole down through layer 1 and
 * across the breach into layer 2. The evidence scenario "m1-two-pods" replays the same flow with a recording.
 */
public class TwoPlayerClientTest implements FabricClientGameTest {
	// Layer 1 is crust at y 0-2, then stone; the pods stand a row above the crust, so the stone row is their first bore.
	private static final int X = 4000;
	private static final int Z = 4000;
	private static final int FLOOR_Y = 4;
	private static final int STONE_ROW_Y = FLOOR_Y - 1;
	private static final int ROOM_WEST = 6;
	private static final int ROOM_EAST = 16;
	private static final int ROOM_RADIUS_Z = 6;
	private static final float EAST = -90f;
	private static final float LOOK_DOWN = 30f;

	/**
	 * Far layer chunks generate on worker threads while game ticks run as fast as the CPU allows, so a pod can sit
	 * un-ticked for a long time. Waits on what a pod does are therefore counted in the pod's own {@code tickCount}. A bore
	 * through the stone row and the crust takes under 700 of them; a run that passes ends at once, so the budget is free.
	 */
	private static final int POD_TICK_BUDGET = 3000;
	/** Pod ticks before the pilots start drilling: long enough to settle on the floor and be scanned. */
	private static final int POD_TICKS_TO_SETTLE = 40;
	/**
	 * A fuse on client ticks, for the case that no pod ticks at all, which a pod-tick budget cannot catch. It is the pod-tick
	 * budget plus margin, about 3 minutes at 20 ticks a second, so a hang fails with a message inside CI's 8-minute step.
	 */
	private static final int CLIENT_TICK_FUSE = POD_TICK_BUDGET + 600;
	private static final int POLL_TICKS = 4;
	private static final int TICKS_PER_FRAME = 16;
	private static final int TICKS_PER_FADE_FRAME = 2;
	private static final int ARRIVAL_FRAMES = 8;
	private static final int ARRIVAL_TICKS_PER_FRAME = 8;
	private static final int RGB = 0xFFFFFF;

	private static final Input SPRINT = new Input(false, false, false, false, false, false, true);

	/** What the server holds for the flow: both pods, and the layer 1 columns each one bores. */
	private record Rig(PodEntity realPod, PodEntity mockPod, List<BlockPos> realColumns, List<BlockPos> mockColumns) {
	}

	/** What the server says about the crossing, read in one call so the two pilots are judged at the same moment. */
	private record Crossing(boolean realInLayer2, boolean mockInLayer2, boolean bothRiding, int podTicks) {
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
			int seen = context.computeOnClient(client -> client.level.players().size());
			if (seen != 2) {
				throw new AssertionError("Expected the real client to see 2 players, saw " + seen);
			}
			boolean mockVisible = context.computeOnClient(
					client -> client.level.getPlayerByUUID(two.mock().player().getUUID()) != null);
			if (!mockVisible) {
				throw new AssertionError("The real client does not see the mock player");
			}
			crossTogether(context, two, () -> {
			});
		}
	}

	/**
	 * Seats the real client and the mock player in one Mole each in layer 1, drills both through the stone and the crust,
	 * and asserts that each ends in layer 2 still riding, with the real client having seen the mock's pod, its bore, and
	 * its own breach fade and scanner. Calls {@code frame} at a steady pace, and faster during a fade.
	 */
	public static void crossTogether(ClientGameTestContext context, TwoPlayerServer two, Runnable frame) {
		CameraType previousCamera = context.computeOnClient(client -> client.options.getCameraType());
		try {
			drive(context, two, frame);
		} finally {
			context.getInput().releaseKey(options -> options.keySprint);
			context.runOnClient(client -> client.options.setCameraType(previousCamera));
		}
	}

	private static void drive(ClientGameTestContext context, TwoPlayerServer two, Runnable frame) {
		UUID mockId = two.mock().player().getUUID();
		Rig rig = two.server().computeOnServer(server -> setUp(server, two));
		context.waitFor(client -> client.player != null && client.player.getVehicle() instanceof PodEntity
				&& client.level.dimension().equals(LayerChain.dimension(1))
				&& client.level.getEntity(rig.mockPod().getId()) instanceof PodEntity, CLIENT_TICK_FUSE);
		context.runOnClient(client -> {
			client.options.setCameraType(CameraType.THIRD_PERSON_BACK);
			client.player.setYRot(EAST);
			client.player.setXRot(LOOK_DOWN);
		});
		awaitPodTicks(context, two, rig, POD_TICKS_TO_SETTLE);
		context.waitTicks(2 * ScannerTuning.DEFAULT.rescanTicks() + 2);
		frame.run();
		expectScannerShowsPod(context, "m1-two-pods-layer-1");

		BlockPos mockStone = rig.mockColumns().getFirst().atY(STONE_ROW_Y);
		// A cell in a chunk the client has not loaded reads as air, so wait for the stone before expecting it gone.
		context.waitFor(client -> client.level.getBlockState(mockStone).is(Blocks.STONE), CLIENT_TICK_FUSE);
		float idleFade = context.computeOnClient(client -> BreachEffects.fadeAlpha(0f));
		if (idleFade != 0f) {
			throw new AssertionError("A breach fade is already running before the drive, so it could not be this crossing's: " + idleFade);
		}
		two.server().runOnServer(server -> {
			rig.realPod().setFuel(100f);
			rig.mockPod().setFuel(100f);
			two.mock().setInput(SPRINT);
		});
		context.getInput().holdKey(options -> options.keySprint);

		boolean realReleased = false;
		boolean mockReleased = false;
		boolean fadeStarted = false;
		boolean clientSawMockBore = false;
		int sinceFrame = 0;
		for (int tick = 1; tick <= CLIENT_TICK_FUSE; tick++) {
			context.waitTick();
			float alpha = context.computeOnClient(client -> BreachEffects.fadeAlpha(0f));
			fadeStarted |= alpha > 0;
			if (++sinceFrame >= (alpha > 0 ? TICKS_PER_FADE_FRAME : TICKS_PER_FRAME)) {
				frame.run();
				sinceFrame = 0;
			}
			if (tick % POLL_TICKS != 0) {
				continue;
			}
			Crossing crossing = two.server().computeOnServer(server -> crossing(server, two, rig));
			if (crossing.realInLayer2() && !realReleased) {
				context.getInput().releaseKey(options -> options.keySprint);
				realReleased = true;
			}
			if (crossing.mockInLayer2() && !mockReleased) {
				two.server().runOnServer(server -> two.mock().releaseInput());
				mockReleased = true;
			}
			clientSawMockBore |= context.computeOnClient(client -> client.level.dimension().equals(LayerChain.dimension(1))
					&& client.level.getBlockState(mockStone).isAir());
			if (realReleased && mockReleased) {
				break;
			}
			if (crossing.podTicks() > POD_TICK_BUDGET) {
				throw new AssertionError("After " + crossing.podTicks() + " pod ticks, real crossed: " + crossing.realInLayer2()
						+ ", mock crossed: " + crossing.mockInLayer2());
			}
		}
		context.getInput().releaseKey(options -> options.keySprint);
		two.server().runOnServer(server -> two.mock().releaseInput());
		if (!realReleased || !mockReleased) {
			throw new AssertionError("Not both pods crossed into layer_2 (real: " + realReleased + ", mock: " + mockReleased + ")");
		}

		for (int i = 0; i < ARRIVAL_FRAMES; i++) {
			context.waitTicks(ARRIVAL_TICKS_PER_FRAME);
			fadeStarted |= context.computeOnClient(client -> BreachEffects.fadeAlpha(0f)) > 0;
			frame.run();
		}

		if (!fadeStarted) {
			throw new AssertionError("The client never received the breach effect: the fade did not start");
		}
		if (!clientSawMockBore) {
			throw new AssertionError("While in layer_1 the client never saw the mock pod's bore at " + mockStone);
		}
		expectEndState(context, two, rig, mockId);
		expectScannerShowsPod(context, "m1-two-pods-layer-2");
	}

	/** Builds a room in layer 1, then seats the real player and the mock each in a Mole, a few rows above the crust. */
	private static Rig setUp(MinecraftServer server, TwoPlayerServer two) {
		ServerLevel one = server.getLevel(LayerChain.dimension(1));
		box(one, 0, 2, LayerBlocks.BREACH_CRUST);
		box(one, STONE_ROW_Y, STONE_ROW_Y, Blocks.STONE);
		box(one, FLOOR_Y, FLOOR_Y + 9, Blocks.AIR);
		lamps(one);
		ServerPlayer real = realPlayer(server, two.mock().player());
		real.teleportTo(one, X, FLOOR_Y, Z, Set.of(), EAST, LOOK_DOWN, true);
		int mockPodId = PodMovementClientTest.mountBoth(two);
		PodEntity realPod = (PodEntity) real.getVehicle();
		PodEntity mockPod = (PodEntity) two.mock().player().getVehicle();
		if (mockPod.getId() != mockPodId || realPod == mockPod || realPod.level() != one || mockPod.level() != one) {
			throw new AssertionError("Each player should ride their own pod in layer_1");
		}
		return new Rig(realPod, mockPod, columns(realPod), columns(mockPod));
	}

	/** The layer 1 columns a pod bores: its footprint on the block grid, as the drill picks it. */
	private static List<BlockPos> columns(PodEntity pod) {
		int width = Mth.ceil(pod.chassis().width());
		int lowX = Mth.floor(pod.getX() - width / 2.0 + 0.5);
		int lowZ = Mth.floor(pod.getZ() - width / 2.0 + 0.5);
		if (lowX < X - ROOM_WEST + 1 || lowX + width > X + ROOM_EAST - 1) {
			throw new AssertionError("The pod at " + pod.position() + " stands outside the room");
		}
		return IntStream.range(0, width * width)
				.mapToObj(i -> new BlockPos(lowX + i / width, 0, lowZ + i % width))
				.toList();
	}

	private static Crossing crossing(MinecraftServer server, TwoPlayerServer two, Rig rig) {
		ServerPlayer mock = two.mock().player();
		ServerPlayer real = realPlayer(server, mock);
		boolean riding = real.getVehicle() instanceof PodEntity && mock.getVehicle() instanceof PodEntity
				&& real.getVehicle() != mock.getVehicle();
		return new Crossing(inLayer2(real), inLayer2(mock), riding, Math.max(rig.realPod().tickCount, rig.mockPod().tickCount));
	}

	private static boolean inLayer2(ServerPlayer player) {
		return player.level().dimension().equals(LayerChain.dimension(2));
	}

	private static ServerPlayer realPlayer(MinecraftServer server, ServerPlayer mock) {
		return server.getPlayerList().getPlayers().stream()
				.filter(player -> player != mock)
				.findFirst()
				.orElseThrow(() -> new AssertionError("the real client's player is not on the server"));
	}

	/** Waits until both of the original pods have ticked {@code ticks} times. */
	private static void awaitPodTicks(ClientGameTestContext context, TwoPlayerServer two, Rig rig, int ticks) {
		for (int waited = 0; waited < CLIENT_TICK_FUSE; waited += POLL_TICKS) {
			int least = two.server().computeOnServer(server -> Math.min(rig.realPod().tickCount, rig.mockPod().tickCount));
			if (least >= ticks) {
				return;
			}
			context.waitTicks(POLL_TICKS);
		}
		throw new AssertionError("The pods never reached " + ticks + " ticks");
	}

	/** Server and client agree: both pilots ride a pod in layer_2, the crust under both pods is gone, and the client has the mock's pod. */
	private static void expectEndState(ClientGameTestContext context, TwoPlayerServer two, Rig rig, UUID mockId) {
		Crossing end = two.server().computeOnServer(server -> crossing(server, two, rig));
		if (!end.realInLayer2() || !end.mockInLayer2() || !end.bothRiding()) {
			throw new AssertionError("Each pod should end in layer_2 with its pilot riding: " + end);
		}
		two.server().runOnServer(server -> {
			ServerLevel one = server.getLevel(LayerChain.dimension(1));
			for (BlockPos column : Stream.concat(rig.realColumns().stream(), rig.mockColumns().stream()).toList()) {
				for (int y = 0; y <= 2; y++) {
					if (one.getBlockState(column.atY(y)).is(LayerBlocks.BREACH_CRUST)) {
						throw new AssertionError("The crust at " + column.atY(y) + " was not bored");
					}
				}
			}
		});
		context.waitFor(client -> client.level.dimension().equals(LayerChain.dimension(2))
				&& client.player.getVehicle() instanceof PodEntity
				&& client.level.getPlayerByUUID(mockId) != null
				&& client.level.getPlayerByUUID(mockId).getVehicle() instanceof PodEntity, CLIENT_TICK_FUSE);
		boolean containsMockPod = context.computeOnClient(client -> {
			Entity pod = client.level.getPlayerByUUID(mockId).getVehicle();
			return client.level.getEntity(pod.getId()) == pod;
		});
		if (!containsMockPod) {
			throw new AssertionError("The real client's level does not contain the mock's pod");
		}
	}

	/** The scanner is drawn while riding: its pod marker is in the middle of the pod's cells, whatever else is on screen. */
	private static void expectScannerShowsPod(ClientGameTestContext context, String screenshotName) {
		ScannerHudTest.HudShot shot = ScannerHudTest.HudShot.take(context, screenshotName);
		int actual = shot.pixel(0, 0);
		int expected = ScannerTuning.DEFAULT.podColor() & RGB;
		if (actual != expected) {
			throw new AssertionError("%s: the scanner HUD should show the pod's marker %06X, the pixel is %06X"
					.formatted(screenshotName, expected, actual));
		}
	}

	private static void box(ServerLevel level, int yFrom, int yTo, Block block) {
		for (int x = X - ROOM_WEST; x <= X + ROOM_EAST; x++) {
			for (int y = yFrom; y <= yTo; y++) {
				for (int z = Z - ROOM_RADIUS_Z; z <= Z + ROOM_RADIUS_Z; z++) {
					level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 3);
				}
			}
		}
	}

	/** Lamps along both walls, for the recording. */
	private static void lamps(ServerLevel level) {
		for (int x = X - 3; x <= X + 11; x += 4) {
			level.setBlock(new BlockPos(x, FLOOR_Y + 3, Z + 4), Blocks.GLOWSTONE.defaultBlockState(), 3);
			level.setBlock(new BlockPos(x, FLOOR_Y + 3, Z - 4), Blocks.GLOWSTONE.defaultBlockState(), 3);
		}
	}
}
