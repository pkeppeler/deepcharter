package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Inputs.SPRINT;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.Tallies;
import io.github.pkeppeler.deepcharter.creature.Tally;
import io.github.pkeppeler.deepcharter.creature.TallyTuning;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.scanner.ScanSlice;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server GameTests for #917, the Tallies. A pod or a person who holds ore draws one; one that trails for about 30 s clamps to the cargo hatch
 * or the pack and takes one ore; the cutter breaks it on the hatch and one blow breaks it on foot; it drops nothing and breaks no block; a
 * scanner reads it as ore. The numbers the tuning rests on are logged as {@code [tally]} lines and go in {@code docs/design/mechanics.md}; the
 * pacing that follows from them is {@code TallyEconomyTest}. Each test builds a closed arena in a far chunk of the test world.
 */
public class TallyTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(TallyTest.class);
    private static final TallyTuning TUNING = TallyTuning.DEFAULT;
    private static final int HALF = 6;
    private static final int ARENA_HEIGHT = 14;
    private static final int BORE_DEPTH = -12;
    private static final int FIRST_SLOT_X = 6808;
    private static final int SLOT_Z_SPACING = 64;
    private static final int FLOOR_Y = TestLayers.worldY(TestLayers.SURFACE, 100);
    /** The trail (600 ticks), with the flight to the mark and a margin for a loaded runner; an annotation needs a constant, so a test pins it to the tuning. */
    private static final int CLAMP_BUDGET_TICKS = 700;
    /** The hold (60 ticks), the flight away (40) and a margin. */
    private static final int SAMPLE_BUDGET_TICKS = 160;

    private static final int ORES = 3;
    /** Ticks a pod and its pilot take to settle before they are asked what they draw. */
    private static final int SETTLE_TICKS = 10;

    private static final int SEND_ATTEMPT_TICKS = 100;
    private static final int LIGHT_BUDGET_TICKS = 200;
    private static final String APPROACH_KEY = "creature.deepcharter.tally_approach";
    private static final String CLAMP_POD_KEY = "creature.deepcharter.tally_clamp_pod";
    private static final String CLAMP_FOOT_KEY = "creature.deepcharter.tally_clamp_foot";
    private static final String BITTEN_KEY = "creature.deepcharter.tally_bitten";
    private static final String BROKEN_KEY = "creature.deepcharter.tally_broken";
    private static final String SAMPLE_KEY = "creature.deepcharter.tally_sample";

    /** A closed arena with a Tally at its west end, and either a pod with a pilot at its east end or a person on foot there. */
    private record Arena(
            ServerLevel level,
            BlockPos origin,
            PodEntity pod,
            boolean podRide,
            MockPlayer holder,
            Tally tally,
            List<BlockState> blocks) {
        static Arena build(GameTestHelper helper, ServerLevel level, BlockPos origin, boolean podRide) {
            for (int u = -HALF - 1; u <= HALF + 1; u++) {
                for (int v = -HALF - 1; v <= HALF + 1; v++) {
                    for (int y = -1; y <= ARENA_HEIGHT; y++) {
                        boolean shell =
                                y == -1 || y == ARENA_HEIGHT || Math.abs(u) == HALF + 1 || Math.abs(v) == HALF + 1;
                        // room-carver: a closed arena of its own stone shell in the overworld test structure, not layer
                        // rock
                        level.setBlock(
                                origin.offset(u, y, v), (shell ? Blocks.STONE : Blocks.AIR).defaultBlockState(), 3);
                    }
                }
            }
            MockPlayer holder = MockPlayers.join(helper, "tally-holder");
            // GameTest players default to creative, and a creative person draws no Tally.
            holder.player().setGameMode(GameType.SURVIVAL);
            // The pod is always there; on foot it is parked at the west wall, empty, and draws nothing.
            PodEntity pod = TestEntities.create(PodRegistry.POD, level);
            pod.setPos(Vec3.atBottomCenterOf(
                    origin.east(podRide ? HALF - 1 : -HALF + 1).north(podRide ? 0 : 3)));
            level.addFreshEntity(pod);
            if (podRide) {
                // The dear ore goes in first: the sample is the cheapest, not the oldest.
                pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.GOLDIUM));
                for (int i = 0; i < ORES; i++) {
                    if (!pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.IRONIUM))) {
                        throw failure(helper, "the bay took no ore number %s", i);
                    }
                }
                holder.teleportTo(level, pod.position(), 90f, 0f);
                if (!holder.player().startRiding(pod)) {
                    throw failure(helper, "the pilot could not mount the pod");
                }
            } else {
                holder.teleportTo(level, Vec3.atBottomCenterOf(origin.east(HALF - 1)), 90f, 0f);
                holder.player().getInventory().add(OreRegistry.stack(OreType.GOLDIUM));
                for (int i = 0; i < ORES; i++) {
                    holder.player().getInventory().add(OreRegistry.stack(OreType.IRONIUM));
                }
            }
            Tally tally = TestEntities.create(CreatureRegistry.TALLY, level);
            tally.setPos(Vec3.atBottomCenterOf(origin.west(HALF - 1).above(2)));
            level.addFreshEntity(tally);
            return new Arena(level, origin, pod, podRide, holder, tally, survey(level, origin));
        }

        private static List<BlockState> survey(ServerLevel level, BlockPos origin) {
            List<BlockState> states = new ArrayList<>();
            for (int u = -HALF - 1; u <= HALF + 1; u++) {
                for (int v = -HALF - 1; v <= HALF + 1; v++) {
                    for (int y = -1; y <= ARENA_HEIGHT; y++) {
                        states.add(level.getBlockState(origin.offset(u, y, v)));
                    }
                }
            }
            return states;
        }

        boolean blocksUnchanged() {
            return blocks.equals(survey(level, origin));
        }

        int ore() {
            return podRide
                    ? pod.cargo().count(OreType.IRONIUM)
                    : holder.player().getInventory().countItem(OreRegistry.item(OreType.IRONIUM));
        }

        long told(String key) {
            return holder.actionBarMessages().stream()
                    .filter(message -> message.getContents() instanceof TranslatableContents contents
                            && contents.getKey().equals(key))
                    .count();
        }

        /** The dear ore the person or the bay holds: a skim never takes it. */
        int dear() {
            return podRide
                    ? pod.cargo().count(OreType.GOLDIUM)
                    : holder.player().getInventory().countItem(OreRegistry.item(OreType.GOLDIUM));
        }

        int droppedItems() {
            // entity-query: the arena's own chunk, forced and ticking for the test
            return level.getEntitiesOfClass(
                            ItemEntity.class, new net.minecraft.world.phys.AABB(origin).inflate(HALF + 2))
                    .size();
        }

        void clear() {
            holder.leave();
            tally.discard();
            pod.discard();
        }
    }

    /**
     * (#917) The skim: a Tally trails a pod that holds ore for about 30 s, clamps to the hatch, and after the hold takes exactly one ore and goes.
     * It hurts neither hull nor pilot, breaks no block, and drops nothing. The ticks to the clamp are the measured trail.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CLAMP_BUDGET_TICKS + SAMPLE_BUDGET_TICKS + 60)
    public void aTallyThatTrailsAPodForAboutThirtySecondsClampsAndTakesOneOre(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(0);
        Arena[] made = {null};
        long[] clampedAt = {-1};
        long[] goneAt = {-1};
        float[] hull = {0};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null) {
                return;
            }
            if (hull[0] == 0) {
                hull[0] = arena.pod.hull();
            }
            if (clampedAt[0] < 0) {
                if (arena.tally.isClamped()) {
                    clampedAt[0] = helper.getTick();
                    LOGGER.info(
                            "[tally] pod: trail then clamp took {} ticks ({} s)", clampedAt[0], clampedAt[0] / 20.0);
                    if (clampedAt[0] < TUNING.trailTicks()) {
                        throw failure(helper, 
                                "the Tally clamped after %s ticks, before it had trailed %s",
                                clampedAt[0], TUNING.trailTicks());
                    }
                    if (arena.ore() != ORES) {
                        throw failure(helper, "the bay held %s ore at the clamp, not %s", arena.ore(), ORES);
                    }
                } else if (helper.getTick() > CLAMP_BUDGET_TICKS) {
                    throw failure(helper, 
                            "the Tally had not clamped to the hatch after %s ticks", helper.getTick());
                }
                return;
            }
            if (arena.tally.isRemoved()) {
                goneAt[0] = helper.getTick();
                LOGGER.info(
                        "[tally] pod: the sample was taken and the Tally gone {} ticks after the clamp",
                        goneAt[0] - clampedAt[0]);
                if (arena.ore() != ORES - 1 || arena.dear() != 1) {
                    throw failure(helper, 
                            "the bay holds %s cheap and %s dear ore after the skim, not %s and 1",
                            arena.ore(), arena.dear(), ORES - 1);
                }
                if (arena.pod.hull() != hull[0]) {
                    throw failure(helper, "the hull went from %s to %s", hull[0], arena.pod.hull());
                }
                if (arena.holder.player().getHealth() != arena.holder.player().getMaxHealth()) {
                    throw failure(helper, "the pilot was hurt");
                }
                if (!arena.blocksUnchanged()) {
                    throw failure(helper, "the Tally changed a block of the arena");
                }
                if (arena.droppedItems() != 0) {
                    throw failure(helper, "the Tally dropped %s items", arena.droppedItems());
                }
                if (arena.told(APPROACH_KEY) != 1 || arena.told(CLAMP_POD_KEY) != 1 || arena.told(SAMPLE_KEY) != 1) {
                    throw failure(helper, 
                            "the pilot was not told once each of the approach, the clamp and the sample: %s",
                            arena.holder.actionBarMessages());
                }
                arena.clear();
                helper.succeed();
            } else if (helper.getTick() - clampedAt[0] > SAMPLE_BUDGET_TICKS) {
                throw failure(helper, 
                        "the Tally was still there %s ticks after it clamped, ore %s",
                        helper.getTick() - clampedAt[0], arena.ore());
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> made[0] = Arena.build(helper, level, origin, true));
    }

    /**
     * (#917) The cutter: the pilot drills the moment the Tally clamps, and the cutter breaks it before it can take its sample. It dies in
     * {@link TallyTuning#cutterBreakTicks} and a margin, the bay keeps all its ore, and it drops nothing.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CLAMP_BUDGET_TICKS + 200)
    public void theCutterBreaksAClampedTallyBeforeItTakesTheSample(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(1);
        Arena[] made = {null};
        long[] clampedAt = {-1};
        long[] brokeAt = {-1};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null) {
                return;
            }
            if (clampedAt[0] < 0) {
                if (arena.tally.isClamped()) {
                    clampedAt[0] = helper.getTick();
                    arena.holder.setInput(SPRINT);
                } else if (helper.getTick() > CLAMP_BUDGET_TICKS) {
                    throw failure(helper, "the Tally had not clamped to the hatch");
                }
                return;
            }
            long since = helper.getTick() - clampedAt[0];
            if (!arena.tally.isAlive()) {
                if (brokeAt[0] < 0) {
                    brokeAt[0] = helper.getTick();
                    LOGGER.info(
                            "[tally] cutter: the Tally broke {} ticks after the pilot drilled (health {})",
                            since,
                            TUNING.maxHealth());
                }
                if (arena.ore() != ORES) {
                    throw failure(helper, "the bay holds %s ore after the cutter, not %s", arena.ore(), ORES);
                }
                if (arena.tally.isClamped()) {
                    throw failure(helper, "the broken Tally still clamps the hatch");
                }
                if (arena.droppedItems() != 0) {
                    throw failure(helper, "the broken Tally dropped %s items", arena.droppedItems());
                }
                if (arena.told(BITTEN_KEY) != 1 || arena.told(BROKEN_KEY) != 1) {
                    // The mock pilot's lines reach it a tick after the server sends them: look again next tick.
                    if (helper.getTick() - brokeAt[0] < 4) {
                        return;
                    }
                    throw failure(helper, 
                            "the pilot was not told once each that the cutter bites and that it broke: %s",
                            arena.holder.actionBarMessages());
                }
                arena.clear();
                helper.succeed();
            } else if (since > TUNING.cutterBreakTicks() + 20) {
                throw failure(helper, 
                        "the Tally lived %s ticks after the pilot drilled (health %s)", since, arena.tally.getHealth());
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            made[0] = Arena.build(helper, level, origin, true);
            for (int u = -HALF; u <= HALF; u++) {
                for (int v = -HALF; v <= HALF; v++) {
                    for (int y = BORE_DEPTH; y < 0; y++) {
                        // room-carver: the stone under this test's own arena, in the overworld test structure, not
                        // layer
                        // rock
                        level.setBlock(origin.offset(u, y, v), Blocks.STONE.defaultBlockState(), 3);
                    }
                }
            }
        });
    }

    /** (#917) On foot the Tally clamps to the pack of a person who carries ore and, left alone, takes one. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CLAMP_BUDGET_TICKS + SAMPLE_BUDGET_TICKS + 60)
    public void onFootATallyTakesAnOreThePersonCarries(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(2);
        Arena[] made = {null};
        long[] clampedAt = {-1};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null) {
                return;
            }
            if (clampedAt[0] < 0) {
                if (arena.tally.isClamped()) {
                    clampedAt[0] = helper.getTick();
                } else if (helper.getTick() > CLAMP_BUDGET_TICKS) {
                    throw failure(helper, "the Tally had not clamped to the pack");
                }
                return;
            }
            if (arena.tally.isRemoved()) {
                LOGGER.info(
                        "[tally] foot: the sample was taken {} ticks after the clamp", helper.getTick() - clampedAt[0]);
                if (arena.ore() != ORES - 1 || arena.dear() != 1) {
                    throw failure(helper, 
                            "the person carries %s cheap and %s dear ore after the skim, not %s and 1",
                            arena.ore(), arena.dear(), ORES - 1);
                }
                if (arena.holder.player().getHealth() != arena.holder.player().getMaxHealth()) {
                    throw failure(helper, "the person was hurt");
                }
                if (arena.told(CLAMP_FOOT_KEY) != 1 || arena.told(SAMPLE_KEY) != 1) {
                    if (helper.getTick() - clampedAt[0] < SAMPLE_BUDGET_TICKS) {
                        return;
                    }
                    throw failure(helper, 
                            "the person was not told once each of the clamp and the sample: %s",
                            arena.holder.actionBarMessages());
                }
                arena.clear();
                helper.succeed();
            } else if (helper.getTick() - clampedAt[0] > SAMPLE_BUDGET_TICKS) {
                throw failure(helper, 
                        "the Tally was still there %s ticks after it clamped", helper.getTick() - clampedAt[0]);
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> made[0] = Arena.build(helper, level, origin, false));
    }

    /** (#917) One blow, however light, breaks a clamped Tally; the person keeps every ore and nothing drops. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CLAMP_BUDGET_TICKS + 100)
    public void oneBlowBreaksATallyOnFootAndItDropsNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(3);
        Arena[] made = {null};
        long[] struckAt = {-1};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null) {
                return;
            }
            if (struckAt[0] < 0) {
                if (arena.tally.isClamped()) {
                    struckAt[0] = helper.getTick();
                    ServerPlayer person = arena.holder.player();
                    // The lightest blow there is: a bare hand does 1.
                    arena.tally.hurtServer(level, level.damageSources().playerAttack(person), 1f);
                    if (arena.tally.isAlive()) {
                        throw failure(helper, 
                                "a blow of 1 left the Tally alive with %s of %s health",
                                arena.tally.getHealth(), arena.tally.getMaxHealth());
                    }
                    LOGGER.info("[tally] foot: one blow of 1 broke it (health {})", TUNING.maxHealth());
                } else if (helper.getTick() > CLAMP_BUDGET_TICKS) {
                    throw failure(helper, "the Tally had not clamped to the pack");
                }
                return;
            }
            if (arena.tally.isRemoved()) {
                if (arena.ore() != ORES) {
                    throw failure(helper, 
                            "the person carries %s ore after the blow, not %s", arena.ore(), ORES);
                }
                if (arena.droppedItems() != 0) {
                    throw failure(helper, "the broken Tally dropped %s items", arena.droppedItems());
                }
                arena.clear();
                helper.succeed();
            } else if (helper.getTick() - struckAt[0] > 60) {
                throw failure(helper, "the broken Tally was still there 60 ticks after the blow");
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> made[0] = Arena.build(helper, level, origin, false));
    }

    /** (#917) What the world does to a Tally does not hurt it: only a person's hand and a pod's cutter do. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 40)
    public void onlyAPersonAndTheCutterHurtATally(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(4);
        TestTicks.each(helper, () -> {});
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            Tally tally = TestEntities.create(CreatureRegistry.TALLY, level);
            tally.setNoAi(true);
            tally.setPos(Vec3.atBottomCenterOf(origin.above(30)));
            level.addFreshEntity(tally);
            for (var source : List.of(
                    level.damageSources().generic(),
                    level.damageSources().magic(),
                    level.damageSources().inWall())) {
                if (tally.hurtServer(level, source, 1000f)) {
                    throw failure(helper, "the Tally took damage from %s", source.getMsgId());
                }
            }
            if (tally.getHealth() != tally.getMaxHealth()) {
                throw failure(helper, "the Tally's health went to %s", tally.getHealth());
            }
            tally.discard();
            helper.succeed();
        });
    }

    /** (#917) What draws a Tally: a person aboard a pod that holds ore, or on foot with ore in the dark; never one with nothing to sample. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + LIGHT_BUDGET_TICKS + 60)
    public void onlyOreInTheDarkDrawsATally(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(5);
        Arena[] made = {null};
        int[] phase = {0};
        long[] litAt = {-1};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null) {
                return;
            }
            ServerPlayer person = arena.holder.player();
            if (phase[0] == 0 && helper.getTick() < SETTLE_TICKS) {
                return;
            }
            if (phase[0] == 0) {
                if (!Tally.draws(arena.pod)) {
                    throw failure(helper, "a pod with a pilot and ore draws no Tally");
                }
                arena.pod.cargo().dump(arena.pod);
                if (Tally.draws(arena.pod)) {
                    throw failure(helper, "a pod with an empty bay draws a Tally");
                }
                person.stopRiding();
                person.getInventory().clearContent();
                if (Tally.draws(person)) {
                    throw failure(helper, "a person with no ore draws a Tally");
                }
                person.getInventory().add(OreRegistry.stack(OreType.IRONIUM));
                if (!Tally.draws(person)) {
                    throw failure(helper, "a person with ore in the dark draws no Tally");
                }
                person.setGameMode(GameType.CREATIVE);
                if (Tally.draws(person)) {
                    throw failure(helper, "a creative person draws a Tally");
                }
                person.setGameMode(GameType.SURVIVAL);
                // room-carver: a light block in this test's own arena
                level.setBlock(person.blockPosition(), Blocks.LIGHT.defaultBlockState(), 3);
                litAt[0] = helper.getTick();
                phase[0] = 1;
            } else if (level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, person.blockPosition())
                    >= io.github.pkeppeler.deepcharter.creature.CreatureTuning.DEFAULT.litBlockLight()) {
                if (Tally.draws(person)) {
                    throw failure(helper, "a person with ore standing in a light draws a Tally");
                }
                arena.clear();
                helper.succeed();
            } else if (helper.getTick() - litAt[0] > LIGHT_BUDGET_TICKS) {
                throw failure(helper, "the light did not reach the person in %s ticks", LIGHT_BUDGET_TICKS);
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> made[0] = Arena.build(helper, level, origin, true));
    }

    /**
     * (#917) The spawner: a check that passes puts one Tally in dark open air between {@code spawnMinBlocks} and {@code spawnMaxBlocks} of the
     * mark, a second finds one near and sends none, and a check that does not pass sends none.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + SEND_ATTEMPT_TICKS + 40)
    public void aPassingCheckSendsOneTallyAndNotASecond(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(6);
        PodEntity[] pod = {null};
        int[] attempts = {0};
        TestTicks.each(helper, () -> {
            if (pod[0] == null) {
                return;
            }
            // Only the chunk the test forces ticks, and a place may fall in a neighbour: the check looks again, as the
            // next one would.
            Optional<Tally> sent = Tallies.checkMark(level, pod[0], 1.0, level.getRandom());
            if (sent.isEmpty()) {
                if (++attempts[0] > SEND_ATTEMPT_TICKS) {
                    throw failure(helper, 
                            "%s checks that passed in open dark air sent no Tally", SEND_ATTEMPT_TICKS);
                }
                return;
            }
            double distance = sent.get().position().distanceTo(pod[0].position());
            if (distance < TUNING.spawnMinBlocks() - 4 || distance > TUNING.spawnMaxBlocks() + 4) {
                throw failure(helper, 
                        "the Tally appeared %s blocks off, outside %s to %s",
                        distance, TUNING.spawnMinBlocks(), TUNING.spawnMaxBlocks());
            }
            if (Tallies.checkMark(level, pod[0], 1.0, level.getRandom()).isPresent()) {
                throw failure(helper, "a second Tally was sent to a mark that has one near");
            }
            sent.get().discard();
            pod[0].discard();
            helper.succeed();
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            PodEntity made = TestEntities.create(PodRegistry.POD, level);
            made.setPos(Vec3.atBottomCenterOf(origin.above(40)));
            level.addFreshEntity(made);
            if (Tallies.checkMark(level, made, 0.0, level.getRandom()).isPresent()) {
                throw failure(helper, "a check with no chance sent a Tally");
            }
            pod[0] = made;
        });
    }

    /** (#917) The casing's zone is a square round its axis, and layer 2 sends more than layer 1. */
    @GameTest
    public void theCasingsZoneIsASquareAndDeeperSendsMore(GameTestHelper helper) {
        BlockPos casing = slot(8);
        int range = (int) TUNING.casingRangeBlocks();
        if (TUNING.trailTicks() + 100 > CLAMP_BUDGET_TICKS
                || TUNING.podSampleTicks() + TUNING.leaveTicks() + 60 > SAMPLE_BUDGET_TICKS) {
            throw failure(helper, "the tests' tick budgets do not cover the tuning %s", TUNING);
        }
        var inside = TestEntities.create(CreatureRegistry.TALLY, helper.getLevel());
        inside.setPos(casing.getX() + 0.5 + range, casing.getY(), casing.getZ() + 0.5 - range);
        if (!Tallies.nearCasing(inside, casing)) {
            throw failure(helper, 
                    "the corner of the zone, %s blocks out each way, is not near the casing", range);
        }
        inside.setPos(casing.getX() + 0.5 + range + 1, casing.getY(), casing.getZ() + 0.5);
        if (Tallies.nearCasing(inside, casing)) {
            throw failure(helper, "%s blocks out is near the casing", range + 1);
        }
        if (Tallies.chance(2) <= Tallies.chance(1)) {
            throw failure(helper, "layer 2 sends %s and layer 1 %s", Tallies.chance(2), Tallies.chance(1));
        }
        helper.succeed();
    }

    /** (#917) A scanner reads a Tally as ore: a cell with one in it is ore on the map, and the cell is air again when it is gone. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 40)
    public void aScannerReadsATallyAsOre(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(7);
        TestTicks.each(helper, () -> {});
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            BlockPos at = origin.above(30);
            PodEntity pod = TestEntities.create(PodRegistry.POD, level);
            pod.setPos(Vec3.atBottomCenterOf(at));
            level.addFreshEntity(pod);
            Tally tally = TestEntities.create(CreatureRegistry.TALLY, level);
            tally.setNoAi(true);
            tally.setPos(Vec3.atBottomCenterOf(at.east(2)));
            level.addFreshEntity(tally);
            ScanSlice with = ScanSlice.scan(Tallies.echoed(level, pod), at, Direction.EAST, 1, Chassis.MOLE);
            if (!(with.cell(2, 0) instanceof ScanSlice.Cell.Ore)) {
                throw failure(helper, "the Tally's cell reads as %s, not ore", with.cell(2, 0));
            }
            if (!(with.cell(3, 0) instanceof ScanSlice.Cell.Air)) {
                throw failure(helper, "the cell beside the Tally reads as %s, not air", with.cell(3, 0));
            }
            tally.discard();
            ScanSlice without = ScanSlice.scan(Tallies.echoed(level, pod), at, Direction.EAST, 1, Chassis.MOLE);
            if (!(without.cell(2, 0) instanceof ScanSlice.Cell.Air)) {
                throw failure(helper, "with no Tally the cell reads as %s, not air", without.cell(2, 0));
            }
            pod.discard();
            helper.succeed();
        });
    }

    private static BlockPos slot(int index) {
        return new BlockPos(FIRST_SLOT_X, FLOOR_Y, FIRST_SLOT_X + index * SLOT_Z_SPACING);
    }
}
