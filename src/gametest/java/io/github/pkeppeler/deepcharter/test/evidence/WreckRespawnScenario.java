package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Set;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.client.CameraType;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayer.RespawnConfig;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;

/**
 * Evidence scenario "m2-wreck-respawn" for #127: a player with a bed set rides a pod far from the colony, the pod becomes a
 * wreck, and the player wakes at the Continuity Office, not at the bed.
 */
public class WreckRespawnScenario extends EvidenceScenario {
	/** How far east of the colony the bed and the pod stand. */
	private static final int DISTANCE = 90;
	private static final int FRAMES_PER_BEAT = 8;

	@Override
	protected String name() {
		return "m2-wreck-respawn";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			context.waitFor(client -> client.player != null && client.level != null);
			context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
			GlobalPos office = singleplayer.getServer().computeOnServer(server -> {
				GlobalPos at = Colony.respawnPoint(server).orElseThrow(() -> new AssertionError("the colony was not built"));
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				// Creative while the far chunks load, so the drop in does not kill the player.
				player.setGameMode(GameType.CREATIVE);
				player.getInventory().clearContent();
				player.teleportTo(server.overworld(), at.pos().getX() + DISTANCE, 200, at.pos().getZ(), Set.of(), 0f, 0f, true);
				return at;
			});
			context.waitTicks(120);

			BlockPos[] bed = {null};
			PodEntity[] pod = {null};
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				ServerLevel level = server.overworld();
				int x = office.pos().getX() + DISTANCE;
				int z = office.pos().getZ();
				int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
				player.teleportTo(level, x + 0.5, ground, z + 0.5, Set.of(), 0f, 0f, true);
				player.resetFallDistance();
				player.setGameMode(GameType.SURVIVAL);
				bed[0] = new BlockPos(x + 3, ground, z + 3);
				BlockState state = Blocks.BED.red().defaultBlockState().setValue(BedBlock.FACING, Direction.EAST);
				level.setBlockAndUpdate(bed[0], state.setValue(BedBlock.PART, BedPart.FOOT));
				level.setBlockAndUpdate(bed[0].east(), state.setValue(BedBlock.PART, BedPart.HEAD));
				player.setRespawnPosition(new RespawnConfig(LevelData.RespawnData.of(Level.OVERWORLD, bed[0], 0f, 0f), false), false);
				pod[0] = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
				pod[0].setPos(x - 3, ground, z + 5);
				level.addFreshEntity(pod[0]);
			});
			context.waitTicks(40);

			// The bed is set, far from the colony.
			look(singleplayer, Vec3.atCenterOf(bed[0]));
			context.waitTicks(10);
			screenshot(context, "bed-is-set");
			beat(context);

			// The player boards the pod.
			look(singleplayer, pod[0].position().add(0, 1, 0));
			context.waitTicks(10);
			singleplayer.getServer().runOnServer(server -> {
				if (!server.getPlayerList().getPlayers().getFirst().startRiding(pod[0], true, false)) {
					throw new AssertionError("the player could not board the pod");
				}
			});
			context.waitTicks(10);
			beat(context);
			screenshot(context, "riding-the-pod");

			// The pod loses its hull and the crew die.
			singleplayer.getServer().runOnServer(server -> pod[0].damageHull(pod[0].maxHull()));
			context.waitForScreen(DeathScreen.class);
			context.waitTicks(25);
			beat(context);
			screenshot(context, "death-screen");

			// The player clicks Respawn.
			context.runOnClient(client -> client.player.respawn());
			context.waitFor(client -> client.gui.screen() == null && client.player != null && client.player.isAlive());
			context.waitTicks(40);
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
				if (player.position().distanceToSqr(Vec3.atBottomCenterOf(office.pos())) > 4) {
					throw new AssertionError("the player should wake at the office " + office.pos() + ", woke at " + player.position());
				}
				if (player.getRespawnConfig() == null || !bed[0].equals(player.getRespawnConfig().respawnData().pos())) {
					throw new AssertionError("the bed should still be the respawn point, it is " + player.getRespawnConfig());
				}
			});
			look(singleplayer, Vec3.atCenterOf(office.pos()).add(8, 3, -8));
			context.waitTicks(10);
			beat(context);
			screenshot(context, "woke-at-the-office");
		}
	}

	private void beat(ClientGameTestContext context) {
		for (int i = 0; i < FRAMES_PER_BEAT; i++) {
			frame(context);
			context.waitTicks(3);
		}
	}

	/** Turns the player to look at {@code target}, where they stand. */
	private static void look(TestSingleplayerContext singleplayer, Vec3 target) {
		singleplayer.getServer().runOnServer(server -> {
			ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
			Vec3 d = target.subtract(player.getEyePosition());
			float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
			float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
			player.teleportTo(player.level(), player.getX(), player.getY(), player.getZ(), Set.of(), yaw, pitch, true);
		});
	}
}
