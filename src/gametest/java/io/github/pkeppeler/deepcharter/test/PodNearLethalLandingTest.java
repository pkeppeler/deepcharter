package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.layer.BreachEvents;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.ore.GasHazard;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.Findings;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for #893: a pod whose hard landing empties or nearly empties the hull stays on the floor it landed
 * on, in the campaign world where the layers are bands of one level. A probe pod of full hull drops first and says what
 * the landing costs; the subject then drops the same way with that much hull, one more or one less. Each case's two
 * shafts sit inside one forced chunk.
 */
public class PodNearLethalLandingTest {
    private static final String SAMPLE = "deepcharter:campaign_sample";
    private static final int DROP_BLOCKS = 14;
    private static final int SETTLE_TICKS = 80;
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 600;
    private static final float HULL_EPSILON = 0.01f;

    private static final Set<UUID> CROSSED = ConcurrentHashMap.newKeySet();

    static {
        BreachEvents.CROSSED.register((entity, from, to, fromLayer, toLayer) -> CROSSED.add(entity.getUUID()));
    }

    /** The hull is the landing's cost, to a hair short so that float rounding cannot leave a sliver of hull. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aCrewedPodWithExactlyTheHullTheLandingCostsIsWreckedOnTheFloor(GameTestHelper helper) {
        drop(helper, 22004, Hit.LANDING, -HULL_EPSILON / 2, true);
    }

    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aCrewedPodOneHullShortOfTheLandingIsWreckedOnTheFloor(GameTestHelper helper) {
        drop(helper, 22068, Hit.LANDING, -1f, true);
    }

    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aCrewedPodLeftWithOneHullByTheLandingStaysWorkingOnTheFloor(GameTestHelper helper) {
        drop(helper, 22132, Hit.LANDING, 1f, true);
    }

    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void anEmptyPodWithExactlyTheHullTheLandingCostsIsWreckedOnTheFloor(GameTestHelper helper) {
        drop(helper, 22196, Hit.LANDING, -HULL_EPSILON / 2, false);
    }

    /** Every other way into 0 hull ends in the same wreck, so a pod emptied in the air is no different once it lands. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aCrewedPodEmptiedByAGasBlastInTheAirIsWreckedOnTheFloor(GameTestHelper helper) {
        drop(helper, 22260, Hit.GAS, 0f, true);
    }

    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aCrewedPodWhoseHullIsSetToZeroInTheAirIsWreckedOnTheFloor(GameTestHelper helper) {
        drop(helper, 22324, Hit.ZERO, 0f, true);
    }

    /** Moving a grounded pod with {@code setPos} leaves {@code onGround()} true until its next tick. */
    @GameTest(maxTicks = 60)
    public void aServerSetPosLeavesAGroundedPodOnGroundForTheTickAfter(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(3.5, 2, 3.5));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (!pod.onGround() || pod.tickCount < 5) {
                        throw helper.assertionException(Component.literal("the pod has not settled"));
                    }
                })
                .thenExecute(() -> pod.setPos(pod.getX(), pod.getY() + 5, pod.getZ()))
                .thenExecute(() -> {
                    if (!pod.onGround()) {
                        throw helper.assertionException(
                                Component.literal("onGround() should stay true right after a setPos lift"));
                    }
                    pod.discard();
                })
                .thenSucceed();
    }

    /** What empties the hull: the landing itself, or something else while the pod is still in the air. */
    private enum Hit {
        LANDING,
        GAS,
        ZERO
    }

    /** The lost hull of the probe at its landing, then {@code spare} more hull on the subject, which takes the same fall. */
    private static void drop(GameTestHelper helper, int x, Hit hit, float spare, boolean crewed) {
        ServerLevel level = helper.getLevel();
        int z = 22004;
        int subjectX = x + 6;
        int floorTop = CampaignBands.LAYER_1.minY() + 60;
        double startY = floorTop + DROP_BLOCKS;
        MockPlayer rider = crewed ? MockPlayers.join(helper, "near-lethal-" + x) : null;
        PodEntity[] pods = {null, null};
        boolean[] landed = {false};
        long[] landedTick = {-1};
        float[] cost = {Float.NaN};
        Findings findings = new Findings();
        TestTicks.each(helper, () -> {
            if (pods[0] == null) {
                return;
            }
            if (pods[1] == null && pods[0].onGround() && pods[0].tickCount >= 5) {
                cost[0] = pods[0].maxHull() - pods[0].hull();
                pods[0].discard();
                PodEntity subject = TestEntities.create(PodRegistry.POD, level);
                subject.setPos(subjectX + 0.5, startY, z + 0.5);
                level.addFreshEntity(subject);
                subject.setHull(hit == Hit.GAS ? 1f : cost[0] + spare);
                pods[1] = subject;
                if (rider != null) {
                    rider.player().setGameMode(GameType.SURVIVAL);
                    rider.teleportTo(level, subject.position(), 0f, 0f);
                    if (!rider.player().startRiding(subject, true, false)) {
                        findings.note("the rider could not board the subject");
                    }
                }
                return;
            }
            PodEntity subject = pods[1];
            if (subject == null) {
                return;
            }
            if (subject.tickCount == 3 && hit == Hit.GAS) {
                BlockPos pocket = subject.blockPosition().offset(2, 0, 0);
                level.setBlock(pocket, HazardBlocks.GAS_POCKET.defaultBlockState(), 3);
                level.destroyBlock(pocket, false);
                GasHazard.vent(level, pocket);
            }
            if (subject.tickCount == 3 && hit == Hit.ZERO) {
                subject.setHull(0f);
            }
            if (subject.getY() < floorTop - 0.05) {
                findings.note("the pod fell below the floor, y=" + subject.getY() + " floor " + floorTop);
            }
            if (landed[0]
                    && (CROSSED.contains(subject.getUUID())
                            || (rider != null && CROSSED.contains(rider.player().getUUID())))) {
                findings.note("a breach crossing was raised for the pod or its rider after the landing");
            }
            if (!landed[0] && subject.onGround()) {
                landed[0] = true;
                landedTick[0] = helper.getTick();
                // The rider's teleport into the shaft from the surface is a crossing of its own, before the drop.
                CROSSED.remove(subject.getUUID());
                if (rider != null) {
                    CROSSED.remove(rider.player().getUUID());
                }
            }
        });
        FarChunks.awaitEntityTicking(helper, level, BlockPos.containing(x, startY, z), () -> {
            // A floor of stone under a shaft cut through a layer's generated rock, one column of it for each pod.
            for (int column : new int[] {x, subjectX}) {
                RoomCarver.carve(level, column - 3, column + 3, floorTop - 1, floorTop - 1, z - 3, z + 3, Blocks.STONE);
                RoomCarver.carve(level, column - 3, column + 3, floorTop, (int) startY + 6, z - 3, z + 3, Blocks.AIR);
            }
            pods[0] = TestEntities.create(PodRegistry.POD, level);
            pods[0].setPos(x + 0.5, startY, z + 0.5);
            level.addFreshEntity(pods[0]);
        });
        helper.succeedWhen(() -> {
            findings.ensure(helper);
            PodEntity subject = pods[1];
            if (subject == null || !landed[0] || helper.getTick() < landedTick[0] + SETTLE_TICKS) {
                throw helper.assertionException(Component.literal("the subject has not landed and settled"));
            }
            float hull = subject.hull();
            boolean lethal = hit != Hit.LANDING || spare <= 0f;
            if (lethal != Wrecks.isWreck(subject) || Math.abs(hull - (lethal ? 0f : spare)) > HULL_EPSILON) {
                throw helper.assertionException(Component.literal(String.format(
                        "a landing that costs %s on %s hull should leave a wreck exactly when the hull is gone, hull %s wreck %s",
                        cost[0], cost[0] + spare, hull, Wrecks.isWreck(subject))));
            }
            if (rider != null) {
                boolean aboard = rider.player().getVehicle() == subject;
                boolean dead = !rider.player().isAlive();
                if (lethal ? aboard || !dead : !aboard || dead) {
                    throw helper.assertionException(Component.literal(String.format(
                            "the rider of a %s pod should be %s, aboard %s dead %s",
                            lethal ? "wrecked" : "working",
                            lethal ? "dead and ejected" : "alive and aboard",
                            aboard,
                            dead)));
                }
                rider.leave();
            }
            subject.discard();
        });
    }
}
