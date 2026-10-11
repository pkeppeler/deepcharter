package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientPacks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidenceWorld;
import io.github.pkeppeler.deepcharter.test.support.LayerConceptScenes;
import io.github.pkeppeler.deepcharter.test.support.LayerConceptScenes.Scene;
import io.github.pkeppeler.deepcharter.test.support.LayerConceptScenes.Size;
import io.github.pkeppeler.deepcharter.test.support.LayerConceptScenes.View;

/**
 * The world and the views of the {@code layer-concepts} scenario (#447): one real world, the three scenes of {@link LayerConceptScenes}
 * built in layers 1 and 2 (layer 3's in layer 2's dimension), and a creative, flying camera with the HUD hidden, under the pins of
 * {@link EvidenceWorld}. The camera stands where the scene file says and the still is shot once the light has settled, every chunk in view
 * has rendered and no mob or loose item is in the world. A texture option's test pack is turned on, as a player does in the pack screen, and
 * so is the form pack of the layer being shot, and both are turned off again.
 */
final class LayerConceptsWorld {
	private static final double EYE = 1.62;
	/** The wall-clock limit of one settle. */
	private static final long SETTLE_LIMIT_NANOS = 120_000_000_000L;
	private static final int SETTLE_POLL_TICKS = 2;
	/**
	 * The render distance, in chunks, while the scenario runs: 4 chunks is 64 blocks, past the 40 blocks where the fog ends, so no still
	 * differs, and a pack switch re-meshes a fraction of the chunks. On a loaded machine the full distance made the Vulkan renderer time out
	 * ("5s timeout reached when waiting for VK semaphore") on the first frames after a switch.
	 */
	private static final int RENDER_DISTANCE = 4;
	private static final int SETTLE_STABLE_POLLS = 5;

	private final ClientGameTestContext ctx;
	private final TestSingleplayerContext sp;
	private final Consumer<String> shoot;
	private final List<Scene> scenes;
	private final Size size;
	private int stills;
	private ResourceKey<Level> parkDimension;
	private Vec3 parkPosition;

	private LayerConceptsWorld(ClientGameTestContext ctx, TestSingleplayerContext sp, Consumer<String> shoot, List<Scene> scenes, Size size) {
		this.ctx = ctx;
		this.sp = sp;
		this.shoot = shoot;
		this.scenes = scenes;
		this.size = size;
	}

	/**
	 * Opens the world, pins it, builds the scenes, hides the HUD and hands the world to {@code body}. The HUD comes back in a finally,
	 * because a full-suite run goes on to tests that draw on it.
	 */
	static void open(ClientGameTestContext ctx, Consumer<String> shoot, Consumer<LayerConceptsWorld> body) {
		ClientChecks.withHudHidden(ctx, () -> openHidden(ctx, shoot, body));
	}

	private static void openHidden(ClientGameTestContext ctx, Consumer<String> shoot, Consumer<LayerConceptsWorld> body) {
		int renderDistance = ctx.computeOnClient(client -> client.options.renderDistance().get());
		try (TestSingleplayerContext singleplayer = ctx.worldBuilder().setUseConsistentSettings(false)
				.adjustSettings(state -> state.setSeed("deepcharter-design-tour")).create()) {
			ClientWait.until(ctx, "the player in the world", client -> client.player != null && client.level != null);
			EvidenceWorld.pin(ctx, singleplayer);
			List<Scene> scenes = singleplayer.getServer().computeOnServer(LayerConceptScenes::read);
			Size size = singleplayer.getServer().computeOnServer(LayerConceptScenes::size);
			LayerConceptsWorld world = new LayerConceptsWorld(ctx, singleplayer, shoot, scenes, size);
			world.preparePlayer();
			ctx.runOnClient(client -> {
				client.options.renderDistance().set(RENDER_DISTANCE);
				client.options.setCameraType(CameraType.FIRST_PERSON);
			});
			world.buildScenes();
			body.accept(world);
		} finally {
			ctx.runOnClient(client -> client.options.renderDistance().set(renderDistance));
		}
	}

	/**
	 * Shoots every view of the three layers with the texture option's pack on, and off again after, in a finally. A block has one blockstate,
	 * so each layer is shot with its own form pack (its Company Rock and breach crust) on as well, and off again after it.
	 */
	void shootStyle(String style, String pack, Map<Integer, String> formPacks) {
		park();
		ClientPacks.enable(ctx, pack);
		try {
			for (Scene scene : scenes) {
				String form = formPacks.get(scene.layer());
				park();
				ClientPacks.enable(ctx, form);
				try {
					shootScene(style, scene);
				} finally {
					park();
					ClientPacks.disable(ctx, form);
				}
			}
		} finally {
			park();
			ClientPacks.disable(ctx, pack);
		}
	}

	/**
	 * Takes the player back to where the world put them, out of the scenes, before a pack is switched. A switch re-meshes every loaded
	 * chunk on the same worker pool that compiles the render pipelines, and the heavy scenes (models of dozens of faces to a block) queued
	 * so much work that the first pipeline the next screen asked for (a loading screen's blur) did not compile for a quarter of an hour on a
	 * loaded machine, and the run hung. At the spawn there is almost nothing to re-mesh.
	 */
	private void park() {
		serverDo(server -> player(server).teleportTo(server.getLevel(parkDimension), parkPosition.x, parkPosition.y, parkPosition.z, Set.of(), 0.0f, 0.0f, true));
		ClientWait.until(ctx, "the player parked at " + parkPosition + " in " + parkDimension,
				client -> client.level.dimension().equals(parkDimension) && client.player.distanceToSqr(parkPosition) < 1.0);
	}

	private void shootScene(String style, Scene scene) {
		serverDo(server -> LayerConceptScenes.startLava(server, scene));
		for (View view : scene.views()) {
			serverDo(server -> LayerConceptScenes.setAtmosphere(server, scene, size, view.light()));
			serverDo(server -> LayerConceptScenes.setCameraLamp(server, scene, view, true));
			look(scene, view);
			still("%s-layer%d-%s".formatted(style, scene.layer(), view.name()));
			serverDo(server -> LayerConceptScenes.setCameraLamp(server, scene, view, false));
		}
		// Build the scene again, which wipes its lava: a flow that nobody watches any more would go on costing ticks while the later scenes
		// are shot, and a loaded machine ran out of time on them.
		serverDo(server -> LayerConceptScenes.place(server, scene, size));
	}

	private void buildScenes() {
		for (Scene scene : scenes) {
			serverDo(server -> LayerConceptScenes.place(server, scene, size));
		}
	}

	private void preparePlayer() {
		serverDo(server -> {
			ServerPlayer player = player(server);
			parkDimension = player.level().dimension();
			parkPosition = player.position();
			player.setGameMode(GameType.CREATIVE);
			player.getInventory().clearContent();
			player.getAbilities().mayfly = true;
			player.getAbilities().flying = true;
			player.onUpdateAbilities();
			player.setPermanentlyInvulnerable(true);
		});
	}

	/** Puts the camera at the view's eye in the scene's layer, looking at its target, then waits the view's ticks. */
	private void look(Scene scene, View view) {
		Vec3 eye = scene.absolute(view.eye());
		Vec3 d = scene.absolute(view.target()).subtract(eye);
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
		serverDo(server -> {
			ServerPlayer player = player(server);
			player.setNoGravity(true);
			ServerLevel level = scene.level(server);
			player.teleportTo(level, eye.x, eye.y - EYE, eye.z, Set.of(), yaw, pitch, true);
		});
		ClientWait.until(ctx, "the camera at " + eye + " in the scene of layer " + scene.layer(),
				client -> client.level.dimension().equals(LayerChain.dimension(scene.dimension()))
						&& client.player.distanceToSqr(eye.x, eye.y - EYE, eye.z) < 0.0001
						&& Math.abs(Mth.wrapDegrees(client.player.getYRot() - yaw)) < 0.01f && Math.abs(client.player.getXRot() - pitch) < 0.01f);
		ctx.waitTicks(view.settle());
	}

	/**
	 * Waits, on a wall-clock limit, until the light has settled, every chunk section in view is rendered, and no mob or loose item is in the
	 * world, for {@link #SETTLE_STABLE_POLLS} looks in a row; then shoots the still.
	 */
	private void still(String stillName) {
		long deadline = System.nanoTime() + SETTLE_LIMIT_NANOS;
		int stable = 0;
		while (stable < SETTLE_STABLE_POLLS) {
			serverDo(server -> server.getAllLevels().forEach(level -> level.getAllEntities().forEach(entity -> {
				if (stray(entity)) {
					entity.discard();
				}
			})));
			ctx.runOnClient(client -> client.particleEngine.clearParticles());
			ctx.waitTicks(SETTLE_POLL_TICKS);
			boolean lit = serverGet(server -> {
				for (ServerLevel level : server.getAllLevels()) {
					if (level.getLightEngine().hasLightWork()) {
						return false;
					}
				}
				return true;
			});
			stable = lit && ctx.computeOnClient(LayerConceptsWorld::rendered) ? stable + 1 : 0;
			if (System.nanoTime() > deadline) {
				throw new AssertionError("still " + stillName + ": the world did not settle in " + SETTLE_LIMIT_NANOS / 1_000_000_000L + " s");
			}
		}
		shoot.accept(stillName);
		stills++;
	}

	/** How many stills this world has shot. */
	int stills() {
		return stills;
	}

	private static boolean rendered(Minecraft client) {
		for (Entity entity : client.level.entitiesForRendering()) {
			if (stray(entity)) {
				return false;
			}
		}
		int radius = client.options.getEffectiveRenderDistance();
		int centreX = client.player.chunkPosition().x();
		int centreZ = client.player.chunkPosition().z();
		for (int x = centreX - radius; x <= centreX + radius; x++) {
			for (int z = centreZ - radius; z <= centreZ + radius; z++) {
				if (client.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) == null) {
					return false;
				}
			}
		}
		return client.levelRenderer.hasRenderedAllSections();
	}

	private static boolean stray(Entity entity) {
		return entity instanceof Mob || entity instanceof ItemEntity;
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().getFirst();
	}

	private <T> T serverGet(Function<MinecraftServer, T> action) {
		return sp.getServer().computeOnServer(action::apply);
	}

	private void serverDo(Consumer<MinecraftServer> action) {
		sp.getServer().runOnServer(action::accept);
	}
}
