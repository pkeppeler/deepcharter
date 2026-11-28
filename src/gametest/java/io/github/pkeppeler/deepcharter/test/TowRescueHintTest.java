package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodTowing;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for #1600: a pod stranded deep, or left empty deep, tells its charter's online members, once, that a
 * crewmate can tow it home with a tow cable; a deep wreck says it too. The line is for a pod in a layer: on the surface
 * a stranding is a walk, and a pod with fuel and a pilot needs no rescue.
 */
public class TowRescueHintTest {
    private static final String STRANDED = "deepcharter.pod.rescue.stranded";
    private static final String LEFT = "deepcharter.pod.rescue.left";
    private static final String WRECK = "deepcharter.wreck.tow_hint";
    private static final int LAYER = 2;
    private static final int SURFACE_FLOOR_Y = TestLayers.worldY(TestLayers.SURFACE, 1);
    /** Ticks a test lets a pod tick after the event, so a line that a later tick would repeat shows up. */
    private static final int SETTLE_TICKS = 30;

    /** A charter of a director and a crewmate with an owned pod, and a stranger in a charter of their own. */
    private record Crew(MockPlayer director, MockPlayer crewmate, MockPlayer stranger, Charter charter) {}

    private static Crew crew(GameTestHelper helper, String tag) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer director = MockPlayers.join(helper, tag + "-director");
        MockPlayer crewmate = MockPlayers.join(helper, tag + "-crew");
        MockPlayer stranger = MockPlayers.join(helper, tag + "-stranger");
        found(helper, stranger);
        Charter charter = found(helper, director);
        if (Charters.apply(server, crewmate.player().getUUID(), charter.id()).isPresent()
                || Charters.approve(
                                server,
                                director.player().getUUID(),
                                crewmate.player().getUUID())
                        .isPresent()) {
            throw failure(helper, "could not add the crewmate to the charter");
        }
        return new Crew(director, crewmate, stranger, charter);
    }

    private static Charter found(GameTestHelper helper, MockPlayer founder) {
        MinecraftServer server = helper.getLevel().getServer();
        Charters.found(
                        server,
                        founder.player().getUUID(),
                        "Rescue " + UUID.randomUUID().toString().substring(0, 8))
                .ifPresent(refusal -> {
                    throw failure(helper, "could not found a charter: %s", refusal);
                });
        return Charters.charterOfOrThrow(server, founder.player().getUUID()).orElseThrow();
    }

    private static long told(MockPlayer player, String key) {
        return player.chatMessages().stream()
                .filter(message -> message.getContents() instanceof TranslatableContents contents
                        && contents.getKey().equals(key))
                .count();
    }

    /** What a test checks once the pod has ticked {@link #SETTLE_TICKS} after its set-up. */
    private interface Check {
        void run(PodEntity pod);
    }

    /** What a test does on each tick after its set-up, given the pod and the ticks since the set-up. */
    private interface Script {
        void tick(PodEntity pod, int sinceSetUp);
    }

    /**
     * Puts an owned pod in the layer at {@code x}, seats the director in it when {@code seated}, runs {@code act} on
     * the first tick the chunk entity-ticks, and {@code check} {@link #SETTLE_TICKS} ticks later.
     */
    private static void inLayer(GameTestHelper helper, Crew crew, double x, boolean seated, Check act, Check check) {
        inLayer(helper, crew, x, seated, act, (pod, sinceSetUp) -> {}, check);
    }

    private static void inLayer(
            GameTestHelper helper, Crew crew, double x, boolean seated, Check act, Script script, Check check) {
        double z = 120000.5;
        ServerLevel layer = TestLayers.level(helper.getLevel().getServer(), LAYER);
        crew.director().teleportTo(layer, TestLayers.vec(LAYER, x, 8, z), 0, 0);
        PodEntity[] pod = {null};
        long[] actedAt = {-1};
        BlockPos at = BlockPos.containing(x, TestLayers.worldY(LAYER, 8), z);
        FarChunks.awaitEntityTicking(helper, layer, at, () -> {
            pod[0] = TestEntities.create(PodRegistry.POD, layer);
            pod[0].setPos(x, TestLayers.worldY(LAYER, 8), z);
            layer.addFreshEntity(pod[0]);
            PodComponents.register(pod[0], crew.charter().id());
            if (seated) {
                crew.director().player().startRiding(pod[0], true, false);
            }
            act.run(pod[0]);
            actedAt[0] = helper.getTick();
        });
        helper.succeedWhen(() -> {
            if (pod[0] == null || actedAt[0] < 0 || helper.getTick() < actedAt[0] + SETTLE_TICKS) {
                if (pod[0] != null && actedAt[0] >= 0) {
                    script.tick(pod[0], (int) (helper.getTick() - actedAt[0]));
                }
                throw failure(helper, "waiting for the pod to tick in the chunk at %s", at);
            }
            check.run(pod[0]);
        });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 80)
    public void aPodStrandedDeepTellsItsCharterOnceAndNobodyElse(GameTestHelper helper) {
        Crew crew = crew(helper, "stranded");
        // The pilot is seated with an empty tank: the pod's own tick strands it, as a real run dry does.
        inLayer(helper, crew, 120000.5, true, pod -> pod.setFuel(0f), pod -> {
            expect(helper, pod.stranded(), "the pod should have run dry and be stranded");
            expect(
                    helper,
                    told(crew.director(), STRANDED) == 1,
                    "the pilot is told once, was %d",
                    told(crew.director(), STRANDED));
            expect(
                    helper,
                    told(crew.crewmate(), STRANDED) == 1,
                    "the crewmate is told once, was %d",
                    told(crew.crewmate(), STRANDED));
            expect(helper, told(crew.stranger(), STRANDED) == 0, "another charter is told nothing");
        });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 80)
    public void aDeepPodWithFuelAndAPilotTellsNothing(GameTestHelper helper) {
        Crew crew = crew(helper, "fueled");
        inLayer(helper, crew, 120040.5, true, pod -> pod.setFuel(50f), pod -> {
            expect(helper, !pod.stranded(), "the pod has fuel and is not stranded");
            for (String key : List.of(STRANDED, LEFT)) {
                expect(helper, told(crew.director(), key) == 0, "a pod under way is told of nobody, got %s", key);
                expect(helper, told(crew.crewmate(), key) == 0, "a pod under way tells the crew nothing, got %s", key);
            }
        });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 80)
    public void aPilotWhoLeavesADeepPodTellsTheCharterOncePerStay(GameTestHelper helper) {
        Crew crew = crew(helper, "left");
        inLayer(
                helper,
                crew,
                120080.5,
                true,
                pod -> {
                    pod.setFuel(50f);
                },
                (pod, sinceSetUp) -> {
                    // Out after the pod has ticked with the pilot aboard, back in, and out again.
                    switch (sinceSetUp) {
                        case 3, 13 -> crew.director().player().stopRiding();
                        case 8 -> crew.director().player().startRiding(pod, true, false);
                        default -> {}
                    }
                },
                pod -> {
                    expect(
                            helper,
                            told(crew.director(), LEFT) == 1,
                            "the pilot is told once per stay, was %d",
                            told(crew.director(), LEFT));
                    expect(
                            helper,
                            told(crew.crewmate(), LEFT) == 1,
                            "the crewmate is told once per stay, was %d",
                            told(crew.crewmate(), LEFT));
                    expect(helper, told(crew.stranger(), LEFT) == 0, "another charter is told nothing");
                    expect(helper, told(crew.crewmate(), STRANDED) == 0, "a pod with fuel is not stranded");
                });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 80)
    public void aStrandedDeepPodThatIsAlreadyOnACableIsToldOfNothing(GameTestHelper helper) {
        Crew crew = crew(helper, "towed");
        inLayer(
                helper,
                crew,
                120120.5,
                false,
                pod -> {
                    PodEntity tower = TestEntities.create(PodRegistry.POD, (ServerLevel) pod.level());
                    tower.setPos(pod.getX() + 3, pod.getY(), pod.getZ());
                    pod.level().addFreshEntity(tower);
                    PodTowing.attach(tower, pod);
                    pod.setStranded(true);
                },
                pod -> {
                    expect(helper, PodTowing.isTowed(pod), "the pod should still be on the cable");
                    expect(helper, told(crew.director(), STRANDED) == 0, "a pod on a cable needs no hint, got one");
                    expect(helper, told(crew.crewmate(), STRANDED) == 0, "a pod on a cable needs no hint, got one");
                });
    }

    @GameTest(maxTicks = 100)
    public void aPodStrandedOrLeftOnTheSurfaceTellsNothing(GameTestHelper helper) {
        Crew crew = crew(helper, "surface");
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                helper.setBlock(new BlockPos(x, SURFACE_FLOOR_Y, z), Blocks.STONE);
            }
        }
        PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(3.5, SURFACE_FLOOR_Y + 1, 3.5));
        PodComponents.register(pod, crew.charter().id());
        crew.director().player().startRiding(pod, true, false);
        pod.setFuel(0f);
        helper.runAfterDelay(5, () -> crew.director().player().stopRiding());
        helper.runAfterDelay(SETTLE_TICKS, () -> {
            expect(helper, pod.stranded(), "the pod should be stranded on the surface");
            for (String key : List.of(STRANDED, LEFT)) {
                expect(helper, told(crew.director(), key) == 0, "a surface stranding is a walk, got %s", key);
                expect(helper, told(crew.crewmate(), key) == 0, "a surface stranding is a walk, got %s", key);
            }
            helper.succeed();
        });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 80)
    public void aWreckDeepTellsItsCharterToTowIt(GameTestHelper helper) {
        Crew crew = crew(helper, "wreck");
        inLayer(helper, crew, 120160.5, true, pod -> pod.damageHull(pod.maxHull()), pod -> {
            expect(
                    helper,
                    told(crew.director(), WRECK) == 1,
                    "the pilot's charter is told once, was %d",
                    told(crew.director(), WRECK));
            expect(
                    helper,
                    told(crew.crewmate(), WRECK) == 1,
                    "the crewmate is told once, was %d",
                    told(crew.crewmate(), WRECK));
            expect(helper, told(crew.stranger(), WRECK) == 0, "another charter is told nothing");
        });
    }

    @GameTest(maxTicks = 100)
    public void aWreckOnTheSurfaceSaysNothingOfTowing(GameTestHelper helper) {
        Crew crew = crew(helper, "surface-wreck");
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                helper.setBlock(new BlockPos(x, SURFACE_FLOOR_Y, z), Blocks.STONE);
            }
        }
        PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(3.5, SURFACE_FLOOR_Y + 1, 3.5));
        PodComponents.register(pod, crew.charter().id());
        pod.damageHull(pod.maxHull());
        helper.runAfterDelay(5, () -> {
            expect(helper, told(crew.director(), WRECK) == 0, "a wreck on the surface is walked to, got a tow hint");
            helper.succeed();
        });
    }
}
