package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.StructureKind;
import io.github.pkeppeler.deepcharter.layer.StructureSite;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientLight.Box;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;

/**
 * Evidence for #427: a player arrives next to a structure of layer 2 that its chunk draws as it loads (the gallery). The rock it replaces
 * held lava, which lights the rock round it, and a client sent the chunk while that light was pending kept the glow in the hollow. The
 * scenario goes to galleries of successive cells until the client reads light the server does not hold, and takes a still of what the
 * client has as it was sent ({@code gallery-as-sent}) and another after the server sends the light again ({@code gallery-light-resent}).
 * Where the chunk is held until it is lit the two are the same still, in every cell.
 */
public class StaleLightScenario extends EvidenceScenario {
	private static final Logger LOGGER = LoggerFactory.getLogger(StaleLightScenario.class);
	/** The first spacing cell to try, far from the colony's Conduit. */
	private static final int FIRST_CELL = 3;
	private static final int CELLS = 10;
	/**
	 * Optional: the one cell to show. The camera stands at a fixed offset from the gallery of a cell with a fixed yaw, so a recording on
	 * the base commit (which goes through the cells until one is stale and logs it) and one on a branch (with this set to that cell) show
	 * the same view.
	 */
	private static final String CELL_VARIABLE = "DEEPCHARTER_STALE_LIGHT_CELL";
	/** Blocks back along the gallery from its middle, where the camera stands. */
	private static final double BACK = 8;

	@Override
	protected String name() {
		return "stale-light";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		ClientChecks.withHudHidden(context, () -> runHidden(context));
	}

	private void runHidden(ClientGameTestContext context) {
		ResourceKey<Level> layer = LayerChain.dimension(2);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			ClientWait.until(context, "the player in the world", client -> client.player != null && client.level != null);
			TestServerContext server = singleplayer.getServer();
			ClientWait.until(context, "the colony built", () -> server.computeOnServer(s -> Colony.placed(s).isPresent()), () -> "no colony");
			server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst().setGameMode(GameType.SPECTATOR));
			String pinned = System.getenv(CELL_VARIABLE);
			int first = pinned == null ? FIRST_CELL : Integer.parseInt(pinned);
			int count = pinned == null ? CELLS : 1;
			for (int cell = first; cell < first + count; cell++) {
				int at = cell;
				StructureSite site = server.computeOnServer(s -> {
					BlockPos conduit = Colony.anchor(s, ColonyAnchor.CONDUIT).orElseThrow();
					ServerLevel level = s.getLevel(layer);
					return StructureSite.in(s.overworld().getSeed(), StructureKind.GALLERY, level.getMinY(), level.getHeight(), at, at, conduit);
				});
				float yaw = site.alongZ() ? 0f : -90f;
				BlockPos origin = site.origin();
				BlockPos stand = site.alongZ() ? origin.offset(0, 1, (int) -BACK) : origin.offset((int) -BACK, 1, 0);
				server.runOnServer(s -> s.getPlayerList().getPlayers().getFirst()
						.teleportTo(s.getLevel(layer), stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, Set.of(), yaw, 0f, true));
				BoundingBox bounds = site.bounds();
				Box box = new Box(new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ()), new BlockPos(bounds.maxX(), bounds.maxY(), bounds.maxZ()));
				ClientWait.until(context, "the client to hold the gallery of cell " + cell,
						client -> client.level.dimension().equals(layer) && ClientLight.chunksPresent(client.level, box), ClientWait::describe);
				ClientWait.until(context, "the client's chunks to render", client -> client.levelRenderer.hasRenderedAllSections(), ClientWait::describe);
				Optional<String> mismatch = ClientLight.settledMismatch(context, server, layer, box);
				boolean last = cell == first + count - 1;
				if (mismatch.isPresent() || last) {
					context.waitTicks(20);
					screenshot(context, "gallery-as-sent");
					frame(context);
					// What the old workaround did: send the light of the chunks again.
					server.runOnServer(s -> {
						ServerLevel level = s.getLevel(layer);
						ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
						for (int x = box.min().getX() >> 4; x <= box.max().getX() >> 4; x++) {
							for (int z = box.min().getZ() >> 4; z <= box.max().getZ() >> 4; z++) {
								player.connection.send(new ClientboundLightUpdatePacket(new ChunkPos(x, z), level.getLightEngine(), null, null));
							}
						}
					});
					ClientWait.until(context, "the resent light to reach the client", () -> ClientLight.matches(context, server, layer, box), () -> "light differs");
					ClientWait.until(context, "the client's chunks to render again", client -> client.levelRenderer.hasRenderedAllSections(), ClientWait::describe);
					context.waitTicks(20);
					screenshot(context, "gallery-light-resent");
					frame(context);
					LOGGER.info("stale-light: the gallery of cell {} was {}", cell, mismatch.map(m -> "STALE as sent (" + m + ")").orElse("in step as sent"));
					return;
				}
			}
		}
	}
}
