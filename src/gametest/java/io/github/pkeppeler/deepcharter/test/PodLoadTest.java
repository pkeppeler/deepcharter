package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.JUMP;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.fuel.FuelTuning;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.LooseOreTuning;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodLining;
import io.github.pkeppeler.deepcharter.pod.PodLiningTuning;
import io.github.pkeppeler.deepcharter.pod.PodLoad;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.pod.PodTowing;
import io.github.pkeppeler.deepcharter.pod.TowTuning;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * The pod's load against its lift (#1449), and the ore a seated pilot carries in their pack, which weighs on the pod (#1446).
 */
public class PodLoadTest {
    private static final int FLOOR_Y = TestLayers.worldY(TestLayers.SURFACE, 1);
    private static final int FLOOR_RADIUS = 2;
    private static final float TOLERANCE = 1e-4f;
    /** What ore in a rider's pack weighs, as a multiple of its mass (ADR 0110). */
    private static final float LOOSE = LooseOreTuning.DEFAULT.weightFactor();
    /** Ticks of held jump in which an unloaded pod climbs well clear of its floor. */
    private static final int CLIMB_TICKS = 15;
    /** Blocks a load under the climb limit rises in {@link #CLIMB_TICKS}: an einsteinium, a platinium and an ironium in the pack (45 of 50) rise well over this. */
    private static final double CLIMBED = 0.1;

    /** A pod on a small stone floor with a mock pilot seated. */
    private record Rig(GameTestHelper helper, PodEntity pod, MockPlayer pilot) {
        static Rig build(GameTestHelper helper, String name) {
            return build(helper, name, PodRegistry.POD);
        }

        static Rig build(GameTestHelper helper, String name, EntityType<PodEntity> chassis) {
            floor(helper, Blocks.STONE);
            PodEntity pod = helper.spawn(chassis, new Vec3(FLOOR_RADIUS + 0.5, FLOOR_Y + 1, FLOOR_RADIUS + 0.5));
            return new Rig(helper, pod, seat(helper, pod, name));
        }

        void finish() {
            pilot.leave();
            pod.discard();
            floor(helper, Blocks.AIR);
        }
    }

    /** A mock player seated in {@code pod}'s next free seat. */
    private static MockPlayer seat(GameTestHelper helper, PodEntity pod, String name) {
        MockPlayer rider = MockPlayers.join(helper, name);
        rider.teleportTo(helper.getLevel(), pod.position(), 0f, 0f);
        if (!rider.player().startRiding(pod)) {
            rider.leave();
            throw failure(helper, "%s could not mount the pod", name);
        }
        return rider;
    }

    private static void floor(GameTestHelper helper, Block block) {
        for (int x = 0; x <= 2 * FLOOR_RADIUS; x++) {
            for (int z = 0; z <= 2 * FLOOR_RADIUS; z++) {
                helper.setBlock(new BlockPos(x, FLOOR_Y, z), block);
            }
        }
    }

    private static void near(GameTestHelper helper, String what, double expected, double actual) {
        expect(helper, Math.abs(expected - actual) <= TOLERANCE, "%s should be %s, got %s", what, expected, actual);
    }

    /**
     * #1446: ore in a seated pilot's pack rode with no weight, so a pack was free cargo. It weighs on the pod now, double its mass (ADR 0110) and on
     * top of the bay's (never counted twice), and a pack heavier than the engine keeps the pod on the ground.
     */
    @GameTest(maxTicks = 60)
    public void aSeatedPilotsPackOreWeighsOnThePodAndKeepsAHeavyPodDown(GameTestHelper helper) {
        Rig rig = Rig.build(helper, "pack-ore pilot");
        float ironium = OreType.IRONIUM.mass();
        float goldium = OreType.GOLDIUM.mass();
        rig.pod.cargo().tryAdd(rig.pod, OreRegistry.stack(OreType.GOLDIUM));
        rig.pod.cargo().tryAdd(rig.pod, OreRegistry.stack(OreType.GOLDIUM));
        rig.pilot.player().getInventory().add(new ItemStack(OreRegistry.item(OreType.IRONIUM), 3));
        near(helper, "the bay's mass with two goldium", 2 * goldium, rig.pod.cargoMass());
        near(
                helper,
                "the pod's extra mass with three ironium in the pack",
                3 * ironium * LOOSE,
                PodEvents.extraMass(rig.pod));
        // Enough pack ore to outweigh the engine on its own: the rotor gets no lift at all.
        int heavy = (int) Math.ceil(PodStats.of(rig.pod).enginePower() / ironium);
        rig.pilot.player().getInventory().add(new ItemStack(OreRegistry.item(OreType.IRONIUM), heavy));
        double startY = rig.pod.getY();
        rig.pilot.setInput(JUMP);
        helper.runAfterDelay(CLIMB_TICKS, () -> {
            try {
                expect(
                        helper,
                        rig.pod.getY() - startY < TOLERANCE && !rig.pod.flying(),
                        "a pilot with %s ironium in the pack outweighs the engine, yet the pod rose %s (flying %s)",
                        heavy + 3,
                        rig.pod.getY() - startY,
                        rig.pod.flying());
                helper.succeed();
            } finally {
                rig.finish();
            }
        });
    }

    /**
     * A passenger's pack weighs on the pod as the pilot's does: a Prospector's second seat carried ore for free. Once the passenger
     * gets out their pack weighs nothing on the pod.
     */
    @GameTest(maxTicks = 40)
    public void aPassengersPackOreWeighsOnThePodUntilTheyGetOut(GameTestHelper helper) {
        Rig rig = Rig.build(helper, "seat pilot", PodRegistry.PROSPECTOR);
        MockPlayer passenger = seat(helper, rig.pod, "seat passenger");
        try {
            passenger.player().getInventory().add(new ItemStack(OreRegistry.item(OreType.IRONIUM), 3));
            near(
                    helper,
                    "the extra mass of a passenger with three ironium",
                    3 * OreType.IRONIUM.mass() * LOOSE,
                    PodEvents.extraMass(rig.pod));
            passenger.player().stopRiding();
            near(helper, "the extra mass once the passenger is out", 0, PodEvents.extraMass(rig.pod));
            helper.succeed();
        } finally {
            passenger.leave();
            rig.finish();
        }
    }

    /**
     * A towed pod weighs with everything its own extra mass counts: the pack of whoever sits in it and its lining bricks, on top of the
     * cable's base mass and its bay.
     */
    @GameTest(maxTicks = 40)
    public void aTowedPodWeighsWithItsRidersPackAndItsBricks(GameTestHelper helper) {
        Rig rig = Rig.build(helper, "tow pilot");
        PodEntity towed = helper.spawn(PodRegistry.POD, new Vec3(FLOOR_RADIUS + 0.5, FLOOR_Y + 1, FLOOR_RADIUS + 2.4));
        MockPlayer rider = seat(helper, towed, "towed rider");
        int bricks = 4;
        try {
            towed.cargo().tryAdd(towed, OreRegistry.stack(OreType.GOLDIUM));
            rider.player().getInventory().add(new ItemStack(OreRegistry.item(OreType.IRONIUM), 2));
            PodLining.modify(towed, state -> state.fused(0, bricks));
            PodTowing.attach(rig.pod, towed);
            near(
                    helper,
                    "the tower's extra mass",
                    TowTuning.DEFAULT.baseMass()
                            + OreType.GOLDIUM.mass()
                            + 2 * OreType.IRONIUM.mass() * LOOSE
                            + bricks * PodLiningTuning.DEFAULT.brickMass(),
                    PodEvents.extraMass(rig.pod));
            helper.succeed();
        } finally {
            PodTowing.detach(towed);
            rider.leave();
            towed.discard();
            rig.finish();
        }
    }

    /**
     * A lining pod's rotor has no thrust, held still by the arm rather than by weight, so its climb limit is the engine's power: a load
     * between a stock Mole's climb limit and its engine does not light OVERLOADED while it lines.
     */
    @GameTest(maxTicks = 40)
    public void aLiningPodsClimbLimitIsTheEngine(GameTestHelper helper) {
        Rig rig = Rig.build(helper, "lining pilot");
        try {
            for (int i = 0; i < 3; i++) {
                rig.pod.cargo().tryAdd(rig.pod, OreRegistry.stack(OreType.EINSTEINIUM));
            }
            PodLining.modify(
                    rig.pod,
                    state -> new PodLining.State(state.spoil(), state.bricks(), state.used(), true, state.dry()));
            PodStats stats = PodStats.of(rig.pod);
            PodLoad load = PodLoad.of(rig.pod, stats);
            expect(
                    helper,
                    stats.thrustAcceleration() == 0f && load.load() > 50f && load.load() < stats.enginePower(),
                    "the rig should be a lining pod (thrust %s) with a load between 50 and the engine, got %s",
                    stats.thrustAcceleration(),
                    load);
            near(helper, "a lining pod's climb limit", stats.enginePower(), load.climbLimit());
            expect(helper, !load.cannotClimb(), "a lining pod with %s does not read OVERLOADED", load);
            helper.succeed();
        } finally {
            rig.finish();
        }
    }

    /**
     * The load is the bay, the pilot's whole pack (ore and fuel), a towed pod with its own cargo and the lining's bricks, against the engine's power;
     * and the client's copy of the extra mass is the server's after the pod's tick.
     */
    @GameTest(maxTicks = 40)
    public void theLoadIsTheBayThePackATowAndTheBricksAgainstTheEngine(GameTestHelper helper) {
        Rig rig = Rig.build(helper, "load pilot");
        PodEntity towed = helper.spawn(PodRegistry.POD, new Vec3(FLOOR_RADIUS + 0.5, FLOOR_Y + 1, FLOOR_RADIUS + 2.4));
        int bricks = 10;
        rig.pod.cargo().tryAdd(rig.pod, OreRegistry.stack(OreType.GOLDIUM));
        rig.pod.cargo().tryAdd(rig.pod, OreRegistry.stack(OreType.GOLDIUM));
        towed.cargo().tryAdd(towed, OreRegistry.stack(OreType.IRONIUM));
        rig.pilot.player().getInventory().add(new ItemStack(OreRegistry.item(OreType.IRONIUM), 1));
        rig.pilot.player().getInventory().add(new ItemStack(Items.COAL, 8));
        PodTowing.attach(rig.pod, towed);
        PodLining.modify(rig.pod, state -> state.fused(0, bricks));
        double expected = 2 * OreType.GOLDIUM.mass()
                + OreType.IRONIUM.mass() * LOOSE
                + 8 * FuelTuning.DEFAULT.weight().coal()
                + TowTuning.DEFAULT.baseMass()
                + OreType.IRONIUM.mass()
                + bricks * PodLiningTuning.DEFAULT.brickMass();
        PodLoad load = PodLoad.of(rig.pod);
        try {
            near(helper, "the load of bay, pack, tow and bricks", expected, load.load());
            near(helper, "the limit", PodStats.of(rig.pod).enginePower(), load.limit());
            near(helper, "the load past the bay", PodEvents.extraMass(rig.pod), load.load() - rig.pod.cargoMass());
        } catch (RuntimeException e) {
            PodTowing.detach(towed);
            towed.discard();
            rig.finish();
            throw e;
        }
        helper.runAfterDelay(2, () -> {
            try {
                near(
                        helper,
                        "the extra mass the client is sent",
                        PodEvents.extraMass(rig.pod),
                        Versioned.readable(rig.pod, PodLoad.EXTRA).orElseThrow().mass());
                helper.succeed();
            } finally {
                PodTowing.detach(towed);
                towed.discard();
                rig.finish();
            }
        });
    }

    /**
     * Lift and the load share one source: under the limit the held rotor thrusts, at the limit it does not and the load leaves no lift, and ore
     * taken from the bay (as a dump takes it) brings the load under the limit on the same tick.
     */
    @GameTest(maxTicks = 60)
    public void theRotorThrustsExactlyWhileTheLoadIsUnderTheLimit(GameTestHelper helper) {
        Rig rig = Rig.build(helper, "limit pilot");
        float limit = PodStats.of(rig.pod).enginePower();
        int einsteinium = 4;
        for (int i = 0; i < einsteinium; i++) {
            rig.pod.cargo().tryAdd(rig.pod, OreRegistry.stack(OreType.EINSTEINIUM));
        }
        int under = (int) ((limit - rig.pod.cargoMass()) / (OreType.IRONIUM.mass() * LOOSE)) - 1;
        rig.pilot.player().getInventory().add(new ItemStack(OreRegistry.item(OreType.IRONIUM), under));
        rig.pilot.setInput(JUMP);
        helper.runAfterDelay(2, () -> {
            PodLoad below = PodLoad.of(rig.pod);
            if (below.lift() == 0f || !rig.pod.flying()) {
                rig.finish();
                throw failure(helper, "a load of %s under the limit thrusts, flying %s", below, rig.pod.flying());
            }
            rig.pilot.player().getInventory().add(new ItemStack(OreRegistry.item(OreType.IRONIUM), 1));
            helper.runAfterDelay(2, () -> {
                PodLoad at = PodLoad.of(rig.pod);
                if (at.lift() != 0f || rig.pod.flying()) {
                    rig.finish();
                    throw failure(
                            helper,
                            "a load of %s at the limit has no lift and does not thrust, flying %s",
                            at,
                            rig.pod.flying());
                }
                rig.pod.cargo().take(rig.pod, OreType.EINSTEINIUM, 1);
                try {
                    expect(
                            helper,
                            PodLoad.of(rig.pod).lift() > 0f,
                            "ore taken from the bay leaves the load under the limit on the same tick, got %s",
                            PodLoad.of(rig.pod));
                    helper.succeed();
                } finally {
                    rig.finish();
                }
            });
        });
    }

    /**
     * The climb limit is where the held rotor stops climbing, well under the engine's power: a load under it climbs, a load over it stays on the
     * ground while the engine still has lift left. An einsteinium and a platinium in the bay and {@code packIronium} in the pack,
     * where ore weighs {@link #LOOSE} times its mass.
     */
    private static void climbs(GameTestHelper helper, int packIronium, boolean expected) {
        Rig rig = Rig.build(helper, "climb pilot " + packIronium);
        rig.pod.cargo().tryAdd(rig.pod, OreRegistry.stack(OreType.EINSTEINIUM));
        rig.pod.cargo().tryAdd(rig.pod, OreRegistry.stack(OreType.PLATINIUM));
        rig.pilot.player().getInventory().add(new ItemStack(OreRegistry.item(OreType.IRONIUM), packIronium));
        PodLoad load = PodLoad.of(rig.pod);
        double startY = rig.pod.getY();
        rig.pilot.setInput(JUMP);
        helper.runAfterDelay(CLIMB_TICKS, () -> {
            try {
                double rose = rig.pod.getY() - startY;
                expect(
                        helper,
                        load.cannotClimb() != expected && (rose > CLIMBED) == expected && load.lift() > 0f,
                        "%s should %s and keep some lift (climb limit %s): it rose %s",
                        load,
                        expected ? "climb" : "not climb",
                        load.climbLimit(),
                        rose);
                helper.succeed();
            } finally {
                rig.finish();
            }
        });
    }

    @GameTest(maxTicks = 40)
    public void aLoadJustUnderTheClimbLimitClimbs(GameTestHelper helper) {
        climbs(helper, 1, true);
    }

    @GameTest(maxTicks = 40)
    public void aLoadJustOverTheClimbLimitDoesNotClimbThoughTheEngineStillLiftsSome(GameTestHelper helper) {
        climbs(helper, 2, false);
    }

    /**
     * The climb limit is the real tuning's, against the gravity the pod's own movement pulls it with ({@code gravityPull}): half the engine for a stock Mole,
     * whose thrust at full lift is twice gravity, and the whole engine for a pod with no gravity, which climbs while it has any lift.
     */
    @GameTest
    public void theClimbLimitIsWhereThrustPaysGravity(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(1.5, 3, 1.5));
        try {
            PodStats stats = PodStats.of(pod);
            near(helper, "a stock Mole's climb limit", 50, PodLoad.of(pod).climbLimit());
            pod.setNoGravity(true);
            near(
                    helper,
                    "the climb limit of a pod with no gravity",
                    stats.enginePower(),
                    PodLoad.of(pod).climbLimit());
            helper.succeed();
        } finally {
            pod.discard();
        }
    }
}
