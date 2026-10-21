package io.github.pkeppeler.deepcharter.test;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.ore.SlagBrick;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodHeatShield;
import io.github.pkeppeler.deepcharter.pod.PodHeatShieldTuning;
import io.github.pkeppeler.deepcharter.pod.PodLining;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Server GameTests for the heat shield (#482): a pod with the part burns slag brick, the rack first and then the seated pilot's pack, in place of the hull
 * while lava touches it. The bricks soak {@code hullPerBrick} of the lava's damage each; with none left the lava takes the hull at the full rate. Each test has
 * a stone floor and a lava cell of its own in the overworld test structure, and counts the pod's own ticks.
 */
public class PodHeatShieldTest {
    private static final int LAVA_TICKS = 60;
    private static final float EPSILON = 1e-3f;
    private static final int RACK = 24;
    private static final AtomicInteger OWNERS = new AtomicInteger();
    private static final Map<UUID, Integer> POD_TICKS = new ConcurrentHashMap<>();

    static {
        PodEvents.AFTER_TICK.register(pod -> POD_TICKS.computeIfPresent(pod.getUUID(), (id, ticks) -> ticks + 1));
    }

    private static float lavaPerTick() {
        return LayerTuning.DEFAULT.lavaHullPerSecond() / 20f;
    }

    /** Bricks a shield needs to soak {@code hull} of lava: a brick is burned as soon as a tick's damage finds none left. */
    private static int bricksFor(float hull) {
        return bricksFor(hull, 1);
    }

    /** Bricks a shield of {@code tier} needs to soak {@code hull} of lava. */
    private static int bricksFor(float hull, int tier) {
        return (int) Math.ceil(hull / PodHeatShieldTuning.DEFAULT.tier(tier).hullPerBrick() - EPSILON);
    }

    private record Scene(PodEntity pod, MockPlayer pilot) {
        int ticks() {
            return Objects.requireNonNull(POD_TICKS.get(pod.getUUID()), "POD_TICKS.get(pod.getUUID()) is absent");
        }
    }

    /**
     * A pod on a stone floor with lava in its cell, fitted with {@code shield} (a heat shield, or a bare pod) and a rack of {@code rack} bricks. The pilot is the
     * pod's owner, with {@code pack} bricks in the pack, when {@code seated}; a pilot of another crew sits it when {@code stranger}.
     */
    private static Scene scene(
            GameTestHelper helper, boolean shield, int rack, int pack, boolean seated, boolean stranger) {
        return scene(helper, shield ? 1 : 0, PodRegistry.POD, rack, pack, seated, stranger);
    }

    /** As above for a shield of {@code tier} (0 for a bare pod) on a pod of {@code type}. */
    private static Scene scene(
            GameTestHelper helper,
            int tier,
            EntityType<PodEntity> type,
            int rack,
            int pack,
            boolean seated,
            boolean stranger) {
        for (int x = 0; x <= 8; x++) {
            for (int z = 0; z <= 6; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.LAVA);
        PodEntity pod = helper.spawn(type, new Vec3(3.5, 2, 3.5));
        MockPlayer owner = MockPlayers.join(helper, "Shield owner " + OWNERS.incrementAndGet());
        if (tier > 0) {
            ScannerPods.fit(helper.getLevel().getServer(), owner.player(), pod, ComponentTrack.HEAT_SHIELD, tier);
        } else {
            ScannerPods.fit(helper.getLevel().getServer(), owner.player(), pod, ComponentTrack.SCANNER, 1);
        }
        PodLining.modify(pod, state -> new PodLining.State(0, rack, 0, false, false));
        MockPlayer pilot = stranger ? MockPlayers.join(helper, "Shield stranger " + OWNERS.incrementAndGet()) : owner;
        if (seated || stranger) {
            pilot.player().setGameMode(GameType.SURVIVAL);
            pilot.teleportTo(helper.getLevel(), pod.position(), 0f, 0f);
            if (!pilot.player().startRiding(pod, true, false)) {
                throw failure(helper, "the pilot could not board the pod");
            }
        }
        if (pack > 0) {
            pilot.player().getInventory().add(new ItemStack(SlagBrick.item(), pack));
        }
        POD_TICKS.put(pod.getUUID(), 0);
        return new Scene(pod, pilot);
    }

    private static int packBricks(Scene scene) {
        return scene.pilot().player().getInventory().countItem(SlagBrick.item());
    }

    @GameTest(maxTicks = LAVA_TICKS + 20)
    public void aShieldedPodInLavaBurnsRackBricksAndKeepsItsHull(GameTestHelper helper) {
        Scene scene = scene(helper, true, RACK, 0, false, false);
        helper.runAfterDelay(LAVA_TICKS, () -> {
            float soaked = scene.ticks() * lavaPerTick();
            int burned = RACK - PodLining.of(scene.pod()).bricks();
            if (scene.pod().hull() != scene.pod().maxHull() || burned != bricksFor(soaked)) {
                throw failure(
                        helper,
                        "after %d ticks (%s hull of lava) the hull should be full and %d bricks burned, found hull %s of %s and %d burned",
                        scene.ticks(),
                        soaked,
                        bricksFor(soaked),
                        scene.pod().hull(),
                        scene.pod().maxHull(),
                        burned);
            }
            scene.pod().discard();
            helper.succeed();
        });
    }

    @GameTest(maxTicks = LAVA_TICKS + 40)
    public void aSpentRackLetsTheLavaThroughAtTheFullRate(GameTestHelper helper) {
        int rack = 3;
        Scene scene = scene(helper, true, rack, 0, false, false);
        helper.runAfterDelay(LAVA_TICKS + 20, () -> {
            float soakable = rack * PodHeatShieldTuning.DEFAULT.tier(1).hullPerBrick();
            float expected = scene.pod().maxHull() - (scene.ticks() * lavaPerTick() - soakable);
            if (PodLining.of(scene.pod()).bricks() != 0 || Math.abs(scene.pod().hull() - expected) > EPSILON) {
                throw failure(
                        helper,
                        "with %d bricks (%s hull) spent the hull should be %s after %d ticks, found %s with %d bricks left",
                        rack,
                        soakable,
                        expected,
                        scene.ticks(),
                        scene.pod().hull(),
                        PodLining.of(scene.pod()).bricks());
            }
            scene.pod().discard();
            helper.succeed();
        });
    }

    @GameTest(maxTicks = LAVA_TICKS + 20)
    public void thePilotsPackBurnsOnceTheRackIsSpent(GameTestHelper helper) {
        int rack = 2;
        int pack = 20;
        Scene scene = scene(helper, true, rack, pack, true, false);
        helper.runAfterDelay(LAVA_TICKS, () -> {
            int burned = rack + pack - PodLining.of(scene.pod()).bricks() - packBricks(scene);
            int expected = bricksFor(scene.ticks() * lavaPerTick());
            if (PodLining.of(scene.pod()).bricks() != 0
                    || burned != expected
                    || scene.pod().hull() != scene.pod().maxHull()) {
                throw failure(
                        helper,
                        "the rack of %d goes first and the pack after: %d bricks should be burned after %d ticks, found %d with %d in the rack and %d in the pack; hull %s",
                        rack,
                        expected,
                        scene.ticks(),
                        burned,
                        PodLining.of(scene.pod()).bricks(),
                        packBricks(scene),
                        scene.pod().hull());
            }
            scene.pod().discard();
            helper.succeed();
        });
    }

    @GameTest(maxTicks = LAVA_TICKS + 20)
    public void aPackOfAnotherCrewIsNotBurned(GameTestHelper helper) {
        Scene scene = scene(helper, true, 0, 20, false, true);
        helper.runAfterDelay(LAVA_TICKS, () -> {
            float expected = scene.pod().maxHull() - scene.ticks() * lavaPerTick();
            if (packBricks(scene) != 20 || Math.abs(scene.pod().hull() - expected) > EPSILON) {
                throw failure(
                        helper,
                        "a pilot of another crew keeps %d bricks and the lava takes the hull to %s, found %d bricks and hull %s",
                        20,
                        expected,
                        packBricks(scene),
                        scene.pod().hull());
            }
            scene.pod().discard();
            helper.succeed();
        });
    }

    @GameTest(maxTicks = LAVA_TICKS + 20)
    public void aPodsOwnRackBurnsForAPilotOfAnotherCrew(GameTestHelper helper) {
        Scene scene = scene(helper, true, RACK, 0, false, true);
        helper.runAfterDelay(LAVA_TICKS, () -> {
            int burned = RACK - PodLining.of(scene.pod()).bricks();
            if (scene.pod().hull() != scene.pod().maxHull() || burned != bricksFor(scene.ticks() * lavaPerTick())) {
                throw failure(
                        helper,
                        "the rack is the pod's own and burns for a stranger too: hull should be full and %d bricks burned, found hull %s and %d burned",
                        bricksFor(scene.ticks() * lavaPerTick()),
                        scene.pod().hull(),
                        burned);
            }
            scene.pod().discard();
            helper.succeed();
        });
    }

    @GameTest(maxTicks = LAVA_TICKS + 20)
    public void aPodWithoutTheShieldKeepsItsBricksAndLosesHull(GameTestHelper helper) {
        Scene scene = scene(helper, false, RACK, 0, false, false);
        helper.runAfterDelay(LAVA_TICKS, () -> {
            float expected = scene.pod().maxHull() - scene.ticks() * lavaPerTick();
            if (PodLining.of(scene.pod()).bricks() != RACK
                    || Math.abs(scene.pod().hull() - expected) > EPSILON) {
                throw failure(
                        helper,
                        "a pod with no shield keeps its %d bricks and the hull falls to %s, found %d bricks and hull %s",
                        RACK,
                        expected,
                        PodLining.of(scene.pod()).bricks(),
                        scene.pod().hull());
            }
            scene.pod().discard();
            helper.succeed();
        });
    }

    @GameTest(maxTicks = LAVA_TICKS + 20)
    public void aShieldedPodOutOfLavaBurnsNothing(GameTestHelper helper) {
        Scene scene = scene(helper, true, RACK, 0, false, false);
        helper.setBlock(new BlockPos(3, 2, 3), Blocks.AIR);
        helper.runAfterDelay(LAVA_TICKS, () -> {
            if (PodLining.of(scene.pod()).bricks() != RACK
                    || scene.pod().hull() != scene.pod().maxHull()) {
                throw failure(
                        helper,
                        "a shielded pod clear of lava burns no brick and loses no hull, found %d bricks and hull %s",
                        PodLining.of(scene.pod()).bricks(),
                        scene.pod().hull());
            }
            scene.pod().discard();
            helper.succeed();
        });
    }

    @GameTest(maxTicks = LAVA_TICKS + 20)
    public void theRadiatorCutsTheBricksALavaSecondBurns(GameTestHelper helper) {
        Scene scene = scene(helper, true, RACK, 0, false, false);
        ScannerPods.fit(helper.getLevel().getServer(), scene.pilot().player(), scene.pod(), ComponentTrack.RADIATOR, 2);
        helper.runAfterDelay(LAVA_TICKS, () -> {
            float ratio = PodComponents.radiatorRatio(scene.pod());
            int expected = bricksFor(scene.ticks() * lavaPerTick() * ratio);
            int burned = RACK - PodLining.of(scene.pod()).bricks();
            if (!(ratio < 1f) || burned != expected) {
                throw failure(
                        helper,
                        "a radiator of ratio %s cuts the lava to %s hull a tick, so %d bricks burn in %d ticks, found %d",
                        ratio,
                        lavaPerTick() * ratio,
                        expected,
                        scene.ticks(),
                        burned);
            }
            scene.pod().discard();
            helper.succeed();
        });
    }

    @GameTest(maxTicks = LAVA_TICKS + 20)
    public void theHudCountIsTheRackAndTheUsablePackAndFallsAsBricksBurn(GameTestHelper helper) {
        Scene scene = scene(helper, true, 5, 4, true, false);
        helper.runAfterDelay(2, () -> {
            if (scene.pod().glazeBricks() > 9 || scene.pod().glazeBricks() < 8) {
                throw failure(
                        helper,
                        "the shield counts the rack of 5 and the pack of 4, less the brick it has burned: found %d",
                        scene.pod().glazeBricks());
            }
        });
        helper.runAfterDelay(LAVA_TICKS, () -> {
            if (scene.pod().glazeBricks() != PodLining.of(scene.pod()).bricks() + packBricks(scene)) {
                throw failure(
                        helper,
                        "the synced count is the bricks left: %d in the rack and %d in the pack, found %d",
                        PodLining.of(scene.pod()).bricks(),
                        packBricks(scene),
                        scene.pod().glazeBricks());
            }
            scene.pod().discard();
            helper.succeed();
        });
    }

    @GameTest(maxTicks = LAVA_TICKS + 20)
    public void aTierTwoShieldSoaksSixHullABrick(GameTestHelper helper) {
        Scene scene = scene(helper, 2, PodRegistry.POD, RACK, 0, false, false);
        helper.runAfterDelay(LAVA_TICKS, () -> {
            float soaked = scene.ticks() * lavaPerTick();
            int burned = RACK - PodLining.of(scene.pod()).bricks();
            if (PodHeatShieldTuning.DEFAULT.tier(2).hullPerBrick() != 6f
                    || scene.pod().hull() != scene.pod().maxHull()
                    || burned != bricksFor(soaked, 2)
                    || burned >= bricksFor(soaked, 1)) {
                throw failure(
                        helper,
                        "a tier 2 shield soaks 6 hull a brick: %s hull of lava takes %d bricks (tier 1 would take %d), found %d burned and hull %s",
                        soaked,
                        bricksFor(soaked, 2),
                        bricksFor(soaked, 1),
                        burned,
                        scene.pod().hull());
            }
            scene.pod().discard();
            helper.succeed();
        });
    }

    @GameTest(maxTicks = LAVA_TICKS + 20)
    public void aTierTwoShieldBurnsTheRackBeforeThePack(GameTestHelper helper) {
        int rack = 6;
        int pack = 30;
        Scene scene = scene(helper, 2, PodRegistry.POD, rack, pack, true, false);
        helper.runAfterDelay(LAVA_TICKS, () -> {
            int rackBurned = rack - PodLining.of(scene.pod()).bricks();
            if (packBricks(scene) != pack || rackBurned != bricksFor(scene.ticks() * lavaPerTick(), 2)) {
                throw failure(
                        helper,
                        "a tier 2 shield burns the rack first and leaves the pack of %d whole while the rack lasts: %d rack bricks should burn, found %d and %d in the pack",
                        pack,
                        bricksFor(scene.ticks() * lavaPerTick(), 2),
                        rackBurned,
                        packBricks(scene));
            }
            scene.pod().discard();
            helper.succeed();
        });
    }

    @GameTest(maxTicks = LAVA_TICKS + 20)
    public void aTierThreeShieldOnAProspectorSoaksEightHullABrickFromThePackFirst(GameTestHelper helper) {
        int rack = 6;
        int pack = 30;
        Scene scene = scene(helper, 3, PodRegistry.PROSPECTOR, rack, pack, true, false);
        helper.runAfterDelay(LAVA_TICKS, () -> {
            int packBurned = pack - packBricks(scene);
            int expected = bricksFor(scene.ticks() * lavaPerTick(), 3);
            if (PodHeatShieldTuning.DEFAULT.tier(3).hullPerBrick() != 8f
                    || PodHeatShield.tier(scene.pod()) != 3
                    || packBurned != expected
                    || PodLining.of(scene.pod()).bricks() != rack
                    || scene.pod().hull() != scene.pod().maxHull()) {
                throw failure(
                        helper,
                        "a tier 3 shield burns the pack first, 8 hull a brick: %d pack bricks should burn after %d ticks and the rack of %d stay whole, found %d from the pack and %d in the rack; hull %s",
                        expected,
                        scene.ticks(),
                        rack,
                        packBurned,
                        PodLining.of(scene.pod()).bricks(),
                        scene.pod().hull());
            }
            scene.pod().discard();
            helper.succeed();
        });
    }

    @GameTest(maxTicks = LAVA_TICKS + 20)
    public void aTierThreeShieldTakesTheRackOnceThePackIsSpent(GameTestHelper helper) {
        int rack = 20;
        int pack = 2;
        Scene scene = scene(helper, 3, PodRegistry.PROSPECTOR, rack, pack, true, false);
        helper.runAfterDelay(LAVA_TICKS, () -> {
            int burned = rack + pack - PodLining.of(scene.pod()).bricks() - packBricks(scene);
            if (packBricks(scene) != 0
                    || burned != bricksFor(scene.ticks() * lavaPerTick(), 3)
                    || scene.pod().hull() != scene.pod().maxHull()) {
                throw failure(
                        helper,
                        "with the pack of %d spent the rack burns: %d bricks should burn after %d ticks, found %d with %d in the pack and %d in the rack; hull %s",
                        pack,
                        bricksFor(scene.ticks() * lavaPerTick(), 3),
                        scene.ticks(),
                        burned,
                        packBricks(scene),
                        PodLining.of(scene.pod()).bricks(),
                        scene.pod().hull());
            }
            scene.pod().discard();
            helper.succeed();
        });
    }

    @GameTest(maxTicks = LAVA_TICKS + 20)
    public void aTierThreeShieldBurnsTheRackWhenThePackIsAnotherCrews(GameTestHelper helper) {
        Scene scene = scene(helper, 3, PodRegistry.PROSPECTOR, RACK, 20, false, true);
        helper.runAfterDelay(LAVA_TICKS, () -> {
            int burned = RACK - PodLining.of(scene.pod()).bricks();
            if (packBricks(scene) != 20
                    || burned != bricksFor(scene.ticks() * lavaPerTick(), 3)
                    || scene.pod().hull() != scene.pod().maxHull()) {
                throw failure(
                        helper,
                        "a stranger's pack is not the shield's to burn: the rack should burn %d bricks and the pack keep 20, found %d burned and %d in the pack; hull %s",
                        bricksFor(scene.ticks() * lavaPerTick(), 3),
                        burned,
                        packBricks(scene),
                        scene.pod().hull());
            }
            scene.pod().discard();
            helper.succeed();
        });
    }

    @GameTest(maxTicks = LAVA_TICKS + 20)
    public void aMoleFitsATierThreeShieldButItWorksAsTierTwo(GameTestHelper helper) {
        Scene scene = scene(helper, 3, PodRegistry.POD, 6, 30, true, false);
        helper.runAfterDelay(LAVA_TICKS, () -> {
            int burned = 6 - PodLining.of(scene.pod()).bricks();
            if (PodHeatShield.tier(scene.pod()) != 2
                    || packBricks(scene) != 30
                    || burned != bricksFor(scene.ticks() * lavaPerTick(), 2)) {
                throw failure(
                        helper,
                        "a Mole's cap makes a tier 3 shield work as tier 2 (6 hull a brick, rack first): tier %d, %d burned from the rack (expected %d), %d in the pack",
                        PodHeatShield.tier(scene.pod()),
                        burned,
                        bricksFor(scene.ticks() * lavaPerTick(), 2),
                        packBricks(scene));
            }
            scene.pod().discard();
            helper.succeed();
        });
    }

    @GameTest
    public void aBrickMustAbsorbHull(GameTestHelper helper) {
        try {
            new PodHeatShieldTuning.Tier(0f, false);
        } catch (IllegalArgumentException expected) {
            helper.succeed();
            return;
        }
        throw failure(helper, "a brick that absorbs no hull should be refused");
    }

    @GameTest
    public void theTuningHasOneTierForEachPartTierAndEachSoaksMore(GameTestHelper helper) {
        List<PodHeatShieldTuning.Tier> tiers = PodHeatShieldTuning.DEFAULT.tiers();
        if (tiers.size() != ComponentTrack.HEAT_SHIELD.maxTier()) {
            throw failure(
                    helper,
                    "the shield has %d part tiers, the tuning %d",
                    ComponentTrack.HEAT_SHIELD.maxTier(),
                    tiers.size());
        }
        for (int tier = 2; tier <= tiers.size(); tier++) {
            if (!(PodHeatShieldTuning.DEFAULT.tier(tier).hullPerBrick()
                    > PodHeatShieldTuning.DEFAULT.tier(tier - 1).hullPerBrick())) {
                throw failure(helper, "tier %d should soak more hull a brick than tier %d", tier, tier - 1);
            }
        }
        try {
            new PodHeatShieldTuning(List.of());
        } catch (IllegalArgumentException expected) {
            helper.succeed();
            return;
        }
        throw failure(helper, "a tuning with no tier should be refused");
    }

    // assertionException(String, Object...) leaves the placeholders unfilled in the report.
    @FormatMethod
    private static RuntimeException failure(
            GameTestHelper helper, @FormatString String format, @Nullable Object... args) {
        return helper.assertionException(Component.literal(String.format(format, args)));
    }
}
