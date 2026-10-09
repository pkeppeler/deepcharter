package io.github.pkeppeler.deepcharter.test;

import java.util.List;
import java.util.Set;

import com.geckolib.cache.GeckoLibResources;
import com.geckolib.cache.model.BakedGeoModel;
import com.geckolib.renderer.layer.builtin.AutoGlowingGeoLayer;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.pod.GeoModel;
import io.github.pkeppeler.deepcharter.client.pod.PodGeoRenderState;
import io.github.pkeppeler.deepcharter.client.pod.PodGeoRenderer;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

/**
 * Client GameTest for #243 (ADR 0030, 0039): both pods draw with GeckoLib from their look files, and GeckoLib baked the models the
 * game measures; the cutter on show is the one the pod's drill tier picks and changes at once when a drill is installed; a wreck
 * shows its own variant; a pod loaded from its save draws the same; and the renderer's own culling box holds the whole model, every
 * cutter, at every heading (it fails if the renderer's override of the box is removed).
 */
public class PodGeckoLibClientTest implements FabricClientGameTest {
	private static final double TOLERANCE = 1e-6;
	private static final int HEADING_STEP = 15;

	/** The ids of the Mole and the Prospector made for the test, and the charter that owns them. */
	private record Scene(int mole, int prospector, CharterId charter) {
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		ClientTestLog.start(this);
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			Scene scene = singleplayer.getServer().computeOnServer(PodGeckoLibClientTest::setUp);
			ClientWait.until(context, "both pods on the client", client -> client.level != null && pod(client, scene.mole()) != null && pod(client, scene.prospector()) != null);

			// Both chassis draw with the GeckoLib renderer, and GeckoLib baked the model the game measures, bone for bone.
			context.runOnClient(client -> {
				for (int id : List.of(scene.mole(), scene.prospector())) {
					PodGeoRenderer renderer = renderer(client, id);
					require(renderer.chassis() == pod(client, id).chassis(), "the renderer of " + id + " is for another chassis: " + renderer.chassis().id());
					BakedGeoModel baked = GeckoLibResources.getBakedModels().getModel(renderer.look().model());
					require(!baked.isMissingno(), "GeckoLib did not bake " + renderer.look().model() + ": it draws its missing-model cube");
					for (GeoModel.Bone bone : renderer.geometry().bones()) {
						require(baked.getBone(bone.name()).isPresent(), "GeckoLib's " + renderer.look().model() + " lacks the bone " + bone.name());
					}
				}
			});
			// One frame with the pods in view runs the extraction, the pose and the glow layer.
			context.waitTick(); // tick-wait: one frame draws the posed models; nothing else is awaited

			// The stock drill shows the tricone, and a wreck is not what it shows.
			cutterIs(context, scene.mole(), "tricone");
			cutterIs(context, scene.prospector(), "tricone");

			// The lamps glow: both renderers draw GeckoLib's glowmask layer over the model.
			context.runOnClient(client -> {
				for (int id : List.of(scene.mole(), scene.prospector())) {
					require(renderer(client, id).getRenderLayers().stream().anyMatch(AutoGlowingGeoLayer.class::isInstance),
							"pod " + id + " draws no glowmask layer, has " + renderer(client, id).getRenderLayers());
				}
			});

			// The cutter turns by GeoModel.drillSpinScale: a wider cone turns slower. Both stock cutters are tricones, the Prospector's the wider.
			double moleRate = spinRate(context, scene.mole());
			double prospectorRate = spinRate(context, scene.prospector());
			double expected = context.computeOnClient(client -> renderer(client, scene.prospector()).geometry().drillSpinScale("tricone")
					/ renderer(client, scene.mole()).geometry().drillSpinScale("tricone"));
			require(moleRate > 0 && prospectorRate < moleRate, "the Prospector's wider cutter should turn slower: " + prospectorRate + " against the Mole's " + moleRate);
			require(Math.abs(prospectorRate / moleRate - expected) < 0.02, "the spin rates should be in the ratio of the drill spin scales, " + expected + ": "
					+ prospectorRate / moleRate);

			// Installing a drill swaps the cutter at once: tier 1 is the stacked rings. Only the stacked bones are drawn.
			singleplayer.getServer().runOnServer(server -> install(server, scene, scene.mole(), 1));
			cutterIs(context, scene.mole(), "stacked");
			context.runOnClient(client -> {
				List<String> hidden = renderer(client, scene.mole()).hiddenBones(renderer(client, scene.mole()).appearanceOf(pod(client, scene.mole())));
				require(hidden.containsAll(List.of("drill_head_tricone", "drill_ring_tricone", "drill_head_fluted", "drill_head_cluster", "drill_ring_cluster")),
						"the stacked Mole should hide the other cutters, hides " + hidden);
				require(!hidden.contains("drill_head_stacked") && !hidden.contains("drill_ring_stacked"), "the stacked Mole should draw its stacked bones, hides " + hidden);
			});
			// Tier 2 is the Mole's cap, the auger. A tier 4 part works as tier 2, so the cutter stays.
			singleplayer.getServer().runOnServer(server -> install(server, scene, scene.mole(), 2));
			cutterIs(context, scene.mole(), "fluted");
			singleplayer.getServer().runOnServer(server -> install(server, scene, scene.mole(), 4));
			context.waitTicks(5); // tick-wait: the sync of a part reaches the client; the cutter must not change
			cutterIs(context, scene.mole(), "fluted");
			// The Prospector's cap is 3, the cluster; a tier 4 part works as tier 3.
			singleplayer.getServer().runOnServer(server -> install(server, scene, scene.prospector(), 3));
			cutterIs(context, scene.prospector(), "cluster");
			singleplayer.getServer().runOnServer(server -> install(server, scene, scene.prospector(), 4));
			context.waitTicks(5); // tick-wait: as above, for the Prospector's cap of 3
			cutterIs(context, scene.prospector(), "cluster");
			context.waitTick(); // tick-wait: a frame draws the new cutter

			// A pod loaded from its save draws the same: the cutter is derived from the parts it already saves, so no pod state is new.
			int[] reloaded = {0};
			singleplayer.getServer().runOnServer(server -> reloaded[0] = reload(server, scene));
			ClientWait.until(context, "the reloaded Mole on the client", client -> pod(client, reloaded[0]) != null);
			cutterIs(context, reloaded[0], "fluted");
			context.waitTick(); // tick-wait: a frame draws the reloaded pod

			int mole = reloaded[0];
			// A wreck draws its own variant and keeps its drill.
			singleplayer.getServer().runOnServer(server -> ((PodEntity) server.overworld().getEntity(mole)).setHull(0f));
			ClientWait.until(context, "the Mole drawn as a wreck", client -> renderer(client, mole).appearanceOf(pod(client, mole)).wrecked(),
					client -> String.valueOf(renderer(client, mole).appearanceOf(pod(client, mole))));
			context.runOnClient(client -> {
				PodGeoRenderer renderer = renderer(client, mole);
				PodGeoRenderer.Appearance wreck = renderer.appearanceOf(pod(client, mole));
				require(wreck.variant().equals(renderer.look().wreck()), "a wreck should draw the look's wreck variant, draws " + wreck);
				require("fluted".equals(wreck.cutter()), "a wreck keeps its drill, shows " + wreck.cutter());
				require(!wreck.glows(true), "the derelict Mole has no light, even when lit");
				require(renderer.hiddenBones(wreck).contains("rotor"), "the derelict Mole has lost its rotor, hides " + renderer.hiddenBones(wreck));
			});
			singleplayer.getServer().runOnServer(server -> ((PodEntity) server.overworld().getEntity(scene.prospector())).setHull(0f));
			ClientWait.until(context, "the Prospector drawn as a wreck", client -> renderer(client, scene.prospector()).appearanceOf(pod(client, scene.prospector())).wrecked());
			context.runOnClient(client -> {
				PodGeoRenderer.Appearance wreck = renderer(client, scene.prospector()).appearanceOf(pod(client, scene.prospector()));
				require(wreck.glows(false), "the scorched Prospector has one lamp lit, even with no pilot and no power");
			});
			context.waitTick(); // tick-wait: a frame draws both wrecks

			// The renderer's own culling box holds every cutter at every heading. This is on the renderer's override: the entity's own box
			// does not hold a cone's tip, so the check fails if the override is removed.
			context.runOnClient(client -> {
				for (int id : List.of(mole, scene.prospector())) {
					checkCulling(renderer(client, id), pod(client, id));
				}
			});
		}
	}

	/**
	 * Every corner of every cutter's extent, with the drill mount at each sampled step of the swing and at the half steps between
	 * (where the margin matters), turned to every heading, lies in the renderer's culling box; so does the whole sweep of the rotor,
	 * and the model shaken by {@link PodGeoRenderer#SHAKE}.
	 */
	private static void checkCulling(PodGeoRenderer renderer, PodEntity pod) {
		GeoModel geo = renderer.geometry();
		AABB box = renderer.getBoundingBoxForCulling(pod, 0f).inflate(TOLERANCE);
		AABB plain = pod.getBoundingBox();
		Vec3 feet = pod.position();
		for (String cutter : geo.cutters()) {
			boolean cutterOutsideThePlainBox = false;
			for (double pitch = 0; pitch <= 90; pitch += GeoModel.SWING_STEP / 2.0) {
				double[] b = geo.restBounds(bone -> geo.inCutter(bone, cutter), pitch);
				for (double x : new double[] {b[0], b[3]}) {
					for (double z : new double[] {b[2], b[5]}) {
						for (double y : new double[] {b[1], b[4]}) {
							for (int degrees = 0; degrees < 360; degrees += HEADING_STEP) {
								Vec3 corner = turnedAbout(feet, x, y, z, degrees);
								require(box.contains(corner), renderer.chassis().id() + "'s " + cutter + " cutter, with its drill turned " + pitch + " degrees, at the heading "
										+ degrees + ", has a corner at " + corner + " outside the renderer's culling box " + box);
								cutterOutsideThePlainBox |= !plain.contains(corner);
							}
						}
					}
				}
			}
			require(cutterOutsideThePlainBox, renderer.chassis().id() + "'s " + cutter + " cutter lies inside the pod's own box " + plain
					+ ", so this test could not tell a missing override");
		}
		require(!geo.cutters().isEmpty(), renderer.chassis().id() + " holds no cutters to check");
		// The rotor's tips sweep a circle about its own pivot, which is off the pod's middle.
		double sweep = geo.rotorReach();
		for (int degrees = 0; degrees < 360; degrees += HEADING_STEP) {
			Vec3 tip = turnedAbout(feet, sweep, geo.restBounds()[4], 0, degrees);
			require(box.contains(tip), renderer.chassis().id() + "'s rotor, reaching " + sweep + " pixels, at the heading " + degrees + " is outside the culling box " + box);
		}
		// The hull shakes by up to SHAKE while the pod drills, on top of the model's own extent.
		AABB shaken = geo.cullingBox().move(feet).inflate(PodGeoRenderer.SHAKE - TOLERANCE);
		require(box.contains(shaken.minX, shaken.minY, shaken.minZ) && box.contains(shaken.maxX, shaken.maxY, shaken.maxZ),
				renderer.chassis().id() + "'s culling box " + box + " does not hold the model's box " + shaken + " shaken");
	}

	/** The point (x, y, z) of the model in pixels, with the pod at {@code feet} turned {@code degrees}. */
	private static Vec3 turnedAbout(Vec3 feet, double x, double y, double z, int degrees) {
		double yaw = Math.toRadians(degrees);
		return feet.add((x * Math.cos(yaw) - z * Math.sin(yaw)) / 16, y / 16, (x * Math.sin(yaw) + z * Math.cos(yaw)) / 16);
	}

	/**
	 * How far the pod's drill turns in a degree-per-tick, read from two render states a few ticks apart while the pod drills. The
	 * client's copy of the pod is set drilling and set back, as the server does not know.
	 */
	private static double spinRate(ClientGameTestContext context, int id) {
		float[] first = new float[2];
		context.runOnClient(client -> {
			PodEntity pod = pod(client, id);
			pod.setDrilling(true);
			PodGeoRenderState state = renderer(client, id).createRenderState(pod, 0f);
			first[0] = state.drillSpin;
			first[1] = state.ageInTicks;
		});
		context.waitTicks(3); // tick-wait: three ticks of drilling between the two states
		double[] rate = new double[1];
		context.runOnClient(client -> {
			PodEntity pod = pod(client, id);
			PodGeoRenderState state = renderer(client, id).createRenderState(pod, 0f);
			rate[0] = (state.drillSpin - first[0]) / (state.ageInTicks - first[1]);
			pod.setDrilling(false);
		});
		return rate[0];
	}

	private static void cutterIs(ClientGameTestContext context, int id, String cutter) {
		ClientWait.until(context, "pod " + id + " showing the " + cutter, client -> cutter.equals(renderer(client, id).appearanceOf(pod(client, id)).cutter()),
				client -> "shows " + renderer(client, id).appearanceOf(pod(client, id)));
	}

	/** Two registered pods of one charter, side by side in front of the player. */
	private static Scene setUp(MinecraftServer server) {
		ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
		// Face +z, where the pods stand.
		player.teleportTo(player.level(), player.getX(), player.getY(), player.getZ(), Set.of(), 0f, 10f, true);
		require(Charters.found(server, player.getUUID(), "Look Test Charter").isEmpty(), "founding should succeed");
		CharterId charter = Charters.charterOfOrThrow(server, player.getUUID()).orElseThrow().id();
		ServerLevel level = player.level();
		PodEntity mole = spawn(level, PodRegistry.POD, player.position().add(-3, 0, 6));
		PodEntity prospector = spawn(level, PodRegistry.PROSPECTOR, player.position().add(3, 0, 8));
		PodComponents.register(mole, charter);
		PodComponents.register(prospector, charter);
		return new Scene(mole.getId(), prospector.getId(), charter);
	}

	private static PodEntity spawn(ServerLevel level, EntityType<PodEntity> type, Vec3 at) {
		PodEntity pod = type.create(level, EntitySpawnReason.COMMAND);
		pod.setPos(at);
		level.addFreshEntity(pod);
		return pod;
	}

	private static void install(MinecraftServer server, Scene scene, int id, int tier) {
		PodEntity pod = (PodEntity) server.overworld().getEntity(id);
		PodComponents.install(pod, ComponentItems.mint(server, ComponentTrack.DRILL, tier, scene.charter()));
	}

	/** Saves the pod, removes it and loads a new one from the save, as a world does when it loads: the id of the new pod. */
	private static int reload(MinecraftServer server, Scene scene) {
		ServerLevel level = server.overworld();
		PodEntity pod = (PodEntity) level.getEntity(scene.mole());
		HolderLookup.Provider registries = level.registryAccess();
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
		pod.saveWithoutId(output);
		Vec3 where = pod.position().add(0, 0, 4);
		pod.discard();
		Entity loaded = EntityType.create(PodRegistry.POD, TagValueInput.create(ProblemReporter.DISCARDING, registries, output.buildResult()), level,
				EntitySpawnReason.LOAD).orElseThrow(() -> new AssertionError("the saved Mole did not load"));
		loaded.setPos(where);
		level.addFreshEntity(loaded);
		return loaded.getId();
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
