package io.github.pkeppeler.deepcharter.test.evidence;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTowing;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;

/**
 * Evidence scenario "tow-sizes" (#399): a tow cable between pods of different sizes, seen from above. A towed pod trails its tower by
 * half of each hull's width and a gap, so the gap between the two hulls is the same for a Mole behind a Mole, a Prospector behind a
 * Mole and a Prospector behind a Prospector. The pods stand still and the towed pod starts far off, so the cable pulls it in.
 */
public class TowSizesScenario extends EvidenceScenario {
	private static final int X = 1200;
	private static final int Z = 1200;
	private static final int FLOOR_Y = 200;
	/** Blocks between the rows of pods. */
	private static final int ROW_SPACING = 7;
	/** Centre to centre, inside the cable's reach and past every trail. */
	private static final double FAR = 6.0;
	private static final double EYE_HEIGHT = 13;
	private static final int SETTLE_TICKS = 80;

	/** The towed pods, by UUID: a pod in a chunk that unloads and loads again is a new entity. */
	private static final List<UUID> TOWED = new ArrayList<>();

	@Override
	protected String name() {
		return "tow-sizes";
	}

	@Override
	protected void run(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			TOWED.clear();
			singleplayer.getServer().runOnServer(server -> {
				ServerLevel level = server.overworld();
				for (int x = X - 12; x <= X + 6; x++) {
					for (int z = Z - 6; z <= Z + 3 * ROW_SPACING; z++) {
						level.setBlock(new BlockPos(x, FLOOR_Y - 1, z), Blocks.STONE.defaultBlockState(), 3);
						for (int y = FLOOR_Y; y <= FLOOR_Y + 8; y++) {
							level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
						}
					}
				}
				row(level, 0, PodRegistry.POD, PodRegistry.POD, "MOLE tows MOLE");
				row(level, 1, PodRegistry.POD, PodRegistry.PROSPECTOR, "MOLE tows PROSPECTOR");
				row(level, 2, PodRegistry.PROSPECTOR, PodRegistry.PROSPECTOR, "PROSPECTOR tows PROSPECTOR");
				var player = server.getPlayerList().getPlayers().getFirst();
				player.setGameMode(GameType.SPECTATOR);
				Vec3 eye = new Vec3(X - 2, FLOOR_Y + EYE_HEIGHT, Z + ROW_SPACING);
				player.teleportTo(level, eye.x, eye.y, eye.z, Set.of(), 0f, 90f, true);
			});
			context.waitTicks(SETTLE_TICKS);
			ClientWait.until(context, "every section in view rendered", client -> client.levelRenderer.hasRenderedAllSections());
			context.waitTicks(10);
			screenshot(context, "tow-sizes");
			frame(context);
			singleplayer.getServer().runOnServer(server -> TOWED.forEach(id -> {
				PodEntity towed = (PodEntity) server.overworld().getEntity(id);
				PodEntity tower = PodTowing.tower(towed).orElseThrow();
				double centres = tower.position().distanceTo(towed.position());
				DeepCharter.LOGGER.info("[tow-sizes] {} behind {}: centres {} apart, hulls {} apart", towed.chassis().id(), tower.chassis().id(),
						String.format("%.2f", centres), String.format("%.2f", centres - (towed.chassis().width() + tower.chassis().width()) / 2));
			}));
		}
	}

	private static void row(ServerLevel level, int row, EntityType<PodEntity> towerType, EntityType<PodEntity> towedType, String label) {
		double z = Z + row * ROW_SPACING;
		PodEntity tower = spawn(level, towerType, new Vec3(X, FLOOR_Y, z), label);
		PodEntity towed = spawn(level, towedType, new Vec3(X - FAR, FLOOR_Y, z), null);
		PodTowing.attach(tower, towed);
		TOWED.add(towed.getUUID());
	}

	private static PodEntity spawn(ServerLevel level, EntityType<PodEntity> type, Vec3 at, String name) {
		PodEntity pod = type.create(level, EntitySpawnReason.COMMAND);
		pod.setPos(at);
		if (name != null) {
			pod.setCustomName(Component.literal(name));
			pod.setCustomNameVisible(true);
		}
		level.addFreshEntity(pod);
		return pod;
	}
}
