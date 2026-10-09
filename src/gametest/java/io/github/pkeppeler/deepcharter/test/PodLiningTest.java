package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodLining;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;

/**
 * Server GameTests for hand lining (#313), in the overworld test area (the drill and the layer rock are in {@code PodLiningDrillTest}).
 * Each test builds a block of stone with a 2 x 2 bore in it, x 4 to 5 and z 4 to 5, and a Mole standing on the bore's floor at y 2, so
 * the pod's slab is the cells x 4 to 5 and z 4 to 5, and the ring of cells it lines is x 3 and 6, z 3 and 6, from y 1 to 3.
 * Cells are given relative to the test, like the helper takes them.
 */
public class PodLiningTest {
	private static final int ROCK = 10;
	private static final BlockPos POD_AT = new BlockPos(5, 2, 5);
	/** A cell east of the bore, in the ring, and the one behind it, in the cave the test cuts. */
	private static final int RING_EAST = 6;
	private static final int BEHIND = 7;
	/** The ticks lava needs to run through two cells: about three flow steps of 30 ticks each, with some margin. */
	private static final int FLOW_TICKS = 150;
	private static final int LINING_BUDGET_TICKS = 400;

	private record Rig(PodEntity pod, MockPlayer pilot) {
	}

	/** A block of stone with the bore cut in it. */
	private static void rock(GameTestHelper helper) {
		for (int x = 0; x < ROCK; x++) {
			for (int y = 1; y <= 7; y++) {
				for (int z = 0; z < ROCK; z++) {
					helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
				}
			}
		}
		for (int x = 4; x <= 5; x++) {
			for (int y = 2; y <= 4; y++) {
				for (int z = 4; z <= 5; z++) {
					helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
				}
			}
		}
	}

	/** A cave of air east of the bore, two cells wide: its first column is in the ring, and the second is behind the lining. */
	private static void caveEast(GameTestHelper helper) {
		for (int x = RING_EAST; x <= BEHIND; x++) {
			for (int y = 2; y <= 3; y++) {
				for (int z = 4; z <= 5; z++) {
					helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
				}
			}
		}
	}

	private static List<BlockPos> ringEast() {
		List<BlockPos> cells = new ArrayList<>();
		for (int y = 2; y <= 3; y++) {
			for (int z = 4; z <= 5; z++) {
				cells.add(new BlockPos(RING_EAST, y, z));
			}
		}
		return cells;
	}

	/** The pod on the bore's floor, and a survival pilot in its seat. The pod is nobody's: anyone may use its stores. */
	private static Rig seat(GameTestHelper helper, String name, int rack) {
		PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(POD_AT.getX(), POD_AT.getY(), POD_AT.getZ()));
		PodLining.modify(pod, state -> new PodLining.State(0, rack, 0, false, false));
		MockPlayer pilot = MockPlayers.join(helper, name);
		pilot.player().setGameMode(GameType.SURVIVAL);
		pilot.teleportTo(helper.getLevel(), pod.position(), 0f, 0f);
		if (!pilot.player().startRiding(pod, true, false)) {
			throw failure(helper, "the pilot could not board the pod");
		}
		return new Rig(pod, pilot);
	}

	private static void carry(Rig rig, int bricks) {
		rig.pilot.player().getInventory().add(new ItemStack(SlagBrick.item(), bricks));
	}

	private static int carried(Rig rig) {
		int count = 0;
		for (int slot = 0; slot < rig.pilot.player().getInventory().getContainerSize(); slot++) {
			ItemStack stack = rig.pilot.player().getInventory().getItem(slot);
			if (stack.is(SlagBrick.item())) {
				count += stack.getCount();
			}
		}
		return count;
	}

	private static boolean isBrick(GameTestHelper helper, BlockPos relative) {
		return helper.getBlockState(relative).is(SlagBrick.BLOCK);
	}

	private static long bricksIn(GameTestHelper helper, List<BlockPos> cells) {
		return cells.stream().filter(cell -> isBrick(helper, cell)).count();
	}

	/** Runs {@code done} on the first tick after the lining has ended, and fails if it does not end in time. */
	private static void whenLiningEnds(GameTestHelper helper, Rig rig, Runnable done) {
		boolean[] finished = {false};
		helper.onEachTick(() -> {
			if (!finished[0] && !PodLining.working(rig.pod)) {
				finished[0] = true;
				done.run();
			}
		});
	}

	@GameTest(maxTicks = LINING_BUDGET_TICKS)
	public void aPressLinesTheOpenCellsBesideTheSlabLavaFirstAndNothingElse(GameTestHelper helper) {
		rock(helper);
		caveEast(helper);
		BlockPos lava = new BlockPos(3, 2, 4);
		helper.setBlock(lava, Blocks.LAVA);
		// Cells that are not open stay as they are: company rock, an ore, and plain stone.
		BlockPos companyRock = new BlockPos(4, 2, 3);
		BlockPos ore = new BlockPos(5, 3, 6);
		BlockPos stone = new BlockPos(5, 2, 3);
		helper.setBlock(companyRock, HazardBlocks.COMPANY_ROCK);
		helper.setBlock(ore, OreRegistry.block(OreType.IRONIUM));
		Rig rig = seat(helper, "Lining pilot", 20);

		List<BlockPos> plan = PodLining.cellsToLine(rig.pod);
		List<BlockPos> expected = new ArrayList<>(ringEast().stream().map(helper::absolutePos).toList());
		expected.add(helper.absolutePos(lava));
		if (plan.size() != expected.size() || !plan.containsAll(expected)) {
			throw failure(helper, "the plan should be the four cave cells in the ring and the lava cell, not %s", plan);
		}
		if (!plan.getFirst().equals(helper.absolutePos(lava))) {
			throw failure(helper, "the lava cell should be lined first, the plan starts with %s", plan.getFirst());
		}
		List<Integer> heights = plan.stream().skip(1).map(BlockPos::getY).toList();
		if (!heights.equals(heights.stream().sorted().toList())) {
			throw failure(helper, "after the lava the lowest cells should come first, the plan is %s", plan);
		}

		PodLining.toggle(rig.pilot.player());
		if (!PodLining.working(rig.pod)) {
			throw failure(helper, "a press with cells to line and brick to line with should start the lining");
		}
		whenLiningEnds(helper, rig, () -> {
			PodLining.State after = PodLining.of(rig.pod);
			if (!isBrick(helper, lava) || bricksIn(helper, ringEast()) != 4) {
				throw failure(helper, "the lava cell and the four cave cells should be slag brick now");
			}
			if (!helper.getBlockState(companyRock).is(HazardBlocks.COMPANY_ROCK) || !helper.getBlockState(ore).is(OreRegistry.block(OreType.IRONIUM))
					|| !helper.getBlockState(stone).is(Blocks.STONE)) {
				throw failure(helper, "company rock, ore and stone must be left as they are");
			}
			if (after.used() != 5 || after.bricks() != 15 || after.dry()) {
				throw failure(helper, "five bricks used out of 20 leaves 15 in the rack: used %s, rack %s, dry %s", after.used(), after.bricks(), after.dry());
			}
			BlockPos behind = new BlockPos(BEHIND, 2, 4);
			if (!helper.getBlockState(behind).isAir()) {
				throw failure(helper, "the cave cell behind the lining is not in the ring and must stay open");
			}
			if (!helper.getLevel().getChunkAt(helper.absolutePos(lava)).isUnsaved()) {
				throw failure(helper, "the lined wall is a block in a chunk that will be saved, but the chunk is not marked to save");
			}
			if (!PodLining.cellsToLine(rig.pod).isEmpty()) {
				throw failure(helper, "a lined slab has nothing left to line");
			}
			helper.setBlock(lava, Blocks.AIR);
			helper.succeed();
		});
	}

	@GameTest(maxTicks = LINING_BUDGET_TICKS)
	public void aBrickGoesInEveryEightTicksAndEveryPlacementGivesFeedback(GameTestHelper helper) {
		rock(helper);
		caveEast(helper);
		Rig rig = seat(helper, "Counting pilot", 20);
		PodLining.toggle(rig.pilot.player());
		List<Long> placedAt = new ArrayList<>();
		long[] seen = {0};
		boolean[] ended = {false};
		helper.onEachTick(() -> {
			long bricks = bricksIn(helper, ringEast());
			if (bricks > seen[0]) {
				seen[0] = bricks;
				placedAt.add(helper.getLevel().getGameTime());
			}
			if (!ended[0] && !PodLining.working(rig.pod)) {
				ended[0] = true;
				List<Long> gaps = new ArrayList<>();
				for (int i = 1; i < placedAt.size(); i++) {
					gaps.add(placedAt.get(i) - placedAt.get(i - 1));
				}
				if (placedAt.size() != 4 || !gaps.stream().allMatch(gap -> gap == 8)) {
					throw failure(helper, "four bricks, one every 8 ticks, expected: placed at %s, gaps %s", placedAt, gaps);
				}
				List<String> progress = rig.pilot.actionBarMessages().stream().map(PodLiningTest::keyOf).toList();
				if (!progress.contains("deepcharter.pod.lining.progress")) {
					throw failure(helper, "each brick should show the count used and left in the action bar, the pilot saw %s", progress);
				}
				helper.succeed();
			}
		});
	}

	@GameTest(maxTicks = LINING_BUDGET_TICKS)
	public void thePodHoldsStillWhileTheArmWorksAndMovesAgainAfter(GameTestHelper helper) {
		rock(helper);
		caveEast(helper);
		Rig rig = seat(helper, "Still pilot", 20);
		PodStats stock = PodStats.of(rig.pod);
		if (stock.horizontalSpeed() <= 0f || stock.thrustAcceleration() <= 0f) {
			throw failure(helper, "an idle Mole drives and climbs, speed %s and thrust %s", stock.horizontalSpeed(), stock.thrustAcceleration());
		}
		PodLining.toggle(rig.pilot.player());
		PodStats lining = PodStats.of(rig.pod);
		if (lining.horizontalSpeed() != 0f || lining.thrustAcceleration() != 0f) {
			throw failure(helper, "a lining pod neither drives nor climbs: speed %s, thrust %s", lining.horizontalSpeed(), lining.thrustAcceleration());
		}
		whenLiningEnds(helper, rig, () -> {
			PodStats after = PodStats.of(rig.pod);
			if (after.horizontalSpeed() != stock.horizontalSpeed() || after.thrustAcceleration() != stock.thrustAcceleration()) {
				throw failure(helper, "a pod whose lining is over drives and climbs again");
			}
			helper.succeed();
		});
	}

	@GameTest(maxTicks = LINING_BUDGET_TICKS)
	public void aSecondPressStopsTheLiningWhereItStands(GameTestHelper helper) {
		rock(helper);
		caveEast(helper);
		Rig rig = seat(helper, "Stopping pilot", 20);
		PodLining.toggle(rig.pilot.player());
		helper.runAfterDelay(12, () -> {
			PodLining.toggle(rig.pilot.player());
			PodLining.State stopped = PodLining.of(rig.pod);
			long placed = bricksIn(helper, ringEast());
			if (stopped.working() || placed == 0 || placed == 4 || stopped.used() != placed || stopped.bricks() != 20 - placed) {
				throw failure(helper, "a press partway stops with some bricks placed: working %s, placed %s, used %s, rack %s",
						stopped.working(), placed, stopped.used(), stopped.bricks());
			}
			helper.runAfterDelay(30, () -> {
				if (bricksIn(helper, ringEast()) != placed) {
					throw failure(helper, "a stopped lining places nothing more");
				}
				helper.succeed();
			});
		});
	}

	@GameTest
	public void noBrickMeansNoLiningAndTheOutOfBrickLine(GameTestHelper helper) {
		rock(helper);
		caveEast(helper);
		Rig rig = seat(helper, "Empty-handed pilot", 0);
		PodLining.toggle(rig.pilot.player());
		PodLining.State state = PodLining.of(rig.pod);
		if (state.working() || !state.dry() || bricksIn(helper, ringEast()) != 0) {
			throw failure(helper, "with no brick anywhere nothing starts and the pod shows it ran dry: working %s, dry %s, placed %s",
					state.working(), state.dry(), bricksIn(helper, ringEast()));
		}
		helper.succeed();
	}

	@GameTest
	public void aSlabWithNothingOpenIsNotLinedAndCostsNothing(GameTestHelper helper) {
		rock(helper);
		Rig rig = seat(helper, "Walled-in pilot", 20);
		PodLining.toggle(rig.pilot.player());
		PodLining.State state = PodLining.of(rig.pod);
		if (state.working() || state.dry() || state.bricks() != 20) {
			throw failure(helper, "a slab in solid rock has nothing to line: working %s, dry %s, rack %s", state.working(), state.dry(), state.bricks());
		}
		helper.succeed();
	}

	@GameTest(maxTicks = LINING_BUDGET_TICKS)
	public void liningRunsDryPartwayAndLeavesTheRestUnlined(GameTestHelper helper) {
		rock(helper);
		caveEast(helper);
		Rig rig = seat(helper, "Short pilot", 2);
		PodLining.toggle(rig.pilot.player());
		boolean[] ended = {false};
		helper.onEachTick(() -> {
			if (!ended[0] && !PodLining.working(rig.pod)) {
				ended[0] = true;
				PodLining.State state = PodLining.of(rig.pod);
				if (!state.dry() || state.bricks() != 0 || state.used() != 2 || bricksIn(helper, ringEast()) != 2) {
					throw failure(helper, "two bricks line two cells and the third press finds none: dry %s, rack %s, used %s, placed %s",
							state.dry(), state.bricks(), state.used(), bricksIn(helper, ringEast()));
				}
				PodLining.toggle(rig.pilot.player());
				if (!PodLining.of(rig.pod).dry() || PodLining.working(rig.pod)) {
					throw failure(helper, "pressing again with none left must not start");
				}
				carry(rig, 1);
				PodLining.toggle(rig.pilot.player());
				if (PodLining.of(rig.pod).dry() || !PodLining.working(rig.pod)) {
					throw failure(helper, "a brick in the pack starts the lining again and clears the out-of-brick line");
				}
				PodLining.toggle(rig.pilot.player());
				helper.succeed();
			}
		});
	}

	@GameTest(maxTicks = LINING_BUDGET_TICKS)
	public void theRackIsUsedBeforeThePackAndEachBrickIsTakenOnce(GameTestHelper helper) {
		rock(helper);
		caveEast(helper);
		Rig rig = seat(helper, "Both pilot", 2);
		carry(rig, 4);
		PodLining.toggle(rig.pilot.player());
		whenLiningEnds(helper, rig, () -> {
			PodLining.State state = PodLining.of(rig.pod);
			if (bricksIn(helper, ringEast()) != 4 || state.bricks() != 0 || carried(rig) != 2 || state.used() != 4) {
				throw failure(helper, "four cells take the 2 in the rack, then 2 from the pack: placed %s, rack %s, pack %s, used %s",
						bricksIn(helper, ringEast()), state.bricks(), carried(rig), state.used());
			}
			helper.succeed();
		});
	}

	@GameTest(maxTicks = LINING_BUDGET_TICKS)
	public void anotherChartersRackIsOffLimitsAndOnlyThePackIsUsed(GameTestHelper helper) {
		rock(helper);
		caveEast(helper);
		MinecraftServer server = helper.getLevel().getServer();
		Rig rig = seat(helper, "Intruder", 5);
		MockPlayer owner = MockPlayers.join(helper, "Owner");
		if (Charters.found(server, owner.player().getUUID(), "Lining owner " + owner.player().getScoreboardName()).isPresent()) {
			throw failure(helper, "the owner could not found a charter");
		}
		PodComponents.register(rig.pod, Charters.charterOfOrThrow(server, owner.player().getUUID()).orElseThrow().id());
		carry(rig, 2);
		PodLining.toggle(rig.pilot.player());
		whenLiningEnds(helper, rig, () -> {
			PodLining.State state = PodLining.of(rig.pod);
			if (bricksIn(helper, ringEast()) != 2 || state.bricks() != 5 || carried(rig) != 0 || !state.dry()) {
				throw failure(helper, "a pilot who may not use the pod's stores lines only with the pack: placed %s, rack %s, pack %s, dry %s",
						bricksIn(helper, ringEast()), state.bricks(), carried(rig), state.dry());
			}
			helper.succeed();
		});
	}

	@GameTest(maxTicks = LINING_BUDGET_TICKS)
	public void aPassengerWhoIsNotThePilotCannotLine(GameTestHelper helper) {
		rock(helper);
		caveEast(helper);
		Rig rig = seat(helper, "Real pilot", 20);
		MockPlayer outsider = MockPlayers.join(helper, "Bystander");
		outsider.player().setGameMode(GameType.SURVIVAL);
		PodLining.toggle(outsider.player());
		if (PodLining.working(rig.pod) || PodLining.of(rig.pod).bricks() != 20) {
			throw failure(helper, "a player who does not ride the pod cannot start its lining");
		}
		helper.succeed();
	}

	@GameTest(maxTicks = FLOW_TICKS + 20)
	public void withoutALiningLavaBehindTheCaveFloodsTheBore(GameTestHelper helper) {
		rock(helper);
		caveEast(helper);
		for (int y = 2; y <= 3; y++) {
			for (int z = 4; z <= 5; z++) {
				helper.setBlock(new BlockPos(BEHIND, y, z), Blocks.LAVA);
			}
		}
		helper.runAfterDelay(FLOW_TICKS, () -> {
			if (lavaInBore(helper) == 0) {
				throw failure(helper, "the control: lava behind an open cave should reach the bore, or the lined test below proves nothing");
			}
			clearLava(helper);
			helper.succeed();
		});
	}

	@GameTest(maxTicks = LINING_BUDGET_TICKS + FLOW_TICKS + 20)
	public void aLinedSlabBesideALavaPocketStaysDry(GameTestHelper helper) {
		rock(helper);
		caveEast(helper);
		Rig rig = seat(helper, "Dry pilot", 20);
		PodLining.toggle(rig.pilot.player());
		whenLiningEnds(helper, rig, () -> {
			for (int y = 2; y <= 3; y++) {
				for (int z = 4; z <= 5; z++) {
					helper.setBlock(new BlockPos(BEHIND, y, z), Blocks.LAVA);
				}
			}
			helper.runAfterDelay(FLOW_TICKS, () -> {
				if (lavaInBore(helper) != 0) {
					throw failure(helper, "lava must not reach a lined slab: %s lava blocks in the bore", lavaInBore(helper));
				}
				if (bricksIn(helper, ringEast()) != 4) {
					throw failure(helper, "lava must not replace a lining block, %s of 4 are left", bricksIn(helper, ringEast()));
				}
				clearLava(helper);
				helper.succeed();
			});
		});
	}

	private static long lavaInBore(GameTestHelper helper) {
		return boreCells().stream().filter(cell -> helper.getLevel().getFluidState(helper.absolutePos(cell)).is(FluidTags.LAVA)).count();
	}

	private static List<BlockPos> boreCells() {
		List<BlockPos> cells = new ArrayList<>();
		for (int x = 4; x <= 5; x++) {
			for (int y = 2; y <= 4; y++) {
				for (int z = 4; z <= 5; z++) {
					cells.add(new BlockPos(x, y, z));
				}
			}
		}
		return cells;
	}

	/** The test's lava leaves the area when it ends, or it keeps flowing into the next test that reuses these cells. */
	private static void clearLava(GameTestHelper helper) {
		for (int x = 0; x < ROCK + 2; x++) {
			for (int y = 1; y <= 7; y++) {
				for (int z = 0; z < ROCK; z++) {
					BlockPos cell = new BlockPos(x, y, z);
					if (helper.getLevel().getFluidState(helper.absolutePos(cell)).is(FluidTags.LAVA)) {
						helper.setBlock(cell, Blocks.AIR);
					}
				}
			}
		}
	}

	@GameTest
	public void theLiningStockCutsLiftLikeCargoAndIsSavedWithThePod(GameTestHelper helper) {
		PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(POD_AT.getX(), POD_AT.getY(), POD_AT.getZ()));
		float empty = PodEvents.extraMass(pod);
		PodLining.modify(pod, state -> new PodLining.State(10, 4, 3, false, true));
		float loaded = PodEvents.extraMass(pod);
		// 10 spoil at 0.1 and 4 bricks at 0.2.
		if (empty != 0f || Math.abs(loaded - 1.8f) > 1e-4f) {
			throw failure(helper, "10 spoil and 4 bricks weigh 1.8 and an empty pod 0, got %s and %s", loaded, empty);
		}
		ServerLevel level = helper.getLevel();
		TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
		pod.saveWithoutId(output);
		PodEntity loadedPod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		loadedPod.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), output.buildResult()));
		if (!PodLining.of(loadedPod).equals(new PodLining.State(10, 4, 3, false, true))) {
			throw failure(helper, "the stock must survive a save and a load, got %s", PodLining.of(loadedPod));
		}
		pod.discard();
		helper.succeed();
	}

	@GameTest
	public void aLiningStateOfAnotherVersionIsKeptAndNeverThrows(GameTestHelper helper) {
		rock(helper);
		caveEast(helper);
		Rig rig = seat(helper, "Unreadable pilot", 20);
		UnreadableChecks.makeUnreadable(rig.pod, PodLining.STATE);
		UnreadableChecks.assertNoThrow(helper, "pod lining", Map.of(
				"the key", () -> PodLining.toggle(rig.pilot.player()),
				"the state", () -> PodLining.of(rig.pod),
				"working", () -> PodLining.working(rig.pod),
				"the mass", () -> PodEvents.extraMass(rig.pod),
				"the stats", () -> PodStats.of(rig.pod)));
		if (!(rig.pod.getAttached(PodLining.STATE) instanceof Versioned.Unreadable<PodLining.State>) || PodLining.working(rig.pod)) {
			throw failure(helper, "unreadable lining state is left as it was read, and a pod with it never lines");
		}
		helper.succeed();
	}

	private static String keyOf(Component message) {
		return message.getContents() instanceof TranslatableContents contents ? contents.getKey() : message.getString();
	}

	// assertionException(String, Object...) leaves the placeholders unfilled in the report.
	private static RuntimeException failure(GameTestHelper helper, String format, Object... args) {
		return helper.assertionException(Component.literal(String.format(format, args)));
	}
}
