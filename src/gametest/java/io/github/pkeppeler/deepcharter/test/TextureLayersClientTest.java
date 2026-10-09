package io.github.pkeppeler.deepcharter.test;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Set;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import io.github.pkeppeler.deepcharter.charter.terminal.ContractTerminal;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.texture.TextureProperties;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

/**
 * Client GameTest for #242: in a sealed stone room with no light, an online terminal's glow layer renders at full light (its
 * {@code light_emission} element), and an offline one shows only its red standby light. The test reads the screenshots: the glow is
 * a pixel colour the dark room cannot make on its own.
 */
public class TextureLayersClientTest implements FabricClientGameTest {
	private static final Logger LOGGER = LoggerFactory.getLogger("TextureLayersClientTest");
	private static final int RADIUS = 3;
	private static final int HEIGHT = 4;
	/** How far in front of the camera the terminal stands, in blocks. */
	private static final int DISTANCE = 3;
	/** The half-size of the box round the middle of the screen that holds the terminal's face, as a share of the screen height. */
	private static final double FACE_HALF = 1 / 6.0;
	/** A pixel of glow at full light is brighter than this in its own channel (green for the CRT, red for the standby light). */
	private static final int GLOW = 120;
	/** How many lit green pixels the CRT glyph makes at the default window size, at the least. */
	private static final int LIT_PIXELS = 400;
	/** How many lit red pixels the standby light makes, at the least. */
	private static final int STANDBY_PIXELS = 12;
	/** The room's own darkness: no pixel outside the terminal is brighter than this. */
	private static final int DARK = 60;

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			ClientWait.until(context, "the player in the world", client -> client.player != null && client.level != null);
			BlockPos floor = singleplayer.getServer().computeOnServer(server -> player(server).blockPosition().above(30));
			BlockPos terminal = floor.offset(0, 2, DISTANCE);
			singleplayer.getServer().runOnServer(server -> build(player(server).level(), floor));
			context.runOnClient(client -> {
				client.options.setCameraType(CameraType.FIRST_PERSON);
				if (!client.gui.hud.isHidden()) {
					client.gui.hud.toggle();
				}
			});

			place(context, singleplayer, terminal, ContractTerminal.TYPE.block().defaultBlockState());
			Reading online = Reading.of(context.takeScreenshot("texture-layers-online-terminal"));
			LOGGER.info("online terminal in the dark: {}", online);
			require(online.green() >= LIT_PIXELS, "An online terminal's CRT glow should show bright green in the dark: " + online);
			require(online.brightestOutside() <= DARK, "The room should be dark round the terminal: " + online);

			place(context, singleplayer, terminal, TerminalTypes.UPGRADE_TERMINAL.block().defaultBlockState());
			Reading offline = Reading.of(context.takeScreenshot("texture-layers-offline-terminal"));
			LOGGER.info("offline terminal in the dark: {}", offline);
			require(offline.green() < LIT_PIXELS / 10, "An offline terminal should show no CRT glow: " + offline);
			require(offline.red() >= STANDBY_PIXELS, "An offline terminal should show its red standby light: " + offline);
		}
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().getFirst();
	}

	/** A sealed stone box, its floor 30 blocks over the player, with the camera at its middle looking south at eye level. */
	private static void build(ServerLevel level, BlockPos floor) {
		for (int dx = -RADIUS - 1; dx <= RADIUS + 1; dx++) {
			for (int dz = -RADIUS - 1; dz <= RADIUS + 1; dz++) {
				for (int dy = 0; dy <= HEIGHT + 1; dy++) {
					boolean shell = dy == 0 || dy == HEIGHT + 1 || Math.abs(dx) == RADIUS + 1 || Math.abs(dz) == RADIUS + 1;
					level.setBlock(floor.offset(dx, dy, dz), (shell ? Blocks.STONE : Blocks.AIR).defaultBlockState(), Block.UPDATE_ALL);
				}
			}
		}
		ServerPlayer player = player(level.getServer());
		player.getAbilities().flying = true;
		player.onUpdateAbilities();
		// The eye is level with the middle of the terminal two blocks up: yaw 0 faces south (+z).
		player.teleportTo(level, floor.getX() + 0.5, floor.getY() + 2.5 - player.getEyeHeight(), floor.getZ() + 0.5, Set.of(), 0f, 0f, true);
	}

	/** Puts {@code state} at {@code pos}, facing the camera, and waits until the client has it, its light has settled and the camera is still. */
	private static void place(ClientGameTestContext context, TestSingleplayerContext singleplayer, BlockPos pos, BlockState state) {
		BlockState facing = state.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH);
		singleplayer.getServer().runOnServer(server -> player(server).level().setBlock(pos, facing, Block.UPDATE_ALL));
		boolean active = singleplayer.getServer().computeOnServer(server -> player(server).level().getBlockState(pos).getValue(TextureProperties.ACTIVE));
		ClientWait.until(context, "the client to have " + facing.getBlock() + " (active=" + active + ") at " + pos.toShortString(), client -> {
			BlockState shown = client.level.getBlockState(pos);
			return shown.is(facing.getBlock()) && shown.getValue(TextureProperties.ACTIVE) == active
					&& Math.abs(Mth.wrapDegrees(client.player.getYRot())) < 0.01f && Math.abs(client.player.getXRot()) < 0.01f;
		});
		ClientWait.until(context, "the room's light to settle",
				() -> !singleplayer.getServer().computeOnServer(server -> player(server).level().getLightEngine().hasLightWork()), () -> "light work pending");
		// tick-wait: the chunk mesh of the new block is rebuilt a few frames after the client has the state; nothing reports it.
		context.waitTicks(20);
	}

	/**
	 * What a screenshot shows: in the box round the screen's middle (the terminal's face), how many pixels are lit green and how
	 * many lit red, as only a glow layer can be in the dark; outside the box, the brightest channel of any pixel.
	 */
	private record Reading(int green, int red, int brightestOutside) {
		static Reading of(Path screenshot) {
			BufferedImage image;
			try {
				image = ImageIO.read(screenshot.toFile());
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			int half = (int) (FACE_HALF * image.getHeight());
			int cx = image.getWidth() / 2;
			int cy = image.getHeight() / 2;
			int green = 0;
			int red = 0;
			int brightestOutside = 0;
			for (int y = 0; y < image.getHeight(); y++) {
				for (int x = 0; x < image.getWidth(); x++) {
					int rgb = image.getRGB(x, y);
					int r = (rgb >> 16) & 255;
					int g = (rgb >> 8) & 255;
					int b = rgb & 255;
					if (Math.abs(x - cx) <= half && Math.abs(y - cy) <= half) {
						green += g > GLOW && g > r + 40 && g > b + 20 ? 1 : 0;
						red += r > GLOW && r > g + 60 ? 1 : 0;
					} else {
						brightestOutside = Math.max(brightestOutside, Math.max(r, Math.max(g, b)));
					}
				}
			}
			return new Reading(green, red, brightestOutside);
		}
	}
}
