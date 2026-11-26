package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.SPRINT;

import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.Drifter;
import io.github.pkeppeler.deepcharter.creature.DrifterAlarm;
import io.github.pkeppeler.deepcharter.creature.DrifterTuning;
import io.github.pkeppeler.deepcharter.creature.Drifters;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.DrifterArenas.Arena;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.LoudPods;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server GameTests for where and how often the Drifters appear and for the alarm (#973), in {@code deepcharter:campaign_sample}, whose layers are
 * bands of one level: the zones are thirds of a band. Spawning keys on the biome tag {@link Drifters#FUNGAL_HOLLOWS}, which the test data pack
 * fills with a test biome; the count is of pods and capped for each zone; a kill raises the alarm of its zone, a burst on touch does not, and the
 * alarm rings for {@link DrifterTuning.Alarm#ticks()} and ends. Each test has its own zone of its own layer, because an alarm outlives its test.
 * Columns 25000 to 26999 are this class's.
 */
public class DrifterSpawnTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(DrifterSpawnTest.class);
    private static final String SAMPLE = "deepcharter:campaign_sample";
    private static final DrifterTuning TUNING = DrifterTuning.DEFAULT;
    /** The seed of the tick's chance in {@code theTickPlacesDriftersByPodsUpToTheZoneCap}. */
    private static final long SEED = 973L;

    private static final Identifier HOLLOWS_TEST_BIOME = Identifier.fromNamespaceAndPath("deepcharter-test", "hollows");
    private static final Identifier DRONE = Identifier.fromNamespaceAndPath("deepcharter", "creature.alarm_drone");
    /** The long room of the spawn test: 47 blocks along x (four chunks), 13 across and 19 high, so that a place 18 to 36 blocks from its middle is in it. */
    private static final int CX = 25_032;

    private static final int CZ = 25_016;
    private static final int HALF_X = 23;
    private static final int HALF_Z = 6;
    private static final int HALF_HEIGHT = 9;
    /** Places a placement is tried at, a call at a time: one call in about ten finds one. */
    private static final int ATTEMPTS = 400;
    /** Calls that must all find no place or no room, for a test that a spawn is refused: all the calls miss by chance with a probability below 1e-12. */
    private static final int REFUSALS = 120;

    /** One column in each chunk the long room spans, at height {@code y}. */
    private static List<BlockPos> columns(int y) {
        List<BlockPos> columns = new ArrayList<>();
        for (int x = CX - HALF_X; x <= CX + HALF_X; x += 16) {
            columns.add(new BlockPos(x, y, CZ));
        }
        columns.add(new BlockPos(CX + HALF_X, y, CZ));
        return columns;
    }

    private static int topY(int minY, int height, int zone) {
        Zones.Span span = Zones.span(minY, height, zone);
        return (span.low() + span.high()) / 2;
    }

    private static void room(ServerLevel level, int y) {
        RoomCarver.carve(
                level,
                new BlockPos(CX - HALF_X - 1, y - HALF_HEIGHT - 1, CZ - HALF_Z - 1),
                new BlockPos(CX + HALF_X + 1, y + HALF_HEIGHT + 1, CZ + HALF_Z + 1),
                Blocks.STONE.defaultBlockState());
        RoomCarver.carve(
                level,
                new BlockPos(CX - HALF_X, y - HALF_HEIGHT, CZ - HALF_Z),
                new BlockPos(CX + HALF_X, y + HALF_HEIGHT, CZ + HALF_Z),
                Blocks.AIR.defaultBlockState());
    }

    private static void hollows(ServerLevel level, int y) {
        CommandSourceStack source =
                level.getServer().createCommandSourceStack().withLevel(level).withSuppressedOutput();
        level.getServer()
                .getCommands()
                .performPrefixedCommand(
                        source,
                        "fillbiome %d %d %d %d %d %d %s"
                                .formatted(
                                        CX - HALF_X - 1,
                                        y - HALF_HEIGHT - 1,
                                        CZ - HALF_Z - 1,
                                        CX + HALF_X + 1,
                                        y + HALF_HEIGHT + 1,
                                        CZ + HALF_Z + 1,
                                        HOLLOWS_TEST_BIOME));
    }

    private static Drifters.Source middle(int y) {
        return new Drifters.Source(new Vec3(CX + 0.5, y, CZ + 0.5), true);
    }

    /** Calls the spawner until it places one, or fails the test after {@link #ATTEMPTS} calls. */
    private static Drifter placed(
            GameTestHelper helper, ServerLevel level, Drifters.Source source, int sources, List<Drifter> made) {
        RandomSource random = level.getRandom();
        for (int i = 0; i < ATTEMPTS; i++) {
            var spawned = Drifters.spawnFor(level, source, sources, random);
            if (spawned.isPresent()) {
                made.add(spawned.get());
                return spawned.get();
            }
        }
        throw helper.assertionException(Component.literal("no drifter was placed in " + ATTEMPTS
                + " calls with room for " + sources + " (" + made.size() + " made)"));
    }

    private static void refused(
            GameTestHelper helper, ServerLevel level, Drifters.Source source, int sources, String why) {
        for (int i = 0; i < REFUSALS; i++) {
            var spawned = Drifters.spawnFor(level, source, sources, level.getRandom());
            if (spawned.isPresent()) {
                spawned.get().discard();
                throw helper.assertionException(Component.literal("a drifter was placed: " + why));
            }
        }
    }

    /**
     * No drifter appears outside the hollows tag, in a room with every other condition. In the tag, one source holds one drifter, two sources
     * two, three sources still two (the top zone's cap); a ringing alarm lets one source hold two; the bottom zone's cap is four.
     */
    @GameTest(dimension = SAMPLE, environment = CampaignEnvironments.D, maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 600)
    public void driftersKeyOnTheTagAndAreCountedByPodsAndCappedByZone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // Not layer 3: its zones are in the hollows tag already. Layer 2's top zone and layer 1's bottom zone are
        // this test's.
        CampaignBands.Band one = CampaignBands.LAYER_1;
        CampaignBands.Band two = CampaignBands.LAYER_2;
        int top = topY(two.minY(), two.height(), 0);
        int bottom = topY(one.minY(), one.height(), 2);
        List<BlockPos> columns = new ArrayList<>();
        for (int x = CX - HALF_X; x <= CX + HALF_X; x += 16) {
            columns.add(new BlockPos(x, top, CZ));
        }
        columns.add(new BlockPos(CX + HALF_X, top, CZ));
        int[] ready = {0};
        boolean[] done = {false};
        TestTicks.each(helper, () -> {
            if (ready[0] < columns.size() || done[0]) {
                return;
            }
            done[0] = true;
            room(level, top);
            room(level, bottom);
            Drifters.Source topSource = middle(top);
            // No tag: every other condition holds, and nothing appears.
            expect(
                    helper,
                    !level.getBiome(BlockPos.containing(topSource.pos())).is(Drifters.FUNGAL_HOLLOWS),
                    "the room is in the hollows before the biome was set");
            refused(helper, level, topSource, 2, "outside the biome tag");
            hollows(level, top);
            hollows(level, bottom);
            expect(
                    helper,
                    level.getBiome(BlockPos.containing(topSource.pos())).is(Drifters.FUNGAL_HOLLOWS),
                    "the test biome is not in the hollows tag");
            List<Drifter> made = new ArrayList<>();
            try {
                Drifter first = placed(helper, level, topSource, 1, made);
                double away = first.position().distanceTo(topSource.pos());
                LOGGER.info("[drifter] one placed {} blocks from its source", String.format("%.1f", away));
                expect(
                        helper,
                        away >= TUNING.spawn().minBlocks() - 1.5
                                && away <= TUNING.spawn().maxBlocks() + 1.5,
                        "a drifter was placed %s blocks from its source",
                        away);
                expect(
                        helper,
                        level.getBiome(first.blockPosition()).is(Drifters.FUNGAL_HOLLOWS),
                        "a drifter was placed outside the hollows");
                refused(helper, level, topSource, 1, "a second one for one source");
                placed(helper, level, topSource, 2, made);
                refused(helper, level, topSource, 3, "a third in a zone that holds two");
                made.forEach(Drifter::discard);
                made.clear();
                // The alarm of the top zone lets one source hold two.
                DrifterAlarm.raise(level, top);
                placed(helper, level, topSource, 1, made);
                placed(helper, level, topSource, 1, made);
                refused(helper, level, topSource, 1, "a third for one source under the alarm");
                made.forEach(Drifter::discard);
                made.clear();
                // The bottom zone holds four.
                Drifters.Source bottomSource = middle(bottom);
                for (int i = 0; i < 4; i++) {
                    placed(helper, level, bottomSource, 5, made);
                }
                refused(helper, level, bottomSource, 5, "a fifth in the bottom zone, which holds four");
            } finally {
                // A failed check must not leave a drifter in a zone another test counts (#1180).
                made.forEach(Drifter::discard);
            }
            helper.succeed();
        });
        FarChunks.awaitEntityTicking(helper, level, columns, index -> ready[0]++);
    }

    /** A kill raises the alarm of the drifter's zone and no other, and a second one starts it again without stacking its length. */
    @GameTest(dimension = SAMPLE, environment = CampaignEnvironments.D, maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 120)
    public void killingADrifterRaisesTheAlarmOfItsZone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CampaignBands.Band three = CampaignBands.LAYER_3;
        int top = topY(three.minY(), three.height(), 0);
        BlockPos origin = new BlockPos(25_500, top, 25_500);
        MockPlayer[] striker = {null};
        long[] since = {-1};
        TestTicks.each(helper, () -> {
            if (since[0] < 0) {
                return;
            }
            long tick = helper.getTick() - since[0];
            if (tick == 20) {
                long left = DrifterAlarm.ticksLeft(level, top);
                expect(
                        helper,
                        left < TUNING.alarm().ticks() && left >= TUNING.alarm().ticks() - 25,
                        "the alarm has %d ticks left 20 ticks after it was raised",
                        left);
                kill(helper, level, striker[0], origin);
                expect(
                        helper,
                        DrifterAlarm.ticksLeft(level, top) == TUNING.alarm().ticks(),
                        "a second kill did not start the alarm again: %s ticks left",
                        DrifterAlarm.ticksLeft(level, top));
                striker[0].leave();
                helper.succeed();
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            striker[0] = MockPlayers.join(helper, "drifter-alarm-striker");
            striker[0].player().setGameMode(GameType.SURVIVAL);
            expect(helper, !DrifterAlarm.isRinging(level, top), "the alarm rang before a kill");
            kill(helper, level, striker[0], origin);
            expect(
                    helper,
                    DrifterAlarm.ticksLeft(level, top) == TUNING.alarm().ticks(),
                    "a kill did not raise the alarm for %s ticks: %s",
                    TUNING.alarm().ticks(),
                    DrifterAlarm.ticksLeft(level, top));
            expect(
                    helper,
                    !DrifterAlarm.isRinging(level, topY(three.minY(), three.height(), 2)),
                    "the alarm of the top zone of layer 3 rings in its bottom zone");
            expect(
                    helper,
                    !DrifterAlarm.isRinging(
                            level, topY(CampaignBands.LAYER_1.minY(), CampaignBands.LAYER_1.height(), 2)),
                    "the alarm of layer 3 rings in the bottom zone of layer 1");
            since[0] = helper.getTick();
        });
    }

    private static void kill(GameTestHelper helper, ServerLevel level, MockPlayer striker, BlockPos at) {
        Drifter drifter = TestEntities.create(CreatureRegistry.DRIFTER, level);
        drifter.setPos(Vec3.atBottomCenterOf(at));
        drifter.setNoAi(true);
        level.addFreshEntity(drifter);
        expect(
                helper,
                drifter.hurtServer(level, level.damageSources().playerAttack(striker.player()), TUNING.maxHealth()),
                "a blow did not hurt the drifter");
        expect(helper, drifter.isRemoved(), "a drifter killed by a blow is still there");
    }

    /** A drifter that bursts on a pod drilling beside it was not killed: no alarm rings. */
    @GameTest(dimension = SAMPLE, environment = CampaignEnvironments.D, maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 300)
    public void aBurstOnTouchRaisesNoAlarm(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CampaignBands.Band two = CampaignBands.LAYER_2;
        int bottom = topY(two.minY(), two.height(), 2);
        BlockPos origin = new BlockPos(25_700, bottom, 25_700);
        Arena[] made = {null};
        Drifter[] drifter = {null};
        long[] since = {0};
        TestTicks.each(helper, () -> {
            if (made[0] == null) {
                return;
            }
            expect(helper, !DrifterAlarm.isRinging(level, bottom), "the alarm rang for a burst nobody struck");
            if (drifter[0].isRemoved()) {
                expect(
                        helper,
                        made[0].pod().hull() < made[0].pod().maxHull(),
                        "the drifter burst and the gas did not reach the hull");
                made[0].clear();
                helper.succeed();
            } else if (helper.getTick() - since[0] > 250) {
                throw helper.assertionException(Component.literal("the drifter never burst on the drilling pod"));
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            made[0] = Arena.build(helper, level, origin);
            made[0].pilot().setInput(SPRINT);
            drifter[0] = made[0].drifter(made[0].at(1.2, 0.5, 0));
            since[0] = helper.getTick();
        });
    }

    /**
     * The alarm shows and ends: the pilot's readout carries the seconds it has left, the drone beats for a person in
     * the zone within {@link DrifterTuning.Alarm#droneTicks()} ticks of the alarm, and after
     * {@link DrifterTuning.Alarm#ticks()} ticks the line goes and the zone is quiet. It is held in memory only, so
     * there is no saved form to read.
     */
    @GameTest(dimension = SAMPLE, environment = CampaignEnvironments.D, maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 3_800)
    public void theAlarmShowsDronesAndEnds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CampaignBands.Band one = CampaignBands.LAYER_1;
        int middle = topY(one.minY(), one.height(), 1);
        BlockPos origin = new BlockPos(25_900, middle, 25_900);
        Arena[] made = {null};
        long[] since = {-1};
        long[] raisedAt = {-1};
        boolean[] droned = {false};
        TestTicks.each(helper, () -> {
            if (since[0] < 0) {
                return;
            }
            Arena arena = made[0];
            long tick = helper.getTick() - since[0];
            PodEntity pod = arena.pod();
            // The mock reads sounds after the test's tick, so judge a beat by its play tick (#1359).
            int beat = droned[0] ? -1 : arena.pilot().soundsHeard().indexOf(DRONE);
            if (beat >= 0) {
                long after = arena.pilot().soundTicks().get(beat) - raisedAt[0];
                expect(
                        helper,
                        after <= TUNING.alarm().droneTicks(),
                        "the first drone beat came %d ticks after the alarm, past its %d",
                        after,
                        TUNING.alarm().droneTicks());
                droned[0] = true;
            }
            int seconds = TUNING.alarm().ticks() / 20;
            if (tick == 100) {
                int shown = DrifterAlarm.warning(pod)
                        .orElseThrow(
                                () -> helper.assertionException(Component.literal("the pod's readout shows no alarm")))
                        .seconds();
                LOGGER.info("[drifter] the alarm shows {} s of {} s after 100 ticks", shown, seconds);
                expect(
                        helper,
                        shown >= seconds - 6 && shown <= seconds - 4,
                        "the readout shows %d s 100 ticks after the alarm, of %d",
                        shown,
                        seconds);
            }
            if (tick > 100 && tick < TUNING.alarm().ticks() - 40) {
                expect(
                        helper,
                        DrifterAlarm.warning(pod).isPresent(),
                        "the readout lost the alarm at tick %d of %d",
                        tick,
                        TUNING.alarm().ticks());
            }
            if (tick == TUNING.alarm().ticks() + 40) {
                expect(
                        helper,
                        !DrifterAlarm.isRinging(level, middle),
                        "the alarm still rings %d ticks after it was raised",
                        tick);
                expect(helper, DrifterAlarm.warning(pod).isEmpty(), "the readout still shows the alarm after it ended");
                expect(
                        helper,
                        droned[0],
                        "no drone beat for a pilot in the zone: %s",
                        arena.pilot().soundsHeard());
                arena.clear();
                helper.succeed();
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            made[0] = Arena.build(helper, level, origin);
            DrifterAlarm.raise(level, middle);
            since[0] = helper.getTick();
            raisedAt[0] = level.getGameTime();
        });
    }

    /**
     * A drifter is placed in the zone of its source and in the world: a source 4 blocks from a zone's edge, and one 3 blocks under the layer's
     * ceiling (the surface band above is no zone), never places across either, in the 8 blocks up or down that a place may be off by.
     */
    @GameTest(dimension = SAMPLE, environment = CampaignEnvironments.D, maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 60)
    public void aDrifterIsPlacedInTheZoneOfItsSourceAndNeverAcrossAnEdge(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CampaignBands.Band one = CampaignBands.LAYER_1;
        CampaignBands.Band three = CampaignBands.LAYER_3;
        // Three blocks under layer 1's ceiling, and the top of layer 3's middle zone (its own: no other test uses it).
        int ceiling = one.minY() + one.height() - 4;
        int edge = Zones.span(three.minY(), three.height(), 1).high() - 3;
        int[] ready = {0};
        boolean[] done = {false};
        List<BlockPos> columns = columns(ceiling);
        TestTicks.each(helper, () -> {
            if (ready[0] < columns.size() || done[0]) {
                return;
            }
            done[0] = true;
            for (int y : new int[] {ceiling, edge}) {
                room(level, y);
                hollows(level, y);
                Drifters.Source source = middle(y);
                var zone = DrifterAlarm.Zone.at(level, y);
                expect(helper, zone.isPresent(), "y %d is in no zone", y);
                int found = 0;
                for (int i = 0; i < 4 * ATTEMPTS && found < 12; i++) {
                    var spawned = Drifters.spawnFor(level, source, 12, level.getRandom());
                    if (spawned.isPresent()) {
                        Drifter drifter = spawned.get();
                        found++;
                        // Read and discard before any check: a failed check must not leave a drifter in a zone another
                        // test counts (#1180: one stray made theTickPlacesDriftersByPodsUpToTheZoneCap fail too).
                        boolean inZone =
                                DrifterAlarm.Zone.at(level, drifter.getBlockY()).equals(zone)
                                        && DrifterAlarm.Zone.at(level, drifter.getBlockY() + 1)
                                                .equals(zone)
                                        && level.isInWorldBounds(drifter.blockPosition());
                        double placedY = drifter.getY();
                        // The spawner's own measure, on the same doubles: its place is the drifter's position.
                        double away = drifter.position().distanceTo(Vec3.atCenterOf(BlockPos.containing(source.pos())));
                        drifter.discard();
                        expect(
                                helper,
                                inZone,
                                "a drifter was placed at y %s, out of the source's zone %s (source y %d)",
                                placedY,
                                zone,
                                y);
                        expect(
                                helper,
                                away >= TUNING.spawn().minBlocks(),
                                "a drifter was placed %s blocks from its source, under %s",
                                away,
                                TUNING.spawn().minBlocks());
                    }
                }
                expect(
                        helper,
                        found >= 3,
                        "only %d drifters were placed in zone %s: the test proves nothing",
                        found,
                        zone);
            }
            helper.succeed();
        });
        FarChunks.awaitEntityTicking(helper, level, columns, index -> ready[0]++);
    }

    /**
     * The production tick (the look, the roll every {@code checkTicks}, the count by pods and the cap) places the drifters, with the alarm
     * ringing so that a roll comes in a few checks: none while {@code spawn_mobs} is off; one pod holds two (1 plus the alarm's 1), a second pod
     * three (the middle zone's cap), and each new drifter comes at least a check after the last. The drifters are held still so that none
     * bursts on its pod. The roll and the placement draw from a seeded source ({@link Drifters#seed}), so the counts are reproducible, not a
     * chance of a flake; the seed is of this zone alone (no other test uses layer 2's middle zone; GameTests of a batch run side by side, and drifters are counted level-wide by zone), so a timeout that skips the reset leaves
     * nothing another test draws from. The budget only bounds a failure.
     */
    @GameTest(
            dimension = SAMPLE,
            environment = CampaignEnvironments.D,
            maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 50_000)
    public void theTickPlacesDriftersByPodsUpToTheZoneCap(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CampaignBands.Band two = CampaignBands.LAYER_2;
        int y = topY(two.minY(), two.height(), 1);
        DrifterAlarm.Zone zone = DrifterAlarm.Zone.at(level, y).orElseThrow();
        List<BlockPos> columns = columns(y);
        int ready = columns.size();
        int[] arrived = {0};
        int[] phase = {0};
        long[] since = {0};
        long[] lastPlacedAt = {-1};
        int[] seen = {0};
        PodEntity[] pods = {null, null};
        MockPlayer[] pilots = {null, null};
        boolean[] set = {false};
        TestTicks.each(helper, () -> {
            if (arrived[0] < ready) {
                return;
            }
            try {
                if (!set[0]) {
                    set[0] = true;
                    Drifters.seed(zone, RandomSource.create(SEED));
                    room(level, y);
                    hollows(level, y);
                    pods[0] = podAt(helper, level, y, 0, pilots, 0);
                    level.getServer().getGameRules().set(GameRules.SPAWN_MOBS, false, level.getServer());
                    since[0] = helper.getTick();
                }
                if ((helper.getTick() - since[0]) % 400 == 0) {
                    DrifterAlarm.raise(level, y);
                }
                List<? extends Drifter> alive = level.getEntities(
                        CreatureRegistry.DRIFTER,
                        drifter -> DrifterAlarm.Zone.at(level, drifter.getBlockY())
                                .equals(DrifterAlarm.Zone.at(level, y)));
                alive.forEach(drifter -> drifter.setNoAi(true));
                long tick = helper.getTick() - since[0];
                if (alive.size() > seen[0]) {
                    long gap = lastPlacedAt[0] < 0 ? Long.MAX_VALUE : helper.getTick() - lastPlacedAt[0];
                    expect(
                            helper,
                            lastPlacedAt[0] < 0 || gap >= TUNING.spawn().checkTicks(),
                            "a drifter came %d ticks after the last, a look is every %d",
                            gap,
                            TUNING.spawn().checkTicks());
                    lastPlacedAt[0] = helper.getTick();
                }
                seen[0] = alive.size();
                switch (phase[0]) {
                    case 0 -> {
                        expect(helper, alive.isEmpty(), "a drifter was placed with spawn_mobs off");
                        if (tick >= 2_500) {
                            level.getServer().getGameRules().set(GameRules.SPAWN_MOBS, true, level.getServer());
                            phase[0] = 1;
                            since[0] = helper.getTick();
                            lastPlacedAt[0] = -1;
                        }
                    }
                    case 1 -> {
                        expect(helper, alive.size() <= 2, "one pod holds %d drifters, not 2", alive.size());
                        if (alive.size() == 2 && tick >= 1_500) {
                            pods[1] = podAt(helper, level, y, 3, pilots, 1);
                            phase[0] = 2;
                            since[0] = helper.getTick();
                            lastPlacedAt[0] = -1;
                        } else if (tick > 20_000 && alive.size() < 2) {
                            throw failure(helper, "one pod drew %d drifters in %d ticks, not 2", alive.size(), tick);
                        }
                    }
                    default -> {
                        expect(
                                helper,
                                alive.size() <= 3,
                                "two pods hold %d drifters, the zone's cap is 3",
                                alive.size());
                        if (alive.size() == 3 && tick >= 1_500) {
                            finish(level, zone, alive, pods, pilots);
                            helper.succeed();
                        } else if (tick > 20_000 && alive.size() < 3) {
                            throw failure(helper, "two pods drew %d drifters in %d ticks, not 3", alive.size(), tick);
                        }
                    }
                }
            } catch (RuntimeException e) {
                Drifters.seed(zone, null);
                level.getServer().getGameRules().set(GameRules.SPAWN_MOBS, false, level.getServer());
                throw e;
            }
        });
        FarChunks.awaitEntityTicking(helper, level, columns, index -> arrived[0]++);
    }

    private static PodEntity podAt(
            GameTestHelper helper, ServerLevel level, int y, int dx, MockPlayer[] pilots, int index) {
        PodEntity pod = TestEntities.create(PodRegistry.POD, level);
        pod.setPos(Vec3.atBottomCenterOf(new BlockPos(CX + dx, y - HALF_HEIGHT, CZ)));
        level.addFreshEntity(pod);
        pilots[index] = MockPlayers.join(helper, "drifter-tick-pilot-" + index);
        pilots[index].teleportTo(level, pod.position(), 90f, 0f);
        if (!pilots[index].player().startRiding(pod)) {
            throw failure(helper, "a pilot could not mount a pod");
        }
        LoudPods.make(pod);
        return pod;
    }

    private static void finish(
            ServerLevel level,
            DrifterAlarm.Zone zone,
            List<? extends Drifter> alive,
            PodEntity[] pods,
            MockPlayer[] pilots) {
        Drifters.seed(zone, null);
        level.getServer().getGameRules().set(GameRules.SPAWN_MOBS, false, level.getServer());
        alive.forEach(Drifter::discard);
        for (int i = 0; i < pods.length; i++) {
            if (pods[i] != null) {
                LoudPods.quiet(pods[i]);
                pilots[i].leave();
                pods[i].discard();
            }
        }
    }
}
