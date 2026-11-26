package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.JUMP;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.fuel.FuelTuning;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
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
    /** Ticks of held jump in which an unloaded pod climbs well clear of its floor. */
    private static final int CLIMB_TICKS = 15;

    /** A pod on a small stone floor with a mock pilot seated. */
    private record Rig(GameTestHelper helper, PodEntity pod, MockPlayer pilot) {
        static Rig build(GameTestHelper helper, String name) {
            floor(helper, Blocks.STONE);
            PodEntity pod =
                    helper.spawn(PodRegistry.POD, new Vec3(FLOOR_RADIUS + 0.5, FLOOR_Y + 1, FLOOR_RADIUS + 0.5));
            MockPlayer pilot = MockPlayers.join(helper, name);
            pilot.teleportTo(helper.getLevel(), pod.position(), 0f, 0f);
            if (!pilot.player().startRiding(pod)) {
                throw failure(helper, "the pilot could not mount the pod");
            }
            return new Rig(helper, pod, pilot);
        }

        void finish() {
            pilot.leave();
            pod.discard();
            floor(helper, Blocks.AIR);
        }
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
     * #1446: ore in a seated pilot's pack rode with no weight, so a pack was free cargo. It weighs on the pod now, as its own mass and on
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
        near(helper, "the pod's extra mass with three ironium in the pack", 3 * ironium, PodEvents.extraMass(rig.pod));
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
                + OreType.IRONIUM.mass()
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
     * Lift and the load share one source: under the limit the held rotor thrusts, at the limit it does not and the load says overloaded, and ore
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
        int under = (int) ((limit - rig.pod.cargoMass()) / OreType.IRONIUM.mass()) - 1;
        rig.pilot.player().getInventory().add(new ItemStack(OreRegistry.item(OreType.IRONIUM), under));
        rig.pilot.setInput(JUMP);
        helper.runAfterDelay(2, () -> {
            PodLoad below = PodLoad.of(rig.pod);
            if (below.overloaded() || !rig.pod.flying()) {
                rig.finish();
                throw failure(helper, "a load of %s under the limit thrusts, flying %s", below, rig.pod.flying());
            }
            rig.pilot.player().getInventory().add(new ItemStack(OreRegistry.item(OreType.IRONIUM), 1));
            helper.runAfterDelay(2, () -> {
                PodLoad at = PodLoad.of(rig.pod);
                if (!at.overloaded() || rig.pod.flying() || at.lift() != 0f) {
                    rig.finish();
                    throw failure(
                            helper,
                            "a load of %s at the limit is overloaded and does not thrust, flying %s",
                            at,
                            rig.pod.flying());
                }
                rig.pod.cargo().take(rig.pod, OreType.EINSTEINIUM, 1);
                try {
                    expect(
                            helper,
                            !PodLoad.of(rig.pod).overloaded(),
                            "ore taken from the bay leaves the load under the limit on the same tick, got %s",
                            PodLoad.of(rig.pod));
                    helper.succeed();
                } finally {
                    rig.finish();
                }
            });
        });
    }
}
