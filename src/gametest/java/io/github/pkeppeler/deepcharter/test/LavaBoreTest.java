package io.github.pkeppeler.deepcharter.test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.DoubleSummaryStatistics;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToDoubleFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.layer.LavaHazard;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLining;
import io.github.pkeppeler.deepcharter.pod.PodLiningTuning;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.scanner.LoadedBlocks;
import io.github.pkeppeler.deepcharter.scanner.ScanArea;
import io.github.pkeppeler.deepcharter.scanner.ScanSlice;
import io.github.pkeppeler.deepcharter.scanner.ScannerTuning;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * Measures how often lava ends a bore (issue 231): a stock Mole with a tier 1 scanner drills from the top of layer 1's rock to the
 * breach into layer 2, over the real worldgen, with the real drill, fuel, hull, lava and breach code. It reports how many bores
 * survive, the hull and pilot health that lava costs, and how far ahead the scanner had the lava in its view.
 *
 * <p>The default run is a smoke case of {@link #SMOKE_BORES} bores: a control through the crust, a bore that meets a lava block
 * placed in its path, and the same bore with the lining bot (#313), which must pass the block dry. It proves the harness in seconds.
 * The measurement is the same test with {@value #BORES_ENV}{@code =<n>} in the environment, for example {@code DEEPCHARTER_LAVA_BORES=100 tools/gametest.sh 'lava_bore_test*'}: n full bores side by side,
 * in chunks of one world seed, printing the lines that start with {@code [lava-bore]}. The pod shields its seated pilot from lava (#288),
 * so lava ends a bore through hull loss, not pilot death.
 *
 * <p>The pilot is a bot with no reaction: it holds sprint, and so bores straight down until the pod is dead, the pilot is dead or
 * the pod is through. Layer 1's rock is full of company rock, which the drill refuses (a clean 2 x 2 column over the whole layer is
 * about one in ten thousand), so when a slab is refused the bot sidesteps two blocks, alternating east and west, and carries on
 * down. Unless it lines (below) it never flies, never turns back and never reads the scanner: the scanner figures say what a pilot could have seen, not what
 * this bot did. The tank is topped up whenever it runs low and the tanks used are counted, because one tank does not last a layer.
 *
 * <p>With {@value #LINING_ENV}{@code =<n>} the bot lines (#313): each time the pod reaches a new slab and a thermal tier scanner would
 * mark lava within {@code n} slabs below it (and within the thermal tier's spread across), it presses the lining key, as a pilot
 * does, and waits for the pod to finish before it bores on. Its pod has the spoil hopper; a pod without one keeps no spoil. The bot
 * lines only on the way down and only while the pod rests on its slab: it never flies, so it cannot stop in a fall. The rack starts with {@value #BRICKS_ENV} bricks (the rack's size by
 * default); the bot never fuses more, because a pilot cannot reach the processor mid-dive. The run prints a {@code [lava-bore] lining}
 * block with the bricks and the time it cost, to read against the same run without lining.
 */
public class LavaBoreTest {
	private static final Logger LOGGER = LoggerFactory.getLogger(LavaBoreTest.class);

	static final String BORES_ENV = "DEEPCHARTER_LAVA_BORES";
	private static final String REQUESTED_BORES = System.getenv(BORES_ENV);
	static final String LINING_ENV = "DEEPCHARTER_LAVA_BORES_LINING";
	static final String BRICKS_ENV = "DEEPCHARTER_LAVA_BORES_BRICKS";
	private static final String REQUESTED_LINING = System.getenv(LINING_ENV);
	private static final String REQUESTED_BRICKS = System.getenv(BRICKS_ENV);
	/** How far either side of the shaft's column the lining bot looks for lava: the thermal tier's own spread. */
	private static final int LINING_REACH = ScannerTuning.DEFAULT.lavaSpread();
	/** The smoke case: a control that bores only the crust, and a bore through the last stretch of Deep Claim. */
	static final int SMOKE_BORES = 3;
	/** A lava encounter this many slabs or fewer below a lining press counts as one the lining did not hold. */
	private static final int RECENT_PRESS_SLABS = 2;
	/** The lining bot's look-ahead in the smoke case: the lava block is 3 slabs below the shaft's bottom. */
	private static final int SMOKE_LOOKAHEAD = 3;
	/** The smoke bore that lines. */
	private static final int SMOKE_LINING_BORE = 2;
	/**
	 * The smoke case starts its pods low, in a shaft cut with RoomCarver from the top of the rock, so that a bore is a thousand ticks
	 * and not the six thousand of the whole layer. The control starts on the crust and must get through; the other starts in Deep Claim.
	 */
	private static final int SMOKE_CROSSING_Y = 3;
	private static final int SMOKE_LAVA_Y = 24;
	/**
	 * The lava block the smoke bore must meet first, 3 slabs below its start: above any lava the world seed put in the shaft's way, and in the scanner's view from the first scan.
	 * The height was picked for the game test world's seed 0, so a different seed may need a different value.
	 * It is in the scan plane (the pod faces south, so the plane is the pod's x) and in the pod's footprint.
	 */
	private static final int SMOKE_LAVA_BLOCK_Y = 21;
	private static final int NO_SHAFT = Integer.MIN_VALUE;
	/** From this many bores the report prints a verdict as well: fewer say nothing about a rate. */
	private static final int VERDICT_FROM = 30;
	/** Under this survival rate a stock Mole's descent is near-hopeless, and the issue allows a tuning change. */
	private static final double NEAR_HOPELESS_SURVIVAL = 0.5;

	private static final int SCANNER_TIER = 1;
	/** The tier that marks lava (#300). The pod keeps its tier 1 scanner; the harness also reads what a scanner of this tier would show, from the same spot. */
	private static final int THERMAL_TIER = ScannerTuning.DEFAULT.lavaTier();
	/**
	 * Lava counts as shown in time when it was in the scanner's view at least this many slabs before the pod touched it:
	 * a quarter of the tier 1 scanner's reach below the pod, which at the measured 36 pod ticks a slab (24 ticks of stone at the surface, slower with depth, plus the bot's sidesteps) is about 15 seconds of warning.
	 */
	private static final int IN_TIME_SLABS = ScannerTuning.DEFAULT.tierOneArea().down() / 4;

	/** Far from every other test's columns. Each bore has a chunk of its own, two chunks apart from the next. */
	private static final int FIRST_CHUNK_X = 2500;
	private static final int CHUNK_Z = 2500;
	private static final int CHUNK_SPACING = 2;
	private static final int PILOT_WAIT_Y = 150;

	private static final int SIDE_STEP = 2;
	/** The bot does not stray farther than this from its first column, so that it stays inside its chunk (the drill will not bore at an unloaded edge). */
	private static final int MAX_DRIFT = 5;
	/** Ticks a pod on the ground neither drills nor moves before the bot calls the slab refused. */
	private static final int STUCK_TICKS = 12;
	/** Pod ticks between two touches of lava that still count as one encounter: about two slabs of drilling. */
	private static final int ENCOUNTER_GAP_TICKS = 60;
	/** A pod that gets no deeper in this many ticks is stalled. */
	private static final int NO_PROGRESS_TICKS = 2000;
	private static final int BORE_TICK_BUDGET = 20000;
	private static final int LAVA_BODY_LIMIT = 256;
	private static final float REFUEL_BELOW_PERCENT = 3f;

	private static final Input SPRINT = new Input(false, false, false, false, false, false, true);
	/** The pod faces south (yaw 0), so forward is south and left is east. */
	private static final Input STRAFE_EAST = new Input(false, false, true, false, false, false, false);
	private static final Input STRAFE_WEST = new Input(false, false, false, true, false, false, false);
	private static final Input DRIVE_SOUTH = new Input(true, false, false, false, false, false, false);
	private static final Input DRIVE_NORTH = new Input(false, true, false, false, false, false, false);

	private enum Outcome { SURVIVED, DIED, STALLED }

	private enum Phase { DOWN, SIDEWAYS }

	/** A way to step aside: the input that drives the pod that way (it faces south), and the axis it moves along. */
	private enum Side {
		EAST(1, 0, STRAFE_EAST), WEST(-1, 0, STRAFE_WEST), SOUTH(0, 1, DRIVE_SOUTH), NORTH(0, -1, DRIVE_NORTH);

		final int dx;
		final int dz;
		final Input input;

		Side(int dx, int dz, Input input) {
			this.dx = dx;
			this.dz = dz;
			this.input = input;
		}

		/** How far the pod is along this side's direction, in blocks from the world's origin. */
		double along(PodEntity pod) {
			return pod.getX() * dx + pod.getZ() * dz;
		}

		Side opposite() {
			return switch (this) {
				case EAST -> WEST;
				case WEST -> EAST;
				case SOUTH -> NORTH;
				case NORTH -> SOUTH;
			};
		}

		Side turnedRight() {
			return switch (this) {
				case EAST -> SOUTH;
				case SOUTH -> WEST;
				case WEST -> NORTH;
				case NORTH -> EAST;
			};
		}

		Side turnedLeft() {
			return turnedRight().opposite();
		}
	}

	/** One run of touching lava, how early the scanner had it in view, and what it cost the hull. */
	private static final class Encounter {
		private final String zone;
		private final int contactY;
		private final int slabsShownAhead;
		private final int contactCellsShownAhead;
		private final int thermalSlabsShownAhead;
		private final int thermalContactCellsShownAhead;
		float hullLost;
		int ticks;

		Encounter(String zone, int contactY, int slabsShownAhead, int contactCellsShownAhead, int thermalSlabsShownAhead, int thermalContactCellsShownAhead) {
			this.zone = zone;
			this.contactY = contactY;
			this.slabsShownAhead = slabsShownAhead;
			this.contactCellsShownAhead = contactCellsShownAhead;
			this.thermalSlabsShownAhead = thermalSlabsShownAhead;
			this.thermalContactCellsShownAhead = thermalContactCellsShownAhead;
		}

		String zone() {
			return zone;
		}

		int contactY() {
			return contactY;
		}

		/**
		 * Slabs between any block of the connected body of lava being in the scanner's view and the pod touching it; -1 when the scanner
		 * never had any. This is an upper bound on what a pilot could use: the body may be seen by a cell far from the one that burns.
		 */
		int slabsShownAhead() {
			return slabsShownAhead;
		}

		/** As {@link #slabsShownAhead}, for the lava blocks the pod actually touched: the strict figure. */
		int contactCellsShownAhead() {
			return contactCellsShownAhead;
		}

		/** As {@link #slabsShownAhead}, for a scanner of the thermal tier, which marks lava as lava (#300). */
		int thermalSlabsShownAhead() {
			return thermalSlabsShownAhead;
		}

		/** As {@link #contactCellsShownAhead}, for a scanner of the thermal tier: the strict figure. */
		int thermalContactCellsShownAhead() {
			return thermalContactCellsShownAhead;
		}

		boolean shownAtAll() {
			return slabsShownAhead >= 0;
		}
	}

	/** One bore: its pilot, its pod, the bot's state and everything the report needs. */
	private static final class Bore {
		final int index;
		final MockPlayer pilot;
		final ServerLevel level;
		final int centreX;
		final int centreZ;
		/** Where the shaft cut for the smoke case ends, or {@link #NO_SHAFT} for the whole layer. */
		final int shaftBottomY;
		/** Slabs below the pod in which thermal-marked lava makes the bot line; 0 for a bot that never lines. */
		final int lookahead;
		PodEntity pod;
		int startY;

		Outcome outcome;
		String cause = "";
		float startHull;
		float endHull;
		float endPilotHealth;
		float lavaHull;
		float lavaPilot;
		/** The worst single tick of hull loss that lava did not cause (a fall, gas, the crust), and the Y it happened at. */
		float biggestOtherHit;
		int biggestOtherHitY;
		int tanks = 1;
		int pilotTicks;
		int sidesteps;
		int lowestFeetY = Integer.MAX_VALUE;
		int lastProgressTick;

		Phase phase = Phase.DOWN;
		int idleTicks;
		Side side = Side.EAST;
		Side lastSide = Side.WEST;
		int lastSideY = Integer.MIN_VALUE;
		double sideStart;
		double lastAlong;
		final Set<Side> refused = EnumSet.noneOf(Side.class);

		boolean wantLining;
		boolean wasLining;
		int liningSessions;
		int liningTicks;
		int bricksPlaced;
		int dryPresses;
		int startBricks;
		/** Lava encounters that began with a lining asked for and not done (the pod was falling), just under a lining, and with none asked for. */
		int unservedTouches;
		int linedTouches;
		int unseenTouches;
		int lastPressY = Integer.MIN_VALUE;

		float lastHull;
		float lastHealth;
		long lastTouchTick = Long.MIN_VALUE / 2;
		BlockPos lastScanned;
		final Map<BlockPos, Integer> shownAt = new HashMap<>();
		/** As {@link #shownAt}, for the cells a thermal tier scanner marks as lava. */
		final Map<BlockPos, Integer> thermalShownAt = new HashMap<>();
		final List<Encounter> encounters = new ArrayList<>();

		Bore(int index, MockPlayer pilot, ServerLevel level, int centreX, int centreZ, int shaftBottomY, int lookahead) {
			this.index = index;
			this.pilot = pilot;
			this.level = level;
			this.centreX = centreX;
			this.centreZ = centreZ;
			this.shaftBottomY = shaftBottomY;
			this.lookahead = lookahead;
		}

		boolean ready() {
			return pod != null;
		}

		boolean done() {
			return outcome != null;
		}
	}

	/**
	 * A stock Mole from the top of layer 1 to layer 2. The bores run side by side in chunks of their own and the test ends when
	 * every one has an outcome, so a bore that cannot be finished must still end as stalled and not hang.
	 */
	@GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + BORE_TICK_BUDGET + 2000)
	public void aStockMoleBoresFromTheTopOfLayerOneToLayerTwoAndTheLavaIsCounted(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		ServerLevel level = layer(helper, 1);
		int count = boreCount(helper);
		boolean smoke = REQUESTED_BORES == null || REQUESTED_BORES.isBlank();
		int measuredLookahead = positiveEnv(helper, LINING_ENV, REQUESTED_LINING, 0);
		List<Bore> bores = new ArrayList<>();
		CharterId charter = null;
		for (int i = 0; i < count; i++) {
			MockPlayer pilot = MockPlayers.join(helper, "Bore pilot " + i);
			pilot.player().setGameMode(GameType.SURVIVAL);
			charter = joinTheCharter(helper, server, pilot, charter, i);
			int centreX = (FIRST_CHUNK_X + i * CHUNK_SPACING) * 16 + 8;
			int centreZ = CHUNK_Z * 16 + 8;
			// The pilot is in the chunk from the start, which keeps it loaded until the pod is ready.
			pilot.teleportTo(level, new Vec3(centreX, PILOT_WAIT_Y, centreZ), 0f, 0f);
			int shaftBottomY = !smoke ? NO_SHAFT : i == 0 ? SMOKE_CROSSING_Y : SMOKE_LAVA_Y;
			int lookahead = smoke ? (i == SMOKE_LINING_BORE ? SMOKE_LOOKAHEAD : 0) : measuredLookahead;
			bores.add(new Bore(i, pilot, level, centreX, centreZ, shaftBottomY, lookahead));
		}
		long wallStart = System.nanoTime();
		boolean[] reported = {false};
		helper.onEachTick(() -> {
			if (reported[0]) {
				return;
			}
			for (Bore bore : bores) {
				if (bore.ready() && !bore.done()) {
					step(helper, bore);
				}
			}
			if (bores.stream().allMatch(Bore::done)) {
				reported[0] = true;
				report(bores, (System.nanoTime() - wallStart) / 1_000_000_000.0, helper.getTick());
				check(helper, bores, smoke);
				helper.succeed();
			}
		});
		List<BlockPos> probes = bores.stream().map(bore -> new BlockPos(bore.centreX, PILOT_WAIT_Y, bore.centreZ)).toList();
		FarChunks.awaitEntityTicking(helper, level, probes, index -> launch(helper, server, bores.get(index)));
	}

	private static int boreCount(GameTestHelper helper) {
		return positiveEnv(helper, BORES_ENV, REQUESTED_BORES, SMOKE_BORES);
	}

	/** The whole number the environment variable {@code name} holds, 1 or more, or {@code unset} when it is absent. */
	private static int positiveEnv(GameTestHelper helper, String name, String value, int unset) {
		if (value == null || value.isBlank()) {
			return unset;
		}
		try {
			int number = Integer.parseInt(value.trim());
			if (number < 1) {
				throw new NumberFormatException();
			}
			return number;
		} catch (NumberFormatException e) {
			throw helper.assertionException(Component.literal(name + " must be a whole number, 1 or more, not '" + value + "'"));
		}
	}

	/** The first pilot founds a charter and the rest join it, so a run of sixty bores leaves one charter in the world and not sixty. */
	private static CharterId joinTheCharter(GameTestHelper helper, MinecraftServer server, MockPlayer pilot, CharterId charter, int index) {
		ServerPlayer player = pilot.player();
		if (charter == null) {
			if (Charters.found(server, player.getUUID(), "Lava bore " + player.getScoreboardName()).isPresent()) {
				throw helper.assertionException(Component.literal("the first pilot could not found a charter"));
			}
			return Charters.charterOfOrThrow(server, player.getUUID()).orElseThrow().id();
		}
		ServerPlayer director = server.getPlayerList().getPlayers().stream()
				.filter(candidate -> Charters.charterOfOrThrow(server, candidate.getUUID()).map(c -> c.id().equals(charter)).orElse(false))
				.findFirst().orElseThrow();
		if (Charters.apply(server, player.getUUID(), charter).isPresent() || Charters.approve(server, director.getUUID(), player.getUUID()).isPresent()) {
			throw helper.assertionException(Component.literal("pilot " + index + " could not join the charter"));
		}
		return charter;
	}

	/** The chunk is entity-ticking: the pod goes on the rock at the top of the column, with a scanner, and the bot starts. */
	private static void launch(GameTestHelper helper, MinecraftServer server, Bore bore) {
		ServerLevel level = bore.level;
		int top = topOfTheRock(level, bore.centreX, bore.centreZ);
		int feetY = top + 1;
		if (bore.shaftBottomY != NO_SHAFT) {
			RoomCarver.carve(level, bore.centreX - 1, bore.centreX, bore.shaftBottomY, top, bore.centreZ - 1, bore.centreZ, Blocks.AIR);
			feetY = bore.shaftBottomY;
			if (bore.shaftBottomY == SMOKE_LAVA_Y) {
				// One lava block in the footprint, below the pod: the encounter and scanner path run whatever the seed gave this chunk.
				RoomCarver.carve(level, bore.centreX, bore.centreX, SMOKE_LAVA_BLOCK_Y, SMOKE_LAVA_BLOCK_Y, bore.centreZ, bore.centreZ, Blocks.LAVA);
			}
		}
		PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
		pod.setPos(bore.centreX, feetY, bore.centreZ);
		level.addFreshEntity(pod);
		ScannerPods.fit(server, bore.pilot.player(), pod, SCANNER_TIER);
		if (bore.lookahead > 0) {
			ScannerPods.fit(server, bore.pilot.player(), pod, ComponentTrack.SPOIL_HOPPER, 1);
		}
		if (!bore.pilot.player().startRiding(pod)) {
			throw helper.assertionException(Component.literal("pilot " + bore.index + " could not mount the pod"));
		}
		if (bore.lookahead > 0) {
			int bricks = positiveEnv(helper, BRICKS_ENV, REQUESTED_BRICKS, PodLiningTuning.DEFAULT.brickCapacity());
			PodLining.modify(pod, state -> new PodLining.State(0, bricks, 0, false, false));
			bore.startBricks = bricks;
		}
		bore.startY = feetY;
		bore.startHull = pod.hull();
		bore.lastHull = pod.hull();
		bore.lastHealth = bore.pilot.player().getHealth();
		bore.lastProgressTick = 0;
		bore.pod = pod;
		bore.pilot.setInput(SPRINT);
	}

	/** The highest block in the 2 x 2 columns of the bore. */
	private static int topOfTheRock(ServerLevel level, int centreX, int centreZ) {
		for (int y = level.getMaxY() - 1; y >= level.getMinY(); y--) {
			for (int x = centreX - 1; x <= centreX; x++) {
				for (int z = centreZ - 1; z <= centreZ; z++) {
					if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) {
						return y;
					}
				}
			}
		}
		throw new IllegalStateException("no rock in the columns at " + centreX + ", " + centreZ);
	}

	// ---- one tick of one bore ----

	private static void step(GameTestHelper helper, Bore bore) {
		ServerPlayer player = bore.pilot.player();
		PodEntity pod = player.getVehicle() instanceof PodEntity ridden ? ridden : bore.pod;
		boolean through = player.level().dimension().equals(LayerChain.dimension(2));
		if (through && player.isAlive() && player.getVehicle() instanceof PodEntity crossed) {
			finish(bore, Outcome.SURVIVED, "", crossed);
			crossed.discard();
			bore.pilot.releaseInput();
			return;
		}
		bore.pod = pod;
		bore.pilotTicks++;
		ServerLevel level = bore.level;
		boolean touching = LavaHazard.touchesLava(pod);
		boolean burning = player.isOnFire() || player.isInLava();

		float hullLost = bore.lastHull - pod.hull();
		float healthLost = bore.lastHealth - player.getHealth();
		bore.lastHull = pod.hull();
		if (hullLost > 0 && touching) {
			bore.lavaHull += hullLost;
		} else if (hullLost > bore.biggestOtherHit) {
			bore.biggestOtherHit = hullLost;
			bore.biggestOtherHitY = pod.blockPosition().getY();
		}
		if (healthLost > 0 && (touching || burning)) {
			bore.lavaPilot += healthLost;
		}

		if (touching) {
			if (helper.getTick() - bore.lastTouchTick > ENCOUNTER_GAP_TICKS) {
				bore.encounters.add(encounter(level, pod, bore));
				if (bore.wantLining) {
					bore.unservedTouches++;
				} else if (bore.lastPressY - pod.blockPosition().getY() >= 0 && bore.lastPressY - pod.blockPosition().getY() <= RECENT_PRESS_SLABS) {
					bore.linedTouches++;
				} else {
					bore.unseenTouches++;
				}
			}
			bore.lastTouchTick = helper.getTick();
			Encounter current = bore.encounters.getLast();
			current.ticks++;
			current.hullLost += Math.max(0f, hullLost);
		}
		bore.lastHealth = player.getHealth();
		boolean pilotDead = !player.isAlive();
		if (pilotDead || pod.hull() <= 0f) {
			boolean lava = touching || burning || helper.getTick() - bore.lastTouchTick <= ENCOUNTER_GAP_TICKS;
			finish(bore, Outcome.DIED, (pilotDead && pod.hull() > 0f ? "pilot" : "pod") + (lava ? " in lava" : " other"), pod);
			pod.discard();
			bore.pilot.releaseInput();
			return;
		}

		int feetY = pod.blockPosition().getY();
		if (feetY < bore.lowestFeetY) {
			bore.lowestFeetY = feetY;
			bore.lastProgressTick = bore.pilotTicks;
		}
		boolean lining = PodLining.working(pod);
		if (lining) {
			bore.liningTicks++;
		} else if (bore.wasLining) {
			bore.bricksPlaced += PodLining.of(pod).used();
		}
		bore.wasLining = lining;
		if (bore.phase == Phase.DOWN && !pod.blockPosition().equals(bore.lastScanned)) {
			bore.lastScanned = pod.blockPosition();
			ScanSlice thermal = noteLavaInView(level, pod, bore, feetY);
			bore.wantLining = bore.lookahead > 0 && lavaWithin(thermal, bore.lookahead);
		}
		if (pod.fuel() < REFUEL_BELOW_PERCENT) {
			pod.setFuel(PodTuning.DEFAULT.shell().fullFuel());
			pod.setStranded(false);
			bore.tanks++;
		}
		if (bore.pilotTicks - bore.lastProgressTick > NO_PROGRESS_TICKS || bore.pilotTicks > BORE_TICK_BUDGET) {
			finish(bore, Outcome.STALLED, "no progress at " + pod.position() + " onGround " + pod.onGround() + " drilling " + pod.drilling()
					+ " phase " + bore.phase + " side " + bore.side + " stranded " + pod.stranded() + " fuel " + pod.fuel() + " hcol " + pod.horizontalCollision
					+ " delta " + pod.getDeltaMovement() + " input " + player.getLastClientInput() + " yaw " + player.getYRot() + "/" + pod.getYRot() + " below " + cellsBelow(level, pod), pod);
			pod.discard();
			bore.pilot.releaseInput();
			return;
		}
		drive(bore, pod);
	}

	/** The bot: sprint down; on a refused slab, move two blocks to a side and sprint down again. */
	private static void drive(Bore bore, PodEntity pod) {
		if (bore.lookahead > 0 && lineIfAsked(bore, pod)) {
			return;
		}
		switch (bore.phase) {
			case DOWN -> {
				bore.idleTicks = pod.onGround() && !pod.drilling() ? bore.idleTicks + 1 : 0;
				if (bore.idleTicks >= STUCK_TICKS) {
					bore.refused.clear();
					startSidestep(bore, pod, firstSide(bore, pod, pod.blockPosition().getY() == bore.lastSideY));
				}
			}
			case SIDEWAYS -> {
				double moved = bore.side.along(pod) - bore.sideStart;
				if (moved >= SIDE_STEP - 0.05) {
					bore.phase = Phase.DOWN;
					bore.lastSide = bore.side;
					bore.lastSideY = pod.blockPosition().getY();
					bore.sidesteps++;
					bore.idleTicks = 0;
					bore.pilot.setInput(SPRINT);
					return;
				}
				boolean still = Math.abs(bore.side.along(pod) - bore.lastAlong) < 1e-4 && !pod.drilling();
				bore.lastAlong = bore.side.along(pod);
				bore.idleTicks = still ? bore.idleTicks + 1 : 0;
				if (bore.idleTicks >= STUCK_TICKS) {
					// The slab beside the pod is refused (company rock): try another side from where the pod is.
					bore.refused.add(bore.side);
					Side next = firstSide(bore, pod, true);
					if (bore.refused.contains(next)) {
						bore.phase = Phase.DOWN;
						bore.idleTicks = 0;
						bore.pilot.setInput(SPRINT);
					} else {
						startSidestep(bore, pod, next);
					}
				}
			}
		}
	}

	/**
	 * The lining bot: when the last scan asked for lining and the pod rests on its slab, presses the lining key once. Returns whether the
	 * pod is lining, in which case the bot waits and does not count the stillness as a refused slab.
	 */
	private static boolean lineIfAsked(Bore bore, PodEntity pod) {
		if (!PodLining.working(pod) && bore.wantLining && pod.onGround()) {
			bore.wantLining = false;
			bore.lastPressY = pod.blockPosition().getY();
			PodLining.toggle(bore.pilot.player());
			if (PodLining.working(pod)) {
				bore.liningSessions++;
			} else if (PodLining.of(pod).dry()) {
				bore.dryPresses++;
			}
		}
		if (PodLining.working(pod)) {
			bore.idleTicks = 0;
			return true;
		}
		return false;
	}

	/** True when the thermal slice marks lava, bright or near, within {@code slabs} below the pod's feet and {@link #LINING_REACH} blocks along the plane. */
	private static boolean lavaWithin(ScanSlice thermal, int slabs) {
		for (int up = 0; up >= -slabs; up--) {
			for (int ahead = -LINING_REACH; ahead <= LINING_REACH; ahead++) {
				ScanSlice.Cell cell = thermal.cell(ahead, up);
				if (cell == ScanSlice.Cell.LAVA || cell == ScanSlice.Cell.LAVA_NEAR) {
					return true;
				}
			}
		}
		return false;
	}

	private static void startSidestep(Bore bore, PodEntity pod, Side side) {
		bore.phase = Phase.SIDEWAYS;
		bore.side = side;
		bore.sideStart = side.along(pod);
		bore.lastAlong = bore.sideStart;
		bore.idleTicks = 0;
		bore.pilot.setInput(side.input);
	}

	/**
	 * The side to step to: at a new depth the one the bot did not take last time; at the depth of the last sidestep, where the column
	 * it reached is refused too, on in the same direction, then to the sides, then back. Never a side that takes the pod
	 * more than {@link #MAX_DRIFT} blocks from its first column, and never one already refused from here. If every side is refused
	 * the answer is one that is, and the caller gives up this attempt.
	 */
	private static Side firstSide(Bore bore, PodEntity pod, boolean sameDepth) {
		Side last = bore.lastSide;
		Side[] order = sameDepth
				? new Side[] {last, last.turnedRight(), last.turnedLeft(), last.opposite()}
				: new Side[] {last.opposite(), last.turnedRight(), last.turnedLeft(), last};
		for (Side side : order) {
			double after = side.dx != 0 ? pod.getX() + side.dx * SIDE_STEP - bore.centreX : pod.getZ() + side.dz * SIDE_STEP - bore.centreZ;
			if (!bore.refused.contains(side) && Math.abs(after) <= MAX_DRIFT) {
				return side;
			}
		}
		return order[0];
	}

	/** For the message of a stalled bore only: what is under the pod, and whether the drill may change it (it refuses at an unloaded chunk edge). */
	private static List<String> cellsBelow(ServerLevel level, PodEntity pod) {
		int lowX = (int) Math.floor(pod.getX() - 0.5);
		int lowZ = (int) Math.floor(pod.getZ() - 0.5);
		int y = pod.blockPosition().getY() - 1;
		List<String> cells = new ArrayList<>();
		for (int x = lowX; x <= lowX + 1; x++) {
			for (int z = lowZ; z <= lowZ + 1; z++) {
				BlockPos pos = new BlockPos(x, y, z);
				cells.add(level.getBlockState(pos).getBlock().builtInRegistryHolder().key().identifier().getPath() + (level.hasChunkAt(pos.north()) && level.hasChunkAt(pos.south()) && level.hasChunkAt(pos.east()) && level.hasChunkAt(pos.west()) ? "" : "(edge)"));
			}
		}
		return cells;
	}

	private static void finish(Bore bore, Outcome outcome, String cause, PodEntity pod) {
		bore.outcome = outcome;
		bore.cause = cause;
		bore.endHull = pod.hull();
		bore.endPilotHealth = bore.pilot.player().getHealth();
	}

	// ---- lava ----

	/**
	 * Reads the scanner as the game does and notes each lava block it shows as open space, with how high the pod was when it first did.
	 * Then reads a scanner of the thermal tier from the same spot, notes each lava block it marks as lava, and returns that reading.
	 */
	private static ScanSlice noteLavaInView(ServerLevel level, PodEntity pod, Bore bore, int feetY) {
		ScanSlice thermal = ScanSlice.scan(new LoadedBlocks(level), pod.blockPosition(), pod.getDirection(), THERMAL_TIER);
		ScanArea thermalArea = thermal.area();
		for (int up = thermalArea.up(); up >= -thermalArea.down(); up--) {
			for (int ahead = -thermalArea.halfWidth(); ahead <= thermalArea.halfWidth(); ahead++) {
				ScanSlice.Cell cell = thermal.cell(ahead, up);
				if (cell != ScanSlice.Cell.LAVA && cell != ScanSlice.Cell.LAVA_NEAR) {
					continue;
				}
				// A marked cell, bright or near, stands for the lava in the plane or beside it: every lava block of that band is shown.
				BlockPos inPlane = pod.blockPosition().relative(pod.getDirection(), ahead).above(up);
				for (int offset = -ScannerTuning.DEFAULT.lavaSpread(); offset <= ScannerTuning.DEFAULT.lavaSpread(); offset++) {
					BlockPos pos = inPlane.relative(pod.getDirection().getClockWise(), offset);
					if (level.getFluidState(pos).is(FluidTags.LAVA)) {
						bore.thermalShownAt.merge(pos.immutable(), feetY, Math::max);
					}
				}
			}
		}
		Optional<ScanSlice> slice = ScanSlice.scan(new LoadedBlocks(level), pod);
		if (slice.isEmpty()) {
			throw new IllegalStateException("the pod has no working scanner");
		}
		ScanArea area = slice.get().area();
		Direction facing = pod.getDirection();
		BlockPos origin = pod.blockPosition();
		for (int up = area.up(); up >= -area.down(); up--) {
			for (int ahead = -area.halfWidth(); ahead <= area.halfWidth(); ahead++) {
				if (slice.get().cell(ahead, up) != ScanSlice.Cell.AIR) {
					continue;
				}
				BlockPos pos = origin.relative(facing, ahead).above(up);
				if (level.getFluidState(pos).is(FluidTags.LAVA)) {
					bore.shownAt.merge(pos.immutable(), feetY, Math::max);
				}
			}
		}
		return thermal;
	}

	/** The pod has just touched lava: the whole body of lava it touches, and the earliest slab at which the scanner had any of it in view. */
	private static Encounter encounter(ServerLevel level, PodEntity pod, Bore bore) {
		AABB box = pod.getBoundingBox().inflate(LavaHazard.TOUCH_REACH);
		Set<BlockPos> body = new HashSet<>();
		Set<BlockPos> touched = new HashSet<>();
		ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
			if (level.getFluidState(pos).is(FluidTags.LAVA) && body.add(pos.immutable())) {
				touched.add(pos.immutable());
				frontier.add(pos.immutable());
			}
		}
		while (!frontier.isEmpty() && body.size() < LAVA_BODY_LIMIT) {
			BlockPos from = frontier.poll();
			for (Direction direction : Direction.values()) {
				BlockPos next = from.relative(direction);
				if (level.getFluidState(next).is(FluidTags.LAVA) && body.add(next)) {
					frontier.add(next);
				}
			}
		}
		int contactY = pod.blockPosition().getY();
		int ahead = slabsAhead(body, bore.shownAt, contactY);
		int contactAhead = slabsAhead(touched, bore.shownAt, contactY);
		int thermalAhead = slabsAhead(body, bore.thermalShownAt, contactY);
		int thermalContactAhead = slabsAhead(touched, bore.thermalShownAt, contactY);
		String zone = Zones.of(level, contactY).map(found -> found.id().getPath()).orElse("outside");
		return new Encounter(zone, contactY, ahead, contactAhead, thermalAhead, thermalContactAhead);
	}

	/** How many slabs above {@code contactY} the pod was when the scanner first had any of {@code cells} in view; -1 for never. */
	private static int slabsAhead(Set<BlockPos> cells, Map<BlockPos, Integer> shownAt, int contactY) {
		int shown = cells.stream().map(shownAt::get).filter(Objects::nonNull).mapToInt(Integer::intValue).max().orElse(Integer.MIN_VALUE);
		return shown == Integer.MIN_VALUE ? -1 : shown - contactY;
	}

	// ---- the report ----

	private static void report(List<Bore> bores, double wallSeconds, long ticks) {
		int n = bores.size();
		long survived = bores.stream().filter(b -> b.outcome == Outcome.SURVIVED).count();
		long died = bores.stream().filter(b -> b.outcome == Outcome.DIED).count();
		long stalled = bores.stream().filter(b -> b.outcome == Outcome.STALLED).count();
		long diedInLava = bores.stream().filter(b -> b.outcome == Outcome.DIED && b.cause.endsWith("in lava")).count();
		long pilotsKilled = bores.stream().filter(b -> b.outcome == Outcome.DIED && b.cause.startsWith("pilot")).count();
		long touched = bores.stream().filter(b -> !b.encounters.isEmpty()).count();
		LOGGER.info("[lava-bore] {} bores, {} columns of layer 1, stock Mole, tier {} scanner (reach {} ahead, {} up, {} down), in time = {} slabs; {} server ticks, {} s wall clock",
				n, n, SCANNER_TIER, ScannerTuning.DEFAULT.area(SCANNER_TIER).halfWidth(), ScannerTuning.DEFAULT.area(SCANNER_TIER).up(),
				ScannerTuning.DEFAULT.area(SCANNER_TIER).down(), IN_TIME_SLABS, ticks, String.format("%.1f", wallSeconds));
		LOGGER.info("[lava-bore] outcome: survived {} of {} ({}), died {} (in lava {}, pilot first {}), stalled {}; bores that touched lava {} ({})",
				survived, n, percent(survived, n), died, diedInLava, pilotsKilled, stalled, touched, percent(touched, n));
		reportLining(bores);
		LOGGER.info("[lava-bore] hull lost to lava, per bore:   {}", distribution(bores, b -> b.lavaHull));
		LOGGER.info("[lava-bore] pilot health lost to lava:     {}", distribution(bores, b -> b.lavaPilot));
		List<Bore> through = bores.stream().filter(b -> b.outcome == Outcome.SURVIVED).toList();
		if (!through.isEmpty()) {
			LOGGER.info("[lava-bore] survivors, hull lost in all:   {}", distribution(through, b -> b.startHull - b.endHull));
			LOGGER.info("[lava-bore] survivors, pilot hp left:      {}", distribution(through, b -> b.endPilotHealth));
		}
		List<Encounter> encounters = bores.stream().flatMap(b -> b.encounters.stream()).toList();
		long shown = encounters.stream().filter(Encounter::shownAtAll).count();
		long inTime = encounters.stream().filter(e -> e.slabsShownAhead() >= IN_TIME_SLABS).count();
		long inTimeForOne = encounters.stream().filter(e -> e.slabsShownAhead() >= 1).count();
		LOGGER.info("[lava-bore] lava encounters: {} ({} per bore); in the scanner's view at all {} ({}), >= 1 slab ahead {} ({}), >= {} slabs ahead {} ({})",
				encounters.size(), String.format("%.2f", encounters.size() / (double) n), shown, percent(shown, encounters.size()),
				inTimeForOne, percent(inTimeForOne, encounters.size()), IN_TIME_SLABS, inTime, percent(inTime, encounters.size()));
		long contactInTime = encounters.stream().filter(e -> e.contactCellsShownAhead() >= IN_TIME_SLABS).count();
		long contactShown = encounters.stream().filter(e -> e.contactCellsShownAhead() >= 0).count();
		LOGGER.info("[lava-bore] strict, the lava blocks actually touched: in view at all {} ({}), >= {} slabs ahead {} ({}). The figures above are for the whole connected body, an upper bound",
				contactShown, percent(contactShown, encounters.size()), IN_TIME_SLABS, contactInTime, percent(contactInTime, encounters.size()));
		long thermalContactInTime = encounters.stream().filter(e -> e.thermalContactCellsShownAhead() >= IN_TIME_SLABS).count();
		long thermalContactShown = encounters.stream().filter(e -> e.thermalContactCellsShownAhead() >= 0).count();
		long thermalInTime = encounters.stream().filter(e -> e.thermalSlabsShownAhead() >= IN_TIME_SLABS).count();
		ScanArea thermalArea = ScannerTuning.DEFAULT.area(THERMAL_TIER);
		LOGGER.info("[lava-bore] thermal tier {} scanner (reach {} ahead, {} up, {} down) marks lava; tier {} shows it as open space. Strict, the lava blocks actually touched: in view at all {} ({}), >= {} slabs ahead {} ({}) against tier {}'s {} ({}). Whole connected body: >= {} slabs ahead {} ({}) against {} ({})",
				THERMAL_TIER, thermalArea.halfWidth(), thermalArea.up(), thermalArea.down(), SCANNER_TIER,
				thermalContactShown, percent(thermalContactShown, encounters.size()), IN_TIME_SLABS, thermalContactInTime, percent(thermalContactInTime, encounters.size()),
				SCANNER_TIER, contactInTime, percent(contactInTime, encounters.size()),
				IN_TIME_SLABS, thermalInTime, percent(thermalInTime, encounters.size()), inTime, percent(inTime, encounters.size()));
		LOGGER.info("[lava-bore] hull lost per encounter:       {}; ticks touching lava per encounter: {}",
				distributionOf(encounters.stream().mapToDouble(e -> e.hullLost).toArray()), distributionOf(encounters.stream().mapToDouble(e -> e.ticks).toArray()));
		List<Encounter> firsts = bores.stream().filter(b -> !b.encounters.isEmpty()).map(b -> b.encounters.getFirst()).toList();
		long firstInTime = firsts.stream().filter(e -> e.slabsShownAhead() >= IN_TIME_SLABS).count();
		LOGGER.info("[lava-bore] first lava of a bore: {}; >= {} slabs ahead {} ({}); slabs ahead (-1 = never in view): {}",
				firsts.size(), IN_TIME_SLABS, firstInTime, percent(firstInTime, firsts.size()),
				distributionOf(firsts.stream().mapToDouble(Encounter::slabsShownAhead).toArray()));
		int deepClaimTop = Zones.span(0, 192, 2).high();
		long firstThermalContactInTime = firsts.stream().filter(e -> e.thermalContactCellsShownAhead() >= IN_TIME_SLABS).count();
		LOGGER.info("[lava-bore] first lava of a bore, thermal tier, the touched blocks: >= {} slabs ahead {} of {} ({}); slabs ahead (-1 = never in view): {}",
				IN_TIME_SLABS, firstThermalContactInTime, firsts.size(), percent(firstThermalContactInTime, firsts.size()),
				distributionOf(firsts.stream().mapToDouble(Encounter::thermalContactCellsShownAhead).toArray()));
		LOGGER.info("[lava-bore] slabs bored in Deep Claim before the first lava (mean {}; 1 / that is the lava chance per slab, censored by the bores that end first); hull left when a bore ended in lava: {}",
				String.format("%.1f", firsts.stream().filter(e -> e.zone().equals("deep_claim") || e.zone().equals("stone_benches")).mapToInt(e -> Math.max(0, deepClaimTop - e.contactY() + 1)).average().orElse(0)),
				distribution(bores.stream().filter(b -> b.outcome == Outcome.DIED && b.cause.endsWith("in lava")).toList(), b -> b.endHull));
		Map<String, Long> byZone = new TreeMap<>();
		encounters.forEach(e -> byZone.merge(e.zone(), 1L, Long::sum));
		LOGGER.info("[lava-bore] encounters by zone (the first of each bore is the bulk): {}", byZone);
		LOGGER.info("[lava-bore] the bot: {} sidesteps per bore, {} tanks per bore, {} pod ticks per bore",
				String.format("%.1f", bores.stream().mapToInt(b -> b.sidesteps).average().orElse(0)),
				String.format("%.1f", bores.stream().mapToInt(b -> b.tanks).average().orElse(0)),
				String.format("%.0f", bores.stream().mapToInt(b -> b.pilotTicks).average().orElse(0)));
		for (Bore b : bores) {
			LOGGER.info("[lava-bore] bore {}: {} {} lava hull {} pilot {} hull {}/{} hp {} encounters {} start y {} lowest y {} sidesteps {} tanks {} worst non-lava hit {} at y {}",
					b.index, b.outcome, b.cause, String.format("%.1f", b.lavaHull), String.format("%.1f", b.lavaPilot),
					String.format("%.1f", b.endHull), String.format("%.0f", b.startHull), String.format("%.1f", b.endPilotHealth),
					b.encounters.stream().map(e -> e.zone() + "@" + e.contactY() + "(+" + e.slabsShownAhead() + ")").toList(),
					b.startY, b.lowestFeetY, b.sidesteps, b.tanks, String.format("%.1f", b.biggestOtherHit), b.biggestOtherHitY);
		}
		if (n >= VERDICT_FROM) {
			double rate = survived / (double) n;
			LOGGER.info("[lava-bore] verdict: survival {} of a stock Mole is {} the near-hopeless line of {}", percent(survived, n),
					rate < NEAR_HOPELESS_SURVIVAL ? "UNDER" : "at or over", String.format("%.0f%%", NEAR_HOPELESS_SURVIVAL * 100));
		}
	}

	/** What the lining bot did and what it cost, when there was one: the bricks, the sessions and the time the pod stood still. */
	private static void reportLining(List<Bore> bores) {
		List<Bore> lining = bores.stream().filter(b -> b.lookahead > 0).toList();
		if (lining.isEmpty()) {
			LOGGER.info("[lava-bore] lining: off, the bot never lines");
			return;
		}
		Bore first = lining.getFirst();
		LOGGER.info("[lava-bore] lining: on for {} bores, the bot lines when the thermal tier marks lava within {} slabs below and {} blocks across; the rack starts with {} bricks",
				lining.size(), first.lookahead, LINING_REACH, first.startBricks);
		LOGGER.info("[lava-bore] lining, sessions per bore:     {}", distribution(lining, b -> b.liningSessions));
		LOGGER.info("[lava-bore] lining, bricks placed per bore: {}", distribution(lining, b -> b.bricksPlaced));
		LOGGER.info("[lava-bore] lining, ticks standing still while lining per bore: {} (of {} pod ticks per bore)",
				distribution(lining, b -> b.liningTicks), String.format("%.0f", lining.stream().mapToInt(b -> b.pilotTicks).average().orElse(0)));
		LOGGER.info("[lava-bore] lining, lava encounters: {} began with a lining asked for and not done (the pod was falling, and the bot lines only at rest), {} within {} slabs below a lining, {} with none asked for",
				lining.stream().mapToInt(b -> b.unservedTouches).sum(), lining.stream().mapToInt(b -> b.linedTouches).sum(), RECENT_PRESS_SLABS,
				lining.stream().mapToInt(b -> b.unseenTouches).sum());
		long outOfBrick = lining.stream().filter(b -> b.dryPresses > 0).count();
		LOGGER.info("[lava-bore] lining, bores that pressed the key with no brick left: {} of {} ({})", outOfBrick, lining.size(), percent(outOfBrick, lining.size()));
	}

	private static String distribution(List<Bore> bores, ToDoubleFunction<Bore> value) {
		return distributionOf(bores.stream().mapToDouble(value).toArray());
	}

	/** Mean, and the nearest-rank 10th, 50th, 90th and 99th percentiles, and the worst. */
	private static String distributionOf(double[] values) {
		if (values.length == 0) {
			return "none";
		}
		double[] sorted = values.clone();
		Arrays.sort(sorted);
		DoubleSummaryStatistics stats = Arrays.stream(sorted).summaryStatistics();
		return String.format("mean %.1f, min %.1f, p10 %.1f, p50 %.1f, p90 %.1f, p99 %.1f, max %.1f",
				stats.getAverage(), sorted[0], rank(sorted, 10), rank(sorted, 50), rank(sorted, 90), rank(sorted, 99), sorted[sorted.length - 1]);
	}

	private static double rank(double[] sorted, int percentile) {
		int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
		return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
	}

	private static String percent(long part, long whole) {
		return whole == 0 ? "n/a" : String.format("%.0f%%", 100.0 * part / whole);
	}

	/**
	 * What the test asserts: the harness measured something. A measurement run asserts no rate. The smoke case asserts that no bore got
	 * stuck (a bore the bot cannot finish says nothing about lava), that the bore with a placed lava block records it as an encounter the
	 * scanner had in view, and that the control crossed. The game test world has a fixed seed (vanilla's GameTestServer uses 0, and the
	 * same columns gave the same start heights and first lava on every run). The smoke case leans on it in one place: {@link #SMOKE_LAVA_BLOCK_Y}
	 * was picked for seed 0 (above any lava that seed puts in the shaft). Otherwise the lava is placed, and the control bores only the
	 * crust under a shaft cut through whatever the seed made.
	 */
	private static void check(GameTestHelper helper, List<Bore> bores, boolean smoke) {
		int reach = ScannerTuning.DEFAULT.tierOneArea().down() + ScannerTuning.DEFAULT.tierOneArea().up();
		int thermalReach = ScannerTuning.DEFAULT.area(THERMAL_TIER).down() + ScannerTuning.DEFAULT.area(THERMAL_TIER).up();
		for (Bore bore : bores) {
			if (bore.encounters.stream().anyMatch(e -> e.slabsShownAhead() > reach)) {
				throw helper.assertionException(Component.literal("bore " + bore.index + ": the scanner showed lava from farther than its reach of " + reach + " slabs, so the lead is mismeasured"));
			}
			if (bore.encounters.stream().anyMatch(e -> e.thermalSlabsShownAhead() > thermalReach)) {
				throw helper.assertionException(Component.literal("bore " + bore.index + ": the thermal scanner showed lava from farther than its reach of " + thermalReach + " slabs, so the lead is mismeasured"));
			}
			// The thermal tier's slice contains the tier 1 slice, so it marks every block that tier 1 shows as open space.
			if (bore.encounters.stream().anyMatch(e -> e.thermalContactCellsShownAhead() < e.contactCellsShownAhead())) {
				throw helper.assertionException(Component.literal("bore " + bore.index + ": the thermal scanner showed the touched lava later than tier 1 did, so the thermal reading is wrong"));
			}
		}
		if (!smoke) {
			return;
		}
		List<String> stuck = bores.stream().filter(b -> b.outcome == Outcome.STALLED).map(b -> "bore " + b.index + " " + b.cause).toList();
		if (!stuck.isEmpty()) {
			throw helper.assertionException(Component.literal("the bot got stuck, so the harness measures nothing: " + stuck));
		}
		Bore lavaBore = bores.get(1);
		if (lavaBore.encounters.isEmpty() || lavaBore.encounters.getFirst().contactCellsShownAhead() < 1
				|| lavaBore.encounters.getFirst().thermalContactCellsShownAhead() < 1) {
			throw helper.assertionException(Component.literal("the smoke bore meets a lava block that was placed in its path and in the scan plane, so it must record an encounter that the scanner had in view ahead of contact: "
					+ lavaBore.encounters.stream().map(e -> e.zone() + "@" + e.contactY() + " body +" + e.slabsShownAhead() + " contact +" + e.contactCellsShownAhead()).toList()));
		}
		Bore control = bores.getFirst();
		if (control.outcome != Outcome.SURVIVED || control.endHull >= control.startHull) {
			throw helper.assertionException(Component.literal("the control bore through the crust should reach layer 2 alive and lose hull to the crust: " + control.outcome + " " + control.cause
					+ ", hull " + control.startHull + " to " + control.endHull));
		}
		// The lining bot sees the placed lava block on the thermal tier from the start, lines the floor over it, and bores through the brick.
		Bore liner = bores.get(SMOKE_LINING_BORE);
		if (liner.liningSessions < 1 || liner.bricksPlaced < 1 || liner.encounters.stream().anyMatch(e -> Math.abs(e.contactY() - SMOKE_LAVA_BLOCK_Y) <= 2)) {
			throw helper.assertionException(Component.literal("the lining bore meets the same lava block as the bore before it, and must line over it and pass it dry: "
					+ liner.liningSessions + " sessions, " + liner.bricksPlaced + " bricks, encounters "
					+ liner.encounters.stream().map(e -> e.zone() + "@" + e.contactY()).toList()));
		}
	}

	private static ServerLevel layer(GameTestHelper helper, int layer) {
		ServerLevel level = helper.getLevel().getServer().getLevel(LayerChain.dimension(layer));
		if (level == null) {
			throw helper.assertionException(Component.literal("dimension " + LayerChain.dimension(layer) + " did not load"));
		}
		return level;
	}
}
