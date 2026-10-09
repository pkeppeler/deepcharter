package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodLiner;
import io.github.pkeppeler.deepcharter.pod.PodLining;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * Server GameTests for the liner (#339), in layer 1 (the liner works only in a layer). Each test has its own X, so the blocks it changes
 * never touch another test's, and builds a stone bed under an open room, with an open column down the east side of the pod's 2 x 2 bore. The ring
 * tests arm the liner by setting its anchor {@code interval} slabs above the pod, so the ring falls due on the first tick; the interval and
 * fall tests use the real drill and the real fall.
 */
public class PodLinerTest {
	/** Each pod's owner founds a charter of its own name, which a charter must not repeat. */
	private static final AtomicInteger OWNERS = new AtomicInteger();
	private static final int Z = 3416;
	private static final int FLOOR = 60;
	private static final int RADIUS = 4;
	private static final int PLENTY = 200;
	private static final int DRILL_TICKS = 2600;
	private static final int RING_TICKS = 400;
	private static final Input SPRINT = new Input(false, false, false, false, false, false, true);

	/**
	 * A bed of stone with the pod's room above it, walled so that the only open cells beside the pod are those of the east column (x + 1, the
	 * pod's two Z). With {@code column} that column runs down through the bed, so a ring that reaches below the pod has cells to line there.
	 */
	private static void shaft(ServerLevel level, int x, int depth, boolean column) {
		RoomCarver.carve(level, x - RADIUS, x + RADIUS + 1, FLOOR - depth, FLOOR - 1, Z - RADIUS, Z + RADIUS, Blocks.STONE);
		RoomCarver.carve(level, x - RADIUS, x + RADIUS + 1, FLOOR, FLOOR + 10, Z - RADIUS, Z + RADIUS, Blocks.AIR);
		if (column) {
			RoomCarver.carve(level, x + 1, x + 1, FLOOR - depth, FLOOR - 1, Z - 1, Z, Blocks.AIR);
		}
		RoomCarver.carve(level, x + 2, x + RADIUS + 1, FLOOR, FLOOR + 10, Z - RADIUS, Z + RADIUS, Blocks.STONE);
		RoomCarver.carve(level, x - RADIUS, x - 2, FLOOR, FLOOR + 10, Z - RADIUS, Z + RADIUS, Blocks.STONE);
		RoomCarver.carve(level, x - 1, x + 1, FLOOR, FLOOR + 10, Z + 1, Z + RADIUS, Blocks.STONE);
		RoomCarver.carve(level, x - 1, x + 1, FLOOR, FLOOR + 10, Z - RADIUS, Z - 2, Blocks.STONE);
	}

	/** The cells of the east column at the pod's two levels: the 4 open cells that the ring lines when it reaches no deeper than the bed's top. */
	private static List<BlockPos> ringCells(int x) {
		List<BlockPos> cells = new ArrayList<>();
		for (int y = FLOOR; y <= FLOOR + 1; y++) {
			for (int z = Z - 1; z <= Z; z++) {
				cells.add(new BlockPos(x + 1, y, z));
			}
		}
		return cells;
	}

	private static long bricksIn(ServerLevel level, List<BlockPos> cells) {
		return cells.stream().filter(cell -> level.getBlockState(cell).is(SlagBrick.BLOCK)).count();
	}

	private static MockPlayer owner(GameTestHelper helper) {
		MockPlayer owner = MockPlayers.join(helper, "Liner owner " + OWNERS.incrementAndGet());
		owner.player().setGameMode(GameType.SURVIVAL);
		return owner;
	}

	private static PodEntity spawnFitted(GameTestHelper helper, ServerLevel level, Vec3 at, int tier, int rack) {
		PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		pod.setPos(at);
		level.addFreshEntity(pod);
		ScannerPods.fit(helper.getLevel().getServer(), owner(helper).player(), pod, ComponentTrack.LINER, tier);
		PodLining.modify(pod, state -> new PodLining.State(0, rack, 0, false, false));
		return pod;
	}

	private static int dueAfter(int tier) {
		return switch (tier) {
			case 1 -> 4;
			case 2 -> 3;
			default -> throw new IllegalArgumentException("the liner has two tiers, not " + tier);
		};
	}

	/** A pod that waits for a ring: its anchor is {@code dueAfter(tier)} slabs above its feet, in its own column. */
	private static final class Armed {
		private PodEntity pod;
	}

	/**
	 * Builds the shaft at {@code x}, puts a Mole with a liner of {@code tier} and {@code rack} bricks in its bore with the next ring due, and
	 * runs {@code check} on the first tick after the liner has rung (its anchor is at the pod's feet again). {@code setup} may change the site first.
	 */
	private void ringTest(GameTestHelper helper, int x, int tier, int rack, Consumer<ServerLevel> setup, Consumer<PodLining.State> check) {
		ServerLevel level = layer(helper);
		shaft(level, x, 8, false);
		setup.accept(level);
		Armed armed = new Armed();
		FarChunks.awaitEntityTicking(helper, level, new BlockPos(x, FLOOR, Z), () -> {
			armed.pod = spawnFitted(helper, level, new Vec3(x, FLOOR, Z), tier, rack);
			armed.pod.setAttached(PodLiner.STATE, Versioned.of(new PodLiner.State(Optional.of(new PodLiner.Anchor(FLOOR + dueAfter(tier), x - 1, Z - 1)))));
		});
		boolean[] done = {false};
		helper.onEachTick(() -> {
			if (armed.pod == null || done[0]) {
				return;
			}
			Optional<PodLiner.Anchor> anchor = Versioned.readable(armed.pod, PodLiner.STATE).orElseThrow().anchor();
			if (anchor.isEmpty() || anchor.get().feetY() != FLOOR) {
				return;
			}
			done[0] = true;
			check.accept(PodLining.of(armed.pod));
			armed.pod.discard();
			helper.succeed();
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + RING_TICKS)
	public void aTierOneRingCostsABrickForEachCellAndLeavesTheRestOfTheRack(GameTestHelper helper) {
		int x = 5204;
		ringTest(helper, x, 1, 10, level -> {
		}, state -> {
			ServerLevel level = layer(helper);
			if (bricksIn(level, ringCells(x)) != 4 || state.bricks() != 6 || state.dry()) {
				throw failure(helper, "tier 1 lines the four open cells with four bricks and leaves 6 in the rack, it lined %s and left %s (dry %s)",
						bricksIn(level, ringCells(x)), state.bricks(), state.dry());
			}
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + RING_TICKS)
	public void aTierTwoRingCostsABrickForTwoCells(GameTestHelper helper) {
		int x = 5252;
		ringTest(helper, x, 2, 10, level -> {
		}, state -> {
			ServerLevel level = layer(helper);
			if (bricksIn(level, ringCells(x)) != 4 || state.bricks() != 8 || state.dry()) {
				throw failure(helper, "tier 2 lines the four open cells with two bricks and leaves 8 in the rack, it lined %s and left %s (dry %s)",
						bricksIn(level, ringCells(x)), state.bricks(), state.dry());
			}
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + RING_TICKS)
	public void aTierOneRingWithTooFewBricksLinesWhatTheRackPaysForAndMarksItDry(GameTestHelper helper) {
		int x = 5300;
		ringTest(helper, x, 1, 3, level -> {
		}, state -> {
			ServerLevel level = layer(helper);
			if (bricksIn(level, ringCells(x)) != 3 || state.bricks() != 0 || !state.dry()) {
				throw failure(helper, "three bricks line three of the four cells and leave the rack dry, it lined %s and left %s (dry %s)",
						bricksIn(level, ringCells(x)), state.bricks(), state.dry());
			}
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + RING_TICKS)
	public void aTierTwoRingWithOneBrickLinesTwoCellsAndMarksItDry(GameTestHelper helper) {
		int x = 5348;
		ringTest(helper, x, 2, 1, level -> {
		}, state -> {
			ServerLevel level = layer(helper);
			if (bricksIn(level, ringCells(x)) != 2 || state.bricks() != 0 || !state.dry()) {
				throw failure(helper, "one brick lines two of the four cells at tier 2 and leaves the rack dry, it lined %s and left %s (dry %s)",
						bricksIn(level, ringCells(x)), state.bricks(), state.dry());
			}
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + RING_TICKS)
	public void aRingWithAnEmptyRackPlacesNothingAndMarksItDry(GameTestHelper helper) {
		int x = 5396;
		ringTest(helper, x, 1, 0, level -> {
		}, state -> {
			ServerLevel level = layer(helper);
			if (bricksIn(level, ringCells(x)) != 0 || !state.dry()) {
				throw failure(helper, "an empty rack lines nothing and shows dry, it lined %s (dry %s)", bricksIn(level, ringCells(x)), state.dry());
			}
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + RING_TICKS)
	public void theRingNeverReplacesCompanyRockOrOre(GameTestHelper helper) {
		int x = 5444;
		BlockPos companyRock = new BlockPos(x + 1, FLOOR, Z - 1);
		BlockPos ore = new BlockPos(x + 1, FLOOR + 1, Z);
		ringTest(helper, x, 1, 10, level -> {
			level.setBlock(companyRock, HazardBlocks.COMPANY_ROCK.defaultBlockState(), 3);
			level.setBlock(ore, OreRegistry.block(OreType.IRONIUM).defaultBlockState(), 3);
		}, state -> {
			ServerLevel level = layer(helper);
			if (!level.getBlockState(companyRock).is(HazardBlocks.COMPANY_ROCK) || !level.getBlockState(ore).is(OreRegistry.block(OreType.IRONIUM))
					|| bricksIn(level, ringCells(x)) != 2 || state.bricks() != 8) {
				throw failure(helper, "company rock and ore stay and the two open cells are lined with 2 bricks, lined %s, rack %s", bricksIn(level, ringCells(x)), state.bricks());
			}
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + RING_TICKS)
	public void aRingThatIsNotDueIsNotLined(GameTestHelper helper) {
		int x = 5492;
		ServerLevel level = layer(helper);
		shaft(level, x, 8, false);
		Armed armed = new Armed();
		FarChunks.awaitEntityTicking(helper, level, new BlockPos(x, FLOOR, Z), () -> {
			armed.pod = spawnFitted(helper, level, new Vec3(x, FLOOR, Z), 1, 10);
			armed.pod.setAttached(PodLiner.STATE, Versioned.of(new PodLiner.State(Optional.of(new PodLiner.Anchor(FLOOR + 3, x - 1, Z - 1)))));
		});
		int[] ticks = {0};
		helper.onEachTick(() -> {
			if (armed.pod == null || ++ticks[0] < 60) {
				return;
			}
			if (bricksIn(level, ringCells(x)) != 0 || PodLining.of(armed.pod).bricks() != 10) {
				throw failure(helper, "three slabs of four sunk lines nothing, it lined %s", bricksIn(level, ringCells(x)));
			}
			armed.pod.discard();
			helper.succeed();
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + RING_TICKS)
	public void aPodInAnotherColumnThanItsAnchorIsDueAtOnce(GameTestHelper helper) {
		int x = 5540;
		ServerLevel level = layer(helper);
		shaft(level, x, 8, false);
		Armed armed = new Armed();
		FarChunks.awaitEntityTicking(helper, level, new BlockPos(x, FLOOR, Z), () -> {
			armed.pod = spawnFitted(helper, level, new Vec3(x, FLOOR, Z), 1, 10);
			// The anchor is at the pod's own height in the column beside: a sidestep puts the pod in a new column, and the ring is due with no slab sunk.
			armed.pod.setAttached(PodLiner.STATE, Versioned.of(new PodLiner.State(Optional.of(new PodLiner.Anchor(FLOOR, x - 3, Z - 1)))));
		});
		boolean[] done = {false};
		helper.onEachTick(() -> {
			if (armed.pod == null || done[0] || bricksIn(level, ringCells(x)) == 0) {
				return;
			}
			done[0] = true;
			PodLiner.Anchor anchor = Versioned.readable(armed.pod, PodLiner.STATE).orElseThrow().anchor().orElseThrow();
			if (anchor.lowX() != x - 1 || bricksIn(level, ringCells(x)) != 4) {
				throw failure(helper, "a ring is laid at once in the new column and the anchor follows the pod, anchor %s, lined %s", anchor, bricksIn(level, ringCells(x)));
			}
			armed.pod.discard();
			helper.succeed();
		});
	}

	/** Pods this class marks as dead: a listener cannot be unregistered, so it acts only on these. */
	private static final Set<UUID> UNPOWERED = ConcurrentHashMap.newKeySet();
	private static boolean unpoweredListener;

	/** Pods the ring tests arm and then keep from lining: it holds a due ring, a rack and a column of open cells, and nothing may line for 12 ticks. */
	private void ringHeld(GameTestHelper helper, int x, Consumer<PodEntity> hold, Consumer<MockPlayer> afterMount) {
		ServerLevel level = layer(helper);
		shaft(level, x, 8, false);
		MockPlayer pilot = owner(helper);
		pilot.teleportTo(level, new Vec3(x, FLOOR, Z), 0f, 0f);
		Armed armed = new Armed();
		FarChunks.awaitEntityTicking(helper, level, new BlockPos(x, FLOOR, Z), () -> {
			PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
			pod.setPos(x, FLOOR, Z);
			level.addFreshEntity(pod);
			if (!pilot.player().startRiding(pod)) {
				throw failure(helper, "the pilot could not mount the pod");
			}
			ScannerPods.fit(helper.getLevel().getServer(), pilot.player(), pod, ComponentTrack.LINER, 1);
			PodLining.modify(pod, state -> new PodLining.State(0, 10, 0, false, false));
			PodLiner.Anchor due = new PodLiner.Anchor(FLOOR + 4, x - 1, Z - 1);
			pod.setAttached(PodLiner.STATE, Versioned.of(new PodLiner.State(Optional.of(due))));
			hold.accept(pod);
			afterMount.accept(pilot);
			armed.pod = pod;
		});
		int[] ticks = {0};
		helper.onEachTick(() -> {
			if (armed.pod == null) {
				return;
			}
			PodLiner.Anchor anchor = Versioned.readable(armed.pod, PodLiner.STATE).orElseThrow().anchor().orElseThrow();
			if (anchor.feetY() != FLOOR + 4 || bricksIn(level, ringCells(x)) > 2 || PodLining.of(armed.pod).bricks() < 8) {
				throw failure(helper, "the liner must hold its ring: anchor %s, %s bricks laid, rack %s", anchor, bricksIn(level, ringCells(x)), PodLining.of(armed.pod).bricks());
			}
			if (++ticks[0] >= 12) {
				pilot.leave();
				armed.pod.discard();
				helper.succeed();
			}
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + RING_TICKS)
	public void theLinerWaitsWhileThePilotLinesByHandAndTheRingStaysDue(GameTestHelper helper) {
		ringHeld(helper, 5780, pod -> {
		}, pilot -> {
			PodLining.toggle(pilot.player());
			if (!PodLining.working((PodEntity) pilot.player().getVehicle())) {
				throw failure(helper, "the press should have started the hand lining");
			}
		});
	}

	/** What a ring leaves behind: the cells lined, the bricks left in the rack and in the pack, and whether the rack shows dry. */
	private record Left(long lined, int rack, int pack, boolean dry) {
	}

	/**
	 * A seated pilot with {@code pack} bricks in the pack, a liner of {@code tier} with {@code rack} bricks, a due ring, and an ore block in
	 * {@code blocked} of the ring's 4 open cells (so the ring has {@code 4 - blocked} cells). Runs the ring and compares what is left.
	 */
	private void packSplit(GameTestHelper helper, int x, int tier, int rack, int pack, int blocked, Left expected) {
		ServerLevel level = layer(helper);
		shaft(level, x, 8, false);
		for (BlockPos cell : ringCells(x).subList(0, blocked)) {
			level.setBlock(cell, OreRegistry.block(OreType.IRONIUM).defaultBlockState(), 3);
		}
		MockPlayer pilot = owner(helper);
		pilot.teleportTo(level, new Vec3(x, FLOOR, Z), 0f, 0f);
		Armed armed = new Armed();
		FarChunks.awaitEntityTicking(helper, level, new BlockPos(x, FLOOR, Z), () -> {
			PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
			pod.setPos(x, FLOOR, Z);
			level.addFreshEntity(pod);
			if (!pilot.player().startRiding(pod)) {
				throw failure(helper, "the pilot could not mount the pod");
			}
			ScannerPods.fit(helper.getLevel().getServer(), pilot.player(), pod, ComponentTrack.LINER, tier);
			PodLining.modify(pod, state -> new PodLining.State(0, rack, 0, false, false));
			pilot.player().getInventory().add(new ItemStack(SlagBrick.item(), pack));
			pod.setAttached(PodLiner.STATE, Versioned.of(new PodLiner.State(Optional.of(new PodLiner.Anchor(FLOOR + dueAfter(tier), x - 1, Z - 1)))));
			armed.pod = pod;
		});
		boolean[] done = {false};
		helper.onEachTick(() -> {
			if (armed.pod == null || done[0] || Versioned.readable(armed.pod, PodLiner.STATE).orElseThrow().anchor().orElseThrow().feetY() != FLOOR) {
				return;
			}
			done[0] = true;
			PodLining.State state = PodLining.of(armed.pod);
			Left left = new Left(bricksIn(level, ringCells(x)), state.bricks(), pilot.player().getInventory().countItem(SlagBrick.item()), state.dry());
			if (!left.equals(expected)) {
				throw failure(helper, "tier %s, rack %s, pack %s, %s of 4 cells blocked: expected %s, got %s", tier, rack, pack, blocked, expected, left);
			}
			pilot.leave();
			armed.pod.discard();
			helper.succeed();
		});
	}

	/** The rack holds 1 brick and the pilot carries 2 more: a tier 1 ring of 4 cells takes the rack's first, then the pack's, and the 4th cell stays open. */
	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + RING_TICKS)
	public void aLinerDrawsOnThePilotsPackAfterTheRack(GameTestHelper helper) {
		packSplit(helper, 5876, 1, 1, 2, 0, new Left(3, 0, 0, true));
	}

	/**
	 * A tier 2 brick lines two cells. The ring has 3 open cells, so it costs 2 bricks: the rack's one brick pays the first two cells and the pack's
	 * first brick pays the third. The ring is whole, the pack keeps its other 2 bricks, and the rack is not dry.
	 */
	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + RING_TICKS)
	public void aTierTwoRingSplitsItsBricksBetweenTheRackAndThePack(GameTestHelper helper) {
		packSplit(helper, 5924, 2, 1, 3, 1, new Left(3, 0, 2, false));
	}

	/** Tier 2, a ring of 4 cells, an odd rack of 1 and a pack of 1: the rack's brick lines two cells and the pack's the other two, so the ring is whole and both stores are empty. */
	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + RING_TICKS)
	public void aTierTwoRingOfFourCellsTakesOneBrickFromEachStore(GameTestHelper helper) {
		packSplit(helper, 5972, 2, 1, 1, 0, new Left(4, 0, 0, false));
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + RING_TICKS)
	public void anUnpoweredPodsLinerLaysNothingAndTheRingStaysDue(GameTestHelper helper) {
		if (!unpoweredListener) {
			unpoweredListener = true;
			PodEvents.IS_POWERED.register(pod -> !UNPOWERED.contains(pod.getUUID()));
		}
		ringHeld(helper, 5828, pod -> UNPOWERED.add(pod.getUUID()), pilot -> {
		});
	}

	@GameTest
	public void aLinerSlowsTheDrillByItsTiersShareAndAPodWithoutOneIsStock(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		PodEntity stock = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		PodEntity one = spawnFitted(helper, level, helper.absoluteVec(new Vec3(1, 2, 1)), 1, 0);
		PodEntity two = spawnFitted(helper, level, helper.absoluteVec(new Vec3(1, 2, 3)), 2, 0);
		float stockTicks = PodStats.of(stock).ticksPerHardness();
		float tierOne = PodStats.of(one).ticksPerHardness();
		float tierTwo = PodStats.of(two).ticksPerHardness();
		// A tier 1 liner takes 10% of the drill's speed, a tier 2 liner 15%: each hardness takes 1/0.9 and 1/0.85 of the stock ticks.
		if (Math.abs(tierOne - stockTicks / 0.9f) > 1e-3 || Math.abs(tierTwo - stockTicks / 0.85f) > 1e-3) {
			throw failure(helper, "stock %s ticks a hardness; tier 1 should take %s and tier 2 %s, got %s and %s", stockTicks, stockTicks / 0.9f, stockTicks / 0.85f, tierOne, tierTwo);
		}
		one.discard();
		two.discard();
		helper.succeed();
	}

	@GameTest
	public void theLinersStateSurvivesASaveAndALoad(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		PodLiner.State state = new PodLiner.State(Optional.of(new PodLiner.Anchor(41, -7, 300)));
		pod.setAttached(PodLiner.STATE, Versioned.of(state));
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		pod.saveWithoutId(output);
		PodEntity loaded = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), output.buildResult()));
		if (!Versioned.readable(loaded, PodLiner.STATE).orElseThrow().equals(state)) {
			throw failure(helper, "the liner's anchor must survive a save and a load, got %s", Versioned.readable(loaded, PodLiner.STATE));
		}
		helper.succeed();
	}

	@GameTest
	public void aLinerStateOfAnotherVersionIsKeptAndNeverThrows(GameTestHelper helper) {
		PodEntity pod = spawnFitted(helper, helper.getLevel(), helper.absoluteVec(new Vec3(1, 2, 1)), 1, 10);
		UnreadableChecks.makeUnreadable(pod, PodLiner.STATE);
		UnreadableChecks.assertNoThrow(helper, "pod liner", Map.of(
				"the next ring", () -> PodLiner.slabsToNextRing(pod),
				"the tick", () -> PodEvents.AFTER_TICK.invoker().afterTick(pod),
				"the stats", () -> PodStats.of(pod)));
		if (!(pod.getAttached(PodLiner.STATE) instanceof Versioned.Unreadable<PodLiner.State>) || PodLining.of(pod).bricks() != 10) {
			throw failure(helper, "unreadable liner state is left as it was read, and a pod with it never lines");
		}
		pod.discard();
		helper.succeed();
	}

	// ---- the real drill and the real fall ----

	/** One ring the liner laid: where the pod's feet were, how many bricks the rack paid, and whether the pod stood on the ground. */
	private record Ring(int feetY, int bricks, boolean onGround) {
	}

	/** Watches a pod's liner and notes each ring: the anchor moves down only when the liner lines. */
	private static final class Watch {
		private final PodEntity pod;
		private PodLiner.Anchor last;
		private int rack;
		int start;
		final List<Ring> rings = new ArrayList<>();

		Watch(PodEntity pod) {
			this.pod = pod;
		}

		void tick() {
			Optional<PodLiner.Anchor> now = Versioned.readable(pod, PodLiner.STATE).orElseThrow().anchor();
			if (now.isEmpty()) {
				return;
			}
			int bricks = PodLining.of(pod).bricks();
			if (last == null) {
				start = now.get().feetY();
			} else if (now.get().feetY() < last.feetY()) {
				rings.add(new Ring(now.get().feetY(), rack - bricks, pod.onGround()));
			}
			last = now.get();
			rack = bricks;
		}
	}

	private static final class Rig {
		private final MockPlayer pilot;
		private PodEntity pod;
		private Watch watch;

		private Rig(MockPlayer pilot) {
			this.pilot = pilot;
		}

		boolean ready() {
			return pod != null;
		}

		static Rig await(GameTestHelper helper, ServerLevel level, Vec3 at, int tier, boolean sprint) {
			MockPlayer pilot = owner(helper);
			pilot.teleportTo(level, at, 0f, 0f);
			Rig rig = new Rig(pilot);
			FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(at), () -> {
				PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
				pod.setPos(at);
				level.addFreshEntity(pod);
				if (!pilot.player().startRiding(pod)) {
					throw failure(helper, "the pilot could not mount the pod");
				}
				ScannerPods.fit(helper.getLevel().getServer(), pilot.player(), pod, ComponentTrack.LINER, tier);
				PodLining.modify(pod, state -> new PodLining.State(0, PLENTY, 0, false, false));
				if (sprint) {
					pilot.setInput(SPRINT);
				}
				rig.watch = new Watch(pod);
				rig.pod = pod;
			});
			return rig;
		}
	}

	private void drillTenSlabs(GameTestHelper helper, int tier, int x, List<Ring> expected) {
		ServerLevel level = layer(helper);
		shaft(level, x, 14, true);
		Rig rig = Rig.await(helper, level, new Vec3(x, FLOOR, Z), tier, true);
		helper.onEachTick(() -> {
			if (!rig.ready()) {
				return;
			}
			rig.watch.tick();
			if (rig.pod.blockPosition().getY() > rig.watch.start - 10) {
				return;
			}
			rig.pilot.releaseInput();
			List<Ring> rings = rig.watch.rings.stream().map(ring -> new Ring(ring.feetY() - rig.watch.start, ring.bricks(), true)).toList();
			if (!rings.equals(expected)) {
				throw failure(helper, "tier %s over 10 slabs should lay %s (the depth below the start, the bricks), it laid %s", tier, expected, rings);
			}
			rig.pod.discard();
			helper.succeed();
		});
	}

	/**
	 * A ring lines the stretch the pod is about to bore: the open east column from {@code interval} slabs below the pod's feet to the top of its
	 * box. The first is 6 slabs of 2 cells (12 bricks, one for each cell), the next starts where the last one ended, so its first two slabs are lined already.
	 */
	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + DRILL_TICKS)
	public void aTierOneLinerLaysARingEveryFourSlabsOfDrilling(GameTestHelper helper) {
		drillTenSlabs(helper, 1, 5588, List.of(new Ring(-4, 12, true), new Ring(-8, 8, true)));
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + DRILL_TICKS)
	public void aTierTwoLinerLaysARingEveryThreeSlabsAtHalfTheBricks(GameTestHelper helper) {
		drillTenSlabs(helper, 2, 5636, List.of(new Ring(-3, 5, true), new Ring(-6, 3, true), new Ring(-9, 3, true)));
	}

	/** A room of open air with a stone bed 8 blocks under the pod, which the pod falls onto. */
	private void fall(GameTestHelper helper, int tier, int x, Consumer<List<Ring>> check) {
		ServerLevel level = layer(helper);
		RoomCarver.carve(level, x - RADIUS, x + RADIUS + 1, FLOOR - 8, FLOOR - 1, Z - RADIUS, Z + RADIUS, Blocks.STONE);
		RoomCarver.carve(level, x - RADIUS, x + RADIUS + 1, FLOOR, FLOOR + 10, Z - RADIUS, Z + RADIUS, Blocks.AIR);
		Rig rig = Rig.await(helper, level, new Vec3(x, FLOOR + 8, Z), tier, false);
		boolean[] airborne = {false};
		int[] settled = {0};
		helper.onEachTick(() -> {
			if (!rig.ready()) {
				return;
			}
			rig.watch.tick();
			airborne[0] |= !rig.pod.onGround();
			settled[0] = airborne[0] && rig.pod.onGround() ? settled[0] + 1 : 0;
			if (settled[0] < 4) {
				return;
			}
			check.accept(rig.watch.rings);
			rig.pod.discard();
			helper.succeed();
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 600)
	public void aTierOneLinerWaitsOutAFallAndLinesWhereThePodLands(GameTestHelper helper) {
		fall(helper, 1, 5684, rings -> {
			// The pod lands on the bed at FLOOR; the two levels of its box are open all round, 8 cells each.
			if (rings.size() != 1 || !rings.getFirst().onGround() || rings.getFirst().feetY() != FLOOR || rings.getFirst().bricks() != 16) {
				throw failure(helper, "a tier 1 liner lays one ring, on the ground, after an 8 slab fall: %s", rings);
			}
		});
	}

	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 600)
	public void aTierTwoLinerLaysARingWhileThePodIsStillFalling(GameTestHelper helper) {
		fall(helper, 2, 5732, rings -> {
			if (rings.isEmpty() || rings.getFirst().onGround() || rings.getFirst().bricks() < 1) {
				throw failure(helper, "a tier 2 liner lays its first ring with the pod in the air, using bricks: %s", rings);
			}
		});
	}

	@GameTest(maxTicks = 100)
	public void aLinerKeepsItsBricksAboveGround(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		PodEntity pod = spawnFitted(helper, level, helper.absoluteVec(new Vec3(3, 2, 3)), 2, 10);
		pod.setAttached(PodLiner.STATE, Versioned.of(new PodLiner.State(Optional.of(new PodLiner.Anchor(helper.absolutePos(new BlockPos(3, 2, 3)).getY() + 3, helper.absolutePos(new BlockPos(2, 2, 2)).getX(), helper.absolutePos(new BlockPos(2, 2, 2)).getZ())))));
		helper.runAfterDelay(40, () -> {
			if (PodLining.of(pod).bricks() != 10) {
				throw failure(helper, "a pod in the overworld lines nothing, but its rack holds %s of 10", PodLining.of(pod).bricks());
			}
			pod.discard();
			helper.succeed();
		});
	}

	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}

	private static ServerLevel layer(GameTestHelper helper) {
		ServerLevel level = helper.getLevel().getServer().getLevel(LayerChain.dimension(1));
		if (level == null) {
			throw failure(helper, "dimension %s did not load", LayerChain.dimension(1));
		}
		return level;
	}
}
