package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
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

import io.github.pkeppeler.deepcharter.DeepCharter;
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
	/** The respawn delay (400) twice, two fades, the light going out and the checks round them, with a margin. */
	private static final int RESPAWN_TEST_TICKS = 1500;
	/** Ticks a figure has to start fading after a cause: light spreads and a check comes round, both slow on a loaded runner. */
	private static final int FADE_START_BUDGET_TICKS = 200;
	/** Ticks a fade, once started, has to end: it counts one a tick whatever the load, so the margin is small. */
	private static final int FADE_END_BUDGET_TICKS = CreatureTuning.DEFAULT.fadeTicks() + 20;
	/** Test ticks for a fade test after the chunk is ready: the quiet walk, the start of the fade, the fade (40 ticks), and a margin. */
	private static final int FADE_TEST_TICKS = QUIET_TICKS + FADE_START_BUDGET_TICKS + 100;
	/** Ticks a killed or fallen figure has to be gone: the void hurts for 4 a tick and a death takes 20 ticks. */
	private static final int REMOVAL_TICKS = 100;
	private static final String MOD_NAMESPACE = "deepcharter";

	private static final CreatureTuning TUNING = CreatureTuning.DEFAULT;

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

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + REMOVAL_TICKS)
	public void killRemovesIt(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos origin = slot(7);
		LamplessFigure[] figure = {null};
		expectRemovedWithin(helper, () -> figure[0] == null ? null : List.of(figure[0]));
		FarChunks.awaitEntityTicking(helper, level, origin, () -> {
			buildCorridor(level, origin);
			figure[0] = spawnFigure(level, origin, 0, Direction.SOUTH);
			figure[0].kill(level);
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + REMOVAL_TICKS)
	public void fallingOutOfTheWorldRemovesIt(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos origin = slot(8);
		LamplessFigure[] figure = {null};
		expectRemovedWithin(helper, () -> figure[0] == null ? null : List.of(figure[0]));
		FarChunks.awaitEntityTicking(helper, level, origin, () -> {
			buildCorridor(level, origin);
			figure[0] = spawnFigure(level, origin, 0, Direction.SOUTH);
			figure[0].setPos(figure[0].getX(), level.getMinY() - 100, figure[0].getZ());
		});
	}

	/**
	 * The gate for every creature to come: each living entity type of the mod must die to {@code /kill}, which is what keeps a
	 * creature from being unkillable by a blanket {@code isInvulnerableTo}. A type that cannot be made on its own (its factory gives
	 * null) and a type that is no living entity are skipped, and the test names them in its log.
	 */
	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + REMOVAL_TICKS)
	public void everyLivingEntityTypeOfTheModDiesToKill(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		BlockPos origin = slot(9);
		List<LivingEntity> killed = new ArrayList<>();
		expectRemovedWithin(helper, () -> killed.isEmpty() ? null : killed);
		FarChunks.awaitEntityTicking(helper, level, origin, () -> {
			buildCorridor(level, origin);
			List<String> skipped = new ArrayList<>();
			for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
				Identifier id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
				if (!id.getNamespace().equals(MOD_NAMESPACE)) {
					continue;
				}
				Entity entity = type.create(level, EntitySpawnReason.COMMAND);
				if (!(entity instanceof LivingEntity living)) {
					skipped.add(id + (entity == null ? " (cannot be created on its own)" : " (not a living entity)"));
					continue;
				}
				living.setPos(Vec3.atBottomCenterOf(origin));
				level.addFreshEntity(living);
				living.kill(level);
				killed.add(living);
			}
			DeepCharter.LOGGER.info("everyLivingEntityTypeOfTheModDiesToKill: killed {}, skipped {}", killed, skipped);
			if (killed.stream().noneMatch(LamplessFigure.class::isInstance)) {
				throw failure(helper, "the gate did not try the lampless figure; it tried %s", killed);
			}
		});
	}

	@GameTest
	public void waterDoesNotPushItAndALeadDoesNotHoldIt(GameTestHelper helper) {
		LamplessFigure figure = CreatureRegistry.LAMPLESS_FIGURE.create(helper.getLevel(), EntitySpawnReason.COMMAND);
		if (figure.isPushedByFluid()) {
			throw failure(helper, "water pushes the figure");
		}
		if (figure.canBeLeashed()) {
			throw failure(helper, "the figure can be leashed");
		}
		helper.succeed();
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + FADE_TEST_TICKS)
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

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + FADE_TEST_TICKS)
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

	/**
	 * Runs in the Nether, not layer 2, because the delay after a fade is kept for each level: a fade here would hold back the layer 2
	 * tests' own spawns. The wiring of {@code tick()} is covered by the layer 2 test below and the delay logic does not look at the
	 * dimension. The slot is far from every other test's.
	 */
	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + RESPAWN_TEST_TICKS)
	public void aFadeDelaysTheNextFigureUntilTheDelayEndsOrAServerStarts(GameTestHelper helper) {
		ServerLevel level = helper.getLevel().getServer().getLevel(Level.NETHER);
		BlockPos origin = slot(4);
		StructureSite site = new StructureSite(StructureKind.RAILS, origin, true, 4, 0L);
		LamplessFigure[] figure = {null};
		int[] phase = {0};
		long[] fadedAt = {0};
		helper.onEachTick(() -> {
			if (figure[0] == null) {
				return;
			}
			if (phase[0] == 0 && figure[0].isRemoved()) {
				fadedAt[0] = level.getGameTime();
				if (LamplessFigures.spawnAt(level, site).isPresent()) {
					throw failure(helper, "a figure was spawned the tick after one faded");
				}
				level.setBlock(origin.above(), light(TUNING.fadeBlockLight() + 3), 3);
				phase[0] = 1;
			} else if (phase[0] == 1 && level.getGameTime() - fadedAt[0] > TUNING.respawnDelayTicks() + 5) {
				figure[0] = LamplessFigures.spawnAt(level, site)
						.orElseThrow(() -> failure(helper, "no figure came back %d ticks after the first faded", level.getGameTime() - fadedAt[0]));
				BlockPos feet = figure[0].blockPosition();
				if (!level.getBlockState(feet).is(BlockTags.RAILS)) {
					throw failure(helper, "the second figure stands at %s, which is not a rail", feet.toShortString());
				}
				if (level.getBrightness(LightLayer.BLOCK, feet) >= TUNING.fadeBlockLight()) {
					throw failure(helper, "the second figure appeared at %s, where the light is %d", feet.toShortString(), level.getBrightness(LightLayer.BLOCK, feet));
				}
				level.setBlock(origin.above(), light(LIT_LEVEL), 3);
				phase[0] = 2;
			} else if (phase[0] == 2 && figure[0].isRemoved()) {
				level.setBlock(origin.above(), Blocks.AIR.defaultBlockState(), 3);
				phase[0] = 3;
			} else if (phase[0] == 3 && railsAreDark(level, origin)) {
				// The light is out, so only the delay of the second fade can hold the next figure back.
				if (LamplessFigures.spawnAt(level, site).isPresent()) {
					throw failure(helper, "a figure was spawned in the delay after the second fade");
				}
				LamplessFigures.clearFadeDelays();
				LamplessFigures.spawnAt(level, site).orElseThrow(() -> failure(helper, "no figure was spawned after the fade delays were cleared")).discard();
				helper.succeed();
			}
		});
		FarChunks.awaitEntityTicking(helper, level, origin, () -> {
			buildCorridor(level, origin);
			figure[0] = LamplessFigures.spawnAt(level, site).orElseThrow(() -> failure(helper, "no figure was spawned on a rail site"));
			if (!level.getBlockState(figure[0].blockPosition()).is(BlockTags.RAILS)) {
				throw failure(helper, "the figure stands at %s, which is not a rail", figure[0].blockPosition().toShortString());
			}
			if (LamplessFigures.spawnAt(level, site).isPresent()) {
				throw failure(helper, "a second figure was spawned on a site that holds one");
			}
			level.setBlock(origin.above(), light(LIT_LEVEL), 3);
		});
	}

	/** A level holds {@code maxPerLevel} figures however many sites ask. The End has no fades, no tick spawns and no other figure test. */
	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 40)
	public void aLevelHoldsNoMoreFiguresThanItsCap(GameTestHelper helper) {
		ServerLevel level = helper.getLevel().getServer().getLevel(Level.END);
		List<StructureSite> sites = new ArrayList<>();
		for (int i = 0; i <= TUNING.maxPerLevel(); i++) {
			sites.add(new StructureSite(StructureKind.RAILS, slot(10 + i), true, 4, 0L));
		}
		int[] ready = {0};
		for (StructureSite site : sites) {
			FarChunks.awaitEntityTicking(helper, level, site.origin(), () -> {
				buildCorridor(level, site.origin());
				if (++ready[0] < sites.size()) {
					return;
				}
				List<LamplessFigure> spawned = new ArrayList<>();
				for (StructureSite each : sites) {
					LamplessFigures.spawnAt(level, each).ifPresent(spawned::add);
				}
				int inLevel = level.getEntities(CreatureRegistry.LAMPLESS_FIGURE, figure -> true).size();
				spawned.forEach(LamplessFigure::discard);
				if (spawned.size() != TUNING.maxPerLevel() || inLevel != TUNING.maxPerLevel()) {
					throw failure(helper, "%d sites asked: %d figures were spawned and %d stood in the level, not %d",
							sites.size(), spawned.size(), inLevel, TUNING.maxPerLevel());
				}
				helper.succeed();
			});
		}
	}

	@GameTest
	public void onlyTheRailLevelLooksForSitesAndOnlyOnTheInterval(GameTestHelper helper) {
		int interval = TUNING.spawnIntervalTicks();
		for (long time : new long[] {0, interval, 7L * interval}) {
			expectSpawnTick(helper, LayerChain.dimension(StructureKind.RAILS.layer()), time, true);
		}
		for (long time : new long[] {1, interval - 1, interval + 1, 7L * interval + 3}) {
			expectSpawnTick(helper, LayerChain.dimension(StructureKind.RAILS.layer()), time, false);
		}
		for (ResourceKey<Level> other : List.of(Level.OVERWORLD, Level.NETHER, Level.END, LayerChain.dimension(1), LayerChain.dimension(StructureKind.RAILS.layer() + 1))) {
			expectSpawnTick(helper, other, 0, false);
			expectSpawnTick(helper, other, interval, false);
		}
		helper.succeed();
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

	/**
	 * A figure that walks undisturbed for {@link #QUIET_TICKS}, then meets a cause, and must fade out and be gone. The two waits
	 * are bounded apart: first for the fade to start, whatever the light and the check interval take, then for the fade itself.
	 */
	private static final class FadeRun {
		LamplessFigure figure;
		ServerPlayer player;
		Runnable onDone = () -> { };
		private int ticks;
		private int causeAt = -1;
		private int fadingAt = -1;

		void step(GameTestHelper helper, ServerLevel level, Runnable cause) {
			if (figure == null) {
				return;
			}
			ticks++;
			if (ticks < QUIET_TICKS) {
				if (figure.fadeFraction() > 0 || !figure.isAlive()) {
					throw failure(helper, "the figure faded after %d ticks with nothing near it", ticks);
				}
				return;
			}
			if (causeAt < 0) {
				causeAt = ticks;
				cause.run();
				return;
			}
			if (fadingAt < 0 && figure.fadeFraction() > 0) {
				fadingAt = ticks;
			}
			if (figure.isRemoved()) {
				if (fadingAt < 0) {
					throw failure(helper, "the figure vanished without a fade");
				}
				onDone.run();
				helper.succeed();
			} else if (fadingAt < 0 && ticks - causeAt > FADE_START_BUDGET_TICKS) {
				figure.discard();
				throw failure(helper, "the figure had not started to fade %d ticks after the cause", ticks - causeAt);
			} else if (fadingAt >= 0 && ticks - fadingAt > FADE_END_BUDGET_TICKS) {
				figure.discard();
				throw failure(helper, "the figure was still there %d ticks after it started to fade (%d ticks after the cause)", ticks - fadingAt, ticks - causeAt);
			}
		}
	}

	/**
	 * Fails unless every entity {@code entities} gives (null while it has none yet) is dead at once and removed within
	 * {@link #REMOVAL_TICKS}, and succeeds when all are removed.
	 */
	private static void expectRemovedWithin(GameTestHelper helper, Supplier<List<? extends LivingEntity>> entities) {
		int[] since = {-1};
		helper.onEachTick(() -> {
			List<? extends LivingEntity> all = entities.get();
			if (all == null) {
				return;
			}
			if (since[0] < 0) {
				since[0] = (int) helper.getTick();
			}
			if (all.stream().allMatch(Entity::isRemoved)) {
				helper.succeed();
			} else if ((int) helper.getTick() - since[0] > REMOVAL_TICKS - 10) {
				List<? extends LivingEntity> left = all.stream().filter(entity -> !entity.isRemoved()).toList();
				all.forEach(Entity::discard);
				throw failure(helper, "%s still there %d ticks after the kill or the fall (health %s)", left, REMOVAL_TICKS - 10, left.stream().map(LivingEntity::getHealth).toList());
			}
		});
	}

	private static void expectSpawnTick(GameTestHelper helper, ResourceKey<Level> dimension, long gameTime, boolean expected) {
		if (LamplessFigures.isSpawnTick(dimension, gameTime) != expected) {
			throw failure(helper, "%s at game time %d: a spawn tick should be %s", dimension.identifier(), gameTime, expected);
		}
	}

	private static boolean railsAreDark(ServerLevel level, BlockPos origin) {
		for (int u = -HALF; u <= HALF; u++) {
			if (level.getBrightness(LightLayer.BLOCK, origin.offset(0, 0, u)) >= TUNING.fadeBlockLight()) {
				return false;
			}
		}
		return true;
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
