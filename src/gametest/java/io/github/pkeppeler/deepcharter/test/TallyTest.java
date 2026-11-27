package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.SPRINT;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.CreatureTuning;
import io.github.pkeppeler.deepcharter.creature.DrawSources;
import io.github.pkeppeler.deepcharter.creature.Tallies;
import io.github.pkeppeler.deepcharter.creature.Tally;
import io.github.pkeppeler.deepcharter.creature.TallyTuning;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.scanner.EchoedBlocks;
import io.github.pkeppeler.deepcharter.scanner.ScanArea;
import io.github.pkeppeler.deepcharter.scanner.ScanSlice;
import io.github.pkeppeler.deepcharter.scanner.ScannerTuning;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TallyTimings;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
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
    /** Blocks beyond which vanilla removes a mob at once for want of a player. */
    private static final int DESPAWN_BLOCKS = 128;

    private static final int ARENA_HEIGHT = 14;
    private static final int BORE_DEPTH = -12;
    private static final int FIRST_SLOT_X = 6808;
    private static final int SLOT_Z_SPACING = 64;
    private static final int FLOOR_Y = TestLayers.worldY(TestLayers.SURFACE, 100);
    /** (#1237) Farther than {@link #DESPAWN_BLOCKS} from every arena slot. */
    private static final BlockPos DESPAWN_SITE = new BlockPos(52_000, FLOOR_Y, 52_000);
    /** The trail (600 ticks), with the flight to the mark (measured up to 83 ticks) and a margin for a loaded runner; an annotation needs a constant, so a test pins it to the tuning. */
    private static final int CLAMP_BUDGET_TICKS = 760;
    /** The hold (60 ticks), the flight away (40) and a margin. */
    private static final int SAMPLE_BUDGET_TICKS = 160;

    private static final int ORES = 3;
    /** The approach to the mark, on top of the trail, at most: measured 37 to 83 ticks. */
    private static final int APPROACH_TICKS_MAX = 100;
    /** How long the flare of the flare tests burns, and how far it reaches. */
    private static final int FLARE_BURN_TICKS = 40;

    private static final double FLARE_REACH = 6;
    /** Ticks the cutter may take over the bites it needs: the pod's drill starts a tick or so after the input. */
    private static final int CUTTER_SLACK_TICKS = 5;
    /** Ticks a pod and its pilot take to settle before they are asked what they draw. */
    private static final int SETTLE_TICKS = 10;

    private static final int SEND_ATTEMPT_TICKS = 100;
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
            List<BlockState> blocks,
            long builtAt) {
        /** Ticks since the arena was built: the budgets count from there, not from the test's start, which the wait for the chunk takes a share of. */
        long age(GameTestHelper helper) {
            return helper.getTick() - builtAt;
        }

        static Arena build(GameTestHelper helper, ServerLevel level, BlockPos origin, boolean podRide) {
            for (int u = -HALF - 1; u <= HALF + 1; u++) {
                for (int v = -HALF - 1; v <= HALF + 1; v++) {
                    for (int y = -1; y <= ARENA_HEIGHT; y++) {
                        boolean shell =
                                y == -1 || y == ARENA_HEIGHT || Math.abs(u) == HALF + 1 || Math.abs(v) == HALF + 1;
                        // room-carver: a closed arena of its own stone shell in the overworld test structure, not
                        // layer rock
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
            Tally tally = TestEntities.mob(CreatureRegistry.TALLY, level);
            tally.setPos(Vec3.atBottomCenterOf(origin.west(HALF - 1).above(2)));
            level.addFreshEntity(tally);
            return new Arena(level, origin, pod, podRide, holder, tally, survey(level, origin), helper.getTick());
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
            return level.getEntitiesOfClass(ItemEntity.class, new AABB(origin).inflate(HALF + 2))
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
                    clampedAt[0] = arena.age(helper);
                    LOGGER.info(
                            "[tally] pod: trail then clamp took {} ticks ({} s)", clampedAt[0], clampedAt[0] / 20.0);
                    if (clampedAt[0] < TUNING.trailTicks() || clampedAt[0] > TUNING.trailTicks() + APPROACH_TICKS_MAX) {
                        throw failure(
                                helper,
                                "the Tally clamped after %s ticks, outside the trail of %s and an approach of at most %s",
                                clampedAt[0],
                                TUNING.trailTicks(),
                                APPROACH_TICKS_MAX);
                    }
                    if (arena.ore() != ORES) {
                        throw failure(helper, "the bay held %s ore at the clamp, not %s", arena.ore(), ORES);
                    }
                } else if (arena.age(helper) > CLAMP_BUDGET_TICKS) {
                    throw failure(helper, "the Tally had not clamped to the hatch after %s ticks", arena.age(helper));
                }
                return;
            }
            if (arena.tally.isRemoved()) {
                goneAt[0] = arena.age(helper);
                LOGGER.info(
                        "[tally] pod: the sample was taken and the Tally gone {} ticks after the clamp",
                        goneAt[0] - clampedAt[0]);
                if (arena.ore() != ORES - 1 || arena.dear() != 1) {
                    throw failure(
                            helper,
                            "the bay holds %s cheap and %s dear ore after the skim, not %s and 1",
                            arena.ore(),
                            arena.dear(),
                            ORES - 1);
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
                    throw failure(
                            helper,
                            "the pilot was not told once each of the approach, the clamp and the sample: %s",
                            arena.holder.actionBarMessages());
                }
                arena.clear();
                helper.succeed();
            } else if (arena.age(helper) - clampedAt[0] > SAMPLE_BUDGET_TICKS) {
                throw failure(
                        helper,
                        "the Tally was still there %s ticks after it clamped, ore %s",
                        arena.age(helper) - clampedAt[0],
                        arena.ore());
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> made[0] = Arena.build(helper, level, origin, true));
    }

    /**
     * (#917) The cutter: the pilot drills the moment the Tally clamps, and the cutter breaks it before it can take its sample. It dies in
     * {@link TallyTimings#cutterBreakTicks} and a margin, the bay keeps all its ore, and it drops nothing.
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
                    clampedAt[0] = arena.age(helper);
                    arena.holder.setInput(SPRINT);
                } else if (arena.age(helper) > CLAMP_BUDGET_TICKS) {
                    throw failure(helper, "the Tally had not clamped to the hatch");
                }
                return;
            }
            long since = arena.age(helper) - clampedAt[0];
            if (!arena.tally.isAlive()) {
                if (brokeAt[0] < 0) {
                    brokeAt[0] = arena.age(helper);
                    if (since < TallyTimings.cutterBreakTicks(TUNING)
                            || since > TallyTimings.cutterBreakTicks(TUNING) + CUTTER_SLACK_TICKS) {
                        throw failure(
                                helper,
                                "the cutter broke the Tally %s ticks after the pilot drilled, outside %s to %s",
                                since,
                                TallyTimings.cutterBreakTicks(TUNING),
                                TallyTimings.cutterBreakTicks(TUNING) + CUTTER_SLACK_TICKS);
                    }
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
                    if (arena.age(helper) - brokeAt[0] < 4) {
                        return;
                    }
                    throw failure(
                            helper,
                            "the pilot was not told once each that the cutter bites and that it broke: %s",
                            arena.holder.actionBarMessages());
                }
                arena.clear();
                helper.succeed();
            } else if (since > TallyTimings.cutterBreakTicks(TUNING) + 20) {
                throw failure(
                        helper,
                        "the Tally lived %s ticks after the pilot drilled (health %s)",
                        since,
                        arena.tally.getHealth());
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            made[0] = Arena.build(helper, level, origin, true);
            for (int u = -HALF; u <= HALF; u++) {
                for (int v = -HALF; v <= HALF; v++) {
                    for (int y = BORE_DEPTH; y < 0; y++) {
                        // room-carver: the stone under this test's own arena, in the overworld test structure,
                        // not layer rock
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
                    clampedAt[0] = arena.age(helper);
                } else if (arena.age(helper) > CLAMP_BUDGET_TICKS) {
                    throw failure(helper, "the Tally had not clamped to the pack");
                }
                return;
            }
            if (arena.tally.isRemoved()) {
                LOGGER.info(
                        "[tally] foot: the sample was taken {} ticks after the clamp",
                        arena.age(helper) - clampedAt[0]);
                if (arena.ore() != ORES - 1 || arena.dear() != 1) {
                    throw failure(
                            helper,
                            "the person carries %s cheap and %s dear ore after the skim, not %s and 1",
                            arena.ore(),
                            arena.dear(),
                            ORES - 1);
                }
                if (arena.holder.player().getHealth() != arena.holder.player().getMaxHealth()) {
                    throw failure(helper, "the person was hurt");
                }
                if (arena.told(CLAMP_FOOT_KEY) != 1 || arena.told(SAMPLE_KEY) != 1) {
                    if (arena.age(helper) - clampedAt[0] < SAMPLE_BUDGET_TICKS) {
                        return;
                    }
                    throw failure(
                            helper,
                            "the person was not told once each of the clamp and the sample: %s",
                            arena.holder.actionBarMessages());
                }
                arena.clear();
                helper.succeed();
            } else if (arena.age(helper) - clampedAt[0] > SAMPLE_BUDGET_TICKS) {
                throw failure(
                        helper,
                        "the Tally was still there %s ticks after it clamped",
                        arena.age(helper) - clampedAt[0]);
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
                    struckAt[0] = arena.age(helper);
                    ServerPlayer person = arena.holder.player();
                    // The lightest blow there is: a bare hand does 1.
                    arena.tally.hurtServer(level, level.damageSources().playerAttack(person), 1f);
                    if (arena.tally.isAlive()) {
                        throw failure(
                                helper,
                                "a blow of 1 left the Tally alive with %s of %s health",
                                arena.tally.getHealth(),
                                arena.tally.getMaxHealth());
                    }
                    LOGGER.info("[tally] foot: one blow of 1 broke it (health {})", TUNING.maxHealth());
                } else if (arena.age(helper) > CLAMP_BUDGET_TICKS) {
                    throw failure(helper, "the Tally had not clamped to the pack");
                }
                return;
            }
            if (arena.tally.isRemoved()) {
                if (arena.ore() != ORES) {
                    throw failure(helper, "the person carries %s ore after the blow, not %s", arena.ore(), ORES);
                }
                if (arena.droppedItems() != 0) {
                    throw failure(helper, "the broken Tally dropped %s items", arena.droppedItems());
                }
                arena.clear();
                helper.succeed();
            } else if (arena.age(helper) - struckAt[0] > 60) {
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
            Tally tally = TestEntities.mob(CreatureRegistry.TALLY, level);
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
    @GameTest(maxTicks = 2 * FarChunks.AWAIT_BUDGET_TICKS + 60)
    public void onlyOreInTheDarkDrawsATally(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(5);
        Arena[] made = {null};
        int[] phase = {0};
        FarChunks.Deadline[] lit = {null};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null) {
                return;
            }
            ServerPlayer person = arena.holder.player();
            if (phase[0] == 0 && arena.age(helper) < SETTLE_TICKS) {
                return;
            }
            if (phase[0] == 0) {
                if (!Tally.draws(arena.pod)) {
                    throw failure(helper, "a pod with a pilot and ore draws no Tally");
                }
                person.setGameMode(GameType.CREATIVE);
                if (Tally.draws(arena.pod)) {
                    throw failure(helper, "a pod flown by a creative pilot draws a Tally");
                }
                person.setGameMode(GameType.SURVIVAL);
                arena.pod.cargo().dump(arena.pod);
                if (Tally.draws(arena.pod)) {
                    throw failure(helper, "a pod with an empty bay draws a Tally");
                }
                arena.pod.cargo().tryAdd(arena.pod, OreRegistry.stack(OreType.IRONIUM));
                person.stopRiding();
                if (Tally.draws(arena.pod)) {
                    throw failure(helper, "a pod with ore and nobody aboard draws a Tally");
                }
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
                lit[0] = FarChunks.deadline();
                phase[0] = 1;
            } else if (level.getBrightness(LightLayer.BLOCK, person.blockPosition())
                    >= CreatureTuning.DEFAULT.litBlockLight()) {
                if (Tally.draws(person)) {
                    throw failure(helper, "a person with ore standing in a light draws a Tally");
                }
                arena.clear();
                helper.succeed();
            } else {
                lit[0].await(helper, level, false, () -> "the light did not reach the person");
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> made[0] = Arena.build(helper, level, origin, true));
    }

    private static void lightFlare(ServerLevel level, BlockPos pos, long burnsOutAt) {
        // room-carver: a flare block in this test's own arena
        level.setBlock(pos, RepairRegistry.FLARE_LIGHT.defaultBlockState(), 3);
        DrawSources.addFlare(level, pos, RepairRegistry.FLARE_LIGHT, FLARE_REACH, burnsOutAt);
    }

    private static void snuffFlare(ServerLevel level, BlockPos pos) {
        // room-carver: the flare block of this test's own arena goes out with its entry
        level.removeBlock(pos, false);
    }

    /**
     * (#1003) A burning flare keeps a Tally off a dark pod as it keeps the Retained off (ADR 0078): the pod inside its reach draws none, and
     * draws one again once the flare has burnt out and its block is gone.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + SETTLE_TICKS + FLARE_BURN_TICKS + 60)
    public void aBurningFlareKeepsATallyOffADarkPodUntilItIsOut(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(14);
        Arena[] made = {null};
        BlockPos[] flare = {null};
        long[] burnsOutAt = {-1};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null || arena.age(helper) < SETTLE_TICKS) {
                return;
            }
            if (flare[0] == null) {
                if (!Tally.draws(arena.pod)) {
                    throw failure(helper, "a dark pod with ore and a pilot draws no Tally before the flare");
                }
                flare[0] = arena.pod.blockPosition().above(2);
                burnsOutAt[0] = level.getGameTime() + FLARE_BURN_TICKS;
                lightFlare(level, flare[0], burnsOutAt[0]);
            } else if (level.getGameTime() < burnsOutAt[0]) {
                if (Tally.draws(arena.pod)) {
                    throw failure(helper, "a dark pod inside a burning flare's reach draws a Tally");
                }
            } else {
                snuffFlare(level, flare[0]);
                if (!Tally.draws(arena.pod)) {
                    throw failure(helper, "a dark pod draws no Tally once the flare is out");
                }
                arena.clear();
                helper.succeed();
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> made[0] = Arena.build(helper, level, origin, true));
    }

    /**
     * (#1003) A Tally that trails a pod drops it when a flare lights the pod, and takes it up again once the flare is out: the pilot is told
     * of the approach a second time.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + APPROACH_TICKS_MAX + FLARE_BURN_TICKS + 400)
    public void aTallyTrailingAPodDropsItWhenAFlareLightsIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(15);
        Arena[] made = {null};
        BlockPos[] flare = {null};
        long[] litAt = {-1};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null) {
                return;
            }
            if (flare[0] == null) {
                if (arena.told(APPROACH_KEY) > 0) {
                    flare[0] = arena.pod.blockPosition().above(2);
                    litAt[0] = level.getGameTime();
                    lightFlare(level, flare[0], litAt[0] + FLARE_BURN_TICKS);
                } else if (arena.age(helper) > APPROACH_TICKS_MAX) {
                    throw failure(helper, "the Tally never began to trail the pod");
                }
                return;
            }
            if (level.getGameTime() < litAt[0] + FLARE_BURN_TICKS) {
                return;
            }
            snuffFlare(level, flare[0]);
            if (arena.told(APPROACH_KEY) > 1) {
                arena.clear();
                helper.succeed();
            } else if (level.getGameTime() > litAt[0] + FLARE_BURN_TICKS + 300) {
                throw failure(helper, "the Tally did not take the pod up again once the flare was out");
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
                    throw failure(helper, "%s checks that passed in open dark air sent no Tally", SEND_ATTEMPT_TICKS);
                }
                return;
            }
            double distance = sent.get().position().distanceTo(pod[0].position());
            if (distance < TUNING.spawnMinBlocks() - 4 || distance > TUNING.spawnMaxBlocks() + 4) {
                throw failure(
                        helper,
                        "the Tally appeared %s blocks off, outside %s to %s",
                        distance,
                        TUNING.spawnMinBlocks(),
                        TUNING.spawnMaxBlocks());
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
        // Never added to a level, so the game never checks it for despawn.
        var inside = TestEntities.create(CreatureRegistry.TALLY, helper.getLevel());
        inside.setPos(casing.getX() + 0.5 + range, casing.getY(), casing.getZ() + 0.5 - range);
        if (!Tallies.nearCasing(inside, casing)) {
            throw failure(helper, "the corner of the zone, %s blocks out each way, is not near the casing", range);
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
            Tally tally = TestEntities.mob(CreatureRegistry.TALLY, level);
            tally.setNoAi(true);
            tally.setPos(Vec3.atBottomCenterOf(at.east(2)));
            level.addFreshEntity(tally);
            ScanSlice with = ScanSlice.scan(EchoedBlocks.ofTallies(level, pod), at, Direction.EAST, 1, Chassis.MOLE);
            if (!(with.cell(2, 0) instanceof ScanSlice.Cell.Ore)) {
                throw failure(helper, "the Tally's cell reads as %s, not ore", with.cell(2, 0));
            }
            if (!(with.cell(3, 0) instanceof ScanSlice.Cell.Air)) {
                throw failure(helper, "the cell beside the Tally reads as %s, not air", with.cell(3, 0));
            }
            tally.discard();
            ScanSlice without = ScanSlice.scan(EchoedBlocks.ofTallies(level, pod), at, Direction.EAST, 1, Chassis.MOLE);
            if (!(without.cell(2, 0) instanceof ScanSlice.Cell.Air)) {
                throw failure(helper, "with no Tally the cell reads as %s, not air", without.cell(2, 0));
            }
            pod.discard();
            helper.succeed();
        });
    }

    /** (#917) A Tally that cannot reach its mark, walled off in a sealed pocket, trails for as long as it likes and never clamps or takes anything. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CLAMP_BUDGET_TICKS + 100)
    public void aTallyWalledOffFromThePodNeverClamps(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(9);
        Arena[] made = {null};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null) {
                return;
            }
            if (arena.tally.isClamped()) {
                throw failure(
                        helper,
                        "the Tally clamped to a pod it is walled off from, %s blocks away",
                        arena.tally.distanceTo(arena.pod));
            }
            if (arena.ore() != ORES || arena.told(CLAMP_POD_KEY) != 0) {
                throw failure(helper, "the walled-off Tally took ore or told the pilot it clamped");
            }
            if (arena.age(helper) > CLAMP_BUDGET_TICKS + 40) {
                if (arena.tally.position().x > origin.getX()) {
                    throw failure(helper, "the Tally came through the wall to %s", arena.tally.position());
                }
                arena.clear();
                helper.succeed();
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            made[0] = Arena.build(helper, level, origin, true);
            for (int v = -HALF; v <= HALF; v++) {
                for (int y = 0; y < ARENA_HEIGHT; y++) {
                    // room-carver: a stone partition across this test's own arena, in the overworld test structure, not
                    // layer rock
                    level.setBlock(origin.offset(0, y, v), Blocks.STONE.defaultBlockState(), 3);
                }
            }
        });
    }

    /**
     * (#917) A light does not free a clamped Tally (it only keeps one off): the pod is lit the tick the Tally clamps, and the sample is still
     * taken. A lit pod, from the start, draws nothing.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CLAMP_BUDGET_TICKS + SAMPLE_BUDGET_TICKS + 60)
    public void aLitPodDrawsNothingAndALightDoesNotFreeAClampedTally(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(10);
        Arena[] made = {null};
        long[] clampedAt = {-1};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null) {
                return;
            }
            if (clampedAt[0] < 0) {
                if (arena.tally.isClamped()) {
                    clampedAt[0] = arena.age(helper);
                    if (!Tally.draws(arena.pod)) {
                        throw failure(helper, "a dark pod with ore and a pilot draws no Tally");
                    }
                    CharterId charter = charter(helper, arena.holder.player().getUUID());
                    PodComponents.register(arena.pod, charter);
                    PodComponents.install(
                            arena.pod, ComponentItems.mint(level.getServer(), ComponentTrack.LIGHTS, 1, charter));
                    if (Tally.draws(arena.pod)) {
                        throw failure(helper, "a lit pod draws a Tally");
                    }
                } else if (arena.age(helper) > CLAMP_BUDGET_TICKS) {
                    throw failure(helper, "the Tally had not clamped to the hatch");
                }
                return;
            }
            if (arena.tally.isRemoved()) {
                if (arena.ore() != ORES - 1) {
                    throw failure(
                            helper,
                            "the bay holds %s cheap ore after a light came on at the clamp, not %s",
                            arena.ore(),
                            ORES - 1);
                }
                arena.clear();
                helper.succeed();
            } else if (arena.ore() == ORES && arena.age(helper) - clampedAt[0] > SAMPLE_BUDGET_TICKS) {
                throw failure(helper, "the Tally let go of a lit pod and took nothing");
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> made[0] = Arena.build(helper, level, origin, true));
    }

    /** (#917) A clamped Tally whose pod is gone lets go and goes back to drifting, whole. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CLAMP_BUDGET_TICKS + 100)
    public void aClampedTallyLetsGoOfAPodThatIsGone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(11);
        Arena[] made = {null};
        long[] goneAt = {-1};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null) {
                return;
            }
            if (goneAt[0] < 0) {
                if (arena.tally.isClamped()) {
                    goneAt[0] = arena.age(helper);
                    arena.pod.discard();
                } else if (arena.age(helper) > CLAMP_BUDGET_TICKS) {
                    throw failure(helper, "the Tally had not clamped to the hatch");
                }
                return;
            }
            if (!arena.tally.isClamped()) {
                if (!arena.tally.isAlive()) {
                    throw failure(helper, "the Tally died when its pod went");
                }
                arena.clear();
                helper.succeed();
            } else if (arena.age(helper) - goneAt[0] > 10) {
                throw failure(helper, "the Tally still clamped a pod that was gone 10 ticks ago");
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> made[0] = Arena.build(helper, level, origin, true));
    }

    /** (#917) A clamped Tally whose person leaves (logs off) lets go and goes back to drifting, and takes nothing. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CLAMP_BUDGET_TICKS + 100)
    public void aClampedTallyLetsGoOfAPersonWhoLeaves(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(12);
        Arena[] made = {null};
        long[] leftAt = {-1};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null) {
                return;
            }
            if (leftAt[0] < 0) {
                if (arena.tally.isClamped()) {
                    leftAt[0] = arena.age(helper);
                    arena.holder.leave();
                } else if (arena.age(helper) > CLAMP_BUDGET_TICKS) {
                    throw failure(helper, "the Tally had not clamped to the pack");
                }
                return;
            }
            if (arena.tally.isRemoved()) {
                throw failure(helper, "the Tally was removed when its person left, not let go");
            }
            if (!arena.tally.isClamped()) {
                if (!arena.tally.isAlive()) {
                    throw failure(helper, "the Tally died when its person left");
                }
                arena.tally.discard();
                arena.pod.discard();
                helper.succeed();
            } else if (arena.age(helper) - leftAt[0] > 10) {
                throw failure(helper, "the Tally still clamped a person who left 10 ticks ago");
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            made[0] = Arena.build(helper, level, origin, false);
            // A Tally with no player within 128 blocks is removed at once (vanilla despawn), clamped or not. The near
            // bystander, creative so it draws no Tally, keeps a player beside the arena, so the release is the only way
            // out (#1138). The far one is another test's player, as in a full run: it makes a regression in the near
            // one fail every time, with "removed, not let go", instead of only in some runs.
            MockPlayer far = MockPlayers.join(helper, "far-bystander");
            double apart = far.player().position().distanceTo(Vec3.atCenterOf(origin));
            if (apart <= DESPAWN_BLOCKS) {
                throw failure(helper, "the far bystander is only %s blocks from the arena", apart);
            }
            LOGGER.info("[tally] far bystander {} blocks from the arena", apart);
            MockPlayer near = MockPlayers.join(helper, "near-bystander");
            near.player().setGameMode(GameType.CREATIVE);
            near.teleportTo(level, Vec3.atBottomCenterOf(origin.north(HALF - 1)), 0f, 0f);
        });
    }

    /**
     * (#917, m2-rules) A pod whose saved cargo this build cannot read draws no Tally, and a Tally already clamped to it takes nothing and leaves
     * the saved cargo as it was. No path of the Tally throws on it.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CLAMP_BUDGET_TICKS + SAMPLE_BUDGET_TICKS + 60)
    public void anUnreadableBayDrawsNothingAndLosesNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(13);
        Arena[] made = {null};
        long[] clampedAt = {-1};
        CompoundTag[] saved = {null};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null) {
                return;
            }
            if (clampedAt[0] < 0) {
                if (arena.tally.isClamped()) {
                    clampedAt[0] = arena.age(helper);
                    TagValueOutput output =
                            TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
                    arena.pod.saveWithoutId(output);
                    CompoundTag tag = output.buildResult();
                    tag.putInt("cargo_version", UnreadableChecks.FUTURE_VERSION);
                    arena.pod
                            .cargo()
                            .load(
                                    TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag),
                                    arena.pod);
                    if (arena.pod.cargo().isReadable()) {
                        throw failure(helper, "the test needs unreadable cargo");
                    }
                    UnreadableChecks.assertNoThrow(
                            helper,
                            "tally",
                            Map.of(
                                    "draws",
                                    () -> Tally.draws(arena.pod),
                                    "echo",
                                    () -> EchoedBlocks.ofTallies(level, arena.pod)));
                    if (Tally.draws(arena.pod)) {
                        throw failure(helper, "a pod with an unreadable bay draws a Tally");
                    }
                    TagValueOutput kept =
                            TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
                    arena.pod.saveWithoutId(kept);
                    saved[0] = kept.buildResult();
                } else if (arena.age(helper) > CLAMP_BUDGET_TICKS) {
                    throw failure(helper, "the Tally had not clamped to the hatch");
                }
                return;
            }
            if (arena.tally.isRemoved()) {
                TagValueOutput after =
                        TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
                arena.pod.saveWithoutId(after);
                if (!Objects.equals(after.buildResult().get("cargo"), saved[0].get("cargo"))) {
                    throw failure(helper, "the Tally changed the saved cargo it could not read");
                }
                arena.clear();
                helper.succeed();
            } else if (arena.age(helper) - clampedAt[0] > SAMPLE_BUDGET_TICKS) {
                throw failure(
                        helper,
                        "the Tally was still there %s ticks after it clamped",
                        arena.age(helper) - clampedAt[0]);
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> made[0] = Arena.build(helper, level, origin, true));
    }

    /** (#917) A Tally is not an entity of Peaceful, and a scanner's echo reaches past the widest scan area of every tier. */
    @GameTest
    public void aTallyIsNotOfPeacefulAndItsEchoReachesTheWidestScan(GameTestHelper helper) {
        if (CreatureRegistry.TALLY.isAllowedInPeaceful()) {
            throw failure(helper, "a Tally is allowed in Peaceful");
        }
        for (int tier = 1; tier <= ComponentTrack.SCANNER.maxTier(); tier++) {
            ScanArea area = ScannerTuning.DEFAULT.area(tier);
            int widest = Math.max(area.halfWidth(), Math.max(area.up(), area.down()));
            if (EchoedBlocks.TALLY_REACH_BLOCKS <= widest) {
                throw failure(
                        helper,
                        "the echo reaches %s blocks, not past the %s of a tier %s scan",
                        EchoedBlocks.TALLY_REACH_BLOCKS,
                        widest,
                        tier);
            }
        }
        helper.succeed();
    }

    /**
     * (#1009) A light in either hand keeps a Tally off a person on foot, as it keeps off a Retained: the same dark,
     * ore-carrying person draws one with empty hands, and draws none with a torch in the main hand or the off hand.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + SETTLE_TICKS + 60)
    public void aLightInHandKeepsATallyOffAPersonOnFoot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(16);
        Arena[] made = {null};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null || arena.age(helper) < SETTLE_TICKS) {
                return;
            }
            ServerPlayer person = arena.holder.player();
            if (!Tally.draws(person)) {
                throw failure(helper, "a person with ore in the dark and empty hands draws no Tally");
            }
            person.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TORCH));
            if (Tally.draws(person)) {
                throw failure(helper, "a person with a torch in the main hand draws a Tally");
            }
            person.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            person.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.TORCH));
            if (Tally.draws(person)) {
                throw failure(helper, "a person with a torch in the off hand draws a Tally");
            }
            person.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.STONE));
            if (!Tally.draws(person)) {
                throw failure(helper, "a stone in the hand keeps a Tally off");
            }
            arena.clear();
            helper.succeed();
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> made[0] = Arena.build(helper, level, origin, false));
    }

    /**
     * (#1009) A light does not free a person a Tally has clamped to: a torch taken in hand at the clamp keeps further
     * Tallies off, and the clamped one still takes its sample.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + CLAMP_BUDGET_TICKS + SAMPLE_BUDGET_TICKS + 60)
    public void aTorchLitAfterTheClampDoesNotFreeAPersonOnFoot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = slot(17);
        Arena[] made = {null};
        long[] clampedAt = {-1};
        TestTicks.each(helper, () -> {
            Arena arena = made[0];
            if (arena == null) {
                return;
            }
            ServerPlayer person = arena.holder.player();
            if (clampedAt[0] < 0) {
                if (arena.tally.isClamped()) {
                    clampedAt[0] = arena.age(helper);
                    if (!Tally.draws(person)) {
                        throw failure(helper, "a dark person with ore and empty hands draws no Tally");
                    }
                    person.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.TORCH));
                    if (Tally.draws(person)) {
                        throw failure(helper, "a person with a torch in hand draws a Tally");
                    }
                } else if (arena.age(helper) > CLAMP_BUDGET_TICKS) {
                    throw failure(helper, "the Tally had not clamped to the pack");
                }
                return;
            }
            if (arena.tally.isRemoved()) {
                if (arena.ore() != ORES - 1) {
                    throw failure(
                            helper,
                            "the person carries %s cheap ore after a torch came to hand at the clamp, not %s",
                            arena.ore(),
                            ORES - 1);
                }
                arena.clear();
                helper.succeed();
            } else if (arena.ore() == ORES && arena.age(helper) - clampedAt[0] > SAMPLE_BUDGET_TICKS) {
                throw failure(helper, "the Tally let go of a person with a torch in hand and took nothing");
            }
        });
        FarChunks.awaitEntityTicking(helper, level, origin, () -> made[0] = Arena.build(helper, level, origin, false));
    }

    /**
     * (#1237) A Tally despawns like any monster ({@code removeWhenFarAway} is true), so a test that leaves one standing
     * builds it with {@code TestEntities.mob}. The only player is more than 128 blocks off, where
     * {@code Mob.checkDespawn} removes a mob at once: a plain {@code create} Tally goes, a persistent one stays. The
     * site is more than 128 blocks from every other test's arena, whose players would otherwise be the nearest.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 40)
    public void aTallyTheTestBuildsWithMobIsNotDespawnedByTheGame(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = DESPAWN_SITE;
        TestTicks.each(helper, () -> {});
        FarChunks.awaitEntityTicking(helper, level, origin, () -> {
            MockPlayer far = MockPlayers.join(helper, "despawn-bystander");
            // GameTest players are creative, and the despawn check looks past a creative player.
            far.player().setGameMode(GameType.SURVIVAL);
            double apart = far.player().position().distanceTo(Vec3.atCenterOf(origin));
            if (apart <= DESPAWN_BLOCKS) {
                throw failure(helper, "the only player is %s blocks from the Tally, within %s", apart, DESPAWN_BLOCKS);
            }
            Tally plain = TestEntities.create(CreatureRegistry.TALLY, level);
            Tally persistent = TestEntities.mob(CreatureRegistry.TALLY, level);
            try {
                for (Tally tally : List.of(plain, persistent)) {
                    tally.setPos(Vec3.atBottomCenterOf(origin.above(30)));
                    level.addFreshEntity(tally);
                    tally.checkDespawn();
                }
                if (!plain.isRemoved()) {
                    throw failure(helper, "a Tally built with create was not despawned %s blocks from a player", apart);
                }
                if (persistent.isRemoved()) {
                    throw failure(
                            helper,
                            "a Tally built with mob was despawned by the game: %s",
                            persistent.getRemovalReason());
                }
            } finally {
                plain.discard();
                persistent.discard();
            }
            helper.succeed();
        });
    }

    private static final AtomicInteger CHARTERS = new AtomicInteger();

    private static CharterId charter(GameTestHelper helper, UUID founder) {
        var server = helper.getLevel().getServer();
        var refusal = Charters.found(server, founder, "Tally " + CHARTERS.incrementAndGet());
        if (refusal.isPresent()) {
            throw failure(helper, "a charter step was refused: %s", refusal.get());
        }
        return Charters.charterOfOrThrow(server, founder).orElseThrow().id();
    }

    private static BlockPos slot(int index) {
        return new BlockPos(FIRST_SLOT_X, FLOOR_Y, FIRST_SLOT_X + index * SLOT_Z_SPACING);
    }
}
