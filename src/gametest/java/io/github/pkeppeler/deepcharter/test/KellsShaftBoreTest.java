package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.layer.LavaHazard;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.layer.StructureKind;
import io.github.pkeppeler.deepcharter.layer.StructureSite;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLining;
import io.github.pkeppeler.deepcharter.pod.PodLiningTuning;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.test.support.Arrival;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Kells' shaft against a straight bore (#716). For each shaft a stock Mole goes down it, and a Mole bores straight down a
 * column {@value #BESIDE} blocks to the side, from the same height (the rail line's floor) to the crust. The columns:
 *
 * <ul>
 *   <li>{@code shaft, lined}: the pilot descends on the rotor and, when lava stands in the wall within reach of the pod, hovers
 *       and presses the lining key (hand lining, #313), then goes on;
 *   <li>{@code shaft, bare}: the same descent and no lining;
 *   <li>{@code bore, bare}: the bot of {@code LavaBoreTest} (sprint to drill, step two blocks aside from a refused slab, brake in a fall);
 *   <li>{@code bore, lined}: that bot with the hand lining it has: when the pod rests on a new slab with lava within 3 slabs below
 *       it and 2 blocks across, it presses the key and waits. It reads the lava off the blocks, not off a scanner, so it is the
 *       best hand-lining pilot of the four.
 * </ul>
 *
 * <p>The default run is a smoke case of one shaft and its two shaft columns: the lined Mole must reach the crust with every gap
 * lined and no touch of lava, and the bare one must meet the gaps. It proves the shaft can be gone down and re-lined. The measurement
 * is the same test with {@value #SHAFTS_ENV}{@code =<n>} in the environment, for example
 * {@code DEEPCHARTER_KELLS_SHAFTS=30 tools/gametest.sh 'kells_shaft_bore_test*'}: n shafts, all four columns each, printing lines that
 * start with {@code [kells-bore]}. Every pod has a tier 1 scanner and a rack of {@code brickCapacity} bricks, and the pilot
 * never leaves the seat or reads more than the lining key. The tank is topped up when it runs low.
 */
public class KellsShaftBoreTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(KellsShaftBoreTest.class);
    static final String SHAFTS_ENV = "DEEPCHARTER_KELLS_SHAFTS";
    private static final String REQUESTED_SHAFTS = System.getenv(SHAFTS_ENV);
    /** Set to 1 with {@value #SHAFTS_ENV} to run only the pilot with no hopper (a fifth of the pods in one server would run out of memory). */
    static final String RESERVE_ENV = "DEEPCHARTER_KELLS_RESERVE";

    private static final boolean RESERVE_ONLY = "1".equals(System.getenv(RESERVE_ENV));
    private static final List<Column> MAIN_COLUMNS = Arrays.stream(Column.values())
            .filter(column -> column != Column.SHAFT_RESERVE)
            .toList();
    /** The layer the Kells' shaft is in. */
    private static final int LAYER = StructureKind.KELLS_SHAFT.layer();

    private static final int SMOKE_SHAFTS = 1;
    /** The spacing cells of the shafts: one cell each, along x, far from every other test. */
    private static final int FIRST_CELL_X = 40;

    private static final int CELL_Z = 40;
    /** Blocks across the line from the shaft's centre to the centre of the straight bore. */
    static final int BESIDE = 8;
    /** The pilot lets the pod sink this fast down the shaft, in blocks a tick, and holds the rotor to keep it from going faster. */
    private static final double SHAFT_SINK = 0.3;
    /** The straight bore brakes in a fall as {@code LavaBoreTest}'s bot does. */
    private static final double BRAKE_ABOVE_SINK = EarlyRunModel.DRIVE_DOWN_SINK;

    private static final int STUCK_TICKS = 12;
    private static final int SIDE_STEP = 2;
    private static final int NO_PROGRESS_TICKS = 2000;
    private static final int RUN_TICK_BUDGET = 20000;
    /** Touches of lava closer together than this are one contact: two gaps are about 7 ticks apart on the way down. */
    private static final int CONTACT_GAP_TICKS = 4;

    private static final int CRUST_BAND = LayerTuning.DEFAULT.crustThickness() + 1;
    private static final float REFUEL_BELOW_PERCENT = 3f;
    /** Slabs below a resting pod, and blocks across it, in which lava makes the lined bore press the key. */
    private static final int LINING_LOOKAHEAD = 3;
    /** Bricks in the pack of the pilot with no hopper: more than any shaft takes, so the run measures the cost, not the supply. */
    private static final int CARRIED_BRICKS = 64;

    private static final int LINING_SPREAD = 2;
    private static final Input IDLE = new Input(false, false, false, false, false, false, false);
    private static final Input ROTOR = new Input(false, false, false, false, true, false, false);
    private static final Input SPRINT = new Input(false, false, false, false, false, false, true);
    private static final Input STRAFE_EAST = new Input(false, false, true, false, false, false, false);
    private static final Input STRAFE_WEST = new Input(false, false, false, true, false, false, false);
    private static final Input DRIVE_SOUTH = new Input(true, false, false, false, false, false, false);
    private static final Input DRIVE_NORTH = new Input(false, true, false, false, false, false, false);

    /** A way to step aside from a refused slab: the pod faces south, and the input that drives it that way. */
    private enum Side {
        EAST(1, 0, STRAFE_EAST),
        WEST(-1, 0, STRAFE_WEST),
        SOUTH(0, 1, DRIVE_SOUTH),
        NORTH(0, -1, DRIVE_NORTH);

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

        /** The next side to try, round the compass. */
        Side next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private enum Column {
        SHAFT_LINED("shaft, lined", true, true, true),
        SHAFT_BARE("shaft, bare", true, false, false),
        BORE_BARE("bore, bare", false, false, false),
        BORE_LINED("bore, lined", false, true, true),
        /** No spoil hopper: the pilot lines from slag brick carried in the pack, as from the reserve at the head. */
        SHAFT_RESERVE("shaft, no hopper, bricks carried", true, true, false);

        final String label;
        final boolean inShaft;
        final boolean lines;
        final boolean hasHopper;

        Column(String label, boolean inShaft, boolean lines, boolean hasHopper) {
            this.label = label;
            this.inShaft = inShaft;
            this.lines = lines;
            this.hasHopper = hasHopper;
        }
    }

    private enum Outcome {
        CRUST,
        DIED_IN_LAVA,
        DIED_OTHER,
        STALLED
    }

    /** One pod going down one column, and what the report needs of it. */
    private static final class Run {
        final Column column;
        final StructureSite site;
        final MockPlayer pilot;
        final ServerLevel level;
        final double centreX;
        final double centreZ;
        final int startFeetY;
        final Arrival<PodEntity> pod = new Arrival<>("pod", "the far chunk ticks");

        @Nullable
        Outcome outcome;

        int ticks;
        int lowestFeetY = Integer.MAX_VALUE;
        int lastProgressTick;
        float lastHull;
        float hullLostToLava;
        int lavaTicks;
        int contacts;
        long lastTouchTick = Long.MIN_VALUE / 2;
        int startBricks;
        int bricksUsed;
        /** The bricks used when the pod first sank below the highest gap's bulkhead: what lining one gap took. */
        int firstGapBricks = -1;

        int liningPresses;
        float endHull;
        // The straight bore's bot.
        boolean sideways;
        int idleTicks;
        Side side = Side.NORTH;
        double sideStart;
        double lastAlong;

        @Nullable
        BlockPos lastLooked;

        boolean wantLining;

        Run(
                Column column,
                StructureSite site,
                MockPlayer pilot,
                ServerLevel level,
                double centreX,
                double centreZ,
                int startFeetY) {
            this.column = column;
            this.site = site;
            this.pilot = pilot;
            this.level = level;
            this.centreX = centreX;
            this.centreZ = centreZ;
            this.startFeetY = startFeetY;
        }

        boolean done() {
            return outcome != null;
        }
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + RUN_TICK_BUDGET + 2000)
    public void aMoleGoesDownTheKellsShaftAndLinesItsGapsAgainstAStraightBore(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel level = layer(helper);
        BlockPos conduit = Colony.anchor(server, ColonyAnchor.CONDUIT)
                .orElseThrow(() -> helper.assertionException(Component.literal("the colony was not built")));
        boolean smoke = REQUESTED_SHAFTS == null || REQUESTED_SHAFTS.isBlank();
        int shafts = smoke ? SMOKE_SHAFTS : shaftCount(helper);
        List<Column> columns = smoke
                ? List.of(Column.SHAFT_LINED, Column.SHAFT_BARE)
                : RESERVE_ONLY ? List.of(Column.SHAFT_RESERVE) : MAIN_COLUMNS;
        List<Run> runs = new ArrayList<>();
        List<BlockPos> probes = new ArrayList<>();
        CharterId charter = null;
        for (int i = 0; i < shafts; i++) {
            // Two shafts: a pod that lines a gap changes its shaft, so each shaft column has its own.
            // The straight bores go down beside the first, one on each side.
            StructureSite bareShaft = shaftIn(level, conduit, FIRST_CELL_X + 2 * i);
            StructureSite linedShaft = shaftIn(level, conduit, FIRST_CELL_X + 2 * i + 1);
            for (Column column : columns) {
                // With no bare pod in its server, the pilot with no hopper takes the bare pod's cell.
                StructureSite site = column == Column.SHAFT_LINED ? linedShaft : bareShaft;
                int side = column == Column.BORE_LINED ? -BESIDE : column.inShaft ? 0 : BESIDE;
                MockPlayer pilot = MockPlayers.join(helper, "Kells pilot " + runs.size());
                pilot.player().setGameMode(GameType.SURVIVAL);
                charter = LavaBoreTest.joinTheCharter(helper, server, pilot, charter, runs.size());
                double centreX = site.origin().getX() + (site.alongZ() ? side : 0);
                double centreZ = site.origin().getZ() + (site.alongZ() ? 0 : side);
                int railFloor = site.origin().getY() + site.height() - 3;
                // The pilot is in the chunk from the start, which keeps it loaded until the pod is ready.
                pilot.teleportTo(level, new Vec3(centreX, railFloor, centreZ), 0f, 0f);
                runs.add(new Run(column, site, pilot, level, centreX, centreZ, railFloor));
                probes.add(new BlockPos((int) centreX, railFloor, (int) centreZ));
            }
        }
        long wallStart = System.nanoTime();
        boolean[] reported = {false};
        TestTicks.each(helper, () -> {
            if (reported[0]) {
                return;
            }
            for (Run run : runs) {
                if (run.pod.isPresent() && !run.done()) {
                    step(run);
                }
            }
            if (runs.stream().allMatch(Run::done)) {
                reported[0] = true;
                report(runs, shafts, (System.nanoTime() - wallStart) / 1_000_000_000.0, helper.getTick());
                if (smoke) {
                    check(helper, runs);
                }
                helper.succeed();
            }
        });
        FarChunks.awaitEntityTicking(helper, level, probes, index -> launch(helper, server, runs.get(index)));
    }

    /** The shaft of a spacing cell, with the chunks round it generated: the straight bores go down beside it. */
    private static StructureSite shaftIn(ServerLevel level, BlockPos conduit, int cellX) {
        StructureSite site = StructureSite.in(
                level.getSeed(), StructureKind.KELLS_SHAFT, level.getMinY(), level.getHeight(), cellX, CELL_Z, conduit);
        for (int x = (site.origin().getX() - BESIDE - 2) >> 4;
                x <= (site.origin().getX() + BESIDE + 2) >> 4;
                x++) {
            for (int z = (site.origin().getZ() - BESIDE - 2) >> 4;
                    z <= (site.origin().getZ() + BESIDE + 2) >> 4;
                    z++) {
                level.getChunk(x, z, ChunkStatus.FULL);
            }
        }
        return site;
    }

    private static int shaftCount(GameTestHelper helper) {
        try {
            int number = Integer.parseInt(REQUESTED_SHAFTS.trim());
            if (number < 1) {
                throw new NumberFormatException();
            }
            return number;
        } catch (NumberFormatException e) {
            throw helper.assertionException(Component.literal(
                    SHAFTS_ENV + " must be a whole number, 1 or more, not '" + REQUESTED_SHAFTS + "'"));
        }
    }

    /** The chunk is entity-ticking: the pod goes to the top of its column and the pilot mounts. */
    private static void launch(GameTestHelper helper, MinecraftServer server, Run run) {
        ServerLevel level = run.level;
        if (!run.column.inShaft) {
            // A start pocket in the rock, 2 x 2 and as tall as the pod, with its shell sealed.
            RoomCarver.carve(
                    level,
                    (int) run.centreX - 1,
                    (int) run.centreX,
                    run.startFeetY,
                    run.startFeetY + 2,
                    (int) run.centreZ - 1,
                    (int) run.centreZ,
                    Blocks.AIR);
        }
        PodEntity pod = TestEntities.create(PodRegistry.typeOf(Chassis.MOLE), level);
        pod.setPos(run.centreX, run.startFeetY, run.centreZ);
        level.addFreshEntity(pod);
        // Hand lining needs the scanner of tier 2 on the feature map; the bare pods keep the stock tier 1.
        ScannerPods.fit(server, run.pilot.player(), pod, run.column.lines ? 2 : 1);
        if (run.column.hasHopper) {
            ScannerPods.fit(server, run.pilot.player(), pod, ComponentTrack.SPOIL_HOPPER, 1);
        }
        if (!run.pilot.player().startRiding(pod)) {
            throw helper.assertionException(
                    Component.literal("a pilot could not mount the pod at the shaft " + run.site.origin()));
        }
        if (run.column == Column.SHAFT_RESERVE) {
            run.pilot.player().getInventory().add(new ItemStack(SlagBrick.item(), CARRIED_BRICKS));
            run.startBricks = CARRIED_BRICKS;
        } else if (run.column.lines) {
            int bricks = PodLiningTuning.DEFAULT.brickCapacity();
            PodLining.modify(pod, state -> new PodLining.State(0, bricks, 0, false, false));
            run.startBricks = bricks;
        }
        run.lastHull = pod.hull();
        run.pod.set(pod);
        run.pilot.setInput(run.column.inShaft ? IDLE : SPRINT);
    }

    private static void step(Run run) {
        PodEntity pod = run.pilot.player().getVehicle() instanceof PodEntity ridden ? ridden : run.pod.get();
        run.pod.replace(pod);
        run.ticks++;
        boolean touching = LavaHazard.touchesLava(pod);
        float hullLost = run.lastHull - pod.hull();
        run.lastHull = pod.hull();
        if (touching) {
            run.lavaTicks++;
            if (run.ticks - run.lastTouchTick > CONTACT_GAP_TICKS) {
                run.contacts++;
            }
            run.lastTouchTick = run.ticks;
            run.hullLostToLava += Math.max(0f, hullLost);
        }
        if (!run.pilot.player().isAlive() || pod.hull() <= 0f) {
            boolean lava = touching || run.ticks - run.lastTouchTick <= CONTACT_GAP_TICKS * 4;
            finish(run, lava ? Outcome.DIED_IN_LAVA : Outcome.DIED_OTHER, pod);
            return;
        }
        int feetY = pod.blockPosition().getY();
        if (feetY <= run.level.getMinY() + CRUST_BAND) {
            finish(run, Outcome.CRUST, pod);
            return;
        }
        if (run.column.inShaft && run.column.lines && run.firstGapBricks < 0) {
            int[] rows = StructureKind.kellsGapRows(run.site);
            if (feetY < run.site.origin().getY() + rows[rows.length - 1] - 1) {
                run.firstGapBricks = run.startBricks - bricksLeft(run, pod);
            }
        }
        if (feetY < run.lowestFeetY) {
            run.lowestFeetY = feetY;
            run.lastProgressTick = run.ticks;
        }
        if (run.ticks - run.lastProgressTick > NO_PROGRESS_TICKS || run.ticks > RUN_TICK_BUDGET) {
            BlockPos below = pod.blockPosition().below();
            LOGGER.info(
                    "[kells-bore] stalled {} at {} ground {} drilling {} sideways {} idle {} fuel {} stranded {} below {}",
                    run.column.label,
                    pod.position(),
                    pod.onGround(),
                    pod.drilling(),
                    run.sideways,
                    run.idleTicks,
                    pod.fuel(),
                    pod.stranded(),
                    BlockPos.betweenClosedStream(below.offset(-1, 0, -1), below.offset(1, 0, 1))
                            .map(c -> c.toShortString() + "="
                                    + pod.level().getBlockState(c).getBlock().getDescriptionId())
                            .toList());
            finish(run, Outcome.STALLED, pod);
            return;
        }
        if (pod.fuel() < REFUEL_BELOW_PERCENT) {
            pod.setFuel(PodTuning.DEFAULT.shell().fullFuel());
            pod.setStranded(false);
        }
        if (run.column.inShaft) {
            driveShaft(run, pod);
        } else {
            driveBore(run, pod);
        }
    }

    /** The bricks the pilot can still line with: the rack and the pack. */
    private static int bricksLeft(Run run, PodEntity pod) {
        Inventory pack = run.pilot.player().getInventory();
        int carried = 0;
        for (int slot = 0; slot < pack.getContainerSize(); slot++) {
            ItemStack stack = pack.getItem(slot);
            if (stack.is(SlagBrick.item())) {
                carried += stack.getCount();
            }
        }
        return PodLining.of(pod).bricks() + carried;
    }

    private static void finish(Run run, Outcome outcome, PodEntity pod) {
        run.outcome = outcome;
        run.endHull = pod.hull();
        run.bricksUsed = run.startBricks - bricksLeft(run, pod);
        pod.discard();
        run.pilot.releaseInput();
    }

    /**
     * Down the shaft: the pod sinks at {@link #SHAFT_SINK} and the pilot holds the rotor to slow it, and lands on each bulkhead. A lined run
     * presses the lining key there when a lava cell is in the lining's reach (hand lining stops the rotor, so the pod must rest to line),
     * and both runs drill the bulkhead once the pod rests and the lining, if any, is done.
     */
    private static void driveShaft(Run run, PodEntity pod) {
        boolean lining = PodLining.working(pod);
        if (run.column.lines && !lining && pod.onGround() && lavaToLine(pod)) {
            PodLining.toggle(run.pilot.player());
            lining = PodLining.working(pod);
            if (lining) {
                run.liningPresses++;
            }
        }
        boolean rotor = !pod.onGround() && pod.getDeltaMovement().y < -SHAFT_SINK;
        run.pilot.setInput(rotor ? ROTOR : !lining && pod.onGround() ? SPRINT : IDLE);
    }

    /** Whether the lining has a lava cell to take: the pilot sees it glowing in the wall. */
    private static boolean lavaToLine(PodEntity pod) {
        return PodLining.cellsToLine(pod).stream()
                .anyMatch(cell -> pod.level().getFluidState(cell).is(FluidTags.LAVA));
    }

    /** A straight bore, as {@code LavaBoreTest}'s bot drives it, down to the crust. */
    private static void driveBore(Run run, PodEntity pod) {
        Input base = run.sideways ? run.side.input : SPRINT;
        if (!pod.onGround() && -pod.getDeltaMovement().y > BRAKE_ABOVE_SINK) {
            run.pilot.setInput(new Input(
                    base.forward(), base.backward(), base.left(), base.right(), true, base.shift(), base.sprint()));
        } else {
            run.pilot.setInput(base);
        }
        if (run.column.lines) {
            if (!Objects.equals(pod.blockPosition(), run.lastLooked)) {
                run.lastLooked = pod.blockPosition();
                run.wantLining = lavaWithin(pod);
            }
            if (PodLining.working(pod)) {
                run.idleTicks = 0;
                return;
            }
            if (run.wantLining && pod.onGround()) {
                run.wantLining = false;
                PodLining.toggle(run.pilot.player());
                if (PodLining.working(pod)) {
                    run.liningPresses++;
                    return;
                }
            }
        }
        if (!run.sideways) {
            run.idleTicks = pod.onGround() && !pod.drilling() ? run.idleTicks + 1 : 0;
            if (run.idleTicks >= STUCK_TICKS) {
                startSidestep(run, pod, run.side.next());
            }
        } else {
            double along = run.side.along(pod);
            if (along - run.sideStart >= SIDE_STEP - 0.05) {
                run.sideways = false;
                run.idleTicks = 0;
                return;
            }
            boolean still = Math.abs(along - run.lastAlong) < 1e-4 && !pod.drilling();
            run.lastAlong = along;
            run.idleTicks = still ? run.idleTicks + 1 : 0;
            if (run.idleTicks >= STUCK_TICKS) {
                // The slab beside the pod is refused too: try the next side from where the pod is.
                startSidestep(run, pod, run.side.next());
            }
        }
    }

    private static void startSidestep(Run run, PodEntity pod, Side side) {
        run.sideways = true;
        run.side = side;
        run.sideStart = side.along(pod);
        run.lastAlong = run.sideStart;
        run.idleTicks = 0;
    }

    /** Lava in the {@link #LINING_LOOKAHEAD} slabs below the pod's feet, or beside its box, within {@link #LINING_SPREAD} of it. */
    private static boolean lavaWithin(PodEntity pod) {
        BlockPos feet = pod.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(
                feet.offset(-1 - LINING_SPREAD, -LINING_LOOKAHEAD, -1 - LINING_SPREAD),
                feet.offset(1 + LINING_SPREAD, 2, 1 + LINING_SPREAD))) {
            if (pod.level().getFluidState(pos).is(FluidTags.LAVA)) {
                return true;
            }
        }
        return false;
    }

    // ---- the report ----

    private static void report(List<Run> runs, int shafts, double wallSeconds, long ticks) {
        Map<Column, List<Run>> byColumn = new EnumMap<>(Column.class);
        for (Run run : runs) {
            byColumn.computeIfAbsent(run.column, column -> new ArrayList<>()).add(run);
        }
        LOGGER.info(
                "[kells-bore] {} shafts, {} pods, {} test ticks, {} s of wall time",
                shafts,
                runs.size(),
                ticks,
                String.format("%.0f", wallSeconds));
        List<Run> bareShafts = byColumn.getOrDefault(Column.SHAFT_BARE, List.of());
        LOGGER.info(
                "[kells-bore] shafts: depth {} blocks on average, {} gaps on average (counted after the bare runs, which line none)",
                mean(bareShafts, r -> r.site.height()),
                mean(bareShafts, r -> gapsLeft(r.level, r.site)));
        for (Map.Entry<Column, List<Run>> entry : byColumn.entrySet()) {
            List<Run> column = entry.getValue();
            long reached =
                    column.stream().filter(r -> r.outcome == Outcome.CRUST).count();
            long touched = column.stream().filter(r -> r.contacts > 0).count();
            long diedLava = column.stream()
                    .filter(r -> r.outcome == Outcome.DIED_IN_LAVA)
                    .count();
            long diedOther =
                    column.stream().filter(r -> r.outcome == Outcome.DIED_OTHER).count();
            long stalled =
                    column.stream().filter(r -> r.outcome == Outcome.STALLED).count();
            LOGGER.info(
                    "[kells-bore] {}: reached the crust {}/{}, touched lava {}, died in lava {}, died otherwise {}, stalled {}; contacts per run {}, ticks in lava {}, hull lost to lava {}, bricks {}, presses {}, ticks {}",
                    entry.getKey().label,
                    reached,
                    column.size(),
                    touched,
                    diedLava,
                    diedOther,
                    stalled,
                    mean(column, r -> r.contacts),
                    mean(column, r -> r.lavaTicks),
                    mean(column, r -> r.hullLostToLava),
                    mean(column, r -> r.bricksUsed),
                    mean(column, r -> r.liningPresses),
                    mean(column, r -> r.ticks));
            if (entry.getKey().inShaft && entry.getKey().lines) {
                LOGGER.info(
                        "[kells-bore] {}: bricks per gap {} (the run's bricks over its gaps), bricks for the first gap {} (largest {})",
                        entry.getKey().label,
                        mean(column, r -> (double) r.bricksUsed / StructureKind.kellsGapRows(r.site).length),
                        mean(column, r -> r.firstGapBricks),
                        column.stream().mapToInt(r -> r.firstGapBricks).max().orElse(0));
            }
        }
    }

    private static String mean(List<Run> runs, java.util.function.ToDoubleFunction<Run> value) {
        return String.format("%.2f", runs.stream().mapToDouble(value).average().orElse(0));
    }

    /** The smoke case: the lined Mole is through with the gaps lined and dry, and the bare one met them. */
    private static void check(GameTestHelper helper, List<Run> runs) {
        Run lined = runs.stream()
                .filter(r -> r.column == Column.SHAFT_LINED)
                .findFirst()
                .orElseThrow();
        Run bare = runs.stream()
                .filter(r -> r.column == Column.SHAFT_BARE)
                .findFirst()
                .orElseThrow();
        if (lined.outcome != Outcome.CRUST || bare.outcome != Outcome.CRUST) {
            throw helper.assertionException(
                    Component.literal("both Moles should reach the crust: lined " + lined.outcome + " at hull "
                            + lined.endHull + ", bare " + bare.outcome + " at hull " + bare.endHull));
        }
        int bareGaps = gapsLeft(lined.level, bare.site);
        if (bare.contacts < 1 || bareGaps < 2 || bareGaps > 4) {
            throw helper.assertionException(
                    Component.literal("the bare Mole touched lava " + bare.contacts + " times and left " + bareGaps
                            + " gaps open: the shaft has 2 to 4 gaps and a bare Mole meets each"));
        }
        int linedGaps = gapsLeft(lined.level, lined.site);
        if (lined.contacts != 0 || lined.liningPresses < 2 || linedGaps != 0) {
            throw helper.assertionException(Component.literal("the lined Mole touched lava " + lined.contacts
                    + " times in " + lined.liningPresses + " presses and left " + linedGaps
                    + " gaps open, expected none touched, at least 2 presses and every gap lined"));
        }
        if (lined.bricksUsed < 2) {
            throw helper.assertionException(
                    Component.literal("the lined Mole used " + lined.bricksUsed + " bricks on 2 to 4 gaps"));
        }
    }

    /** The side cells of the shaft's ring that still hold lava: the gaps no pod has lined. */
    private static int gapsLeft(ServerLevel level, StructureSite site) {
        int left = 0;
        for (int y = 0; y < site.height(); y++) {
            for (StructureKind.RingSide side : StructureKind.kellsRingSides()) {
                int x = site.origin().getX() + (site.alongZ() ? side.v() : side.u());
                int z = site.origin().getZ() + (site.alongZ() ? side.u() : side.v());
                if (level.getBlockState(new BlockPos(x, site.origin().getY() + y, z))
                        .is(Blocks.LAVA)) {
                    left++;
                }
            }
        }
        return left;
    }

    private static ServerLevel layer(GameTestHelper helper) {
        return TestLayers.level(helper, LAYER);
    }
}
