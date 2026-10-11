package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTowing;
import io.github.pkeppeler.deepcharter.test.support.TwoPlayerServer;

/**
 * What the tow-line scenarios (#425) share: a mock pilot in a tower pod with a second pod on its cable, and the real player as a
 * spectator who films them. It is not a scenario itself, so the scenarios stay one class each.
 */
final class TowStage {
	/** Chat lines (the join messages) stay on screen for 10 seconds. */
	static final int CHAT_FADE_TICKS = 220;
	static final int TICKS_PER_FRAME = 3;
	static final Input FORWARD = new Input(true, false, false, false, false, false, false);
	static final Input JUMP = new Input(false, false, false, false, true, false, false);
	static final Input FORWARD_JUMP = new Input(true, false, false, false, true, false, false);
	/** The mock pilot's yaws: it drives south, and turns to drive east or back north. */
	static final float SOUTH = 0f;
	static final float EAST = -90f;
	static final float NORTH = 180f;
	private static final String NO_NAME_TAGS = "no-name-tags";

	private TowStage() {
	}

	/** The real player, who films. */
	static ServerPlayer watcher(ServerLevel level, TwoPlayerServer two) {
		return level.getServer().getPlayerList().getPlayers().stream().filter(player -> player != two.mock().player()).findFirst().orElseThrow();
	}

	/**
	 * Makes the real player a spectator, so it flies free of the pods and walls, and puts it at {@code near}, which keeps the stage's
	 * chunks loaded for the pods. Hides the pilot's name over the tower.
	 */
	static void prepare(ServerLevel level, TwoPlayerServer two, Vec3 near) {
		ServerPlayer watcher = watcher(level, two);
		watcher.setGameMode(GameType.SPECTATOR);
		watcher.teleportTo(level, near.x, near.y, near.z, Set.of(), 90f, 0f, true);
		// The sun stays overhead and out of the frame, so it does not hide the cable or move between two takes.
		for (String command : new String[] {"gamerule advance_time false", "time set noon", "weather clear"}) {
			level.getServer().getCommands().performPrefixedCommand(level.getServer().createCommandSourceStack(), command);
		}
		PlayerTeam team = level.getScoreboard().getPlayerTeam(NO_NAME_TAGS);
		if (team == null) {
			team = level.getScoreboard().addPlayerTeam(NO_NAME_TAGS);
		}
		team.setNameTagVisibility(Team.Visibility.NEVER);
		level.getScoreboard().addPlayerToTeam(two.mock().player().getScoreboardName(), team);
	}

	/** Puts the camera at {@code from}, looking at {@code at}. */
	static void film(ServerLevel level, TwoPlayerServer two, Vec3 from, Vec3 at) {
		Vec3 line = at.subtract(from);
		float yaw = (float) (Mth.atan2(-line.x, line.z) * Mth.RAD_TO_DEG);
		float pitch = (float) -(Mth.atan2(line.y, line.horizontalDistance()) * Mth.RAD_TO_DEG);
		watcher(level, two).teleportTo(level, from.x, from.y, from.z, Set.of(), yaw, pitch, true);
	}

	/** The tower's place. Looked up by UUID: a pod whose chunk is unloaded and loaded again is a new entity. */
	static Vec3 where(TwoPlayerServer two, UUID pod) {
		return two.server().computeOnServer(server -> server.overworld().getEntity(pod).position());
	}

	/** Seats the mock pilot in a new tower pod at {@code at}, fuelled, and puts a pod on its cable at {@code towedAt}. Returns the two pods' ids. */
	static UUID[] pair(ServerLevel level, TwoPlayerServer two, Vec3 at, Vec3 towedAt, boolean wreck) {
		two.mock().teleportTo(level, at, SOUTH, 0);
		PodEntity tower = spawn(level, at, "TOWER");
		PodEntity towed = spawn(level, towedAt, "TOWED");
		if (!two.mock().player().startRiding(tower)) {
			throw new AssertionError("the mock pilot could not mount the tower");
		}
		tower.setFuel(100f);
		if (wreck) {
			towed.damageHull(towed.maxHull());
		}
		PodTowing.attach(tower, towed);
		return new UUID[] {tower.getUUID(), towed.getUUID()};
	}

	static PodEntity spawn(ServerLevel level, Vec3 at, String name) {
		PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		pod.setPos(at);
		pod.setCustomName(Component.literal(name));
		pod.setCustomNameVisible(true);
		level.addFreshEntity(pod);
		return pod;
	}

	static void fill(ServerLevel level, int x1, int x2, int y1, int y2, int z1, int z2, Block block) {
		for (int x = x1; x <= x2; x++) {
			for (int y = y1; y <= y2; y++) {
				for (int z = z1; z <= z2; z++) {
					level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 3);
				}
			}
		}
	}
}
