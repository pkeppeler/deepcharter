package io.github.pkeppeler.deepcharter.test;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.layer.Depth;
import io.github.pkeppeler.deepcharter.layer.GrainedCrust;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.pod.PodDrill;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.test.support.Arrival;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.PulseClock;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Server GameTests for the grained crust (#746, ADR 0060), one for each rule: the drill makes no progress sideways across the grain, a block
 * placed against it crumbles on the next pulse and drops nothing, a fluid that reaches it is absorbed, and whatever stops in it is squeezed
 * (a stopped pod loses hull, a player on foot is hurt, a moving pod takes nothing). Each test has its own X in a carved room of layer 1: the
 * rules hold wherever the block is, so none needs the campaign world. The pulse runs on the world clock, so a test waits for it by ticks.
 */
public class GrainedCrustTest {
    private static final LayerTuning TUNING = LayerTuning.DEFAULT;
    private static final int Z = 6200;
    private static final int FLOOR = TestLayers.worldY(1, 60);
    private static final int ROOM = 8;
    private static final float EAST = -90f;
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 1200;
    /** Ticks either side of a pulse that a baseline reading keeps clear of, so that no pulse is half applied when it is taken. */
    private static final int PULSE_MARGIN = 5;
    /** How long before each pulse the warning creak sounds (ADR 0060). */
    private static final int WARN_TICKS = 10;

    private static final Identifier WARNING = SoundEvents.CREAKING_TWITCH.location();
    /** Ticks a fluid may sit against the grain before it is absorbed: it is absorbed on the tick after it arrives, and a callback may run between. */
    private static final int ABSORB_TICKS = 2;
    /** Ticks a fluid has to reach the wall: water takes 5 ticks a cell and lava 30, so the wall has been reached by now. */
    private static final int FLUID_SETTLE_TICKS = 160;

    private static final Input SPRINT = new Input(false, false, false, false, false, false, true);
    private static final Input FORWARD = new Input(true, false, false, false, false, false, false);

    /** A pilot, and the pod it sits in once the far chunk ticks entities. */
    private static final class Rig {
        private final MockPlayer pilot;
        private final Arrival<PodEntity> pod = new Arrival<>("pod", "the far chunk ticks");

        private Rig(MockPlayer pilot) {
            this.pilot = pilot;
        }
    }

    /**
     * Counts the pulse ticks a test has seen. A test callback may run before or after the level tick it shares with a pulse, so on a pulse
     * tick the loss may or may not be in yet; on any other tick every pulse so far is.
     */
    private static final class Pulses {
        private int seen;
        private boolean onPulse;

        void observe(ServerLevel level) {
            onPulse = GrainedCrust.isPulse(level);
            if (onPulse) {
                seen++;
            }
        }

        /** The pulses that are surely in: all of them but, on a pulse tick, the one in flight. */
        int settled() {
            return onPulse ? seen - 1 : seen;
        }

        boolean lossFits(double lost, double each) {
            return Math.abs(lost - settled() * each) < 0.01 || (onPulse && Math.abs(lost - seen * each) < 0.01);
        }
    }

    @GameTest
    public void theBlockIdIsPermanentAndThePulseIsAboutFiveSeconds(GameTestHelper helper) {
        String id = BuiltInRegistries.BLOCK.getKey(LayerBlocks.GRAINED_CRUST).toString();
        if (!id.equals("deepcharter:grained_crust")) {
            throw failure(helper, "the grained crust's id is permanent (ADR 0060), it is %s", id);
        }
        int ticks = TUNING.grainPulseTicks();
        if (ticks < 80 || ticks > 120) {
            throw failure(helper, "the pulse is 4 to 6 s (80 to 120 ticks) by design, it is %d ticks", ticks);
        }
        helper.succeed();
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aDrillMakesNoProgressSidewaysAcrossTheGrain(GameTestHelper helper) {
        int x = 4600;
        ServerLevel level = room(helper, x);
        box(level, x + 4, x + 5, FLOOR, FLOOR + 10, Z - ROOM, Z + ROOM, LayerBlocks.GRAINED_CRUST);
        Rig rig = rig(helper, level, new Vec3(x + 0.3, FLOOR, Z + 0.3), EAST, "grain-sideways", FORWARD);
        int[] held = {0};
        float[] hullAtWall = {0};
        TestTicks.each(helper, () -> {
            if (!rig.pod.isPresent()) {
                return;
            }
            PodEntity pod = rig.pod.get();
            if (held[0] == 0 && !(pod.horizontalCollision && pod.getX() > x + 2)) {
                return;
            }
            if (held[0]++ == 0) {
                hullAtWall[0] = pod.hull();
            }
            if (pod.drilling()) {
                throw failure(helper, "the drill must not bite across the grain, but the pod is drilling");
            }
            if (count(level, x + 4, x + 5, FLOOR, FLOOR + 10, Z - ROOM, Z + ROOM, Blocks.AIR) != 0) {
                throw failure(helper, "the drill made progress across the grain: a cell of the wall is gone");
            }
            PodStats stats = PodStats.of(pod);
            float crustHardness =
                    LayerBlocks.GRAINED_CRUST.defaultBlockState().getDestroySpeed(level, pod.blockPosition());
            int bore = PodDrill.drillTicks(stats, crustHardness, Depth.feet(Depth.of(level, FLOOR)));
            if (held[0] < 3 * bore) {
                return;
            }
            double heat = TUNING.grainSkateHeatPerSecond() * held[0] / 20.0;
            double lost = hullAtWall[0] - pod.hull();
            if (lost < heat - 0.5) {
                throw failure(helper, "the skating drill should heat the hull by %.2f, it lost %.2f", heat, lost);
            }
            if (!heard(rig.pilot, SoundEvents.GRINDSTONE_USE.location())) {
                throw failure(
                        helper, "the drill should squeal on the grain, the pilot heard %s", rig.pilot.soundsHeard());
            }
            if (!toldOf(rig.pilot, "deepcharter.layer.grain_skates")) {
                throw failure(helper, "the pilot should be told the drill skates");
            }
            rig.pilot.releaseInput();
            pod.discard();
            helper.succeed();
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aDrillStillBoresDownAlongTheGrain(GameTestHelper helper) {
        int x = 4640;
        ServerLevel level = room(helper, x);
        box(level, x - 4, x + 4, FLOOR - 2, FLOOR - 1, Z - 4, Z + 4, LayerBlocks.GRAINED_CRUST);
        Rig rig = rig(helper, level, new Vec3(x + 0.3, FLOOR, Z + 0.3), 0f, "grain-down", SPRINT);
        TestTicks.each(helper, () -> {
            if (!rig.pod.isPresent()) {
                return;
            }
            if (count(level, x - 2, x + 2, FLOOR - 1, FLOOR - 1, Z - 2, Z + 2, Blocks.AIR) < 4) {
                return;
            }
            rig.pilot.releaseInput();
            rig.pod.get().discard();
            helper.succeed();
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aBlockPlacedAgainstOrIntoTheGrainCrumblesOnThePulseAndDropsNothing(GameTestHelper helper) {
        int x = 4680;
        ServerLevel level = room(helper, x);
        BlockPos against = new BlockPos(x + 2, FLOOR + 1, Z);
        box(level, x + 3, x + 3, FLOOR, FLOOR + 2, Z, Z, LayerBlocks.GRAINED_CRUST);
        // A cube with a pocket cut into its middle: the second block goes in there.
        BlockPos pocket = new BlockPos(x + 6, FLOOR + 1, Z + 4);
        box(level, x + 5, x + 7, FLOOR, FLOOR + 2, Z + 3, Z + 5, LayerBlocks.GRAINED_CRUST);
        level.setBlock(pocket, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        Arrival<Boolean> ready = ready(helper, level, x);
        long[] pulseAt = {-1};
        TestTicks.each(helper, () -> {
            if (!ready.isPresent()) {
                return;
            }
            long now = level.getGameTime();
            if (pulseAt[0] < 0) {
                if (PulseClock.ticksToPulse(level) < PULSE_MARGIN * 6) {
                    return;
                }
                level.setBlock(against, Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_ALL);
                level.setBlock(pocket, Blocks.COBBLESTONE.defaultBlockState(), Block.UPDATE_ALL);
                pulseAt[0] = now + PulseClock.ticksToPulse(level);
                return;
            }
            boolean standing = level.getBlockState(against).is(Blocks.COBBLESTONE)
                    && level.getBlockState(pocket).is(Blocks.COBBLESTONE);
            if (now < pulseAt[0]) {
                if (!standing) {
                    throw failure(helper, "a placed block crumbled %d ticks before the pulse", pulseAt[0] - now);
                }
                return;
            }
            if (now < pulseAt[0] + PULSE_MARGIN) {
                return;
            }
            if (!level.getBlockState(against).isAir()
                    || !level.getBlockState(pocket).isAir()) {
                throw failure(helper, "a block placed against or into the grain should be gone after one pulse");
            }
            if (!level.getBlockState(new BlockPos(x + 3, FLOOR + 1, Z)).is(LayerBlocks.GRAINED_CRUST)
                    || !level.getBlockState(new BlockPos(x + 6, FLOOR, Z + 4)).is(LayerBlocks.GRAINED_CRUST)) {
                throw failure(helper, "the crumbling must take the placed block only, never the grained crust");
            }
            List<ItemEntity> drops = level.getEntitiesOfClass(
                    ItemEntity.class, new AABB(x - ROOM, FLOOR - 1, Z - ROOM, x + ROOM, FLOOR + 10, Z + ROOM));
            if (!drops.isEmpty()) {
                throw failure(helper, "a crumbled block must drop nothing, found %s", drops);
            }
            helper.succeed();
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aFluidThatReachesTheGrainIsAbsorbed(GameTestHelper helper) {
        int x = 4720;
        ServerLevel level = room(helper, x);
        box(level, x + 4, x + 5, FLOOR, FLOOR + 10, Z - ROOM, Z + ROOM, LayerBlocks.GRAINED_CRUST);
        int waterZ = Z - 6;
        int lavaZ = Z + 6;
        level.setBlock(new BlockPos(x, FLOOR, waterZ), Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(new BlockPos(x + 2, FLOOR, lavaZ), Blocks.LAVA.defaultBlockState(), Block.UPDATE_ALL);
        Arrival<Boolean> ready = ready(helper, level, x);
        long[] readyAt = {-1};
        int[] stayed = new int[2 * ROOM + 1];
        BlockPos waterWitness = new BlockPos(x + 1, FLOOR, waterZ);
        BlockPos lavaWitness = new BlockPos(x + 2, FLOOR, lavaZ + 1);
        TestTicks.each(helper, () -> {
            if (!ready.isPresent()) {
                return;
            }
            if (readyAt[0] < 0) {
                readyAt[0] = level.getGameTime();
            }
            for (int z = Z - ROOM; z <= Z + ROOM; z++) {
                int cell = z - (Z - ROOM);
                stayed[cell] =
                        level.getFluidState(new BlockPos(x + 3, FLOOR, z)).isEmpty() ? 0 : stayed[cell] + 1;
                if (stayed[cell] > ABSORB_TICKS) {
                    throw failure(
                            helper,
                            "a fluid reached the cell against the grain at z %d and stayed: %s",
                            z,
                            level.getBlockState(new BlockPos(x + 3, FLOOR, z)));
                }
            }
            boolean waterFlowed = level.getFluidState(waterWitness).is(Fluids.FLOWING_WATER);
            boolean lavaFlowed = level.getFluidState(lavaWitness).is(Fluids.FLOWING_LAVA);
            if (waterFlowed && lavaFlowed && level.getGameTime() > readyAt[0] + FLUID_SETTLE_TICKS) {
                helper.succeed();
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aStoppedPodLosesHullOnEachPulse(GameTestHelper helper) {
        int x = 4760;
        ServerLevel level = room(helper, x);
        pad(level, x);
        Arrival<PodEntity> alone = lonePod(helper, level, new Vec3(x + 0.5, FLOOR, Z + 0.5));
        Pulses pulses = new Pulses();
        float[] baseline = {-1};
        TestTicks.each(helper, () -> {
            if (!alone.isPresent()) {
                return;
            }
            PodEntity pod = alone.get();
            if (baseline[0] < 0) {
                if (pod.onGround() && clearOfAPulse(level)) {
                    baseline[0] = pod.hull();
                }
                return;
            }
            pulses.observe(level);
            double lost = baseline[0] - pod.hull();
            if (!pulses.lossFits(lost, TUNING.grainSqueezeHull())) {
                throw failure(
                        helper,
                        "after %d pulses the hull should have lost %.2f each, it lost %.2f in all",
                        pulses.seen,
                        TUNING.grainSqueezeHull(),
                        lost);
            }
            if (pulses.settled() == 2) {
                pod.discard();
                helper.succeed();
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aPlayerOnFootIsSqueezedOnEachPulse(GameTestHelper helper) {
        int x = 4800;
        ServerLevel level = room(helper, x);
        pad(level, x);
        MockPlayer walker = MockPlayers.join(helper, "grain-foot");
        walker.player().setGameMode(GameType.SURVIVAL);
        walker.teleportTo(level, new Vec3(x + 0.5, FLOOR, Z + 0.5), 0f, 0f);
        Arrival<Boolean> ready = ready(helper, level, x);
        Pulses pulses = new Pulses();
        float[] baseline = {-1};
        TestTicks.each(helper, () -> {
            if (!ready.isPresent()) {
                return;
            }
            // A joined player is invulnerable for the first 60 ticks, and a full stomach heals: keep the food low.
            walker.player().getFoodData().setFoodLevel(6);
            if (baseline[0] < 0) {
                if (helper.getTick() > 100 && walker.player().onGround() && clearOfAPulse(level)) {
                    baseline[0] = walker.player().getHealth();
                }
                return;
            }
            pulses.observe(level);
            double lost = baseline[0] - walker.player().getHealth();
            if (!pulses.lossFits(lost, TUNING.grainSqueezeDamage())) {
                throw failure(
                        helper,
                        "after %d pulses the player should have lost %.2f each, lost %.2f in all",
                        pulses.seen,
                        TUNING.grainSqueezeDamage(),
                        lost);
            }
            if (pulses.settled() == 2) {
                helper.succeed();
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aMovingPodTakesNoSqueeze(GameTestHelper helper) {
        int x = 4840;
        ServerLevel level = room(helper, x);
        box(level, x - ROOM, x + ROOM, FLOOR - 1, FLOOR - 1, Z - 3, Z + 3, LayerBlocks.GRAINED_CRUST);
        Rig rig = rig(helper, level, new Vec3(x - ROOM + 1.5, FLOOR, Z + 0.5), EAST, "grain-moving", null);
        long[] pulseAt = {-1};
        float[] hullAtStart = {0};
        double[] startX = {0};
        TestTicks.each(helper, () -> {
            if (!rig.pod.isPresent()) {
                return;
            }
            PodEntity pod = rig.pod.get();
            long now = level.getGameTime();
            if (pulseAt[0] < 0) {
                // Drive off 20 ticks (4 blocks of the pad) before a pulse, so that the pulse finds the pod in motion.
                if (pod.onGround() && PulseClock.ticksToPulse(level) == 20) {
                    hullAtStart[0] = pod.hull();
                    startX[0] = pod.getX();
                    pulseAt[0] = now + 20;
                    rig.pilot.setInput(FORWARD);
                }
                return;
            }
            if (now < pulseAt[0] + PULSE_MARGIN) {
                return;
            }
            double driven = pod.getX() - startX[0];
            if (driven < 1.5 || pod.getX() > x + ROOM) {
                throw failure(helper, "the pod should have driven along the pad, it moved %.2f blocks", driven);
            }
            if (pod.hull() != hullAtStart[0]) {
                throw failure(
                        helper,
                        "a moving pod takes no squeeze, its hull went from %.2f to %.2f",
                        hullAtStart[0],
                        pod.hull());
            }
            rig.pilot.releaseInput();
            pod.discard();
            helper.succeed();
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void theFirstPlacementAgainstTheGrainExplainsWhyOncePerPlayer(GameTestHelper helper) {
        int x = 4880;
        ServerLevel level = room(helper, x);
        box(level, x + 3, x + 3, FLOOR, FLOOR + 3, Z, Z, LayerBlocks.GRAINED_CRUST);
        MockPlayer first = MockPlayers.join(helper, "grain-first");
        MockPlayer second = MockPlayers.join(helper, "grain-second");
        first.teleportTo(level, new Vec3(x + 0.5, FLOOR, Z + 2.5), 0f, 0f);
        second.teleportTo(level, new Vec3(x + 0.5, FLOOR, Z - 2.5), 0f, 0f);
        Arrival<Boolean> ready = ready(helper, level, x);
        int[] attemptedAt = {-1};
        TestTicks.each(helper, () -> {
            if (!ready.isPresent()) {
                return;
            }
            if (attemptedAt[0] < 0) {
                attemptedAt[0] = (int) helper.getTick();
                place(first, level, x + 2, FLOOR);
                place(first, level, x + 2, FLOOR + 1);
                place(second, level, x + 2, FLOOR + 2);
                return;
            }
            // A mock reads its messages on its own tick, so they lag by one.
            if (helper.getTick() < attemptedAt[0] + 3) {
                return;
            }
            for (MockPlayer player : List.of(first, second)) {
                long told = player.chatMessages().stream()
                        .filter(message -> isKey(message, "deepcharter.layer.grain_no_place"))
                        .count();
                if (told != 1) {
                    throw failure(helper, "each player is told why once, %s was told %d times", player.player(), told);
                }
                if (!Versioned.readable(player.player(), GrainedCrust.EXPLAINED)
                        .orElseThrow()
                        .told()) {
                    throw failure(helper, "%s should be recorded as told", player.player());
                }
            }
            helper.succeed();
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aPlacementWithUnreadableSavedDataDoesNotThrow(GameTestHelper helper) {
        int x = 4920;
        ServerLevel level = room(helper, x);
        box(level, x + 3, x + 3, FLOOR, FLOOR + 3, Z, Z, LayerBlocks.GRAINED_CRUST);
        MockPlayer player = MockPlayers.join(helper, "grain-unreadable");
        player.teleportTo(level, new Vec3(x + 0.5, FLOOR, Z + 2.5), 0f, 0f);
        UnreadableChecks.makeUnreadable(player.player(), GrainedCrust.EXPLAINED);
        Arrival<Boolean> ready = ready(helper, level, x);
        TestTicks.each(helper, () -> {
            if (!ready.isPresent()) {
                return;
            }
            UnreadableChecks.assertNoThrow(
                    helper,
                    "grained crust",
                    Map.of("placement against the grain", () -> place(player, level, x + 2, FLOOR + 1)));
            if (!(player.player().getAttached(GrainedCrust.EXPLAINED) instanceof Versioned.Unreadable<?>)) {
                throw failure(helper, "unreadable saved data must be left as it is");
            }
            helper.succeed();
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aWaterloggedBlockThatCrumblesLeavesNoWater(GameTestHelper helper) {
        int x = 4960;
        ServerLevel level = room(helper, x);
        box(level, x + 3, x + 3, FLOOR, FLOOR + 2, Z, Z, LayerBlocks.GRAINED_CRUST);
        BlockPos against = new BlockPos(x + 2, FLOOR + 1, Z);
        Arrival<Boolean> ready = ready(helper, level, x);
        long[] pulseAt = {-1};
        TestTicks.each(helper, () -> {
            if (!ready.isPresent()) {
                return;
            }
            long now = level.getGameTime();
            if (pulseAt[0] < 0) {
                if (PulseClock.ticksToPulse(level) < PULSE_MARGIN * 6) {
                    return;
                }
                level.setBlock(
                        against,
                        Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true),
                        Block.UPDATE_ALL);
                pulseAt[0] = now + PulseClock.ticksToPulse(level);
                return;
            }
            if (now < pulseAt[0] + PULSE_MARGIN) {
                return;
            }
            if (!level.getBlockState(against).isAir()
                    || !level.getFluidState(against).isEmpty()) {
                throw failure(
                        helper,
                        "a waterlogged block that crumbled should leave neither block nor water, found %s",
                        level.getBlockState(against));
            }
            helper.succeed();
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void theLayersOwnRockBesideTheGrainStaysAndPlacedLayerRockCrumbles(GameTestHelper helper) {
        int x = 5000;
        ServerLevel level = room(helper, x);
        BlockPos natural = new BlockPos(x + 2, FLOOR, Z);
        BlockPos placed = new BlockPos(x + 2, FLOOR + 1, Z);
        // The rock is there first, as the world generates it; the crust comes after.
        level.setBlock(natural, LayerBlocks.PACKED_SHALE.defaultBlockState(), Block.UPDATE_ALL);
        box(level, x + 3, x + 3, FLOOR, FLOOR + 2, Z, Z, LayerBlocks.GRAINED_CRUST);
        Arrival<Boolean> ready = ready(helper, level, x);
        long[] pulseAt = {-1};
        TestTicks.each(helper, () -> {
            if (!ready.isPresent()) {
                return;
            }
            long now = level.getGameTime();
            if (pulseAt[0] < 0) {
                if (PulseClock.ticksToPulse(level) < PULSE_MARGIN * 6) {
                    return;
                }
                level.setBlock(placed, LayerBlocks.PACKED_SHALE.defaultBlockState(), Block.UPDATE_ALL);
                pulseAt[0] = now + PulseClock.ticksToPulse(level);
                return;
            }
            if (now < pulseAt[0] + PULSE_MARGIN) {
                return;
            }
            if (!level.getBlockState(natural).is(LayerBlocks.PACKED_SHALE)) {
                throw failure(helper, "the layer's own rock beside the grain must stay");
            }
            if (!level.getBlockState(placed).isAir()) {
                throw failure(helper, "a placed block of the layer's rock must crumble like any other");
            }
            helper.succeed();
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aWarningCreakComesBeforeEachPulseToWhatWouldBeSqueezed(GameTestHelper helper) {
        int x = 5080;
        ServerLevel level = room(helper, x);
        pad(level, x);
        MockPlayer walker = MockPlayers.join(helper, "grain-warned");
        walker.player().setGameMode(GameType.SURVIVAL);
        walker.teleportTo(level, new Vec3(x + 0.5, FLOOR, Z + 0.5), 0f, 0f);
        MockPlayer bystander = MockPlayers.join(helper, "grain-bystander");
        bystander.player().setGameMode(GameType.SURVIVAL);
        // Far out of earshot of the walker's creak, in no crust at all.
        bystander.teleportTo(level, new Vec3(x + 200, FLOOR, Z + 0.5), 0f, 0f);
        Arrival<Boolean> ready = ready(helper, level, x);
        int period = TUNING.grainPulseTicks();
        int warnAt = period - WARN_TICKS;
        int[] cues = {0};
        int[] seen = {0};
        TestTicks.each(helper, () -> {
            if (!ready.isPresent()) {
                return;
            }
            int heard =
                    (int) walker.soundsHeard().stream().filter(WARNING::equals).count();
            if (heard > seen[0]) {
                // A mock reads its sounds on its own tick, so a cue is seen a tick or two late.
                long phase = level.getGameTime() % period;
                if (phase < warnAt || phase > warnAt + 3) {
                    throw failure(
                            helper,
                            "a warning came at %d of the pulse's %d ticks, not %d before it",
                            phase,
                            period,
                            WARN_TICKS);
                }
                cues[0] += heard - seen[0];
                seen[0] = heard;
            }
            if (cues[0] >= 2) {
                if (bystander.soundsHeard().contains(WARNING)) {
                    throw failure(helper, "a player who would not be squeezed was warned");
                }
                helper.succeed();
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void rightClickingAChestWithABlockDoesNotSpendTheExplanation(GameTestHelper helper) {
        int x = 5120;
        ServerLevel level = room(helper, x);
        box(level, x + 3, x + 3, FLOOR, FLOOR + 3, Z, Z, LayerBlocks.GRAINED_CRUST);
        BlockPos chest = new BlockPos(x + 2, FLOOR, Z);
        level.setBlock(chest, Blocks.CHEST.defaultBlockState(), Block.UPDATE_ALL);
        MockPlayer player = MockPlayers.join(helper, "grain-chest");
        player.teleportTo(level, new Vec3(x + 0.5, FLOOR, Z + 2.5), 0f, 0f);
        Arrival<Boolean> ready = ready(helper, level, x);
        int[] step = {0};
        int[] at = {0};
        TestTicks.each(helper, () -> {
            if (!ready.isPresent()) {
                return;
            }
            int tick = (int) helper.getTick();
            if (step[0] == 0) {
                ItemStack stack = new ItemStack(Items.COBBLESTONE);
                player.player().setItemInHand(InteractionHand.MAIN_HAND, stack);
                BlockHitResult hit =
                        new BlockHitResult(Vec3.atCenterOf(chest).add(0, 0.5, 0), Direction.UP, chest, false);
                player.player().gameMode.useItemOn(player.player(), level, stack, InteractionHand.MAIN_HAND, hit);
                step[0] = 1;
                at[0] = tick;
            } else if (step[0] == 1 && tick >= at[0] + 3) {
                if (told(player) != 0
                        || Versioned.readable(player.player(), GrainedCrust.EXPLAINED)
                                .orElseThrow()
                                .told()) {
                    throw failure(helper, "opening a chest is no placement: nothing should be said or spent");
                }
                player.player().closeContainer();
                place(player, level, x + 2, FLOOR + 1);
                step[0] = 2;
                at[0] = tick;
            } else if (step[0] == 2 && tick >= at[0] + 3) {
                if (told(player) != 1) {
                    throw failure(
                            helper, "a real placement should still explain once, it said it %d times", told(player));
                }
                helper.succeed();
            }
        });
    }

    private static long told(MockPlayer player) {
        return player.chatMessages().stream()
                .filter(message -> isKey(message, "deepcharter.layer.grain_no_place"))
                .count();
    }

    /** Uses a cobblestone on the west face of the grain at {@code (x + 1, y, Z)}, as a player placing a block against it does. */
    private static void place(MockPlayer player, ServerLevel level, int x, int y) {
        ItemStack stack = new ItemStack(Items.COBBLESTONE);
        player.player().setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockPos grain = new BlockPos(x + 1, y, Z);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(grain).add(-0.5, 0, 0), Direction.WEST, grain, false);
        player.player().gameMode.useItemOn(player.player(), level, stack, InteractionHand.MAIN_HAND, hit);
    }

    /** A carved room of layer 1 at {@code x}: a stone bed and air above it. */
    private static ServerLevel room(GameTestHelper helper, int x) {
        ServerLevel level = TestLayers.level(helper, 1);
        RoomCarver.carve(level, x - ROOM, x + ROOM, FLOOR - 8, FLOOR - 1, Z - ROOM, Z + ROOM, Blocks.STONE);
        RoomCarver.carve(level, x - ROOM, x + ROOM, FLOOR, FLOOR + 10, Z - ROOM, Z + ROOM, Blocks.AIR);
        return level;
    }

    /** A 7 x 7 pad of grained crust for the floor, where a pod or a player can stand. */
    private static void pad(ServerLevel level, int x) {
        box(level, x - 3, x + 3, FLOOR - 1, FLOOR - 1, Z - 3, Z + 3, LayerBlocks.GRAINED_CRUST);
    }

    /** True once the far chunk of the room ticks entities and its scheduled block ticks run. */
    private static Arrival<Boolean> ready(GameTestHelper helper, ServerLevel level, int x) {
        Arrival<Boolean> ready = new Arrival<>("the room", "the far chunk ticks");
        FarChunks.awaitEntityTicking(helper, level, new BlockPos(x, FLOOR, Z), () -> ready.set(true));
        return ready;
    }

    /** A pod with nobody in it, made once the far chunk ticks entities. */
    private static Arrival<PodEntity> lonePod(GameTestHelper helper, ServerLevel level, Vec3 at) {
        Arrival<PodEntity> arrival = new Arrival<>("pod", "the far chunk ticks");
        FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(at), () -> {
            PodEntity pod = TestEntities.create(PodRegistry.POD, level);
            pod.setPos(at);
            level.addFreshEntity(pod);
            arrival.set(pod);
        });
        return arrival;
    }

    /** Joins a pilot and, once the far chunk ticks, seats it in a pod; a non-null {@code input} is held from then on. */
    private static Rig rig(
            GameTestHelper helper, ServerLevel level, Vec3 at, float yaw, String name, @Nullable Input input) {
        MockPlayer pilot = MockPlayers.join(helper, name);
        pilot.teleportTo(level, at, yaw, 0f);
        Rig rig = new Rig(pilot);
        FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(at), () -> {
            PodEntity pod = TestEntities.create(PodRegistry.POD, level);
            pod.setPos(at);
            pod.setYRot(yaw);
            level.addFreshEntity(pod);
            if (!pilot.player().startRiding(pod)) {
                throw failure(helper, "the pilot could not mount the pod");
            }
            if (input != null) {
                pilot.setInput(input);
            }
            rig.pod.set(pod);
        });
        return rig;
    }

    /** True when no pulse is within {@link #PULSE_MARGIN} ticks, either side. */
    private static boolean clearOfAPulse(ServerLevel level) {
        int toPulse = PulseClock.ticksToPulse(level);
        return toPulse > PULSE_MARGIN && toPulse < TUNING.grainPulseTicks() - PULSE_MARGIN;
    }

    private static boolean heard(MockPlayer player, Identifier sound) {
        return player.soundsHeard().contains(sound);
    }

    private static boolean toldOf(MockPlayer player, String key) {
        return player.actionBarMessages().stream().anyMatch(message -> isKey(message, key));
    }

    private static boolean isKey(Component message, String key) {
        return message.getContents() instanceof TranslatableContents translatable
                && translatable.getKey().equals(key);
    }

    private static int count(ServerLevel level, int x1, int x2, int y1, int y2, int z1, int z2, Block block) {
        int n = 0;
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    if (level.getBlockState(new BlockPos(x, y, z)).is(block)) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    private static void box(ServerLevel level, int x1, int x2, int y1, int y2, int z1, int z2, Block block) {
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    // assertionException(String, Object...) leaves the placeholders unfilled in the report.
    @FormatMethod
    private static RuntimeException failure(
            GameTestHelper helper, @FormatString String format, @Nullable Object... args) {
        return helper.assertionException(Component.literal(String.format(format, args)));
    }
}
