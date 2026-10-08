package io.github.pkeppeler.deepcharter.test;

import java.awt.image.BufferedImage;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.scanner.ScannerTuning;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;

/**
 * Client GameTest on a real dedicated server: a mock player pilots a Prospector and the real client rides it as the navigator. Both
 * show on the client, the navigator's screen holds the scanner (its marker fills all three cells of the Prospector) and not the pod
 * status lines, and when the pilot gets off the client takes the controls and the status lines come back.
 */
public class ProspectorChassisClientTest implements FabricClientGameTest {
	private static final int X = 1200;
	private static final int Z = 1200;
	private static final int FLOOR_Y = 200;
	private static final int ROOM_RADIUS = 6;
	private static final int ROOM_HEIGHT = 8;
	private static final int POD_CELLS = 3;
	private static final int RGB = 0xFFFFFF;
	/** The status lines are white text in the top left corner of the GUI; this is the box (in GUI pixels) that holds them. */
	private static final int STATUS_WIDTH = 90;
	private static final int STATUS_HEIGHT = 50;

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
			UUID mockId = two.mock().player().getUUID();
			two.server().runOnServer(server -> buildRoom(server.overworld(), two));
			// The client must have the room before the players board, or the boarding reaches it before the pod does.
			context.waitFor(client -> client.level.getBlockState(new BlockPos(X, FLOOR_Y - 1, Z)).is(Blocks.STONE)
					&& client.level.getBlockState(new BlockPos(X + 1, FLOOR_Y, Z)).isAir());
			int podId = two.server().computeOnServer(server -> board(server.overworld(), two));
			context.waitFor(client -> client.player != null && client.player.getVehicle() instanceof PodEntity pod
					&& pod.getId() == podId && pod.getPassengers().size() == 2);
			context.runOnClient(client -> client.gui.toastManager().clear());
			context.waitTicks(2 * ScannerTuning.DEFAULT.rescanTicks() + 2);

			boolean ridingTogether = context.computeOnClient(client -> {
				PodEntity pod = (PodEntity) client.player.getVehicle();
				return pod.chassis() == Chassis.PROSPECTOR && pod.getPassengers().size() == 2
						&& pod.getControllingPassenger() == client.level.getPlayerByUUID(mockId) && pod.getPassengers().get(1) == client.player;
			});
			if (!ridingTogether) {
				throw new AssertionError("The client should see itself as the navigator of a two-seat Prospector piloted by the mock player");
			}

			ScannerHudTest.HudShot navigator = ScannerHudTest.HudShot.take(context, "prospector-navigator");
			for (int up = 0; up < POD_CELLS; up++) {
				expectPixel(navigator, "the navigator's scanner marks cell " + up + " of the Prospector", up, ScannerTuning.DEFAULT.podColor());
			}
			expectPixel(navigator, "the cell above the Prospector is air", POD_CELLS, ScannerTuning.DEFAULT.airColor());
			int navigatorText = whitePixels(navigator);
			if (navigatorText != 0) {
				throw new AssertionError("The navigator should see the scanner only, but " + navigatorText + " pixels of status text are on screen");
			}

			two.server().runOnServer(server -> two.mock().player().stopRiding());
			context.waitFor(client -> client.player.getVehicle() instanceof PodEntity pod && pod.getPassengers().size() == 1);
			context.waitTicks(2);
			ScannerHudTest.HudShot pilot = ScannerHudTest.HudShot.take(context, "prospector-pilot");
			if (whitePixels(pilot) == 0) {
				throw new AssertionError("With the pilot gone the client takes the controls and should see the pod status lines");
			}
		}
	}

	/** Builds a closed stone room and puts both players in it. */
	private static void buildRoom(ServerLevel level, TwoPlayerServer two) {
		fill(level, FLOOR_Y - 1, FLOOR_Y + ROOM_HEIGHT + 1, Blocks.STONE);
		fill(level, FLOOR_Y, FLOOR_Y + ROOM_HEIGHT, Blocks.AIR, ROOM_RADIUS - 1);
		level.setBlock(new BlockPos(X, FLOOR_Y + ROOM_HEIGHT, Z), Blocks.GLOWSTONE.defaultBlockState(), 3);
		ServerPlayer real = level.getServer().getPlayerList().getPlayers().stream()
				.filter(player -> player != two.mock().player()).findFirst().orElseThrow();
		real.teleportTo(level, X + 0.5, FLOOR_Y, Z + 3.5, Set.of(), 180f, 0f, true);
		two.mock().teleportTo(level, new Vec3(X + 0.5, FLOOR_Y, Z + 0.5), 0f, 0f);
	}

	/** Seats the mock first and the real player second in a new Prospector. Returns the pod's id. */
	private static int board(ServerLevel level, TwoPlayerServer two) {
		ServerPlayer real = level.getServer().getPlayerList().getPlayers().stream()
				.filter(player -> player != two.mock().player()).findFirst().orElseThrow();
		PodEntity pod = PodRegistry.PROSPECTOR.create(level, EntitySpawnReason.COMMAND);
		pod.setPos(X + 0.5, FLOOR_Y, Z + 0.5);
		pod.setFuel(100f);
		level.addFreshEntity(pod);
		if (!two.mock().player().startRiding(pod) || !real.startRiding(pod)) {
			throw new AssertionError("Both players should board the Prospector");
		}
		return pod.getId();
	}

	private static void fill(ServerLevel level, int yFrom, int yTo, Block block) {
		fill(level, yFrom, yTo, block, ROOM_RADIUS);
	}

	private static void fill(ServerLevel level, int yFrom, int yTo, Block block, int radius) {
		for (int x = X - radius; x <= X + radius; x++) {
			for (int y = yFrom; y <= yTo; y++) {
				for (int z = Z - radius; z <= Z + radius; z++) {
					level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 3);
				}
			}
		}
	}

	private static void expectPixel(ScannerHudTest.HudShot shot, String what, int up, int colour) {
		int actual = shot.pixel(0, up);
		if (actual != (colour & RGB)) {
			throw new AssertionError("%s: the pixel should be %06X, it is %06X".formatted(what, colour & RGB, actual));
		}
	}

	/** The pure white pixels in the top left corner, where the pod status text is drawn. */
	private static int whitePixels(ScannerHudTest.HudShot shot) {
		BufferedImage image = shot.image();
		int white = 0;
		for (int x = 0; x < STATUS_WIDTH * shot.scale(); x++) {
			for (int y = 0; y < STATUS_HEIGHT * shot.scale(); y++) {
				if ((image.getRGB(x, y) & RGB) == RGB) {
					white++;
				}
			}
		}
		return white;
	}
}
