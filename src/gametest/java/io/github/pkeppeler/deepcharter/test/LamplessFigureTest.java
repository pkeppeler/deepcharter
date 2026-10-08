package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.CreatureTuning;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import io.github.pkeppeler.deepcharter.creature.LamplessFigures;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.StructureKind;
import io.github.pkeppeler.deepcharter.layer.StructureSite;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;

/**
 * Server GameTests for #83, the placeholder lampless figure. Most tests build a short rail corridor in a far chunk of the
 * test world, with the figure walking it; the spawn tests use a {@link StructureSite} and, for the wiring, a real rail site of layer 2.
 */
public class LamplessFigureTest {
	/** The corridor's half length along its rail; it is 13 blocks, wholly inside one chunk, so one forced chunk holds it. */
	private static final int HALF = 6;
	private static final int FIRST_SLOT_X = 6008;
	private static final int SLOT_Z_SPACING = 64;
	private static final int FLOOR_Y = 100;
	private static final int WALK_TICKS = 400;
	private static final int WINDOW_TICKS = 20;
	/** Blocks of path in {@link #WINDOW_TICKS} below which the figure has stopped; it walks about three times as far. */
	private static final double MIN_WINDOW_PATH = 0.5;
	/** Blocks in a tick below which the figure is standing, as it does for a tick at a wall. */
	private static final double TURN_EPSILON = 1e-4;
	private static final int QUIET_TICKS = 60;
	private static final int LIT_LEVEL = 15;
	private static final int RAIL_SITE_CELL = 9;
	/** The respawn delay (400), a fade and the checks round it, with a margin. */
	private static final int RESPAWN_TEST_TICKS = 800;

	private static final CreatureTuning TUNING = CreatureTuning.DEFAULT;
	/** Ticks from a cause to the end of the figure: the next check, the fade, and a margin. */
	private static final int FADE_BUDGET_TICKS = TUNING.fadeCheckTicks() + TUNING.fadeTicks() + 10;

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + WALK_TICKS + 40)
	public void itWalksTheRailAndNeverStops(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos origin = slot(0);
		List<Vec3> track = new ArrayList<>();
		LamplessFigure[] figure = {null};
		helper.onEachTick(() -> {
			if (figure[0] == null || track.size() >= WALK_TICKS) {
				return;
			}
			if (!figure[0].isAlive()) {
				throw failure(helper, "the figure was removed after %d ticks of walking", track.size());
			}
			track.add(figure[0].position());
			if (track.size() < WALK_TICKS) {
				return;
			}
			figure[0].discard();
			expectNeverStopped(helper, track);
			int turns = 0;
			double lastDirection = 0;
			for (int i = 1; i < track.size(); i++) {
				double step = track.get(i).z - track.get(i - 1).z;
				if (Math.abs(step) > TURN_EPSILON) {
					turns += lastDirection * step < 0 ? 1 : 0;
					lastDirection = step;
				}
			}
			if (turns < 2) {
				throw failure(helper, "the figure turned %d times in a 13 block corridor over %d ticks", turns, WALK_TICKS);
			}
			for (Vec3 position : track) {
				if (Math.abs(position.x - (origin.getX() + 0.5)) > 1 || Math.abs(position.z - (origin.getZ() + 0.5)) > HALF + 1) {
					throw failure(helper, "the figure left the rail at %s (rail centre %s)", position, origin.toShortString());
				}
			}
			helper.succeed();
		});
		FarChunks.awaitEntityTicking(helper, level, origin, () -> {
			buildCorridor(level, origin);
			figure[0] = spawnFigure(level, origin, -4, Direction.SOUTH);
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 300)
	public void itNeverHurtsAPodOrTakesDamageThatMatters(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos origin = slot(1);
		LamplessFigure[] figure = {null};
		PodEntity[] pod = {null};
		float[] podHull = {0};
		int[] ticks = {0};
		helper.onEachTick(() -> {
			if (figure[0] == null) {
				return;
			}
			if (++ticks[0] < 200) {
				return;
			}
			float hull = pod[0].hull();
			float health = figure[0].getHealth();
			float maxHealth = figure[0].getMaxHealth();
			figure[0].discard();
			pod[0].discard();
			if (hull != podHull[0]) {
				throw failure(helper, "the pod's hull went from %s to %s while the figure walked into it", podHull[0], hull);
			}
			if (health != maxHealth) {
				throw failure(helper, "the figure's health went from %s to %s", maxHealth, health);
			}
			helper.succeed();
		});
		FarChunks.awaitEntityTicking(helper, level, origin, () -> {
			buildCorridor(level, origin);
			figure[0] = spawnFigure(level, origin, 0, Direction.SOUTH);
			if (figure[0].getAttribute(Attributes.ATTACK_DAMAGE) != null) {
				throw failure(helper, "the figure has an attack damage attribute");
			}
			for (DamageSource source : List.of(level.damageSources().generic(), level.damageSources().magic(), level.damageSources().inWall())) {
				if (figure[0].hurtServer(level, source, 1000f)) {
					throw failure(helper, "the figure took damage from %s", source.getMsgId());
				}
			}
			pod[0] = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
			pod[0].setPos(Vec3.atBottomCenterOf(origin.south(3)));
			level.addFreshEntity(pod[0]);
			podHull[0] = pod[0].hull();
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + QUIET_TICKS + 300)
	public void itFadesWhenAPlayerApproachesAndHurtsNobody(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos origin = slot(2);
		FadeRun run = new FadeRun();
		helper.onEachTick(() -> run.step(helper, level, () -> {
			MockPlayer mock = MockPlayers.join(helper, "figure-approach");
			mock.teleportTo(level, new Vec3(origin.getX() - 1.5, origin.getY(), origin.getZ() + 0.5), 0f, 0f);
			run.player = mock.player();
		}));
		FarChunks.awaitEntityTicking(helper, level, origin, () -> {
			buildCorridor(level, origin);
			run.figure = spawnFigure(level, origin, -4, Direction.SOUTH);
		});
		run.onDone = () -> {
			if (run.player.getHealth() != run.player.getMaxHealth()) {
				throw failure(helper, "the approaching player's health went from %s to %s", run.player.getMaxHealth(), run.player.getHealth());
			}
		};
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + QUIET_TICKS + 300)
	public void itFadesWhenLit(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos origin = slot(3);
		FadeRun run = new FadeRun();
		helper.onEachTick(() -> run.step(helper, level, () -> level.setBlock(origin.offset(2, 1, 0), light(LIT_LEVEL), 3)));
		FarChunks.awaitEntityTicking(helper, level, origin, () -> {
			buildCorridor(level, origin);
			run.figure = spawnFigure(level, origin, -4, Direction.SOUTH);
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + RESPAWN_TEST_TICKS)
	public void aSiteHoldsOneFigureAndAnotherComesLaterOnTheRail(GameTestHelper helper) {
		// The Nether, because the delay after a fade is kept for each level and the other tests fade figures in the Overworld.
		ServerLevel level = helper.getLevel().getServer().getLevel(Level.NETHER);
		BlockPos origin = slot(4);
		StructureSite site = new StructureSite(StructureKind.RAILS, origin, true, 4, 0L);
		LamplessFigure[] first = {null};
		int[] phase = {0};
		long[] fadedAt = {0};
		helper.onEachTick(() -> {
			if (first[0] == null) {
				return;
			}
			if (phase[0] == 0 && first[0].isRemoved()) {
				fadedAt[0] = level.getGameTime();
				if (LamplessFigures.spawnAt(level, site).isPresent()) {
					throw failure(helper, "a figure was spawned the tick after one faded");
				}
				level.setBlock(origin.above(), light(TUNING.fadeBlockLight() + 3), 3);
				phase[0] = 1;
			} else if (phase[0] == 1 && level.getGameTime() - fadedAt[0] > TUNING.respawnDelayTicks() + 5) {
				LamplessFigure second = LamplessFigures.spawnAt(level, site)
						.orElseThrow(() -> failure(helper, "no figure came back %d ticks after the first faded", level.getGameTime() - fadedAt[0]));
				BlockPos feet = second.blockPosition();
				second.discard();
				if (!level.getBlockState(feet).is(BlockTags.RAILS)) {
					throw failure(helper, "the second figure stands at %s, which is not a rail", feet.toShortString());
				}
				if (level.getBrightness(LightLayer.BLOCK, feet) >= TUNING.fadeBlockLight()) {
					throw failure(helper, "the second figure appeared at %s, where the light is %d", feet.toShortString(), level.getBrightness(LightLayer.BLOCK, feet));
				}
				helper.succeed();
			}
		});
		FarChunks.awaitEntityTicking(helper, level, origin, () -> {
			buildCorridor(level, origin);
			first[0] = LamplessFigures.spawnAt(level, site).orElseThrow(() -> failure(helper, "no figure was spawned on a rail site"));
			if (!level.getBlockState(first[0].blockPosition()).is(BlockTags.RAILS)) {
				throw failure(helper, "the figure stands at %s, which is not a rail", first[0].blockPosition().toShortString());
			}
			if (LamplessFigures.spawnAt(level, site).isPresent()) {
				throw failure(helper, "a second figure was spawned on a site that holds one");
			}
			level.setBlock(origin.above(), light(LIT_LEVEL), 3);
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 1200)
	public void aLoadedRailSiteOfLayerTwoGetsOneFigureWhenAPlayerIsNear(GameTestHelper helper) {
		ServerLevel level = helper.getLevel().getServer().getLevel(LayerChain.dimension(StructureKind.RAILS.layer()));
		BlockPos conduit = Colony.anchor(level.getServer(), ColonyAnchor.CONDUIT).orElseThrow(() -> failure(helper, "the colony was not built"));
		StructureSite site = StructureSite.in(level.getSeed(), StructureKind.RAILS, level.getMinY(), level.getHeight(), RAIL_SITE_CELL, RAIL_SITE_CELL, conduit);
		BoundingBox bounds = site.bounds();
		long[] firstSeen = {-1};
		helper.onEachTick(() -> {
			if (firstSeen[0] < 0 && figuresIn(level, site).isEmpty()) {
				return;
			}
			if (firstSeen[0] < 0) {
				firstSeen[0] = level.getGameTime();
			}
			List<? extends LamplessFigure> here = figuresIn(level, site);
			int inLevel = level.getEntities(CreatureRegistry.LAMPLESS_FIGURE, figure -> true).size();
			if (here.size() > TUNING.maxPerSite() || inLevel > TUNING.maxPerLevel()) {
				throw failure(helper, "%d figures on the rail site and %d in the level", here.size(), inLevel);
			}
			if (level.getGameTime() - firstSeen[0] >= 3L * TUNING.spawnIntervalTicks() && here.size() == 1) {
				here.forEach(LamplessFigure::discard);
				helper.succeed();
			}
		});
		FarChunks.awaitEntityTicking(helper, level, site.origin(), () -> {
			for (int chunkX = bounds.minX() >> 4; chunkX <= bounds.maxX() >> 4; chunkX++) {
				for (int chunkZ = bounds.minZ() >> 4; chunkZ <= bounds.maxZ() >> 4; chunkZ++) {
					level.setChunkForced(chunkX, chunkZ, true);
					level.getChunk(chunkX, chunkZ, ChunkStatus.FULL);
				}
			}
			MockPlayer mock = MockPlayers.join(helper, "figure-watcher");
			mock.player().setGameMode(GameType.SPECTATOR);
			mock.teleportTo(level, Vec3.atBottomCenterOf(site.origin().above()), 0f, 0f);
		});
	}

	/** A figure that walks undisturbed for {@link #QUIET_TICKS}, then meets a cause, and must fade out and be gone. */
	private static final class FadeRun {
		LamplessFigure figure;
		ServerPlayer player;
		Runnable onDone = () -> { };
		private int ticks;
		private int causeAt = -1;
		private boolean seenFading;

		void step(GameTestHelper helper, ServerLevel level, Runnable cause) {
			if (figure == null) {
				return;
			}
			ticks++;
			if (ticks < QUIET_TICKS) {
				if (figure.isFading() || !figure.isAlive()) {
					throw failure(helper, "the figure faded after %d ticks with nothing near it", ticks);
				}
				return;
			}
			if (causeAt < 0) {
				causeAt = ticks;
				cause.run();
				return;
			}
			seenFading |= figure.isFading();
			if (figure.isRemoved()) {
				if (!seenFading) {
					throw failure(helper, "the figure vanished without a fade");
				}
				onDone.run();
				helper.succeed();
			} else if (ticks - causeAt > FADE_BUDGET_TICKS) {
				figure.discard();
				throw failure(helper, "the figure had not faded %d ticks after the cause (fading: %s)", ticks - causeAt, seenFading);
			}
		}
	}

	private static List<? extends LamplessFigure> figuresIn(ServerLevel level, StructureSite site) {
		BoundingBox box = site.bounds();
		return level.getEntities(CreatureRegistry.LAMPLESS_FIGURE, figure -> box.isInside(figure.blockPosition()));
	}

	private static void expectNeverStopped(GameTestHelper helper, List<Vec3> track) {
		for (int start = 0; start + WINDOW_TICKS < track.size(); start += WINDOW_TICKS) {
			double path = 0;
			for (int i = start + 1; i <= start + WINDOW_TICKS; i++) {
				path += track.get(i).distanceTo(track.get(i - 1));
			}
			if (path < MIN_WINDOW_PATH) {
				throw failure(helper, "the figure walked %s blocks in ticks %d to %d", path, start, start + WINDOW_TICKS);
			}
		}
	}

	private static BlockPos slot(int index) {
		return new BlockPos(FIRST_SLOT_X, FLOOR_Y, FIRST_SLOT_X + index * SLOT_Z_SPACING);
	}

	/** A closed corridor along z with a rail down its middle: {@code origin} is the rail at the middle of it. */
	private static void buildCorridor(ServerLevel level, BlockPos origin) {
		for (int u = -HALF - 1; u <= HALF + 1; u++) {
			for (int v = -3; v <= 3; v++) {
				for (int y = -1; y <= 4; y++) {
					boolean shell = y == -1 || y == 4 || Math.abs(u) == HALF + 1 || Math.abs(v) == 3;
					level.setBlock(origin.offset(v, y, u), (shell ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 3);
				}
			}
		}
		for (int u = -HALF; u <= HALF; u++) {
			level.setBlock(origin.offset(0, 0, u), Blocks.RAIL.defaultBlockState(), 3);
		}
	}

	private static LamplessFigure spawnFigure(ServerLevel level, BlockPos origin, int along, Direction heading) {
		LamplessFigure figure = CreatureRegistry.LAMPLESS_FIGURE.create(level, EntitySpawnReason.COMMAND);
		figure.setPos(Vec3.atBottomCenterOf(origin.offset(0, 0, along)));
		figure.setHeading(heading);
		level.addFreshEntity(figure);
		return figure;
	}

	private static BlockState light(int level) {
		return Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, level);
	}

	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}
}
