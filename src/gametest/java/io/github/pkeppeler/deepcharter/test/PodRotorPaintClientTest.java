package io.github.pkeppeler.deepcharter.test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import com.mojang.blaze3d.platform.NativeImage;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.client.pod.BoneRole;
import io.github.pkeppeler.deepcharter.client.pod.PodGeoRenderState;
import io.github.pkeppeler.deepcharter.client.pod.PodGeoRenderer;
import io.github.pkeppeler.deepcharter.client.pod.PodMotion;
import io.github.pkeppeler.deepcharter.client.pod.PodPaint;
import io.github.pkeppeler.deepcharter.client.pod.PodPose;
import io.github.pkeppeler.deepcharter.client.theme.PodPaintLook;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

/**
 * Client GameTest for #383: the rotor folds out for lift-off and in on landing and stays in while the drill bites, and each charter's
 * pods carry its paint colour from the resource-pack palette. Also the {@link PodMotion} clock: a frame drawn at an earlier partial tick
 * than one already seen must not be counted again by the next.
 */
public class PodRotorPaintClientTest implements FabricClientGameTest {
	/** The charters of the test: made-up ids whose UUIDs hash to the palette slots 1 and 2. */
	private static final CharterId EMBER = new CharterId(new UUID(0L, 1L));
	private static final CharterId COBALT = new CharterId(new UUID(0L, 2L));
	/** How many frames, one tick apart, the fold is given: the fold takes about ten. */
	private static final int FOLD_FRAMES = 30;

	/** The ids of the pods made for the test. */
	private record Scene(int ember, int cobalt, int unowned, int prospector) {
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			Scene scene = singleplayer.getServer().computeOnServer(PodRotorPaintClientTest::setUp);
			ClientWait.until(context, "all four pods on the client", client -> client.level != null && pod(client, scene.ember()) != null
					&& pod(client, scene.cobalt()) != null && pod(client, scene.unowned()) != null && pod(client, scene.prospector()) != null);
			// The owner arrives in a packet of its own, after the pod.
			ClientWait.until(context, "the owners of the pods on the client", client -> PodComponents.registration(pod(client, scene.ember())).isPresent()
					&& PodComponents.registration(pod(client, scene.cobalt())).isPresent() && PodComponents.registration(pod(client, scene.prospector())).isPresent());

			context.runOnClient(client -> {
				bladesFoldFromTheRotor(client, scene);
				theRotorFoldsOutAndIn(client, scene.ember());
				theRotorStaysInWhileDrilling(client, scene.ember());
				aBackwardsFrameIsNotCountedTwice(client, scene.ember());
				eachCharterCarriesItsPaint(client, scene);
				theHullIsRecoloured(client, scene.ember());
			});
			// A frame draws the painted, folded pods through the real renderer, the glow layer included.
			context.waitTick(); // tick-wait: one frame draws the pods; nothing else is awaited

			// A wreck is not painted: it keeps its derelict texture.
			singleplayer.getServer().runOnServer(server -> ((PodEntity) server.overworld().getEntity(scene.ember())).setHull(0f));
			ClientWait.until(context, "the first Mole drawn as a wreck", client -> renderer(client, scene.ember()).appearanceOf(pod(client, scene.ember())).wrecked());
			context.runOnClient(client -> {
				PodGeoRenderer.Appearance wreck = renderer(client, scene.ember()).appearanceOf(pod(client, scene.ember()));
				require(wreck.paint().isEmpty(), "a wreck is not painted, shows the paint " + wreck.paint());
			});
			context.waitTick(); // tick-wait: a frame draws the wreck
		}
	}

	/** Both models fold their rotor from two blades that hang from it, each on its own side of the hub. */
	private static void bladesFoldFromTheRotor(Minecraft client, Scene scene) {
		for (int id : List.of(scene.ember(), scene.prospector())) {
			var geo = renderer(client, id).geometry();
			var blades = geo.bones().stream().filter(bone -> bone.role() == BoneRole.BLADE).toList();
			require(blades.size() == 2, pod(client, id).chassis().id() + " folds two blades, has " + blades);
			require(blades.stream().allMatch(blade -> blade.parent().map(parent -> geo.bones().stream()
					.anyMatch(bone -> bone.name().equals(parent) && bone.role() == BoneRole.ROTOR)).orElse(false)), "the blades hang from the rotor: " + blades);
			require(blades.stream().mapToDouble(blade -> Math.signum(blade.pivot().x)).sum() == 0, "the blades are on both sides of the hub: " + blades);
			require(blades.stream().flatMap(blade -> blade.cubes().stream()).count() >= 4, "the blades carry the rotor's cubes");
		}
		require(PodPose.bladeFold(1f, 1f) == 0f && PodPose.bladeFold(1f, -1f) == 0f, "out, the blades lie flat in the file's pose");
		require(PodPose.bladeFold(0f, 1f) == 90f && PodPose.bladeFold(0f, -1f) == -90f, "folded, the blades turn a quarter, mirrored: "
				+ PodPose.bladeFold(0f, 1f) + ", " + PodPose.bladeFold(0f, -1f));
	}

	/** Lift-off folds the rotor out, it stays out in the air, landing folds it in; it turns only once it is out. */
	private static void theRotorFoldsOutAndIn(Minecraft client, int id) {
		PodEntity pod = pod(client, id);
		PodMotion motion = new PodMotion();
		PodGeoRenderState state = new PodGeoRenderState();
		pod.setFlying(false);
		pod.setOnGround(true);
		try {
			frames(motion, pod, state, 0, 5);
			require(state.rotorOut == 0f && state.rotorSpin == 0f, "a parked pod has its rotor folded and still: out " + state.rotorOut + ", spin " + state.rotorSpin);
			pod.setFlying(true);
			pod.setOnGround(false);
			frames(motion, pod, state, 5, 3);
			require(state.rotorOut > 0f && state.rotorOut < 1f, "three ticks after lift-off the rotor is folding out: " + state.rotorOut);
			float halfway = state.rotorSpin;
			frames(motion, pod, state, 8, FOLD_FRAMES);
			require(state.rotorOut == 1f, "the rotor is out after lift-off: " + state.rotorOut);
			require(PodPose.bladeFold(state.rotorOut, 1f) == 0f, "the blades lie flat once out");
			require(state.rotorSpin != halfway, "the rotor turns while the pod flies: " + state.rotorSpin);
			// The engine cuts and the pod coasts: still in the air, so the blades stay out and the rotor winds down.
			pod.setFlying(false);
			frames(motion, pod, state, 8 + FOLD_FRAMES, FOLD_FRAMES);
			require(state.rotorOut == 1f, "the rotor stays out while the pod is in the air: " + state.rotorOut);
			pod.setOnGround(true);
			frames(motion, pod, state, 8 + 2 * FOLD_FRAMES, 3);
			require(state.rotorOut > 0f && state.rotorOut < 1f, "three ticks after landing the rotor is folding in: " + state.rotorOut);
			frames(motion, pod, state, 11 + 2 * FOLD_FRAMES, FOLD_FRAMES);
			require(state.rotorOut == 0f, "the rotor is folded after landing: " + state.rotorOut);
			float settled = state.rotorSpin;
			frames(motion, pod, state, 11 + 3 * FOLD_FRAMES, FOLD_FRAMES);
			require(state.rotorSpin == settled, "a folded rotor is still");
			// A pod first seen in the air is not seen unfolding.
			pod.setOnGround(false);
			PodGeoRenderState fresh = new PodGeoRenderState();
			frame(new PodMotion(), pod, fresh, 0f);
			require(fresh.rotorOut == 1f, "a pod first drawn in the air has its rotor out at once: " + fresh.rotorOut);
		} finally {
			pod.setFlying(false);
			pod.setOnGround(true);
		}
	}

	private static void theRotorStaysInWhileDrilling(Minecraft client, int id) {
		PodEntity pod = pod(client, id);
		PodMotion motion = new PodMotion();
		PodGeoRenderState state = new PodGeoRenderState();
		pod.setDrilling(true);
		pod.setDrillDirection(Direction.DOWN);
		pod.setFlying(true);
		pod.setOnGround(false);
		try {
			frames(motion, pod, state, 0, FOLD_FRAMES);
			require(state.rotorOut == 0f && state.drilling, "a drilling pod keeps its rotor folded, even with the engine up: out " + state.rotorOut);
		} finally {
			pod.setDrilling(false);
			pod.setFlying(false);
			pod.setOnGround(true);
		}
	}

	/**
	 * A pod drawn twice in a frame, the second time at an earlier partial tick, counts each tick once: the spin after frames at ticks 10,
	 * 11, 10.5 and 11 is the spin after 10 and 11.
	 */
	private static void aBackwardsFrameIsNotCountedTwice(Minecraft client, int id) {
		PodEntity pod = pod(client, id);
		pod.setDrilling(true);
		try {
			PodMotion once = new PodMotion();
			PodGeoRenderState reference = new PodGeoRenderState();
			frame(once, pod, reference, 10f);
			frame(once, pod, reference, 11f);
			PodMotion twice = new PodMotion();
			PodGeoRenderState state = new PodGeoRenderState();
			frame(twice, pod, state, 10f);
			frame(twice, pod, state, 11f);
			frame(twice, pod, state, 10.5f);
			frame(twice, pod, state, 11f);
			require(reference.drillSpin > 0f, "the drill spins while it drills, so this can tell a double count");
			require(state.drillSpin == reference.drillSpin, "a frame at an earlier partial tick was counted again: the drill turned " + state.drillSpin + " degrees, "
					+ "and " + reference.drillSpin + " is one tick's worth");
		} finally {
			pod.setDrilling(false);
		}
	}

	/** Pods of two charters carry two colours, from the palette; two pods of one charter, one; a pod of no charter, the stock paint. */
	private static void eachCharterCarriesItsPaint(Minecraft client, Scene scene) {
		PodPaintLook palette = PodPaintLook.current();
		require(palette.paints().size() >= 2, "the palette has colours to tell charters apart: " + palette.paints());
		require(palette.slotOf(EMBER) == 1 && palette.slotOf(COBALT) == 2, "the test's charters pick slots 1 and 2: " + palette.slotOf(EMBER) + ", " + palette.slotOf(COBALT));
		PodGeoRenderer.Appearance ember = renderer(client, scene.ember()).appearanceOf(pod(client, scene.ember()));
		PodGeoRenderer.Appearance cobalt = renderer(client, scene.cobalt()).appearanceOf(pod(client, scene.cobalt()));
		require(ember.paint().equals(OptionalInt.of(palette.paintOf(EMBER))), "the first charter's pod carries its palette colour, shows " + ember.paint());
		require(cobalt.paint().equals(OptionalInt.of(palette.paintOf(COBALT))), "the second charter's pod carries its palette colour, shows " + cobalt.paint());
		require(!ember.paint().equals(cobalt.paint()), "two charters carry two colours: " + ember.paint());
		Identifier emberTexture = renderer(client, scene.ember()).textureOf(ember);
		Identifier cobaltTexture = renderer(client, scene.cobalt()).textureOf(cobalt);
		require(!emberTexture.equals(cobaltTexture) && !emberTexture.equals(ember.variant().texture()), "each charter's pod draws its own painted texture: " + emberTexture + ", " + cobaltTexture);
		require(emberTexture.equals(renderer(client, scene.ember()).textureOf(ember)), "a charter's colour is painted once");
		require(client.getTextureManager().getTexture(emberTexture) != null, "the painted texture is registered");
		PodGeoRenderer.Appearance unowned = renderer(client, scene.unowned()).appearanceOf(pod(client, scene.unowned()));
		require(unowned.paint().isEmpty(), "a pod of no charter keeps the stock paint, shows " + unowned.paint());
		require(renderer(client, scene.unowned()).textureOf(unowned).equals(unowned.variant().texture()), "a pod of no charter draws the stock texture");
		require(renderer(client, scene.prospector()).appearanceOf(pod(client, scene.prospector())).paint().isPresent(), "the Prospector is painted too");
	}

	/** The paint mask's texels take the colour times their shade, and no other texel changes. */
	private static void theHullIsRecoloured(Minecraft client, int id) {
		PodGeoRenderer renderer = renderer(client, id);
		Identifier baseId = renderer.look().intact().texture();
		Identifier maskId = renderer.look().paintMask().orElseThrow();
		int rgb = PodPaintLook.current().paintOf(EMBER);
		try (NativeImage base = read(client, baseId); NativeImage mask = read(client, maskId); NativeImage painted = PodPaint.paint(base, mask, rgb)) {
			int masked = 0;
			int bare = 0;
			boolean shaded = false;
			for (int y = 0; y < base.getHeight(); y++) {
				for (int x = 0; x < base.getWidth(); x++) {
					int mark = mask.getPixel(x, y);
					if (mark >>> 24 == 0) {
						require(painted.getPixel(x, y) == base.getPixel(x, y), "a texel outside the paint changed at " + x + ", " + y);
						bare++;
					} else {
						int grey = mark >> 16 & 255;
						int expected = (rgb >> 16 & 255) * grey / PodPaint.MASK_UNIT;
						int red = Math.min(255, expected);
						require((painted.getPixel(x, y) >> 16 & 255) == red && painted.getPixel(x, y) >>> 24 == 255,
								"the paint texel at " + x + ", " + y + " should be the colour's red times " + grey + "/" + PodPaint.MASK_UNIT + " = " + red + ", is "
										+ (painted.getPixel(x, y) >> 16 & 255));
						shaded |= grey != PodPaint.MASK_UNIT;
						masked++;
					}
				}
			}
			require(masked > 1000 && bare > 1000, "the mask picks the hull's paint and leaves the rest: " + masked + " painted texels, " + bare + " others");
			require(shaded, "the paint keeps its shading");
		}
	}

	/** {@code count} frames, one tick apart, starting at tick {@code from}. */
	private static void frames(PodMotion motion, PodEntity pod, PodGeoRenderState state, int from, int count) {
		for (int tick = from; tick < from + count; tick++) {
			frame(motion, pod, state, tick);
		}
	}

	private static void frame(PodMotion motion, PodEntity pod, PodGeoRenderState state, float age) {
		state.ageInTicks = age;
		motion.advance(pod, state, 0f, 1f);
	}

	private static NativeImage read(Minecraft client, Identifier texture) {
		try (InputStream png = client.getResourceManager().getResource(texture).orElseThrow().open()) {
			return NativeImage.read(png);
		} catch (IOException e) {
			throw new AssertionError("cannot read " + texture, e);
		}
	}

	/** Two Moles of two charters, a Mole of none and a Prospector, in a row in front of the player. */
	private static Scene setUp(MinecraftServer server) {
		ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
		player.teleportTo(player.level(), player.getX(), player.getY(), player.getZ(), Set.of(), 0f, 10f, true);
		ServerLevel level = player.level();
		PodEntity ember = spawn(level, PodRegistry.POD, player.position().add(-4, 0, 7));
		PodEntity cobalt = spawn(level, PodRegistry.POD, player.position().add(-1, 0, 7));
		PodEntity unowned = spawn(level, PodRegistry.POD, player.position().add(2, 0, 7));
		PodEntity prospector = spawn(level, PodRegistry.PROSPECTOR, player.position().add(5, 0, 9));
		PodComponents.register(ember, EMBER);
		PodComponents.register(cobalt, COBALT);
		PodComponents.register(prospector, COBALT);
		return new Scene(ember.getId(), cobalt.getId(), unowned.getId(), prospector.getId());
	}

	private static PodEntity spawn(ServerLevel level, EntityType<PodEntity> type, Vec3 at) {
		PodEntity pod = type.create(level, EntitySpawnReason.COMMAND);
		pod.setPos(at);
		level.addFreshEntity(pod);
		return pod;
	}

	private static PodEntity pod(Minecraft client, int id) {
		return client.level.getEntity(id) instanceof PodEntity pod ? pod : null;
	}

	private static PodGeoRenderer renderer(Minecraft client, int id) {
		Object drawn = client.getEntityRenderDispatcher().getRenderer(client.level.getEntity(id));
		require(drawn instanceof PodGeoRenderer, "pod " + id + " is not drawn with the GeckoLib renderer, but with " + drawn);
		return (PodGeoRenderer) drawn;
	}
}
