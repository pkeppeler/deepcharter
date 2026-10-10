package io.github.pkeppeler.deepcharter.test;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.geckolib.cache.GeckoLibResources;
import com.geckolib.cache.model.BakedGeoModel;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;

import io.github.pkeppeler.deepcharter.client.creature.FigureConcept;
import io.github.pkeppeler.deepcharter.client.creature.FigureConceptRenderer;
import io.github.pkeppeler.deepcharter.client.creature.LamplessFigureRenderer;
import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

/**
 * Client GameTest for #250: the dev switch is off by default and the figure keeps its placeholder renderer; a name that is no concept
 * fails and names the four; with the switch, each concept is complete (its model, texture and both animations are there), GeckoLib
 * bakes every bone and both animations of it, and it draws in view; clearing the switch brings the placeholder back.
 */
public class FigureConceptClientTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		require(System.getProperty(FigureConcept.PROPERTY) == null, "the dev switch " + FigureConcept.PROPERTY + " is already set");
		require(FigureConcept.selected().isEmpty(), "with no switch set, no concept is selected");

		System.setProperty(FigureConcept.PROPERTY, "plain-tall-miner");
		try {
			FigureConcept.selected();
			throw new AssertionError("a switch that names no concept should fail");
		} catch (IllegalArgumentException e) {
			for (FigureConcept concept : FigureConcept.values()) {
				require(e.getMessage().contains(concept.id()), "the error should name the concept " + concept.id() + ": " + e.getMessage());
			}
		} finally {
			System.clearProperty(FigureConcept.PROPERTY);
		}

		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			int[] figureId = {0};
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				// Face +z, where the figure stands.
				player.teleportTo(player.level(), player.getX(), player.getY(), player.getZ(), Set.of(), 0f, 10f, true);
				LamplessFigure figure = CreatureRegistry.LAMPLESS_FIGURE.create(player.level(), EntitySpawnReason.COMMAND);
				figure.setNoAi(true);
				figure.setPos(player.position().add(0, 0, 4));
				player.level().addFreshEntity(figure);
				figureId[0] = figure.getId();
			});
			ClientWait.until(context, "the figure on the client", client -> client.level != null && client.level.getEntity(figureId[0]) instanceof LamplessFigure);
			require(context.computeOnClient(client -> renderer(client, figureId[0]) instanceof LamplessFigureRenderer),
					"without the dev switch the figure should keep its placeholder renderer");
			try {
				for (FigureConcept concept : FigureConcept.values()) {
					System.setProperty(FigureConcept.PROPERTY, concept.id());
					context.runOnClient(Minecraft::reloadResourcePacks);
					ClientWait.until(context, "the figure drawn as " + concept.id(),
							client -> renderer(client, figureId[0]) instanceof FigureConceptRenderer drawn && drawn.concept() == concept,
							client -> String.valueOf(renderer(client, figureId[0])));
					context.runOnClient(client -> bakedBonesAndAnimations(client, concept));
					// One rendered frame with the concept in view: the pose and the draw run.
					context.waitTick(); // tick-wait: one frame draws the posed model; nothing else is awaited
				}
			} finally {
				System.clearProperty(FigureConcept.PROPERTY);
				context.runOnClient(Minecraft::reloadResourcePacks);
				ClientWait.until(context, "the figure back on its placeholder renderer", client -> renderer(client, figureId[0]) instanceof LamplessFigureRenderer,
						client -> String.valueOf(renderer(client, figureId[0])));
			}
		}
	}

	/** GeckoLib baked the concept's model with every bone its file names, and loaded both of its animations. */
	private static void bakedBonesAndAnimations(Minecraft client, FigureConcept concept) {
		BakedGeoModel baked = GeckoLibResources.getBakedModels().getModel(concept.resource());
		require(!baked.isMissingno(), "GeckoLib did not bake " + concept.resource() + ": it draws its missing-model cube");
		List<String> missing = new ArrayList<>();
		for (String bone : boneNames(client, concept)) {
			if (baked.getBone(bone).isEmpty()) {
				missing.add(bone);
			}
		}
		require(missing.isEmpty(), "GeckoLib's " + concept.resource() + " lacks the bones " + missing);
		for (String animation : List.of(FigureConcept.IDLE, FigureConcept.WALK)) {
			require(GeckoLibResources.getBakedAnimations().cache().containsKey(concept.resource())
					&& GeckoLibResources.getBakedAnimations().cache().get(concept.resource()).animations().containsKey(animation),
					"GeckoLib did not load the animation " + animation + " of " + concept.resource());
		}
	}

	private static List<String> boneNames(Minecraft client, FigureConcept concept) {
		List<String> names = new ArrayList<>();
		try (Reader reader = client.getResourceManager().getResourceOrThrow(concept.modelFile()).openAsReader()) {
			JsonObject geometry = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
			for (JsonElement bone : geometry.getAsJsonArray("bones")) {
				names.add(bone.getAsJsonObject().get("name").getAsString());
			}
		} catch (IOException e) {
			throw new AssertionError("could not read " + concept.modelFile(), e);
		}
		require(!names.isEmpty(), concept.modelFile() + " names no bones");
		return names;
	}

	private static EntityRenderer<?, ?> renderer(Minecraft client, int id) {
		return client.getEntityRenderDispatcher().getRenderer(client.level.getEntity(id));
	}
}
