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
import org.joml.Matrix4f;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.EntitySpawnReason;

import io.github.pkeppeler.deepcharter.client.creature.LamplessFigureAssets;
import io.github.pkeppeler.deepcharter.client.creature.LamplessFigureAnimatable;
import io.github.pkeppeler.deepcharter.client.creature.LamplessFigureRenderState;
import io.github.pkeppeler.deepcharter.client.creature.LamplessFigureRenderer;
import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

/**
 * Client GameTest for #250: the figure is drawn by {@link LamplessFigureRenderer} with no switch; GeckoLib bakes every bone and both
 * animations of its files; it draws in view; a whole figure has its full shadow, a cutout type and alpha 1 while a fading one has a smaller
 * shadow, a translucent type and alpha under 1; and it is not culled at the edge of the screen while any part of it is still in view.
 */
public class LamplessFigureClientTest implements FabricClientGameTest {
	/** The camera's field of view and the window's shape: only their ratio matters to the edge. */
	private static final float FOV_Y_DEGREES = 70f;
	private static final float ASPECT = 16f / 9f;
	private static final double DISTANCE = 5;
	/** The figure's middle is this far past the edge at its own depth; its hand, which reaches 0.8 blocks from the middle in the walk, is still on screen. */
	private static final double JUST_PAST_THE_EDGE = 0.7;
	private static final double FAR_PAST_THE_EDGE = 6;

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		boolean shadows = context.computeOnClient(client -> client.options.entityShadows().get());
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			// The renderer extracts a shadow only when the player's option has entity shadows on.
			context.runOnClient(client -> client.options.entityShadows().set(true));
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
					"the figure should be drawn by LamplessFigureRenderer");
			context.runOnClient(LamplessFigureClientTest::bakedBonesAndAnimations);
			// One rendered frame with the figure in view: the pose and the draw run.
			context.waitTick(); // tick-wait: one frame draws the posed model; nothing else is awaited
			context.runOnClient(client -> notCulledAtTheEdge(client, figureId[0]));
			context.runOnClient(client -> require(drawnWhole(client, figureId[0]).isEmpty(), "a whole figure is drawn wrong: " + drawnWhole(client, figureId[0])));

			// A second figure with its AI on fades as the player stands near it; its AI is then stopped, which freezes the fade part way.
			int[] fadingId = {0};
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				LamplessFigure figure = CreatureRegistry.LAMPLESS_FIGURE.create(player.level(), EntitySpawnReason.COMMAND);
				figure.setPos(player.position().add(3, 0, 0));
				player.level().addFreshEntity(figure);
				fadingId[0] = figure.getId();
			});
			ClientWait.until(context, "the second figure part way through its fade",
					client -> client.level.getEntity(fadingId[0]) instanceof LamplessFigure f && f.fadeFraction() > 0);
			singleplayer.getServer().runOnServer(server -> ((LamplessFigure) server.getPlayerList().getPlayers().getFirst().level().getEntity(fadingId[0])).setNoAi(true));
			context.waitTick(); // tick-wait: the freeze reaches the client before the figure is read
			context.runOnClient(client -> require(drawnFading(client, fadingId[0]).isEmpty(), "a fading figure is drawn wrong: " + drawnFading(client, fadingId[0])));
		} finally {
			context.runOnClient(client -> client.options.entityShadows().set(shadows));
		}
	}

	/** What is wrong with the way a whole figure (fade 0) is drawn: its state is read as the renderer reads it, so a dropped line in the renderer shows here. */
	private static List<String> drawnWhole(Minecraft client, int id) {
		LamplessFigure figure = (LamplessFigure) client.level.getEntity(id);
		LamplessFigureRenderer renderer = (LamplessFigureRenderer) renderer(client, id);
		LamplessFigureRenderState state = extract(client, figure);
		List<String> wrong = new ArrayList<>();
		if (state.fade != 0f) {
			wrong.add("fade " + state.fade + " is not 0");
		}
		if (state.shadowRadius != LamplessFigureRenderer.SHADOW_RADIUS) {
			wrong.add("shadow radius " + state.shadowRadius + " is not the full " + LamplessFigureRenderer.SHADOW_RADIUS);
		}
		if (renderer.getRenderType(state, LamplessFigureAssets.texture()).equals(RenderTypes.entityTranslucent(LamplessFigureAssets.texture()))) {
			wrong.add("the render type is translucent");
		}
		int alpha = ARGB.alpha(renderer.getRenderColor(new LamplessFigureAnimatable(), figure, 1f));
		if (alpha != 255) {
			wrong.add("alpha " + alpha + " is not 255");
		}
		return wrong;
	}

	/** What is wrong with the way a figure with fade above 0 is drawn: a smaller shadow, a translucent type, alpha under 255. */
	private static List<String> drawnFading(Minecraft client, int id) {
		LamplessFigure figure = (LamplessFigure) client.level.getEntity(id);
		LamplessFigureRenderer renderer = (LamplessFigureRenderer) renderer(client, id);
		LamplessFigureRenderState state = extract(client, figure);
		List<String> wrong = new ArrayList<>();
		if (state.fade <= 0f || state.fade >= 1f) {
			wrong.add("fade " + state.fade + " is not part way");
		}
		if (state.shadowRadius >= LamplessFigureRenderer.SHADOW_RADIUS) {
			wrong.add("shadow radius " + state.shadowRadius + " did not shrink from " + LamplessFigureRenderer.SHADOW_RADIUS);
		}
		if (!renderer.getRenderType(state, LamplessFigureAssets.texture()).equals(RenderTypes.entityTranslucent(LamplessFigureAssets.texture()))) {
			wrong.add("the render type is not translucent");
		}
		int alpha = ARGB.alpha(renderer.getRenderColor(new LamplessFigureAnimatable(), figure, 1f));
		if (alpha >= 255) {
			wrong.add("alpha " + alpha + " is not under 255");
		}
		return wrong;
	}

	/** The state as a frame builds it: the dispatcher extracts the renderer's state, then the shadow, which asks the renderer for its radius. */
	private static LamplessFigureRenderState extract(Minecraft client, LamplessFigure figure) {
		return (LamplessFigureRenderState) client.getEntityRenderDispatcher().extractEntity(figure, 1f);
	}

	/** GeckoLib baked the model with every bone its file names, and loaded both of its animations. */
	private static void bakedBonesAndAnimations(Minecraft client) {
		BakedGeoModel baked = GeckoLibResources.getBakedModels().getModel(LamplessFigureAssets.resource());
		require(!baked.isMissingno(), "GeckoLib did not bake " + LamplessFigureAssets.resource() + ": it draws its missing-model cube");
		List<String> missing = new ArrayList<>();
		for (String bone : boneNames(client)) {
			if (baked.getBone(bone).isEmpty()) {
				missing.add(bone);
			}
		}
		require(missing.isEmpty(), "GeckoLib's " + LamplessFigureAssets.resource() + " lacks the bones " + missing);
		for (String animation : List.of(LamplessFigureAssets.IDLE, LamplessFigureAssets.WALK)) {
			require(GeckoLibResources.getBakedAnimations().cache().containsKey(LamplessFigureAssets.resource())
					&& GeckoLibResources.getBakedAnimations().cache().get(LamplessFigureAssets.resource()).animations().containsKey(animation),
					"GeckoLib did not load the animation " + animation + " of " + LamplessFigureAssets.resource());
		}
	}

	/**
	 * A camera that looks along +z with the figure ahead at the right or the left edge of its view, its middle just off the screen and its
	 * hand still on it: the renderer must draw it, and it must still cull it when it is well outside the view. The python test
	 * ({@code tools/tests/test_lampless_figure.py}) holds the model, in every keyframe, inside the box the renderer culls by.
	 */
	private static void notCulledAtTheEdge(Minecraft client, int id) {
		LamplessFigure figure = (LamplessFigure) client.level.getEntity(id);
		LamplessFigureRenderer renderer = (LamplessFigureRenderer) renderer(client, id);
		double edge = DISTANCE * Math.tan(Math.toRadians(FOV_Y_DEGREES) / 2) * ASPECT;
		Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(FOV_Y_DEGREES), ASPECT, 0.05f, 128f);
		Matrix4f view = new Matrix4f().rotateY((float) Math.PI);
		for (double side : new double[] {-1, 1}) {
			Frustum near = frustum(view, projection, figure, side * (edge + JUST_PAST_THE_EDGE));
			require(renderer.shouldRender(figure, near, near.getCamX(), near.getCamY(), near.getCamZ(), 1f),
					"the figure is culled " + JUST_PAST_THE_EDGE + " blocks past the screen edge, side " + side + ": it pops out of view while its arm is still on screen");
			Frustum far = frustum(view, projection, figure, side * (edge + FAR_PAST_THE_EDGE));
			require(!renderer.shouldRender(figure, far, far.getCamX(), far.getCamY(), far.getCamZ(), 1f),
					"the figure is drawn " + FAR_PAST_THE_EDGE + " blocks past the screen edge, side " + side + ": the cull box is far too large");
		}
	}

	/** The frustum of a camera that looks along +z from {@code DISTANCE} behind the figure, with the figure {@code offset} blocks to the side of the middle of the view. */
	private static Frustum frustum(Matrix4f view, Matrix4f projection, LamplessFigure figure, double offset) {
		Frustum frustum = new Frustum(view, projection);
		frustum.prepare(figure.getX() + offset, figure.getY() + 1.6, figure.getZ() - DISTANCE);
		return frustum;
	}

	private static List<String> boneNames(Minecraft client) {
		List<String> names = new ArrayList<>();
		try (Reader reader = client.getResourceManager().getResourceOrThrow(LamplessFigureAssets.modelFile()).openAsReader()) {
			JsonObject geometry = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
			for (JsonElement bone : geometry.getAsJsonArray("bones")) {
				names.add(bone.getAsJsonObject().get("name").getAsString());
			}
		} catch (IOException e) {
			throw new AssertionError("could not read " + LamplessFigureAssets.modelFile(), e);
		}
		require(!names.isEmpty(), LamplessFigureAssets.modelFile() + " names no bones");
		return names;
	}

	private static EntityRenderer<?, ?> renderer(Minecraft client, int id) {
		return client.getEntityRenderDispatcher().getRenderer(client.level.getEntity(id));
	}
}
