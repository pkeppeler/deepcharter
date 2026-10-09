package io.github.pkeppeler.deepcharter.test;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;

import io.github.pkeppeler.deepcharter.client.pod.PodConcept;
import io.github.pkeppeler.deepcharter.client.pod.PodGeoRenderer;
import io.github.pkeppeler.deepcharter.client.pod.PodRenderer;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

/**
 * Client GameTest for #334: without the dev switch the Mole keeps its shipping renderer; with it, each concept bakes from its
 * geometry on a resource reload and draws in view of the player; clearing the switch brings the shipping look back.
 */
public class PodConceptClientTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		require(System.getProperty(PodConcept.PROPERTY) == null, "the dev switch " + PodConcept.PROPERTY + " is already set");
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			int[] moleId = {0};
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				// Face +z, where the Mole stands.
				player.teleportTo(player.level(), player.getX(), player.getY(), player.getZ(), Set.of(), 0f, 10f, true);
				PodEntity mole = PodRegistry.POD.create(player.level(), EntitySpawnReason.COMMAND);
				mole.setPos(player.position().add(0, 0, 4));
				player.level().addFreshEntity(mole);
				moleId[0] = mole.getId();
			});
			ClientWait.until(context, "the Mole on the client", client -> client.level != null && client.level.getEntity(moleId[0]) instanceof PodEntity);
			require(context.computeOnClient(client -> renderer(client, moleId[0]) instanceof PodRenderer),
					"without the dev switch the Mole should keep its shipping renderer");
			try {
				for (PodConcept concept : PodConcept.values()) {
					System.setProperty(PodConcept.PROPERTY, concept.id());
					context.runOnClient(Minecraft::reloadResourcePacks);
					ClientWait.until(context, "the Mole drawn as " + concept.id(),
							client -> renderer(client, moleId[0]) instanceof PodGeoRenderer geo && geo.concept() == concept,
							client -> String.valueOf(renderer(client, moleId[0])));
					// One rendered frame with the concept in view: setupAnim and the glowmask pass run.
					context.waitTick(); // tick-wait: one frame draws the posed model; nothing else is awaited
				}
			} finally {
				System.clearProperty(PodConcept.PROPERTY);
				context.runOnClient(Minecraft::reloadResourcePacks);
				ClientWait.until(context, "the Mole back on its shipping renderer", client -> renderer(client, moleId[0]) instanceof PodRenderer,
						client -> String.valueOf(renderer(client, moleId[0])));
			}
		}
	}

	private static EntityRenderer<?, ?> renderer(Minecraft client, int id) {
		return client.getEntityRenderDispatcher().getRenderer(client.level.getEntity(id));
	}
}
