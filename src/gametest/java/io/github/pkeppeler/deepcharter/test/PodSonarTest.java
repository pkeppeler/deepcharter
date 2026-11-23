package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import com.mojang.serialization.Codec;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodSonar;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.pod.SonarEcho;
import io.github.pkeppeler.deepcharter.pod.SonarEvents;
import io.github.pkeppeler.deepcharter.pod.SonarKind;
import io.github.pkeppeler.deepcharter.pod.SonarMark;
import io.github.pkeppeler.deepcharter.pod.SonarPing;
import io.github.pkeppeler.deepcharter.pod.SonarTuning;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.OreBlocks;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for the navigator's sonar (#988), in layer 1. A scene is a cube of stone 25 blocks each way round a Prospector, so the natural
 * ore and caves of the layer never join an echo count, with an open room of 2 x 1 x 2 sonar cells at its middle: the pod stands at the corner
 * the four cells share. The cells are the world's 4-block grid, which {@link #FLOOR}, {@link #Z} and every scene's X sit on, so a test names
 * the cell it expects by its corner and middle. Each test has its own X, so the blocks it changes never touch another's.
 *
 * <p>The pacing numbers of the design log ({@code docs/design/mechanics.md}, "The navigator's sonar") are asserted here as player units: the
 * power a ping takes, how far it sees, how far it is heard, how long its echoes show and how many pings a stock tank pays for.
 */
public class PodSonarTest {
    private static final AtomicInteger PLAYERS = new AtomicInteger();
    private static final SonarTuning TUNING = SonarTuning.DEFAULT;
    private static final int Z = 7000;
    private static final int FLOOR = TestLayers.worldY(1, 40);
    /** Blocks the stone reaches from the pod, past the range, so that a cell at the edge of it is still stone. */
    private static final int REACH = 26;

    private static final int SETTLE_TICKS = 4;
    private static final int CELL = 4;
    /** Pods the test has marked as without power; a Fabric listener cannot be unregistered, so it acts only on these. */
    private static final Set<UUID> UNPOWERED = ConcurrentHashMap.newKeySet();

    static {
        PodEvents.IS_POWERED.register(pod -> !UNPOWERED.contains(pod.getUUID()));
    }

    private record Scene(PodEntity pod, MockPlayer pilot, MockPlayer navigator) {}

    private static void block(ServerLevel level, int x, int y, int z, net.minecraft.world.level.block.Block block) {
        level.setBlock(new BlockPos(x, y, z), block.defaultBlockState(), 3);
    }

    /** Stone all round, and the open room of the pod. */
    private static void site(ServerLevel level, int x) {
        RoomCarver.carve(level, x - REACH, x + REACH, FLOOR - REACH, FLOOR + REACH, Z - REACH, Z + REACH, Blocks.STONE);
        room(level, x - CELL, FLOOR, Z - CELL, 2, 2, 4);
    }

    /** Opens air over {@code cellsX} x {@code cellsZ} cells with their low corner at ({@code x}, {@code y}, {@code z}), {@code high} blocks tall. */
    private static void room(ServerLevel level, int x, int y, int z, int cellsX, int cellsZ, int high) {
        RoomCarver.carve(
                level,
                new BlockPos(x, y, z),
                new BlockPos(x + cellsX * CELL - 1, y + high - 1, z + cellsZ * CELL - 1),
                Blocks.AIR.defaultBlockState());
    }

    /** The middle block of the sonar cell holding ({@code x}, {@code y}, {@code z}): what an echo of that cell reports. */
    private static BlockPos middle(int x, int y, int z) {
        return new BlockPos(
                Math.floorDiv(x, CELL) * CELL + CELL / 2,
                Math.floorDiv(y, CELL) * CELL + CELL / 2,
                Math.floorDiv(z, CELL) * CELL + CELL / 2);
    }

    /**
     * Builds the site, lets {@code build} change it, puts a Prospector with a pilot and a navigator at the corner of the room, and runs
     * {@code check} on the tick after they settle. The test succeeds when {@code check} returns.
     */
    private static void scene(GameTestHelper helper, int x, Consumer<ServerLevel> build, Consumer<Scene> check) {
        run(helper, x, build, scene -> {
            check.accept(scene);
            return true;
        });
    }

    /** As {@link #scene}, running {@code perTick} on every tick after the settle until it returns true. */
    private static void run(GameTestHelper helper, int x, Consumer<ServerLevel> build, Predicate<Scene> perTick) {
        ServerLevel level = TestLayers.level(helper, 1);
        for (int cx = (x - REACH) >> 4; cx <= (x + REACH) >> 4; cx++) {
            for (int cz = (Z - REACH) >> 4; cz <= (Z + REACH) >> 4; cz++) {
                FarChunks.force(helper, level, cx, cz);
            }
        }
        site(level, x);
        build.accept(level);
        MockPlayer pilot = MockPlayers.join(helper, "Sonar pilot " + PLAYERS.incrementAndGet());
        MockPlayer navigator = MockPlayers.join(helper, "Sonar navigator " + PLAYERS.incrementAndGet());
        for (MockPlayer rider : List.of(pilot, navigator)) {
            rider.player().setGameMode(GameType.SURVIVAL);
            rider.teleportTo(level, new Vec3(x, FLOOR, Z), 0, 0);
        }
        Scene[] scene = {null};
        int[] ticks = {0};
        FarChunks.awaitEntityTicking(helper, level, new BlockPos(x, FLOOR, Z), () -> {
            PodEntity pod = TestEntities.create(PodRegistry.PROSPECTOR, level);
            pod.setPos(x, FLOOR, Z);
            level.addFreshEntity(pod);
            if (!pilot.player().startRiding(pod, true, false)
                    || !navigator.player().startRiding(pod, true, false)) {
                throw failure(helper, "the mocks could not board the Prospector");
            }
            scene[0] = new Scene(pod, pilot, navigator);
        });
        TestTicks.each(helper, () -> {
            if (scene[0] != null && ++ticks[0] >= SETTLE_TICKS && perTick.test(scene[0])) {
                scene[0].pod().discard();
                helper.succeed();
            }
        });
    }

    private static List<SonarEcho> echoes(PodEntity pod) {
        return PodSonar.reading(pod).orElseThrow().echoes();
    }

    private static void clearWait(PodEntity pod) {
        pod.setAttached(PodSonar.STATE, Versioned.of(PodSonar.State.EMPTY));
    }

    private static boolean hasEcho(PodEntity pod, SonarKind kind, BlockPos pos) {
        return echoes(pod).contains(new SonarEcho(kind, pos));
    }

    private static void expectEcho(GameTestHelper helper, PodEntity pod, SonarKind kind, BlockPos pos) {
        expect(
                helper,
                hasEcho(pod, kind, pos),
                "the ping should echo %s at %s, it returned %s",
                kind,
                pos,
                echoes(pod));
    }

    // ---- who may ping ----

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void theNavigatorPingsAndThePilotCannot(GameTestHelper helper) {
        int x = 7000;
        boolean[] asked = {false};
        float[] before = {0};
        run(helper, x, level -> {}, scene -> {
            if (!asked[0]) {
                asked[0] = true;
                before[0] = scene.pod().fuel();
                expect(
                        helper,
                        PodSonar.pingBy(scene.pilot().player()) == PodSonar.Result.NOT_NAVIGATOR,
                        "the pilot does not work the sonar");
                expect(
                        helper,
                        scene.pod().fuel() == before[0] && echoes(scene.pod()).isEmpty(),
                        "a refused ping costs nothing and returns nothing");
                expect(
                        helper,
                        PodSonar.pingBy(scene.navigator().player()) == PodSonar.Result.PINGED,
                        "the navigator pings");
                expect(helper, scene.pod().fuel() < before[0], "a ping draws power");
                return false;
            }
            // The overlay line travels as a packet, which a mock records a tick later.
            expect(
                    helper,
                    scene.pilot().actionBarMessages().stream().anyMatch(PodSonarTest::isNotNavigator),
                    "the pilot is told why, got %s",
                    scene.pilot().actionBarMessages());
            expect(
                    helper,
                    scene.navigator().actionBarMessages().stream().noneMatch(PodSonarTest::isNotNavigator),
                    "the navigator is told nothing, got %s",
                    scene.navigator().actionBarMessages());
            return true;
        });
    }

    private static boolean isNotNavigator(Component message) {
        return message.getContents() instanceof TranslatableContents contents
                && contents.getKey().equals("deepcharter.pod.sonar.not_navigator");
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void aPlayerOnFootCannotPing(GameTestHelper helper) {
        int x = 7064;
        scene(helper, x, level -> {}, scene -> {
            MockPlayer walker = MockPlayers.join(helper, "Sonar walker " + PLAYERS.incrementAndGet());
            expect(
                    helper,
                    PodSonar.pingBy(walker.player()) == PodSonar.Result.NOT_NAVIGATOR,
                    "a player in no pod has no sonar");
            expect(helper, echoes(scene.pod()).isEmpty(), "and the pod heard nothing");
        });
    }

    // ---- what a ping returns ----

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void aPingReturnsOreGasAndCavesAsContactsWithAKind(GameTestHelper helper) {
        int x = 7128;
        scene(
                helper,
                x,
                level -> {
                    block(level, x + 9, FLOOR + 1, Z + 1, OreBlocks.of(OreType.IRONIUM));
                    block(level, x - 7, FLOOR + 1, Z + 1, HazardBlocks.GAS_POCKET);
                    room(level, x + 12, FLOOR, Z, 1, 1, 4);
                },
                scene -> {
                    PodSonar.Result result = PodSonar.ping(scene.pod());
                    expect(helper, result == PodSonar.Result.PINGED, "the pod pings, got %s", result);
                    expectEcho(helper, scene.pod(), SonarKind.ORE, middle(x + 9, FLOOR + 1, Z + 1));
                    expectEcho(helper, scene.pod(), SonarKind.GAS, middle(x - 7, FLOOR + 1, Z + 1));
                    expectEcho(helper, scene.pod(), SonarKind.CAVE, middle(x + 12, FLOOR, Z));
                    expect(
                            helper,
                            echoes(scene.pod()).size() == 3,
                            "stone returns nothing, and the pod's own room is inside the blind zone: %s",
                            echoes(scene.pod()));
                });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void aGasPocketReadsAsAnEmptyCaveBeyondTheResolveDistance(GameTestHelper helper) {
        int x = 7192;
        int near = TUNING.gasResolveBlocks() - CELL;
        int far = TUNING.gasResolveBlocks() + CELL;
        scene(
                helper,
                x,
                level -> {
                    block(level, x + near, FLOOR + 1, Z + 1, HazardBlocks.GAS_POCKET);
                    block(level, x - far, FLOOR + 1, Z + 1, HazardBlocks.GAS_POCKET);
                },
                scene -> {
                    PodSonar.ping(scene.pod());
                    expectEcho(helper, scene.pod(), SonarKind.GAS, middle(x + near, FLOOR + 1, Z + 1));
                    expectEcho(helper, scene.pod(), SonarKind.CAVE, middle(x - far, FLOOR + 1, Z + 1));
                });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void aCellIsACaveFromHalfAir(GameTestHelper helper) {
        int x = 7256;
        scene(
                helper,
                x,
                level -> {
                    // 8 x 4 blocks of a 4 x 4 x 4 cell is 32 of 64: a cave. One block fewer is rock.
                    room(level, x + 8, FLOOR, Z, 1, 1, 2);
                    room(level, x + 8, FLOOR, Z + 8, 1, 1, 2);
                    block(level, x + 8, FLOOR, Z + 8, Blocks.STONE);
                },
                scene -> {
                    PodSonar.ping(scene.pod());
                    expectEcho(helper, scene.pod(), SonarKind.CAVE, middle(x + 8, FLOOR, Z));
                    expect(
                            helper,
                            !echoes(scene.pod()).contains(new SonarEcho(SonarKind.CAVE, middle(x + 8, FLOOR, Z + 8))),
                            "31 blocks of air in 64 is not a cave: %s",
                            echoes(scene.pod()));
                });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void creaturesInTheEchoTagEchoAsMovedOrAsOre(GameTestHelper helper) {
        int x = 7320;
        List<Entity> bodies = new ArrayList<>();
        scene(
                helper,
                x,
                level -> {
                    room(level, x + 8, FLOOR, Z, 2, 1, 4);
                    for (EntityType<?> type :
                            List.of(EntityTypes.ARMOR_STAND, EntityTypes.ARMOR_STAND, EntityTypes.MARKER)) {
                        Entity body = TestEntities.create(type, level);
                        body.setNoGravity(true);
                        body.setPos(x + 9 + 4 * bodies.size(), FLOOR + 1, Z + 1);
                        level.addFreshEntity(body);
                        bodies.add(body);
                    }
                },
                scene -> {
                    // The server's entity tick has not run since the velocity is set, so the ping reads it as set.
                    bodies.get(1).setDeltaMovement(TUNING.movingSpeed() * 2, 0, 0);
                    PodSonar.ping(scene.pod());
                    expectEcho(helper, scene.pod(), SonarKind.ORE, bodies.get(0).blockPosition());
                    expectEcho(
                            helper, scene.pod(), SonarKind.MOVED, bodies.get(1).blockPosition());
                    expect(
                            helper,
                            echoes(scene.pod()).stream()
                                    .noneMatch(echo ->
                                            echo.pos().equals(bodies.get(2).blockPosition())),
                            "a creature outside the echo tag is hollow and returns nothing: %s",
                            echoes(scene.pod()));
                    bodies.forEach(Entity::discard);
                });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void anotherSystemAddsEchoesThroughTheCollectEvent(GameTestHelper helper) {
        int x = 7384;
        BlockPos extra = new BlockPos(x + 3, FLOOR + 8, Z);
        SonarEvents.Collect listener = (ping, sink) -> {
            if (ping.pod().entityTags().contains("sonar-test-collect")) {
                sink.add(SonarKind.MOVED, extra);
                sink.add(SonarKind.MOVED, extra);
            }
        };
        SonarEvents.COLLECT.register(listener);
        scene(helper, x, level -> {}, scene -> {
            scene.pod().addTag("sonar-test-collect");
            PodSonar.ping(scene.pod());
            expect(
                    helper,
                    echoes(scene.pod()).equals(List.of(new SonarEcho(SonarKind.MOVED, extra))),
                    "a listener's echo is returned once, got %s",
                    echoes(scene.pod()));
        });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void aPingReturnsAtMostTheCapAndKeepsWhatMovesAndOreFirst(GameTestHelper helper) {
        int x = 7448;
        BlockPos moved = new BlockPos(x + 3, FLOOR + 8, Z);
        SonarEvents.Collect listener = (ping, sink) -> {
            if (ping.pod().entityTags().contains("sonar-test-cap")) {
                sink.add(SonarKind.MOVED, moved);
            }
        };
        SonarEvents.COLLECT.register(listener);
        scene(
                helper,
                x,
                level -> {
                    // A wide cave of well over the cap's worth of cells, with one ore in it.
                    room(level, x - 20, FLOOR + 8, Z - 20, 10, 10, 8);
                    block(level, x - 16, FLOOR + 9, Z - 16, OreBlocks.of(OreType.IRONIUM));
                },
                scene -> {
                    scene.pod().addTag("sonar-test-cap");
                    PodSonar.ping(scene.pod());
                    List<SonarEcho> returned = echoes(scene.pod());
                    expect(
                            helper,
                            returned.size() == TUNING.maxEchoes(),
                            "a ping returns at most %s echoes, got %s",
                            TUNING.maxEchoes(),
                            returned.size());
                    expect(
                            helper,
                            returned.get(0).kind() == SonarKind.MOVED
                                    && returned.get(1).kind() == SonarKind.ORE,
                            "what moves, then ore, come before open space: %s",
                            returned.subList(0, 3));
                    expect(
                            helper,
                            returned.get(2).kind() == SonarKind.CAVE,
                            "the rest is open space, nearest first: %s",
                            returned.subList(0, 4));
                });
    }

    // ---- power, the wait and the reserve ----

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void aPingWaitsForTheNextAndRefusesWithoutPowerOrBelowTheReserve(GameTestHelper helper) {
        int x = 7512;
        scene(helper, x, level -> {}, scene -> {
            PodEntity pod = scene.pod();
            expect(helper, PodSonar.ping(pod) == PodSonar.Result.PINGED, "the first ping goes out");
            expect(helper, PodSonar.ping(pod) == PodSonar.Result.COOLING_DOWN, "the next waits for the sonar to clear");
            float fuel = pod.fuel();
            clearWait(pod);
            UNPOWERED.add(pod.getUUID());
            expect(helper, PodSonar.ping(pod) == PodSonar.Result.NO_POWER, "a pod without power cannot ping");
            UNPOWERED.remove(pod.getUUID());
            expect(helper, pod.fuel() == fuel, "a refused ping costs nothing");
            float reserve = PodTuning.DEFAULT.fuel().urgentFuelPercent();
            pod.setFuel(reserve + PodSonar.costPercent(pod) - 0.5f);
            expect(
                    helper,
                    PodSonar.ping(pod) == PodSonar.Result.LOW_POWER,
                    "a ping cannot take the tank under its reserve");
            pod.setFuel(reserve + PodSonar.costPercent(pod) + 0.01f);
            expect(
                    helper,
                    PodSonar.ping(pod) == PodSonar.Result.PINGED,
                    "a ping that leaves the reserve intact goes out");
            expect(helper, pod.fuel() >= reserve, "and the tank stays at or above its reserve, at %s", pod.fuel());
        });
    }

    // ---- pacing, in player units ----

    /** The power of a ping: litres, the share of a stock tank, and what a stock tank pays for. Measured: the tank's own reading before and after real pings. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void pacingAPingTakesAFortiethOfAStockTankAndATankPaysForTwentyThree(GameTestHelper helper) {
        int x = 7576;
        scene(helper, x, level -> {}, scene -> {
            PodEntity pod = scene.pod();
            float full = PodTuning.DEFAULT.shell().fullFuel();
            pod.setFuel(full);
            float before = pod.fuel();
            PodSonar.ping(pod);
            float percent = before - pod.fuel();
            float litres = percent / full * PodTuning.DEFAULT.fuel().tankLitres();
            expect(helper, Math.abs(litres - 0.4f) < 0.001f, "a ping takes 0.4 L, it took %s L", litres);
            expect(helper, Math.abs(percent - 4f) < 0.01f, "that is 4%% of a stock 10 L tank, it took %s%%", percent);
            pod.setFuel(full);
            int pings = 0;
            while (true) {
                clearWait(pod);
                if (PodSonar.ping(pod) != PodSonar.Result.PINGED) {
                    break;
                }
                pings++;
            }
            expect(
                    helper,
                    pings == 23,
                    "a full stock tank pays for 23 pings down to its reserve, it paid for %s",
                    pings);
            expect(
                    helper,
                    pod.fuel() >= PodTuning.DEFAULT.fuel().urgentFuelPercent(),
                    "and is not left stranded, at %s%%",
                    pod.fuel());
        });
    }

    /** How far a ping sees: a row of ore, one block in each cell along the pod's east, and the farthest cell that echoes. Measured. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void pacingAPingSeesTwentyFourBlocksOutToTheMiddleOfACell(GameTestHelper helper) {
        int x = 7640;
        scene(
                helper,
                x,
                level -> {
                    for (int cell = 2; cell <= (REACH - 2) / CELL; cell++) {
                        block(level, x + cell * CELL, FLOOR + 1, Z + 1, OreBlocks.of(OreType.IRONIUM));
                    }
                },
                scene -> {
                    long started = System.nanoTime();
                    PodSonar.ping(scene.pod());
                    DeepCharter.LOGGER.info(
                            "[sonar] one ping, {} cells of stone read and {} echoes returned, took {} ms of the server tick",
                            (2 * TUNING.rangeBlocks() / CELL + 1)
                                    * (2 * TUNING.rangeBlocks() / CELL + 1)
                                    * (2 * TUNING.rangeBlocks() / CELL + 1),
                            echoes(scene.pod()).size(),
                            (System.nanoTime() - started) / 1_000_000.0);
                    Vec3 origin = scene.pod().position();
                    double farthest = 0;
                    double nearestSilent = Double.MAX_VALUE;
                    for (int cell = 2; cell <= (REACH - 2) / CELL; cell++) {
                        BlockPos middle = middle(x + cell * CELL, FLOOR + 1, Z + 1);
                        double away = Math.sqrt(middle.distToCenterSqr(origin));
                        if (hasEcho(scene.pod(), SonarKind.ORE, middle)) {
                            farthest = Math.max(farthest, away);
                        } else {
                            nearestSilent = Math.min(nearestSilent, away);
                        }
                    }
                    expect(
                            helper,
                            farthest > TUNING.rangeBlocks() - CELL && farthest <= TUNING.rangeBlocks(),
                            "the farthest ore cell to echo has its middle within the %s-block range and less than a cell inside it, it is %s blocks",
                            TUNING.rangeBlocks(),
                            farthest);
                    expect(
                            helper,
                            nearestSilent > TUNING.rangeBlocks(),
                            "an ore cell past the range is silent: the nearest silent one is %s blocks",
                            nearestSilent);
                });
    }

    /** What #924's creatures will ask of a ping, written here so the radius is pinned before they do: within the noise radius of where it came from. */
    private static boolean withinNoise(SonarPing ping, Vec3 listener) {
        return ping.origin().distanceToSqr(listener) <= (double) ping.noiseBlocks() * ping.noiseBlocks();
    }

    /** How far a ping is heard: the noise event carries the radius, and a listener answers it by that radius. Measured on a real ping. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void pacingAPingIsHeardFortyEightBlocksAndAfterItsEchoesAreSet(GameTestHelper helper) {
        int x = 7704;
        List<SonarPing> heard = new ArrayList<>();
        List<Integer> echoesAtTheNoise = new ArrayList<>();
        SonarEvents.PINGED.register(ping -> {
            if (ping.pod().entityTags().contains("sonar-test-noise")) {
                heard.add(ping);
                echoesAtTheNoise.add(
                        PodSonar.reading(ping.pod()).orElseThrow().echoes().size());
            }
        });
        scene(helper, x, level -> block(level, x + 9, FLOOR + 1, Z + 1, OreBlocks.of(OreType.IRONIUM)), scene -> {
            scene.pod().addTag("sonar-test-noise");
            PodSonar.ping(scene.pod());
            expect(helper, heard.size() == 1, "one ping is one noise, got %s", heard.size());
            SonarPing ping = heard.get(0);
            Vec3 origin = scene.pod().position();
            expect(helper, ping.noiseBlocks() == 48, "a ping is heard 48 blocks, not %s", ping.noiseBlocks());
            expect(
                    helper,
                    ping.noiseBlocks() > ping.rangeBlocks(),
                    "a ping is heard farther than it sees: %s and %s",
                    ping.noiseBlocks(),
                    ping.rangeBlocks());
            expect(helper, ping.origin().equals(origin) && ping.pod() == scene.pod(), "the noise is the pod's");
            expect(
                    helper,
                    withinNoise(ping, origin.add(ping.noiseBlocks(), 0, 0)),
                    "something 48 blocks off is in the noise");
            expect(
                    helper,
                    !withinNoise(ping, origin.add(ping.noiseBlocks() + 0.01, 0, 0)),
                    "something just past 48 blocks is not");
            expect(
                    helper,
                    echoesAtTheNoise.get(0) == 1,
                    "the pod has paid and holds its echoes when the noise goes out, held %s",
                    echoesAtTheNoise.get(0));
        });
    }

    /** How long the sonar rings between pings: the first tick a second ping is accepted. Measured on the world clock. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 200)
    public void pacingTheSonarClearsForTheNextPingAfterFourSeconds(GameTestHelper helper) {
        int x = 7832;
        ServerLevel level = TestLayers.level(helper, 1);
        long[] pinged = {-1};
        run(helper, x, l -> {}, scene -> {
            long now = level.getGameTime();
            if (pinged[0] < 0) {
                expect(helper, PodSonar.ping(scene.pod()) == PodSonar.Result.PINGED, "the first ping goes out");
                pinged[0] = now;
                return false;
            }
            long age = now - pinged[0];
            PodSonar.Result again = PodSonar.ping(scene.pod());
            if (age < TUNING.cooldownTicks()) {
                expect(
                        helper,
                        again == PodSonar.Result.COOLING_DOWN,
                        "at %s ticks the sonar is still ringing, got %s",
                        age,
                        again);
                return false;
            }
            expect(
                    helper,
                    age == TUNING.cooldownTicks() && again == PodSonar.Result.PINGED,
                    "the second ping is accepted at %s ticks (4 s), at %s it was %s",
                    TUNING.cooldownTicks(),
                    age,
                    again);
            return true;
        });
    }

    /** How long an echo shows: from the ping to the first tick the pod's picture is clear. Measured on the world clock. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 400)
    public void pacingAnEchoShowsTenSecondsThenFades(GameTestHelper helper) {
        int x = 7768;
        ServerLevel level = TestLayers.level(helper, 1);
        long[] pinged = {-1};
        long[] firstClear = {-1};
        run(helper, x, l -> block(l, x + 9, FLOOR + 1, Z + 1, OreBlocks.of(OreType.IRONIUM)), scene -> {
            long now = level.getGameTime();
            if (pinged[0] < 0) {
                PodSonar.ping(scene.pod());
                pinged[0] = now;
                return false;
            }
            long age = now - pinged[0];
            PodSonar.State state = PodSonar.reading(scene.pod()).orElseThrow();
            if (age < TUNING.lifetimeTicks()) {
                expect(
                        helper,
                        !state.shown(now).isEmpty() && !state.echoes().isEmpty(),
                        "the echo shows at %s ticks of %s",
                        age,
                        TUNING.lifetimeTicks());
            }
            if (firstClear[0] < 0 && state.echoes().isEmpty()) {
                firstClear[0] = age;
            }
            if (age == TUNING.lifetimeTicks() + 2) {
                expect(
                        helper,
                        firstClear[0] == TUNING.lifetimeTicks() || firstClear[0] == TUNING.lifetimeTicks() + 1,
                        "an echo shows %s ticks (10 s) and then the pod's picture clears, it cleared at %s",
                        TUNING.lifetimeTicks(),
                        firstClear[0]);
                expect(helper, state.shown(now).isEmpty(), "a faded echo is not shown");
                return true;
            }
            return false;
        });
    }

    // ---- marks ----

    private static List<BlockPos> marked(PodEntity pod) {
        return PodSonar.reading(pod).orElseThrow().marks().stream()
                .map(SonarMark::pos)
                .toList();
    }

    /** Lets the pod take another mark at once, which the mark wait would hold for a few ticks. */
    private static void freeMark(PodEntity pod) {
        PodSonar.State state = PodSonar.reading(pod).orElseThrow();
        pod.setAttached(
                PodSonar.STATE,
                Versioned.of(new PodSonar.State(state.readyAt(), state.pingedAt(), state.echoes(), state.marks(), 0L)));
    }

    /** The mark wait: a second mark inside it is refused with no write, so a client cannot force a sync at packet rate. Measured on the world clock. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void pacingAPodTakesAMarkEveryFiveTicksAtMost(GameTestHelper helper) {
        int x = 8088;
        ServerLevel level = TestLayers.level(helper, 1);
        long[] marked = {-1};
        BlockPos ore = middle(x + 9, FLOOR + 1, Z + 1);
        run(helper, x, l -> block(l, x + 9, FLOOR + 1, Z + 1, OreBlocks.of(OreType.IRONIUM)), scene -> {
            PodEntity pod = scene.pod();
            long now = level.getGameTime();
            if (marked[0] < 0) {
                PodSonar.ping(pod);
                expect(helper, PodSonar.mark(pod, ore) == PodSonar.MarkResult.MARKED, "the first mark goes on");
                marked[0] = now;
                PodSonar.State before = PodSonar.reading(pod).orElseThrow();
                expect(
                        helper,
                        PodSonar.mark(pod, ore) == PodSonar.MarkResult.TOO_FAST,
                        "a second in the same tick is refused");
                expect(
                        helper,
                        PodSonar.reading(pod).orElseThrow().equals(before),
                        "a refused mark changes nothing, so nothing syncs");
                return false;
            }
            long age = now - marked[0];
            PodSonar.MarkResult result = PodSonar.mark(pod, ore);
            if (age < TUNING.markCooldownTicks()) {
                expect(
                        helper,
                        result == PodSonar.MarkResult.TOO_FAST,
                        "at %s ticks the mark is refused, got %s",
                        age,
                        result);
                return false;
            }
            expect(
                    helper,
                    age == TUNING.markCooldownTicks() && result == PodSonar.MarkResult.UNMARKED,
                    "the next mark is accepted at %s ticks, at %s it was %s",
                    TUNING.markCooldownTicks(),
                    age,
                    result);
            return true;
        });
    }

    /** The navigator marks an echo; the pilot reads the mark off the pod's picture, and it outlives the echo it came from. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 400)
    public void aMarkShowsOnThePodAndOutlivesItsEcho(GameTestHelper helper) {
        int x = 7896;
        ServerLevel level = TestLayers.level(helper, 1);
        BlockPos ore = middle(x + 9, FLOOR + 1, Z + 1);
        long[] pinged = {-1};
        run(helper, x, l -> block(l, x + 9, FLOOR + 1, Z + 1, OreBlocks.of(OreType.IRONIUM)), scene -> {
            long now = level.getGameTime();
            PodEntity pod = scene.pod();
            if (pinged[0] < 0) {
                PodSonar.ping(pod);
                pinged[0] = now;
                expect(
                        helper,
                        PodSonar.markBy(scene.pilot().player(), ore) == PodSonar.MarkResult.NOT_NAVIGATOR,
                        "the pilot cannot mark");
                expect(helper, marked(pod).isEmpty(), "and nothing is marked");
                expect(
                        helper,
                        PodSonar.markBy(scene.navigator().player(), ore.above(30)) == PodSonar.MarkResult.NO_ECHO,
                        "there is no echo where the navigator did not hear one");
                expect(
                        helper,
                        PodSonar.markBy(scene.navigator().player(), ore) == PodSonar.MarkResult.MARKED,
                        "the navigator marks the ore echo");
                expect(helper, marked(pod).equals(List.of(ore)), "the pod holds the mark, got %s", marked(pod));
                expect(
                        helper,
                        PodSonar.reading(pod).orElseThrow().marks().get(0).kind() == SonarKind.ORE,
                        "and it is an ore mark");
                return false;
            }
            long age = now - pinged[0];
            if (age == TUNING.lifetimeTicks() + 5) {
                expect(helper, echoes(pod).isEmpty(), "the echoes have faded");
                expect(
                        helper,
                        marked(pod).equals(List.of(ore)),
                        "the mark holds after its echo is gone: %s",
                        marked(pod));
                expect(
                        helper,
                        PodSonar.markBy(scene.navigator().player(), ore.above(30)) == PodSonar.MarkResult.NO_ECHO,
                        "a faded echo cannot be marked");
                expect(
                        helper,
                        PodSonar.markBy(scene.navigator().player(), ore) == PodSonar.MarkResult.UNMARKED
                                && marked(pod).isEmpty(),
                        "but a mark that outlived its echo can still be taken off by its place");
                return true;
            }
            return false;
        });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void markingAMarkedEchoTakesItOffAndAPodHoldsThree(GameTestHelper helper) {
        int x = 7960;
        List<BlockPos> ores = List.of(
                new BlockPos(x + 9, FLOOR + 1, Z + 1),
                new BlockPos(x + 13, FLOOR + 1, Z + 1),
                new BlockPos(x + 9, FLOOR + 1, Z + 9),
                new BlockPos(x + 13, FLOOR + 1, Z + 9));
        scene(
                helper,
                x,
                level -> ores.forEach(
                        ore -> block(level, ore.getX(), ore.getY(), ore.getZ(), OreBlocks.of(OreType.IRONIUM))),
                scene -> {
                    PodEntity pod = scene.pod();
                    PodSonar.ping(pod);
                    List<BlockPos> middles = ores.stream()
                            .map(ore -> middle(ore.getX(), ore.getY(), ore.getZ()))
                            .toList();
                    expect(
                            helper,
                            PodSonar.mark(pod, middles.get(0)) == PodSonar.MarkResult.MARKED,
                            "marking an echo marks it");
                    freeMark(pod);
                    expect(
                            helper,
                            PodSonar.mark(pod, middles.get(0)) == PodSonar.MarkResult.UNMARKED,
                            "and marking it again takes the mark off");
                    expect(helper, marked(pod).isEmpty(), "and nothing is marked then, got %s", marked(pod));
                    for (BlockPos echo : middles) {
                        freeMark(pod);
                        expect(helper, PodSonar.mark(pod, echo) == PodSonar.MarkResult.MARKED, "marking %s", echo);
                    }
                    expect(
                            helper,
                            marked(pod).equals(middles.subList(1, 4)),
                            "a pod holds %s marks and the fourth drops the oldest, got %s",
                            TUNING.maxMarks(),
                            marked(pod));
                });
    }

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void aMarkLapsesAfterAMinute(GameTestHelper helper) {
        int x = 8024;
        ServerLevel level = TestLayers.level(helper, 1);
        boolean[] set = {false};
        run(helper, x, l -> {}, scene -> {
            PodEntity pod = scene.pod();
            long now = level.getGameTime();
            if (!set[0]) {
                set[0] = true;
                BlockPos fresh = new BlockPos(1, 2, 3);
                BlockPos lapsed = new BlockPos(4, 5, 6);
                pod.setAttached(
                        PodSonar.STATE,
                        Versioned.of(new PodSonar.State(
                                0L,
                                0L,
                                List.of(),
                                List.of(
                                        new SonarMark(SonarKind.ORE, lapsed, now - TUNING.markLifetimeTicks()),
                                        new SonarMark(SonarKind.GAS, fresh, now - TUNING.markLifetimeTicks() + 2)),
                                0L)));
                return false;
            }
            expect(
                    helper,
                    marked(pod).equals(List.of(new BlockPos(1, 2, 3))),
                    "a mark of %s ticks (a minute) has lapsed and one a tick short of it has not, got %s",
                    TUNING.markLifetimeTicks(),
                    marked(pod));
            return true;
        });
    }

    // ---- saving ----

    @GameTest
    public void theSonarPictureSurvivesASaveAndALoad(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PodEntity pod = TestEntities.create(PodRegistry.PROSPECTOR, level);
        PodSonar.State state = new PodSonar.State(
                500L,
                420L,
                List.of(new SonarEcho(SonarKind.GAS, new BlockPos(1, -2, 3))),
                List.of(new SonarMark(SonarKind.MOVED, new BlockPos(4, 5, 6), 430L)),
                435L);
        pod.setAttached(PodSonar.STATE, Versioned.of(state));
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        pod.saveWithoutId(output);
        PodEntity loaded = TestEntities.create(PodRegistry.PROSPECTOR, level);
        loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), output.buildResult()));
        expect(
                helper,
                PodSonar.reading(loaded).orElseThrow().equals(state),
                "the sonar picture survives a save and a load, got %s",
                PodSonar.reading(loaded));
        helper.succeed();
    }

    /** The saved shape is permanent: a literal, so a rename of a field or a kind cannot pass by agreeing with itself. */
    @GameTest
    public void theSavedShapeAndTheKindNamesArePinned(GameTestHelper helper) {
        Codec<Versioned<PodSonar.State>> codec = Versioned.codec(PodSonar.VERSION, PodSonar.State.BODY);
        PodSonar.State state = new PodSonar.State(
                5L,
                3L,
                List.of(
                        new SonarEcho(SonarKind.ORE, new BlockPos(1, 2, 3)),
                        new SonarEcho(SonarKind.GAS, new BlockPos(4, 5, 6)),
                        new SonarEcho(SonarKind.CAVE, new BlockPos(7, 8, 9)),
                        new SonarEcho(SonarKind.MOVED, new BlockPos(10, 11, 12))),
                List.of(new SonarMark(SonarKind.CAVE, new BlockPos(7, 8, 9), 4L)),
                9L);
        Tag saved = codec.encodeStart(NbtOps.INSTANCE, Versioned.of(state)).getOrThrow();
        CompoundTag expected = new CompoundTag();
        expected.putInt("version", 1);
        expected.putLong("readyAt", 5L);
        expected.putLong("pingedAt", 3L);
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        String[] kinds = {"ore", "gas", "cave", "moved"};
        int[][] positions = {{1, 2, 3}, {4, 5, 6}, {7, 8, 9}, {10, 11, 12}};
        for (int i = 0; i < kinds.length; i++) {
            CompoundTag echo = new CompoundTag();
            echo.putString("kind", kinds[i]);
            echo.putIntArray("pos", positions[i]);
            list.add(echo);
        }
        expected.put("echoes", list);
        CompoundTag mark = new CompoundTag();
        mark.putString("kind", "cave");
        mark.putIntArray("pos", new int[] {7, 8, 9});
        mark.putLong("markedAt", 4L);
        net.minecraft.nbt.ListTag marks = new net.minecraft.nbt.ListTag();
        marks.add(mark);
        expected.put("marks", marks);
        expected.putLong("markReadyAt", 9L);
        expect(helper, saved.equals(expected), "the saved sonar picture should be %s, is %s", expected, saved);
        Versioned<PodSonar.State> back = codec.parse(NbtOps.INSTANCE, expected).getOrThrow();
        expect(helper, back.equals(Versioned.of(state)), "and it reads back as saved, got %s", back);
        helper.succeed();
    }

    @GameTest
    public void aSonarPictureOfAnotherVersionIsKeptAndNeverThrows(GameTestHelper helper) {
        PodEntity pod = TestEntities.create(PodRegistry.PROSPECTOR, helper.getLevel());
        UnreadableChecks.makeUnreadable(pod, PodSonar.STATE);
        UnreadableChecks.assertNoThrow(
                helper,
                "pod sonar",
                Map.of(
                        "the reading", () -> PodSonar.reading(pod),
                        "the ping", () -> PodSonar.ping(pod),
                        "the tick", () -> PodEvents.AFTER_TICK.invoker().afterTick(pod)));
        expect(
                helper,
                PodSonar.ping(pod) == PodSonar.Result.UNREADABLE,
                "a ping on a picture this build cannot read says so");
        expect(
                helper,
                pod.getAttached(PodSonar.STATE) instanceof Versioned.Unreadable<PodSonar.State>,
                "an unreadable sonar picture is left as it was read");
        pod.discard();
        helper.succeed();
    }
}
