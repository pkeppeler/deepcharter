package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerStructures;
import io.github.pkeppeler.deepcharter.layer.StructureSite;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;

/**
 * Evidence scenario "m2-prospector-chassis" for #82. First PROSPECTOR-0002's wreck at the site nearest the Conduit, with its lamp
 * still burning and Note N10 on the table. Then two players ride a Prospector: a mock pilot drives it and drills a 3 x 3 bore
 * down, and the real client rides as the navigator in third person, with only the scanner on its screen.
 */
public class ProspectorChassisScenario extends EvidenceScenario {
	private static final int SETTLE_TICKS = 80;
	private static final int TICKS_PER_FRAME = 3;
	private static final int ORBIT_FRAMES = 8;
	private static final double ORBIT_RADIUS = 5;
	private static final int X = 1500;
	private static final int Z = 1500;
	private static final int FLOOR_Y = 200;
	private static final int SLAB_RADIUS = 10;
	private static final int SLAB_DEPTH = 40;
	/** Chat lines (the join messages) stay on screen for 10 seconds. */
	private static final int CHAT_FADE_TICKS = 220;
	private static final int DRIVE_TICKS = 30;
	private static final int DRILL_TICKS_PER_FRAME = 5;
	private static final int MAX_DRILL_TICKS = 900;
	private static final double DRILL_DEPTH = 6;
	private static final float LOOK_DOWN = 40f;
	/** The navigator looks along the slab, across the pod, so that the camera behind sees both riders side by side. */
	private static final float SIDE_ON = 90f;
	private static final Input FORWARD = new Input(true, false, false, false, false, false, false);
	private static final Input SPRINT = new Input(false, false, false, false, false, false, true);

	@Override
	protected String name() {
		return "m2-prospector-chassis";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		wreck(context);
		ride(context);
	}

	/** The wreck at the site nearest the Conduit: the pod, the lamp that still burns, and Ines's log. */
	private void wreck(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitFor(client -> client.player != null && client.level != null);
			StructureSite site = singleplayer.getServer().computeOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				player.setPermanentlyInvulnerable(true);
				player.getInventory().clearContent();
				player.getAbilities().mayfly = true;
				player.getAbilities().flying = true;
				player.onUpdateAbilities();
				player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false));
				StructureSite prospector = LayerStructures.prospector(server).orElseThrow(() -> new AssertionError("the colony was not built when the world started"));
				ServerLevel level = server.getLevel(LayerChain.dimension(prospector.kind().layer()));
				BoundingBox box = prospector.bounds();
				for (int chunkX = box.minX() >> 4; chunkX <= box.maxX() >> 4; chunkX++) {
					for (int chunkZ = box.minZ() >> 4; chunkZ <= box.maxZ() >> 4; chunkZ++) {
						level.getChunk(chunkX, chunkZ, ChunkStatus.FULL);
					}
				}
				return prospector;
			});
			Vec3 pod = at(site, 0, 1.2, 0);
			for (int i = 0; i < ORBIT_FRAMES; i++) {
				double angle = 2 * Math.PI * i / ORBIT_FRAMES;
				fly(singleplayer, site, at(site, ORBIT_RADIUS * Math.cos(angle), 2, ORBIT_RADIUS * Math.sin(angle)), pod);
				context.waitTicks(i == 0 ? SETTLE_TICKS : TICKS_PER_FRAME);
				if (i == 0) {
					screenshot(context, "prospector-0002-wreck");
				}
				frame(context);
			}
			// The lamp on the far side from the table, then the table with Ines's log.
			fly(singleplayer, site, at(site, -1, 1.8, -3), at(site, 3, 0.8, 2));
			context.waitTicks(SETTLE_TICKS);
			screenshot(context, "the-lamp-still-burning");
			frame(context);
			fly(singleplayer, site, at(site, 1.5, 1.6, 6), at(site, 0, 1.2, 4));
			context.waitTicks(SETTLE_TICKS);
			screenshot(context, "note-n10-on-the-table");
			frame(context);
			// The three Cicatrium in the corners of the bay: the catalyst of the restore.
			fly(singleplayer, site, at(site, 0, 2.5, -1), at(site, -5, 0.5, -5));
			context.waitTicks(SETTLE_TICKS);
			screenshot(context, "cicatrium-in-the-bay-corner");
			frame(context);
			fly(singleplayer, site, at(site, -1, 2.5, 0), at(site, 5, 0.5, -5));
			context.waitTicks(SETTLE_TICKS);
			screenshot(context, "cicatrium-in-the-far-corner");
			frame(context);
		}
	}

	/** Two players on one Prospector: the mock pilots it across a slab and drills down; the real client navigates. */
	private void ride(ClientGameTestContext context) {
		try (TwoPlayerServer two = TwoPlayerServer.start(context)) {
			UUID[] pilot = {null};
			two.server().runOnServer(server -> buildSlab(server.overworld(), two));
			// The client must have the slab before the players board, or the boarding reaches it before the pod does.
			context.waitFor(client -> client.level.getBlockState(new BlockPos(X, FLOOR_Y - 1, Z)).is(Blocks.STONE));
			two.server().runOnServer(server -> pilot[0] = board(server.overworld(), two));
			context.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
			context.waitTicks(CHAT_FADE_TICKS);
			look(context, two);
			screenshot(context, "two-players-ride-the-prospector");

			two.server().runOnServer(server -> two.mock().setInput(FORWARD));
			for (int ticks = 0; ticks < DRIVE_TICKS; ticks += TICKS_PER_FRAME) {
				context.waitTicks(TICKS_PER_FRAME);
				look(context, two);
			}
			two.server().runOnServer(server -> two.mock().releaseInput());
			context.waitTicks(10);
			look(context, two);

			double startY = podY(two, pilot[0]);
			two.server().runOnServer(server -> two.mock().setInput(SPRINT));
			int ticks = 0;
			boolean shot = false;
			while (startY - podY(two, pilot[0]) < DRILL_DEPTH && ticks < MAX_DRILL_TICKS) {
				context.waitTicks(DRILL_TICKS_PER_FRAME);
				ticks += DRILL_TICKS_PER_FRAME;
				look(context, two);
				if (!shot && startY - podY(two, pilot[0]) >= 1) {
					screenshot(context, "the-3x3-bore");
					shot = true;
				}
			}
			two.server().runOnServer(server -> two.mock().releaseInput());
			if (startY - podY(two, pilot[0]) < DRILL_DEPTH) {
				throw new AssertionError("The Prospector did not drill " + DRILL_DEPTH + " blocks down within " + MAX_DRILL_TICKS + " ticks");
			}
			for (int i = 0; i < 6; i++) {
				context.waitTicks(TICKS_PER_FRAME);
				look(context, two);
			}
			screenshot(context, "the-bore-from-the-navigators-seat");
		}
	}

	/** One frame with no toast or chat line over the pod. */
	private void look(ClientGameTestContext context, TwoPlayerServer two) {
		context.runOnClient(client -> {
			client.gui.toastManager().clear();
			client.player.setXRot(LOOK_DOWN);
		});
		frame(context);
	}

	private static double podY(TwoPlayerServer two, UUID pod) {
		return two.server().computeOnServer(server -> server.overworld().getEntity(pod).getY());
	}

	/** Builds the slab and puts both players on it. */
	private static void buildSlab(ServerLevel level, TwoPlayerServer two) {
		fill(level, FLOOR_Y - SLAB_DEPTH, FLOOR_Y - 1, Blocks.STONE);
		fill(level, FLOOR_Y, FLOOR_Y + 10, Blocks.AIR);
		real(level, two).teleportTo(level, X + 0.5, FLOOR_Y, Z - 7.5, Set.of(), SIDE_ON, LOOK_DOWN, true);
		two.mock().teleportTo(level, new Vec3(X + 0.5, FLOOR_Y, Z - 6.5), 0f, 0f);
	}

	private static ServerPlayer real(ServerLevel level, TwoPlayerServer two) {
		return level.getServer().getPlayerList().getPlayers().stream().filter(player -> player != two.mock().player()).findFirst().orElseThrow();
	}

	/** Seats the mock first and the real player second in a new Prospector. Returns the pod's UUID. */
	private static UUID board(ServerLevel level, TwoPlayerServer two) {
		ServerPlayer real = real(level, two);
		PodEntity pod = PodRegistry.PROSPECTOR.create(level, EntitySpawnReason.COMMAND);
		pod.setPos(X + 0.5, FLOOR_Y, Z - 6.5);
		pod.setFuel(100f);
		pod.setCustomName(Component.literal("PROSPECTOR"));
		pod.setCustomNameVisible(true);
		level.addFreshEntity(pod);
		if (!two.mock().player().startRiding(pod) || !real.startRiding(pod)) {
			throw new AssertionError("Both players should board the Prospector");
		}
		return pod.getUUID();
	}

	private static void fill(ServerLevel level, int yFrom, int yTo, Block block) {
		for (int x = X - SLAB_RADIUS; x <= X + SLAB_RADIUS; x++) {
			for (int y = yFrom; y <= yTo; y++) {
				for (int z = Z - SLAB_RADIUS; z <= Z + SLAB_RADIUS; z++) {
					level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 2);
				}
			}
		}
	}

	/** The world position of a point in the structure's own axes, {@code y} up from the floor of its hollow. */
	private static Vec3 at(StructureSite site, double u, double y, double v) {
		BlockPos origin = site.origin();
		return new Vec3(origin.getX() + 0.5 + (site.alongZ() ? v : u), origin.getY() + y, origin.getZ() + 0.5 + (site.alongZ() ? u : v));
	}

	/** Puts the player at {@code at} in the site's layer, looking at {@code target}. */
	private static void fly(TestSingleplayerContext singleplayer, StructureSite site, Vec3 at, Vec3 target) {
		Vec3 d = target.subtract(at);
		float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
		float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
		singleplayer.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			player.setNoGravity(true);
			player.getAbilities().flying = true;
			player.onUpdateAbilities();
			player.teleportTo(server.getLevel(LayerChain.dimension(site.kind().layer())), at.x, at.y, at.z, Set.of(), yaw, pitch, true);
		});
	}
}
