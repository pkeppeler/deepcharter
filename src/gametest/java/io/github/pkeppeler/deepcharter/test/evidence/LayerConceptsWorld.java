package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.test.support.ClientPacks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidenceWorld;
import io.github.pkeppeler.deepcharter.test.support.LayerConceptScenes;
import io.github.pkeppeler.deepcharter.test.support.LayerConceptScenes.Scene;
import io.github.pkeppeler.deepcharter.test.support.LayerConceptScenes.Size;
import io.github.pkeppeler.deepcharter.test.support.LayerConceptScenes.View;

/**
 * The world and the views of the {@code layer-concepts} scenario (#241): one real world, the six scenes of {@link LayerConceptScenes}
 * built in layers 1 and 2, and a creative, flying camera with the HUD hidden, under the pins of {@link EvidenceWorld}. The camera stands
 * where the scene file says and the still is shot once the light has settled, every chunk in view has rendered and no mob or loose
 * item is in the world. An option's test pack is turned on, as a player does in the pack screen, and off again.
 */
final class LayerConceptsWorld {
	private static final double EYE = 1.62;
	/** The wall-clock limit of one settle. */
	private static final long SETTLE_LIMIT_NANOS = 120_000_000_000L;
	private static final int SETTLE_POLL_TICKS = 2;
	private static final int SETTLE_STABLE_POLLS = 5;

	private final ClientGameTestContext ctx;
	private final TestSingleplayerContext sp;
	private final Consumer<String> shoot;
	private final List<Scene> scenes;
	private final Size size;

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
		try (TestSingleplayerContext singleplayer = ctx.worldBuilder().setUseConsistentSettings(false)
				.adjustSettings(state -> state.setSeed("deepcharter-design-tour")).create()) {
			ClientWait.until(ctx, "the player in the world", client -> client.player != null && client.level != null);
			EvidenceWorld.pin(ctx, singleplayer);
			List<Scene> scenes = singleplayer.getServer().computeOnServer(LayerConceptScenes::read);
			Size size = singleplayer.getServer().computeOnServer(LayerConceptScenes::size);
			LayerConceptsWorld world = new LayerConceptsWorld(ctx, singleplayer, shoot, scenes, size);
			world.preparePlayer();
			ctx.runOnClient(client -> {
				client.options.setCameraType(CameraType.FIRST_PERSON);
				if (!client.gui.hud.isHidden()) {
					client.gui.hud.toggle();
				}
			});
			world.buildScenes();
			body.accept(world);
		} finally {
			ctx.runOnClient(client -> {
				if (client.gui.hud.isHidden()) {
					client.gui.hud.toggle();
				}
			});
		}
	}

	/** Shoots every view of both layers of {@code option} with its test pack on, and off again after, in a finally. */
	void shootOption(String option, String pack) {
		ClientPacks.enable(ctx, pack);
		try {
			for (Scene scene : scenes) {
				if (scene.option().equals(option)) {
					shootScene(scene);
				}
			}
		} finally {
			ClientPacks.disable(ctx, pack);
		}
	}

	private void shootScene(Scene scene) {
		serverDo(server -> LayerConceptScenes.startLava(server, scene));
		for (View view : scene.views()) {
			look(scene, view);
			still("%s-layer%d-%s".formatted(scene.option(), scene.layer(), view.name()));
		}
	}

	private void buildScenes() {
		for (Scene scene : scenes) {
			serverDo(server -> LayerConceptScenes.place(server, scene, size));
		}
	}

	private void preparePlayer() {
		serverDo(server -> {
			ServerPlayer player = player(server);
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
		ClientWait.until(ctx, "the camera at " + eye + " in layer " + scene.layer(),
				client -> client.level.dimension().equals(LayerChain.dimension(scene.layer()))
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
