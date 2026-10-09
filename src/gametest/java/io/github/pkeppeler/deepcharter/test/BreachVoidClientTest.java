package io.github.pkeppeler.deepcharter.test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.imageio.ImageIO;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.layer.BreachService;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;

/**
 * Client GameTest for #333: looking through a hole at the bottom of a layer shows rock and darkness, never the void behind it. The
 * void is drawn in the fog colour (and a night-vision potion lifts that colour to full brightness), and on the surface in the
 * sky's. Each of the surface, layer 1 and layer 2 gets a room with a 3 x 3 hole through its floor (the crust broken in a layer, a bored shaft
 * on the surface), seen straight down and from the side of the room, high up, with and without night vision. The pixels of the hole must be dark.
 */
public class BreachVoidClientTest implements FabricClientGameTest {
	private static final int X = 2000;
	private static final int Z = 2000;
	private static final double EYE = 1.62;
	/** The hole is a 3 x 3 shaft three blocks deep under a room whose floor is that many blocks above the bottom of the world. */
	private static final int SHAFT_DEPTH = 3;
	private static final int ROOM_HEIGHT = 6;
	/** Each channel of a pixel of the hole is at or under this: darkness, not a fog or sky colour. */
	private static final int DARK = 0x10;
	/** The patch of pixels read at the hole, either side of its middle. */
	private static final int PATCH = 6;
	/** Pixels of the patch that a speck of dust may light. */
	private static final int SPECKS = 6;

	private record View(String name, Vec3 eye) {
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		List<String> failures = new ArrayList<>();
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			ClientWait.until(context, "the client in the world", client -> client.player != null && client.level != null);
			// F1: no crosshair over the middle of the picture, where the hole is.
			context.getInput().pressKey(options -> options.keyToggleGui);
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.setGameMode(GameType.CREATIVE);
				player.setNoGravity(true);
				player.getAbilities().flying = true;
				player.onUpdateAbilities();
			});
			for (int layer : new int[] {LayerChain.SURFACE, 1, 2}) {
				int minY = singleplayer.getServer().computeOnServer(server -> openHole(server.getLevel(LayerChain.dimension(layer))));
				Vec3 hole = new Vec3(X + 0.5, minY + 0.5, Z + 0.5);
				// From the side of the room and high up, the line of sight to the hole's middle passes the rim and goes down the shaft.
				View[] views = {
						new View("down", new Vec3(X + 0.5, minY + SHAFT_DEPTH + 3.5, Z + 0.5)),
						new View("aslant", new Vec3(X - 2.5, minY + SHAFT_DEPTH + 5.5, Z + 0.5))};
				for (boolean nightVision : new boolean[] {false, true}) {
					singleplayer.getServer().runOnServer(server -> {
						ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
						player.removeAllEffects();
						if (nightVision) {
							player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false));
						}
					});
					for (View view : views) {
						String name = "breach-void-layer-" + layer + "-" + view.name() + (nightVision ? "-night-vision" : "");
						Path shot = look(context, singleplayer, layer, view.eye(), hole, name);
						voidShowing(shot, name).ifPresent(failures::add);
					}
				}
			}
		}
		// Every view is taken first, so one run names all the views that show the void.
		if (!failures.isEmpty()) {
			throw new AssertionError(String.join("\n", failures));
		}
	}

	/** Carves a lit room over the bottom of {@code level} with a 3 x 3 hole through its floor to the void. Returns the bottom Y. */
	private static int openHole(ServerLevel level) {
		int minY = level.getMinY();
		RoomCarver.carve(level, new BlockPos(X - 3, minY + SHAFT_DEPTH, Z - 3), new BlockPos(X + 3, minY + SHAFT_DEPTH + ROOM_HEIGHT, Z + 3),
				Blocks.AIR.defaultBlockState());
		if (level.dimension().equals(LayerChain.dimension(LayerChain.SURFACE))) {
			// The surface has no crust: its floor is open, and the hole is a shaft bored to the bottom.
			RoomCarver.carve(level, new BlockPos(X - 1, minY, Z - 1), new BlockPos(X + 1, minY + SHAFT_DEPTH - 1, Z + 1), Blocks.AIR.defaultBlockState());
		} else {
			for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(X - 1, minY, Z - 1), new BlockPos(X + 1, minY + SHAFT_DEPTH - 1, Z + 1))) {
				if (!BreachService.breakCrust(level, pos)) {
					throw new AssertionError("The floor of " + level.dimension().identifier() + " at " + pos + " holds no breach crust");
				}
			}
		}
		return minY;
	}

	/** Puts the camera at {@code eye} looking at {@code target}, waits until it has arrived and the view has rendered, and takes the screenshot. */
	private static Path look(ClientGameTestContext context, TestSingleplayerContext singleplayer, int layer, Vec3 eye, Vec3 target, String name) {
		Vec3 d = target.subtract(eye);
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
		singleplayer.getServer().runOnServer(server -> server.getPlayerList().getPlayers().getFirst()
				.teleportTo(server.getLevel(LayerChain.dimension(layer)), eye.x, eye.y - EYE, eye.z, Set.of(), yaw, pitch, true));
		ClientWait.until(context, "the camera at " + name, client -> client.level.dimension().equals(LayerChain.dimension(layer))
				&& client.player.distanceToSqr(eye.x, eye.y - EYE, eye.z) < 0.0001
				&& Math.abs(Mth.wrapDegrees(client.player.getYRot() - yaw)) < 0.01f
				&& Math.abs(client.player.getXRot() - pitch) < 0.01f);
		ClientWait.until(context, "the view " + name + " rendered and lit",
				() -> context.computeOnClient(client -> client.gui.screen() == null && client.levelRenderer.hasRenderedAllSections())
						&& singleplayer.getServer().computeOnServer(server -> !server.getLevel(LayerChain.dimension(layer)).getLightEngine().hasLightWork()),
				() -> "chunks still rendering or light still settling");
		// tick-wait: the fog colour is computed per frame, and a few frames must pass after the last chunk section is built
		context.waitTicks(10);
		return context.takeScreenshot(name);
	}

	/**
	 * The camera looks at the middle of the hole, so the patch of pixels at the middle of the screen holds only darkness: at most
	 * {@link #SPECKS} pixels brighter than {@link #DARK} in any channel (the surface's drifting dust crosses it now and then).
	 */
	private static Optional<String> voidShowing(Path shot, String name) {
		BufferedImage image = read(shot);
		int bright = 0;
		int brightest = 0;
		for (int y = image.getHeight() / 2 - PATCH; y <= image.getHeight() / 2 + PATCH; y++) {
			for (int x = image.getWidth() / 2 - PATCH; x <= image.getWidth() / 2 + PATCH; x++) {
				int rgb = image.getRGB(x, y);
				int channel = Math.max((rgb >> 16) & 0xFF, Math.max((rgb >> 8) & 0xFF, rgb & 0xFF));
				if (channel > DARK) {
					bright++;
				}
				brightest = Math.max(brightest, channel);
			}
		}
		if (bright > SPECKS) {
			return Optional.of("%s: the hole shows the void, not darkness: %d pixels of its middle are brighter than %d in a channel, up to %d"
					.formatted(name, bright, DARK, brightest));
		}
		return Optional.empty();
	}

	private static BufferedImage read(Path shot) {
		try {
			return ImageIO.read(shot.toFile());
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
