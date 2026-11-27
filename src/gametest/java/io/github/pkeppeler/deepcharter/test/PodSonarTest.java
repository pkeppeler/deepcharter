package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodSeats;
import io.github.pkeppeler.deepcharter.pod.PodSonar;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.pod.SonarEcho;
import io.github.pkeppeler.deepcharter.pod.SonarEvents;
import io.github.pkeppeler.deepcharter.pod.SonarKind;
import io.github.pkeppeler.deepcharter.pod.SonarMark;
import io.github.pkeppeler.deepcharter.pod.SonarPing;
import io.github.pkeppeler.deepcharter.pod.SonarTags;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.animal.chicken.Chicken;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.level.ChunkPos;
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
    /** How close to the pod's middle a walker is when it has arrived: at the hull, which is wider than the walker. */
    private static final double PRESENT_BLOCKS = 7;

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
        scene(helper, x, List.of(), build, check);
    }

    /** As {@link #scene}, discarding {@code bodies} (the mobs the build adds) when the test ends, whether it passes or fails. */
    private static void scene(
            GameTestHelper helper, int x, List<Entity> bodies, Consumer<ServerLevel> build, Consumer<Scene> check) {
        run(helper, x, bodies, build, scene -> {
            check.accept(scene);
            return true;
        });
    }

    /**
     * As {@link #scene(GameTestHelper, int, List, Consumer, Consumer)}, running {@code check} on the first tick after the settle on which every body is on
     * the ground. A body falls from where the build put it, and the settle ticks are a count, not a landing: a loaded runner's cow was still in the air
     * when they ran out (#1569). The wait fails under a {@link FarChunks.Deadline}, naming the body in the air.
     */
    private static void sceneLanded(
            GameTestHelper helper, int x, List<Entity> bodies, Consumer<ServerLevel> build, Consumer<Scene> check) {
        ServerLevel level = TestLayers.level(helper, 1);
        FarChunks.Deadline landing = FarChunks.deadline();
        run(helper, x, bodies, build, scene -> {
            Optional<Entity> airborne =
                    bodies.stream().filter(body -> !body.onGround()).findFirst();
            landing.await(helper, level, airborne.isEmpty(), () -> airborne.get() + " had not landed");
            if (airborne.isPresent()) {
                return false;
            }
            check.accept(scene);
            return true;
        });
    }

    /** As {@link #scene}, running {@code perTick} on every tick after the settle until it returns true. */
    private static void run(GameTestHelper helper, int x, Consumer<ServerLevel> build, Predicate<Scene> perTick) {
        run(helper, x, List.of(), build, perTick);
    }

    /**
     * As {@link #run(GameTestHelper, int, Consumer, Predicate)}, discarding {@code bodies} when the test ends. The mobs the sonar tags name (a husk, a
     * cow, a pig, a sheep) exist only in the test mod's tags, but a mob left in a forced chunk keeps ticking and answering pings, so a failing test must
     * not leave one behind.
     */
    private static void run(
            GameTestHelper helper, int x, List<Entity> bodies, Consumer<ServerLevel> build, Predicate<Scene> perTick) {
        run(helper, x, bodies, 0, build, perTick);
    }

    /**
     * As {@link #run(GameTestHelper, int, List, Consumer, Predicate)}. With a {@code laterChunksTick} above 0 only the pod's chunk is forced on the
     * first tick and the rest of the site on that tick: a loaded chunk that is not yet entity ticking, as a loaded runner leaves a neighbour of the
     * pod's chunk for ticks after the pod's own (#1234).
     */
    private static void run(
            GameTestHelper helper,
            int x,
            List<Entity> bodies,
            int laterChunksTick,
            Consumer<ServerLevel> build,
            Predicate<Scene> perTick) {
        ServerLevel level = TestLayers.level(helper, 1);
        List<ChunkPos> later = new ArrayList<>();
        for (int cx = (x - REACH) >> 4; cx <= (x + REACH) >> 4; cx++) {
            for (int cz = (Z - REACH) >> 4; cz <= (Z + REACH) >> 4; cz++) {
                if (laterChunksTick > 0 && (cx != x >> 4 || cz != Z >> 4)) {
                    later.add(new ChunkPos(cx, cz));
                } else {
                    FarChunks.force(helper, level, cx, cz);
                }
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
        FarChunks.Deadline bodiesDeadline = FarChunks.deadline();
        TestTicks.each(helper, () -> {
            try {
                if (helper.getTick() == laterChunksTick) {
                    later.forEach(chunk -> FarChunks.force(helper, level, chunk.x(), chunk.z()));
                }
                if (scene[0] == null) {
                    return;
                }
                // A body in a chunk that is not entity ticking does not move: the settle ticks count from when all of
                // them do.
                Optional<Entity> idle = bodies.stream()
                        .filter(body -> !level.isPositionEntityTicking(body.blockPosition()))
                        .findFirst();
                bodiesDeadline.await(
                        helper,
                        level,
                        idle.isEmpty(),
                        () -> idle.get() + " in chunk "
                                + ChunkPos.containing(idle.get().blockPosition()) + " was not entity-ticking");
                if (idle.isEmpty() && ++ticks[0] >= SETTLE_TICKS && perTick.test(scene[0])) {
                    scene[0].pod().discard();
                    bodies.forEach(Entity::discard);
                    helper.succeed();
                }
            } catch (RuntimeException | Error e) {
                bodies.forEach(Entity::discard);
                throw e;
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

    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void theSeatAssignmentFollowsBoardingOrder(GameTestHelper helper) {
        int x = 8152;
        boolean[] left = {false};
        run(helper, x, level -> {}, scene -> {
            // The assignment is written at the end of the pod's tick, so each step waits for it to catch up.
            List<UUID> synced = Versioned.readable(scene.pod(), PodSeats.STATE)
                    .orElseThrow()
                    .riders();
            UUID pilot = scene.pilot().player().getUUID();
            UUID navigator = scene.navigator().player().getUUID();
            if (!left[0]) {
                if (!synced.equals(List.of(pilot, navigator))) {
                    return false;
                }
                scene.pilot().player().stopRiding();
                left[0] = true;
                return false;
            }
            if (!synced.equals(List.of(navigator))) {
                return false;
            }
            expect(
                    helper,
                    PodSeats.pilot(scene.pod()).filter(navigator::equals).isPresent(),
                    "once the pilot gets off the navigator holds the pilot's seat");
            return true;
        });
    }

    @GameTest
    public void aRiderThatIsNoPlayerDoesNotPilot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PodEntity pod = TestEntities.create(PodRegistry.PROSPECTOR, level);
        Entity stand = TestEntities.create(EntityTypes.ARMOR_STAND, level);
        expect(helper, stand.startRiding(pod, true, false), "the armour stand boards the pod");
        PodEvents.AFTER_TICK.invoker().afterTick(pod);
        PodSeats.State state = Versioned.readable(pod, PodSeats.STATE).orElseThrow();
        expect(
                helper,
                state.riders().equals(List.of(stand.getUUID())) && !state.pilotIsPlayer(),
                "the stand holds seat 0 and the assignment says it is no player, got %s",
                state);
        expect(helper, PodSeats.pilot(pod).isEmpty(), "a pod whose first rider is no player has no pilot");
        stand.discard();
        pod.discard();
        helper.succeed();
    }

    @GameTest
    public void aSeatAssignmentOfAnotherVersionIsKeptAndNeverThrows(GameTestHelper helper) {
        PodEntity pod = TestEntities.create(PodRegistry.PROSPECTOR, helper.getLevel());
        UnreadableChecks.makeUnreadable(pod, PodSeats.STATE);
        UnreadableChecks.assertNoThrow(
                helper,
                "pod seats",
                Map.of(
                        "the pilot", () -> PodSeats.pilot(pod),
                        "the tick", () -> PodEvents.AFTER_TICK.invoker().afterTick(pod)));
        expect(
                helper,
                pod.getAttached(PodSeats.STATE) instanceof Versioned.Unreadable<PodSeats.State>,
                "an unreadable seat assignment is left as it was read");
        pod.discard();
        helper.succeed();
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
        List<EntityType<?>> types = List.of(EntityTypes.ARMOR_STAND, EntityTypes.ARMOR_STAND, EntityTypes.MARKER);
        // The bodies stand 9, 13 and 17 blocks from the pod, in chunks other than the pod's.
        List<BlockPos> spots = IntStream.range(0, types.size())
                .mapToObj(i -> new BlockPos(x + 9 + 4 * i, FLOOR + 1, Z + 1))
                .toList();
        // An entity added to a chunk that is loaded but not yet entity ticking is not found by a query (#1125).
        ServerLevel level = TestLayers.level(helper, 1);
        FarChunks.awaitAllEntityTicking(helper, level, spots, () -> {
            for (int i = 0; i < types.size(); i++) {
                Entity body = TestEntities.create(types.get(i), level);
                body.setNoGravity(true);
                body.setPos(Vec3.atBottomCenterOf(spots.get(i)));
                level.addFreshEntity(body);
                bodies.add(body);
            }
        });
        run(helper, x, bodies, build -> room(build, x + 8, FLOOR, Z, 2, 1, 4), scene -> {
            if (bodies.isEmpty()) {
                return false;
            }
            // The server's entity tick has not run since the velocity is set, so the ping reads it as set.
            bodies.get(1).setDeltaMovement(TUNING.movingSpeed() * 2, 0, 0);
            PodSonar.ping(scene.pod());
            expectEcho(helper, scene.pod(), SonarKind.ORE, bodies.get(0).blockPosition());
            expectEcho(helper, scene.pod(), SonarKind.MOVED, bodies.get(1).blockPosition());
            expect(
                    helper,
                    echoes(scene.pod()).stream()
                            .noneMatch(echo -> echo.pos().equals(bodies.get(2).blockPosition())),
                    "a creature outside the echo tag is hollow and returns nothing: %s",
                    echoes(scene.pod()));
            return true;
        });
    }

    // ---- signatures: what echoes and what does not (#924) ----

    private static <T extends Mob> T still(ServerLevel level, EntityType<T> type, double x, double y, double z) {
        T mob = TestEntities.mob(type, level);
        mob.removeFreeWill();
        mob.setPos(x, y, z);
        level.addFreshEntity(mob);
        return mob;
    }

    private static boolean isIn(EntityType<?> type, TagKey<EntityType<?>> tag) {
        return BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(type).is(tag);
    }

    private static boolean echoesAt(PodEntity pod, BlockPos pos) {
        return echoes(pod).stream().anyMatch(echo -> echo.pos().equals(pos));
    }

    /** A Company machine echoes as ore (CREATURES.md section 2), moving or not; a Retained is hollow and returns nothing. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void aTallyEchoesAsOreAtAnySpeedAndARetainedReturnsNothing(GameTestHelper helper) {
        int x = 8152;
        List<Entity> bodies = new ArrayList<>();
        scene(
                helper,
                x,
                bodies,
                level -> {
                    room(level, x + 8, FLOOR, Z, 2, 1, 4);
                    bodies.add(still(level, CreatureRegistry.TALLY, x + 9.5, FLOOR + 1, Z + 1.5));
                    bodies.add(still(level, CreatureRegistry.TALLY, x + 13.5, FLOOR + 1, Z + 1.5));
                    bodies.add(still(level, CreatureRegistry.LAMPLESS_FIGURE, x + 11.5, FLOOR, Z + 2.5));
                },
                scene -> {
                    bodies.get(1).setDeltaMovement(TUNING.movingSpeed() * 4, 0, 0);
                    PodSonar.ping(scene.pod());
                    expectEcho(helper, scene.pod(), SonarKind.ORE, bodies.get(0).blockPosition());
                    expectEcho(helper, scene.pod(), SonarKind.ORE, bodies.get(1).blockPosition());
                    expect(
                            helper,
                            !echoesAt(scene.pod(), bodies.get(2).blockPosition()),
                            "a Retained is hollow and returns nothing: %s",
                            echoes(scene.pod()));
                });
    }

    /** The tags that say who echoes are pinned with the real types, so a creature that joins by a tag line cannot move another. */
    @GameTest
    public void theShippedSignatureTagsHoldTheCreaturesThatExist(GameTestHelper helper) {
        expect(
                helper,
                isIn(CreatureRegistry.TALLY, SonarTags.ORE_ECHOING) && !isIn(CreatureRegistry.TALLY, SonarTags.HOLLOW),
                "a Tally echoes as ore");
        expect(
                helper,
                isIn(CreatureRegistry.LAMPLESS_FIGURE, SonarTags.HOLLOW)
                        && !isIn(CreatureRegistry.LAMPLESS_FIGURE, SonarTags.ECHOING)
                        && !isIn(CreatureRegistry.LAMPLESS_FIGURE, SonarTags.ORE_ECHOING)
                        && !isIn(CreatureRegistry.LAMPLESS_FIGURE, SonarTags.HEARS),
                "a Retained is hollow: no echo, and it does not hear");
        helper.succeed();
    }

    /** A real mob on the ground reads as still: gravity is not a move. Walking at a block a second reads as moved. A hollow type in a broad tag still returns none. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void aMobStandingOnTheFloorEchoesStillAndAWalkingOneAsMovedAndHollowWins(GameTestHelper helper) {
        int x = 8216;
        List<Entity> bodies = new ArrayList<>();
        sceneLanded(
                helper,
                x,
                bodies,
                level -> {
                    room(level, x + 8, FLOOR, Z, 2, 1, 4);
                    bodies.add(still(level, EntityTypes.COW, x + 9.5, FLOOR, Z + 1.5));
                    bodies.add(still(level, EntityTypes.COW, x + 13.5, FLOOR, Z + 1.5));
                    bodies.add(still(level, EntityTypes.PIG, x + 11.5, FLOOR, Z + 2.5));
                },
                scene -> {
                    bodies.get(1)
                            .setDeltaMovement(
                                    TUNING.movingSpeed() * 2, bodies.get(1).getDeltaMovement().y, 0);
                    PodSonar.ping(scene.pod());
                    expectEcho(helper, scene.pod(), SonarKind.ORE, bodies.get(0).blockPosition());
                    expectEcho(
                            helper, scene.pod(), SonarKind.MOVED, bodies.get(1).blockPosition());
                    expect(
                            helper,
                            !echoesAt(scene.pod(), bodies.get(2).blockPosition()),
                            "a type in both the echo and the hollow tag returns nothing: %s",
                            echoes(scene.pod()));
                });
    }

    // ---- the creature tests wait for what they stand on, and nothing removes what they place (#1234) ----

    /** The tick on which the late-chunk test lets {@link #run} force the chunks beside the pod's. */
    private static final int LATER_CHUNKS_TICK = 20;

    /**
     * The check waits until every body's chunk is entity ticking: a cow in the chunk beside the pod's, forced on tick {@value #LATER_CHUNKS_TICK},
     * has landed when it runs. The later chunks stay loaded through the carve, so the cow survives until the force without a ticket.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void aCreatureInTheChunkBesideThePodsIsWaitedForUntilItTicks(GameTestHelper helper) {
        int x = 9100;
        List<Entity> bodies = new ArrayList<>();
        ServerLevel world = TestLayers.level(helper, 1);
        FarChunks.Deadline landing = FarChunks.deadline();
        run(
                helper,
                x,
                bodies,
                LATER_CHUNKS_TICK,
                level -> {
                    room(level, x + 8, FLOOR, Z, 2, 1, 4);
                    bodies.add(still(level, EntityTypes.COW, x + 9.5, FLOOR, Z + 1.5));
                },
                scene -> {
                    landing.await(
                            helper,
                            world,
                            bodies.get(0).onGround(),
                            () -> "the cow had not settled on the floor, on tick " + helper.getTick());
                    return bodies.get(0).onGround();
                });
    }

    /** Idle ticks after which {@code Mob.checkDespawn} may remove a monster 32 or more blocks from every player. */
    private static final int DESPAWN_IDLE_TICKS = 600;

    /** Tries at 1 in 800 each: a mob the game may remove survives them with a chance near e^-25. */
    private static final int DESPAWN_TRIES = 20_000;

    /** A creature the test places 36 blocks from the pod is never despawned, however long the test waits. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void aCreatureTheTestPlacesFarFromThePlayersIsNotDespawnedByTheGame(GameTestHelper helper) {
        int x = 9164;
        List<Entity> bodies = new ArrayList<>();
        Husk[] husk = new Husk[1];
        scene(
                helper,
                x,
                bodies,
                level -> {
                    corridor(helper, level, x, 56);
                    husk[0] = walker(level, x + 36.5, 0.5);
                    bodies.add(husk[0]);
                },
                scene -> {
                    husk[0].setNoActionTime(DESPAWN_IDLE_TICKS + 1);
                    for (int i = 0; i < DESPAWN_TRIES && !husk[0].isRemoved(); i++) {
                        husk[0].checkDespawn();
                    }
                    expect(
                            helper,
                            !husk[0].isRemoved(),
                            "a placed creature far from the players was removed by the game's despawn check: %s",
                            husk[0].getRemovalReason());
                });
    }

    // ---- the ping draws the Sleeper's (#924) ----

    private static void corridor(GameTestHelper helper, ServerLevel level, int x, int length) {
        for (int cx = (x + REACH) >> 4; cx <= (x + length + 4) >> 4; cx++) {
            for (int cz = (Z - REACH) >> 4; cz <= (Z + REACH) >> 4; cz++) {
                FarChunks.force(helper, level, cx, cz);
            }
        }
        RoomCarver.carve(
                level, x + REACH + 1, x + length + 4, FLOOR - REACH, FLOOR + REACH, Z - REACH, Z + REACH, Blocks.STONE);
        RoomCarver.carve(
                level,
                new BlockPos(x + CELL, FLOOR, Z - 3),
                new BlockPos(x + length, FLOOR + 2, Z + 3),
                Blocks.AIR.defaultBlockState());
    }

    /** A husk with no will of its own, the test's stand-in for a Sleeper's walker (sun-proof; its follow range is how far it can path). */
    private static Husk walker(ServerLevel level, double x, double lane) {
        Husk husk = TestEntities.mob(EntityTypes.HUSK, level);
        husk.setPos(x, FLOOR, Z + lane);
        husk.removeFreeWill();
        level.addFreshEntity(husk);
        return husk;
    }

    /**
     * Who hears a ping, and how fast it comes. Tagged husks (the test's stand-ins for the Sleeper's) at 36 and 47 blocks set out for the pod; one
     * at 52, past the noise, does not; a chicken inside the noise and outside the tag does not. The husk from 36 blocks walks to the hull.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 1200)
    public void pacingAPingDrawsTheTaggedCreaturesInTheNoiseAndNoOtherAndTheyWalkTheDistance(GameTestHelper helper) {
        int x = 8300;
        Husk[] walkers = new Husk[3];
        Chicken[] chicken = new Chicken[1];
        double[] startAway = new double[2];
        int[] age = {-1};
        int[] arrived = {0};
        List<Entity> bodies = new ArrayList<>();
        run(
                helper,
                x,
                bodies,
                level -> {
                    corridor(helper, level, x, 56);
                    walkers[0] = walker(level, x + 36.5, 0.5);
                    walkers[1] = walker(level, x + 47.5, -2.5);
                    walkers[2] = walker(level, x + 52.5, 2.5);
                    chicken[0] = TestEntities.mob(EntityTypes.CHICKEN, level);
                    chicken[0].setPos(x + 20.5, FLOOR, Z + 0.5);
                    chicken[0].removeFreeWill();
                    level.addFreshEntity(chicken[0]);
                    bodies.addAll(List.of(walkers));
                    bodies.add(chicken[0]);
                },
                scene -> {
                    Vec3 pod = scene.pod().position();
                    if (age[0] < 0) {
                        // A creature paths only from the ground, and the far chunks tick a little after the pod's.
                        if (!Stream.concat(Stream.of(walkers), Stream.of(chicken))
                                .allMatch(Entity::onGround)) {
                            return false;
                        }
                        startAway[0] = walkers[0].position().distanceTo(pod);
                        startAway[1] = walkers[1].position().distanceTo(pod);
                        PodSonar.ping(scene.pod());
                        expect(
                                helper,
                                walkers[0].getNavigation().isInProgress()
                                        && walkers[1].getNavigation().isInProgress(),
                                "a tagged creature in the noise sets out for the ping: %s from %s blocks, %s from %s",
                                walkers[0].getNavigation().isInProgress(),
                                startAway[0],
                                walkers[1].getNavigation().isInProgress(),
                                startAway[1]);
                        expect(
                                helper,
                                !walkers[2].getNavigation().isInProgress(),
                                "a tagged creature past the noise does not hear it");
                        expect(
                                helper,
                                !chicken[0].getNavigation().isInProgress(),
                                "a creature not in the tag does not answer it");
                        age[0] = 0;
                        return false;
                    }
                    age[0]++;
                    if (arrived[0] == 0 && walkers[0].position().distanceTo(pod) < PRESENT_BLOCKS) {
                        arrived[0] = age[0];
                    }
                    boolean walking = walkers[0].getNavigation().isInProgress()
                            || walkers[1].getNavigation().isInProgress();
                    if (arrived[0] == 0 || walking) {
                        expect(
                                helper,
                                age[0] < 1100,
                                "the creatures walked: arrived %s, walking %s",
                                arrived[0],
                                walking);
                        return false;
                    }
                    double far = walkers[1].position().distanceTo(pod);
                    DeepCharter.LOGGER.info(
                            "[sonar-lure] a walker heard from {} blocks was at the hull after {} ticks; one heard from {} stopped {} blocks off; "
                                    + "one past the noise stayed {} blocks off",
                            startAway[0],
                            arrived[0],
                            startAway[1],
                            far,
                            walkers[2].position().distanceTo(pod));
                    expect(
                            helper,
                            far < startAway[1] - 20,
                            "the creature at the edge of the noise came a good way: %s of %s blocks",
                            far,
                            startAway[1]);
                    expect(
                            helper,
                            walkers[2].position().distanceTo(pod) > TUNING.noiseBlocks(),
                            "the creature past the noise stayed where it was");
                    return true;
                });
    }

    /** A thing that reads as an empty cave does so at any speed: CREATURES.md's Drifters, "sonar reads one as empty cave". */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void aCreatureInTheCaveTagReadsAsAnEmptyCaveStillOrMoving(GameTestHelper helper) {
        int x = 8600;
        List<Entity> bodies = new ArrayList<>();
        sceneLanded(
                helper,
                x,
                bodies,
                level -> {
                    room(level, x + 8, FLOOR, Z, 2, 1, 4);
                    bodies.add(still(level, EntityTypes.SHEEP, x + 9.5, FLOOR, Z + 1.5));
                    bodies.add(still(level, EntityTypes.SHEEP, x + 13.5, FLOOR, Z + 1.5));
                },
                scene -> {
                    bodies.get(1).setDeltaMovement(TUNING.movingSpeed() * 4, 0, 0);
                    PodSonar.ping(scene.pod());
                    for (Entity sheep : bodies) {
                        expectEcho(helper, scene.pod(), SonarKind.CAVE, sheep.blockPosition());
                        expect(
                                helper,
                                !hasEcho(scene.pod(), SonarKind.MOVED, sheep.blockPosition())
                                        && !hasEcho(scene.pod(), SonarKind.ORE, sheep.blockPosition()),
                                "a cave signature is a cave and nothing else: %s",
                                echoes(scene.pod()));
                    }
                });
    }

    /** Off the ground a creature's whole velocity counts: a no-gravity thing rising faster than a block a second moved. On the ground, a hop is not a walk. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 100)
    public void aCreatureRisingInTheAirReadsAsMovedAndOneOnTheGroundDoesNotForAVerticalSpeed(GameTestHelper helper) {
        int x = 8664;
        List<Entity> bodies = new ArrayList<>();
        sceneLanded(
                helper,
                x,
                bodies,
                level -> {
                    room(level, x + 8, FLOOR, Z, 2, 1, 4);
                    bodies.add(still(level, EntityTypes.COW, x + 9.5, FLOOR + 2, Z + 1.5));
                    bodies.add(still(level, EntityTypes.COW, x + 13.5, FLOOR, Z + 1.5));
                },
                scene -> {
                    bodies.get(0).setNoGravity(true);
                    bodies.get(0).setDeltaMovement(0, TUNING.movingSpeed() * 2, 0);
                    bodies.get(1).setDeltaMovement(0, TUNING.movingSpeed() * 2, 0);
                    PodSonar.ping(scene.pod());
                    expectEcho(
                            helper, scene.pod(), SonarKind.MOVED, bodies.get(0).blockPosition());
                    expectEcho(helper, scene.pod(), SonarKind.ORE, bodies.get(1).blockPosition());
                });
    }

    /**
     * A cow that is in the hears tag and keeps its own goals but the random stroll: the test sends it elsewhere by hand, and a stroll that starts on
     * the tick after a resend (one in 120) takes it off the ping before the test's 10-tick window reads it (#1234). A subclass, because the goal
     * selector is protected; it is made persistent as {@link TestEntities#mob} makes it.
     */
    private static Cow busyCow(ServerLevel level, double x) {
        Cow cow = new Cow(EntityTypes.COW, level) {
            {
                goalSelector.removeAllGoals(goal -> goal instanceof RandomStrollGoal);
            }
        };
        cow.setPersistenceRequired();
        cow.setPos(x, FLOOR, Z + 0.5);
        level.addFreshEntity(cow);
        return cow;
    }

    private static boolean headsFor(Mob mob, BlockPos target) {
        return mob.getNavigation().isInProgress()
                && Objects.equals(target, mob.getNavigation().getTargetPos());
    }

    /**
     * The hold: a ping sends a creature for {@link SonarTuning#lureTicks()} ticks, and when its own goals send it elsewhere (here, at tick 13) it is
     * sent again within 10 ticks; after the hold it is left alone. Measured on a cow that keeps its goals.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 1200)
    public void pacingAPingHoldsTheCreaturesThatHeardItForTenSecondsAgainstTheirOwnGoals(GameTestHelper helper) {
        int x = 8728;
        List<Entity> bodies = new ArrayList<>();
        Cow[] cow = new Cow[1];
        int[] age = {-1};
        double[] start = {0};
        int[] sentAgain = {0};
        run(
                helper,
                x,
                bodies,
                level -> {
                    corridor(helper, level, x, 40);
                    cow[0] = busyCow(level, x + 20.5);
                    bodies.add(cow[0]);
                },
                scene -> {
                    BlockPos origin = BlockPos.containing(scene.pod().position());
                    if (age[0] < 0) {
                        if (!cow[0].onGround()) {
                            return false;
                        }
                        start[0] = cow[0].position().distanceTo(scene.pod().position());
                        PodSonar.ping(scene.pod());
                        expect(helper, headsFor(cow[0], origin), "the cow sets out for the ping");
                        age[0] = 0;
                        return false;
                    }
                    age[0]++;
                    int hold = TUNING.lureTicks();
                    if (age[0] == 13 || age[0] == hold + 13) {
                        // Its own goals send it somewhere else.
                        cow[0].getNavigation().moveTo(x + 38.5, FLOOR, Z + 0.5, 1.0);
                        expect(helper, !headsFor(cow[0], origin), "the cow's own goal has taken it off the ping");
                        if (age[0] == 13) {
                            sentAgain[0] = 0;
                        }
                    }
                    if (age[0] > 13 && age[0] <= 23 && sentAgain[0] == 0 && headsFor(cow[0], origin)) {
                        sentAgain[0] = age[0];
                    }
                    if (age[0] == 150) {
                        double away = cow[0].position().distanceTo(scene.pod().position());
                        expect(
                                helper,
                                sentAgain[0] > 0 && away < start[0] - 6,
                                "the cow was sent again at tick %s and came from %s to %s blocks",
                                sentAgain[0],
                                start[0],
                                away);
                    }
                    if (age[0] == hold + 40) {
                        expect(
                                helper,
                                !headsFor(cow[0], origin),
                                "after the hold the cow is left to its own goals, and it is not heading for the ping");
                        DeepCharter.LOGGER.info(
                                "[sonar-lure] a cow with its own goals was sent again {} ticks after they took it off the ping, "
                                        + "and left alone {} ticks after the hold of {}",
                                sentAgain[0] - 13,
                                40,
                                hold);
                        return true;
                    }
                    return false;
                });
    }

    /**
     * What the shipped {@code sonar_hears} tag promises: a creature in it can path the whole noise radius, which is its follow range (the
     * pathfinder's node budget is {@code 16 x follow range}, fixed when the mob is built, so the lure cannot raise it). Reads the mod's own tag file, not
     * the test mod's, so the creatures #918 and #936 add are held to it the day they are added.
     */
    @GameTest
    public void everyCreatureInTheShippedHearsTagCanPathTheWholeNoise(GameTestHelper helper) {
        java.nio.file.Path file = FabricLoader.getInstance()
                .getModContainer(DeepCharter.MOD_ID)
                .orElseThrow()
                .findPath("data/deepcharter/tags/entity_type/sonar_hears.json")
                .orElseThrow();
        JsonArray values;
        try {
            values = JsonParser.parseString(java.nio.file.Files.readString(file))
                    .getAsJsonObject()
                    .getAsJsonArray("values");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        for (JsonElement value : values) {
            String id = value.isJsonObject() ? value.getAsJsonObject().get("id").getAsString() : value.getAsString();
            expect(
                    helper,
                    !id.startsWith("#"),
                    "%s: a sonar_hears entry names a creature, not a tag, so this test can read its attributes",
                    id);
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(id));
            @SuppressWarnings("unchecked")
            EntityType<? extends LivingEntity> living = (EntityType<? extends LivingEntity>) type;
            expect(helper, DefaultAttributes.hasSupplier(living), "%s is in sonar_hears but is not a mob", id);
            double range = DefaultAttributes.getSupplier(living).getBaseValue(Attributes.FOLLOW_RANGE);
            expect(
                    helper,
                    range >= TUNING.noiseBlocks(),
                    "%s is in the shipped sonar_hears tag with a follow range of %s, under the %s blocks a ping is heard: it would come only "
                            + "part of the way. Set Attributes.FOLLOW_RANGE to at least SonarTuning.DEFAULT.noiseBlocks() in its attribute "
                            + "builder (the lure never sets it), or take it out of the tag.",
                    id,
                    range,
                    TUNING.noiseBlocks());
        }
        helper.succeed();
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
            expect(helper, ping.hears(origin.add(ping.noiseBlocks(), 0, 0)), "something 48 blocks off is in the noise");
            expect(
                    helper,
                    !ping.hears(origin.add(ping.noiseBlocks() + 0.01, 0, 0)),
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
