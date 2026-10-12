package io.github.pkeppeler.deepcharter.test;

import java.util.Arrays;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.Options;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.pkeppeler.deepcharter.client.theme.ScannerLook;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.ScannerHudTest.HudShot;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.scanner.ScanArea;
import io.github.pkeppeler.deepcharter.scanner.ScannerTuning;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

/**
 * #537: a measurement more than a regression test. Frame time at the largest scan the game allows (a tier 3 scanner on the Prospector: the Mole's cap is 2) against a pod with no
 * scanner, in a world whose scan area is full of mixed rock and ore, so that every cell the scanner can read is drawn. It logs
 * {@code [scanner-frame]} lines and asserts nothing: wall time under load is not a stable pass line.
 */
public class ScannerFrameTimeClientTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("ScannerFrameTime");
	private static final ScannerTuning TUNING = ScannerTuning.DEFAULT;
	/** Blocks each side of the pod, at its height and above, left as they are so that the rock does not smother it. */
	private static final int POD_CLEARANCE = 3;
	private static final int WARMUP_TICKS = 60;
	private static final int MEASURED_FRAMES = 200;
	private static final int ROUNDS = 9;
	private static final Block[] MIX = {Blocks.STONE, Blocks.STONE, Blocks.DEEPSLATE, Blocks.IRON_ORE, Blocks.COAL_ORE, Blocks.GOLD_ORE,
			Blocks.DIAMOND_ORE, Blocks.COBBLESTONE, Blocks.STONE, Blocks.DEEPSLATE};

	/**
	 * Median wall milliseconds per frame, the median of {@link #ROUNDS} rounds, of the CPU side of a frame: the extraction of the whole
	 * render state, the HUD with its scanner panel in it, run back to back on the render thread. A client tick waits for the 20 ticks a
	 * second and would time that wait instead, and {@code renderFrame} cannot be driven from a test (it returns at once without a
	 * surface to draw to), so it times nothing. The GUI state is reset after each frame, as the renderer does after drawing one.
	 */
	private static double millisPerFrame(ClientGameTestContext context) {
		context.waitTicks(WARMUP_TICKS);
		double[] rounds = new double[ROUNDS];
		for (int i = 0; i < ROUNDS; i++) {
			rounds[i] = context.computeOnClient(client -> {
				long start = System.nanoTime();
				for (int frame = 0; frame < MEASURED_FRAMES; frame++) {
					client.gameRenderer.extract(client.getDeltaTracker(), true);
					client.gameRenderer.gameRenderState().guiRenderState.reset();
				}
				return (System.nanoTime() - start) / 1e6 / MEASURED_FRAMES;
			});
		}
		Arrays.sort(rounds);
		LOGGER.info("[scanner-frame] rounds ms/frame: {}", Arrays.toString(rounds));
		return rounds[ROUNDS / 2];
	}

	private static double hiddenMillis(ClientGameTestContext context) {
		double[] result = new double[1];
		ClientChecks.withHudHidden(context, () -> result[0] = millisPerFrame(context));
		return result[0];
	}

	/** Mounts the first player on a new pod of {@code chassis} with a scanner of {@code scannerTier}, or with none for 0. */
	private static void mount(TestServerContext server, Chassis chassis, int scannerTier) {
		server.runOnServer(minecraftServer -> {
			ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
			PodEntity pod = (chassis == Chassis.PROSPECTOR ? PodRegistry.PROSPECTOR : PodRegistry.POD).create(player.level(), EntitySpawnReason.COMMAND);
			pod.setPos(player.position());
			player.level().addFreshEntity(pod);
			if (scannerTier > 0) {
				ScannerPods.fit(minecraftServer, player, pod, scannerTier);
			}
			if (!player.startRiding(pod)) {
				throw new AssertionError("the player could not mount the new pod");
			}
		});
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.startUntimed(this);
		// The largest scan the game allows: the best chassis' tier cap, which is below the scanner track's own top tier.
		Chassis chassis = Chassis.PROSPECTOR;
		int tier = chassis.tierCap();
		ScanArea area = TUNING.area(tier);
		boolean vsync = context.computeOnClient(client -> client.options.enableVsync().get());
		int frameLimit = context.computeOnClient(client -> client.options.framerateLimit().get());
		// Unpaced, or the frames time the display's refresh and the scanner cannot show in them.
		context.runOnClient(client -> {
			client.options.enableVsync().set(false);
			client.options.framerateLimit().set(Options.UNLIMITED_FRAMERATE_CUTOFF);
		});
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			TestServerContext server = singleplayer.getServer();
			server.runCommand("time set midnight");
			context.waitTicks(20);

			mount(server, chassis, 0);
			ClientWait.until(context, "the client riding a pod", client -> client.player != null && client.player.getVehicle() instanceof PodEntity);
			int filled = server.computeOnServer(minecraftServer -> {
				PodEntity pod = (PodEntity) minecraftServer.getPlayerList().getPlayers().getFirst().getVehicle();
				ServerLevel level = (ServerLevel) pod.level();
				Direction facing = pod.getDirection();
				int count = 0;
				for (int up = area.up(); up >= -area.down(); up--) {
					for (int ahead = -area.halfWidth(); ahead <= area.halfWidth(); ahead++) {
						BlockPos at = pod.blockPosition().relative(facing, ahead).above(up);
						if (level.hasChunkAt(at) && level.isInsideBuildHeight(at.getY()) && (up < 0 || Math.abs(ahead) > POD_CLEARANCE)) {
							level.setBlock(at, MIX[Math.floorMod(ahead * 7 + up * 13, MIX.length)].defaultBlockState(), 2);
							count++;
						}
					}
				}
				return count;
			});
			LOGGER.info("[scanner-frame] filled {} of {} cells of the tier {} area ({} x {})", filled, area.columns() * area.rows(), tier, area.columns(), area.rows());
			context.waitTicks(100);
			double without = millisPerFrame(context);

			ScannerHudTest.leavePod(context, server);
			mount(server, chassis, tier);
			ClientWait.until(context, "the client riding a pod", client -> client.player != null && client.player.getVehicle() instanceof PodEntity);
			context.waitTicks(2 * TUNING.rescanTicks() + 2);
			double with = millisPerFrame(context);
			// The scanner arm must have drawn its panel: its pod marker is in the screenshot.
			HudShot shot = HudShot.take(context, "scanner-frame-time", area);
			require(shot.pixel(0, 0) == (ScannerLook.current().podColor() & 0xFFFFFF), "the tier %d panel should be drawn in the scanner arm".formatted(tier));
			// A control: the same scene with the whole HUD hidden. It shows that the frames timed here include the HUD, and so the scanner.
			double hudHidden = hiddenMillis(context);
			LOGGER.info("[scanner-frame] control, scanner pod with the HUD hidden: {} ms/frame", hudHidden);
			LOGGER.info("[scanner-frame] no scanner {} ms/frame, tier {} scanner {} ms/frame, scanner costs {} ms/frame", without, tier, with, with - without);
		} finally {
			context.runOnClient(client -> {
				client.options.enableVsync().set(vsync);
				client.options.framerateLimit().set(frameLimit);
			});
		}
	}
}
