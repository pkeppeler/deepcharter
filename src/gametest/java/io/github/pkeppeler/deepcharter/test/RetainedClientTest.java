package io.github.pkeppeler.deepcharter.test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import com.geckolib.cache.GeckoLibResources;
import com.geckolib.cache.model.BakedGeoModel;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.client.creature.LamplessFigureAssets;
import io.github.pkeppeler.deepcharter.client.creature.LamplessFigureRenderer;
import io.github.pkeppeler.deepcharter.client.creature.LamplessFigureRenderState;
import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientPacks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.TestPacks;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

/**
 * Client GameTest for #449: a figure that carries a lamp is drawn from the lamp-carrier's own model, texture and glowmask, in each of the three
 * ways to carry the lamp (the default and the two test packs); a lampless figure is drawn from the lampless model, which has no lantern; and a
 * figure that holds a pod's hull is drawn in the cling pose, with its animation loaded.
 */
public class RetainedClientTest implements FabricClientGameTest {
	private static final int HALF = 7;
	private static final int APPROACH = 6;
	private static final String LANTERN = "lantern";

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			int[] ids = {0, 0};
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				ids[0] = spawn(player, true, 4).getId();
				ids[1] = spawn(player, false, -4).getId();
			});
			ClientWait.until(context, "both figures on the client", client -> client.level != null && client.level.getEntity(ids[0]) instanceof LamplessFigure lamp && lamp.hasLamp()
					&& client.level.getEntity(ids[1]) instanceof LamplessFigure);
			context.runOnClient(client -> expectModels(client, ids[0], ids[1]));
			List<Integer> looks = new ArrayList<>();
			looks.add(context.computeOnClient(RetainedClientTest::lampLook));
			for (String pack : List.of(TestPacks.RETAINED_LAMP_POLE, TestPacks.RETAINED_LAMP_RIBS)) {
				ClientPacks.enable(context, pack);
				try {
					context.runOnClient(client -> expectModels(client, ids[0], ids[1]));
					int look = context.computeOnClient(RetainedClientTest::lampLook);
					require(!looks.contains(look), pack + " did not change the lamp's model, texture and glowmask from the other ways to carry it");
					looks.add(look);
				} finally {
					ClientPacks.disable(context, pack);
				}
			}
			expectTheClingPose(context, singleplayer, ids);
		}
	}

	/** The lamp-carrier has the lantern in its baked model and a model of its own; the lampless figure has none. */
	private static void expectModels(Minecraft client, int lampId, int plainId) {
		EntityRenderer<?, ?> renderer = client.getEntityRenderDispatcher().getRenderer(client.level.getEntity(lampId));
		var model = ((LamplessFigureRenderer) renderer).getGeoModel();
		LamplessFigureRenderState lamp = state(client, lampId);
		LamplessFigureRenderState plain = state(client, plainId);
		require(lamp.lamp && !plain.lamp, "the render state does not carry whether the figure has a lamp: lamp " + lamp.lamp + ", plain " + plain.lamp);
		require(model.getModelResource(lamp).equals(LamplessFigureAssets.resource(true)) && model.getTextureResource(lamp).equals(LamplessFigureAssets.lampTexture()),
				"a lamp-carrier is not drawn from the lamp model and texture");
		require(model.getModelResource(plain).equals(LamplessFigureAssets.resource()) && model.getTextureResource(plain).equals(LamplessFigureAssets.texture()),
				"a lampless figure is not drawn from the lampless model and texture");
		BakedGeoModel carrier = GeckoLibResources.getBakedModels().getModel(LamplessFigureAssets.resource(true));
		BakedGeoModel lampless = GeckoLibResources.getBakedModels().getModel(LamplessFigureAssets.resource());
		require(!carrier.isMissingno(), "GeckoLib did not bake the lamp-carrier model " + LamplessFigureAssets.resource(true));
		require(carrier.getBone(LANTERN).isPresent(), "the lamp-carrier has no lantern");
		require(!lampless.isMissingno(), "GeckoLib did not bake the lampless model " + LamplessFigureAssets.resource());
		require(lampless.getBone(LANTERN).isEmpty(), "the lampless figure has a lantern");
		require(client.getResourceManager().getResource(LamplessFigureAssets.glowmask()).isPresent(), "the glowmask of the lamp is missing");
		require(GeckoLibResources.getBakedAnimations().cache().get(LamplessFigureAssets.resource()).animations().containsKey(LamplessFigureAssets.CLING),
				"GeckoLib did not load the cling animation");
	}

	/** A hash of the three files of the lamp-carrier's look as the client reads them now: the winning pack's, or the mod's own. */
	private static int lampLook(Minecraft client) {
		int hash = 1;
		for (Identifier file : List.of(LamplessFigureAssets.lampModelFile(), LamplessFigureAssets.lampTexture(), LamplessFigureAssets.glowmask())) {
			try (var stream = client.getResourceManager().getResourceOrThrow(file).open()) {
				hash = 31 * hash + Arrays.hashCode(stream.readAllBytes());
			} catch (IOException e) {
				throw new AssertionError("could not read " + file, e);
			}
		}
		return hash;
	}

	private static LamplessFigureRenderState state(Minecraft client, int id) {
		return (LamplessFigureRenderState) client.getEntityRenderDispatcher().extractEntity(client.level.getEntity(id), 1f);
	}

	private static LamplessFigure spawn(ServerPlayer player, boolean lamp, int dz) {
		ServerLevel level = player.level();
		LamplessFigure figure = CreatureRegistry.LAMPLESS_FIGURE.create(level, EntitySpawnReason.COMMAND);
		figure.setNoAi(true);
		figure.setLamp(lamp);
		figure.setPos(player.position().add(0, 0, dz));
		level.addFreshEntity(figure);
		return figure;
	}

	/** A pilot in a dark pod on a flat floor, and a figure that walks to the hull: its client copy holds the hull and is drawn in the cling pose. */
	private static void expectTheClingPose(ClientGameTestContext context, TestSingleplayerContext singleplayer, int[] ids) {
		int[] clinging = {0};
		singleplayer.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			ServerLevel level = player.level();
			((LamplessFigure) level.getEntity(ids[0])).discard();
			((LamplessFigure) level.getEntity(ids[1])).discard();
			player.setGameMode(GameType.SURVIVAL);
			BlockPos origin = player.blockPosition().above(60);
			for (int u = -HALF - 1; u <= HALF + 1; u++) {
				for (int v = -HALF - 1; v <= HALF + 1; v++) {
					for (int y = -1; y <= 6; y++) {
						boolean shell = y == -1 || y == 6 || Math.abs(u) == HALF + 1 || Math.abs(v) == HALF + 1;
						// room-carver: a closed arena of its own stone shell high over the overworld's test spawn, not layer rock
						level.setBlock(origin.offset(u, y, v), (shell ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 3);
					}
				}
			}
			PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
			pod.setPos(Vec3.atBottomCenterOf(origin.east(APPROACH)));
			level.addFreshEntity(pod);
			player.teleportTo(level, pod.getX(), pod.getY(), pod.getZ(), Set.of(), 0f, 0f, true);
			if (!player.startRiding(pod)) {
				throw new AssertionError("the player could not mount the pod");
			}
			LamplessFigure figure = CreatureRegistry.LAMPLESS_FIGURE.create(level, EntitySpawnReason.COMMAND);
			figure.setPos(Vec3.atBottomCenterOf(origin.west(APPROACH)));
			level.addFreshEntity(figure);
			clinging[0] = figure.getId();
		});
		ClientWait.until(context, "the figure clinging to the pod on the client", client -> client.level != null && client.level.getEntity(clinging[0]) instanceof LamplessFigure figure && figure.isClinging());
		context.runOnClient(client -> require(state(client, clinging[0]).clinging, "a clinging figure's render state is not clinging"));
	}
}
