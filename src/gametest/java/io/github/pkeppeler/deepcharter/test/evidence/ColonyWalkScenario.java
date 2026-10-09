package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.List;
import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidenceWorld;

/**
 * Evidence scenario "colony-walk" for #244, the colony rebuild: a player's walk through the town the world builds at spawn, at eye
 * height, in the world of the design tour. It starts where a new player stands, in the Continuity Office, and goes out of its door onto
 * the square, past the terminals and the headframe to the Host, across the square to the Lamp and Pick, whose door faces the square,
 * and west along the street into the hangar's bay. Every stop looks at the thing it is for and holds a moment; the frames are the GIF.
 *
 * <p>Positions are offsets from the colony's centre (the middle of the square, at ground level; X east, Z south), so the walk follows
 * the colony wherever the world's spawn is. The player is moved a step at a time, feet on the ground, never flying, so the view is
 * the one a walking player has. It is a creative, invulnerable camera with the HUD hidden.
 */
public class ColonyWalkScenario extends EvidenceScenario {
	private static final double EYE = 1.62;
	/** Blocks a step covers, and server ticks between two frames: a walking player's pace, 4.5 blocks a second. */
	private static final double STEP = 0.45;
	private static final int TICKS_PER_FRAME = 2;
	/** Frames a stop holds, so each place is seen. */
	private static final int HOLD = 14;
	private static final long SETTLE_LIMIT_NANOS = 120_000_000_000L;
	private static final int SETTLE_POLL_TICKS = 2;
	private static final int SETTLE_STABLE_POLLS = 5;

	private ClientGameTestContext ctx;
	private TestSingleplayerContext sp;
	private BlockPos centre;

	/** A stop on the walk: where the feet are, what the eyes look at, whether the walk holds there, and the still it takes there. */
	private record Stop(double x, double z, Vec3 look, boolean hold, String still) {
		Stop(double x, double z, Vec3 look) {
			this(x, z, look, false, null);
		}
	}

	/** The walk, from the Continuity Office. */
	private static final List<Stop> WALK = List.of(
			new Stop(18, 0, new Vec3(10, 3.5, 0), true, "walk-1-the-continuity-office"),
			new Stop(15, 0, new Vec3(6, 4, 0)),
			new Stop(11, 0, new Vec3(3, 6, -6)),
			new Stop(8, 3, new Vec3(4, 2.8, -8)),
			new Stop(4, -5, new Vec3(4, 2.8, -8), true, "walk-2-the-repair-station"),
			new Stop(-4, -5, new Vec3(-4, 2.8, -8), true, "walk-3-the-ore-processor"),
			new Stop(-4, -5, new Vec3(-4, 14, -14), true, "walk-4-the-headframe-over-the-conduit"),
			new Stop(-7, 2, new Vec3(0, 12, 0)),
			new Stop(0, 9, new Vec3(0, 12, 0), true, "walk-5-the-host"),
			new Stop(3, 10.5, new Vec3(3, 4, 14), true, "walk-6-the-lamp-and-pick"),
			new Stop(-8, 7, new Vec3(-23, 3, 7)),
			new Stop(-14, 7, new Vec3(-23, 2, 7)),
			new Stop(-20, 7, new Vec3(-23, 1.5, 7), true, "walk-7-the-hangar"));

	@Override
	protected String name() {
		return "colony-walk";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		ctx = context;
		// The design tour's world: the campaign's own surface, under the pinned sky.
		try (TestSingleplayerContext singleplayer = context.worldBuilder().setUseConsistentSettings(false)
				.adjustSettings(state -> state.setSeed("deepcharter-design-tour")).create()) {
			sp = singleplayer;
			ClientWait.until(ctx, "the player in the world", client -> client.player != null && client.level != null);
			centre = serverGet(server -> Colony.placed(server).orElseThrow(() -> new AssertionError("the colony was not built")).center());
			EvidenceWorld.pin(ctx, sp);
			setUpCamera();
			try {
				walk();
			} finally {
				ctx.runOnClient(client -> {
					if (client.gui.hud.isHidden()) {
						client.gui.hud.toggle();
					}
				});
			}
		}
	}

	private void setUpCamera() {
		serverDo(server -> {
			ServerPlayer player = player(server);
			player.setGameMode(GameType.CREATIVE);
			player.getInventory().clearContent();
			player.setPermanentlyInvulnerable(true);
			// Feet on the ground, never flying: the view is a walking player's.
			player.setNoGravity(true);
		});
		ctx.runOnClient(client -> {
			client.options.setCameraType(CameraType.FIRST_PERSON);
			if (!client.gui.hud.isHidden()) {
				client.gui.hud.toggle();
			}
		});
	}

	private void walk() {
		Stop here = WALK.getFirst();
		go(here.x(), here.z(), here.look());
		settle("the first stop");
		hold(here);
		for (int i = 1; i < WALK.size(); i++) {
			Stop next = WALK.get(i);
			double distance = Math.hypot(next.x() - here.x(), next.z() - here.z());
			Vec3 lookFrom = here.look();
			int steps = Math.max(1, (int) Math.ceil(distance / STEP));
			if (distance < 0.01) {
				// A turn on the spot: as many frames as a quarter turn takes, so the view swings, not jumps.
				steps = 10;
			}
			for (int step = 1; step <= steps; step++) {
				double t = smooth((double) step / steps);
				go(Mth.lerp(t, here.x(), next.x()), Mth.lerp(t, here.z(), next.z()), lookFrom.lerp(next.look(), t));
				ctx.waitTicks(TICKS_PER_FRAME);
				frame(ctx);
			}
			hold(next);
			here = next;
		}
	}

	/** The stop's frames, held, and its still. */
	private void hold(Stop stop) {
		if (stop.still() != null) {
			settle(stop.still());
			screenshot(ctx, stop.still());
		}
		if (stop.hold()) {
			for (int i = 0; i < HOLD; i++) {
				ctx.waitTicks(TICKS_PER_FRAME);
				frame(ctx);
			}
		}
	}

	private static double smooth(double t) {
		return t * t * (3 - 2 * t);
	}

	/** Puts the player's feet at {@code x}, {@code z} from the centre, on the ground, looking at {@code look}, and waits for the client to stand there. */
	private void go(double x, double z, Vec3 look) {
		Vec3 feet = Vec3.atLowerCornerOf(centre).add(x, 1, z);
		Vec3 target = Vec3.atLowerCornerOf(centre).add(look);
		Vec3 d = target.subtract(feet.add(0, EYE, 0));
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
		serverDo(server -> player(server).teleportTo(server.overworld(), feet.x, feet.y, feet.z, Set.of(), yaw, pitch, true));
		ClientWait.until(ctx, "the camera at " + feet, client -> client.player.distanceToSqr(feet.x, feet.y, feet.z) < 0.0001
				&& Math.abs(Mth.wrapDegrees(client.player.getYRot() - yaw)) < 0.01f && Math.abs(client.player.getXRot() - pitch) < 0.01f);
	}

	/**
	 * Waits, on the wall clock, until the server has no light work, the client holds and has drawn every chunk round the camera, and the
	 * camera has stood still for a few polls. Fails naming the stop after {@link #SETTLE_LIMIT_NANOS}.
	 */
	private void settle(String stop) {
		long deadline = System.nanoTime() + SETTLE_LIMIT_NANOS;
		int stable = 0;
		String lastPose = null;
		while (stable < SETTLE_STABLE_POLLS) {
			ctx.runOnClient(client -> client.particleEngine.clearParticles());
			ctx.waitTicks(SETTLE_POLL_TICKS);
			boolean lit = serverGet(server -> !server.overworld().getLightEngine().hasLightWork());
			boolean drawn = ctx.computeOnClient(client -> chunksLoaded(client) && client.levelRenderer.hasRenderedAllSections());
			String pose = ctx.computeOnClient(client -> client.getCameraEntity().position() + " " + client.getCameraEntity().getYRot());
			stable = lit && drawn && pose.equals(lastPose) ? stable + 1 : 0;
			lastPose = pose;
			if (System.nanoTime() > deadline) {
				throw new AssertionError("walk stop " + stop + ": the world did not settle in " + SETTLE_LIMIT_NANOS / 1_000_000_000L + " s");
			}
		}
	}

	private static boolean chunksLoaded(Minecraft client) {
		int radius = client.options.getEffectiveRenderDistance();
		int x = client.player.chunkPosition().x();
		int z = client.player.chunkPosition().z();
		for (int cx = x - radius; cx <= x + radius; cx++) {
			for (int cz = z - radius; cz <= z + radius; cz++) {
				if (client.level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) == null) {
					return false;
				}
			}
		}
		return true;
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().getFirst();
	}

	private <T> T serverGet(java.util.function.Function<MinecraftServer, T> action) {
		return sp.getServer().computeOnServer(action::apply);
	}

	private void serverDo(java.util.function.Consumer<MinecraftServer> action) {
		sp.getServer().runOnServer(action::accept);
	}
}
