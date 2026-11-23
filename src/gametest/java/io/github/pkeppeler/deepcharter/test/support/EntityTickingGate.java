package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Fails the running test whose pod or player drives into a chunk that is not entity-ticking (#1089). The game stops such an entity dead
 * at the border, so the test sees a pod that "covered 0.0 blocks", and which neighbour tests keep the next chunk ticking decides
 * whether it passes. {@code GameTestTickerMixin} calls {@link #scan} once per server tick with the running tests.
 *
 * <p>An entity is the test's own when its chunk lies in the test's structure bounds or in a chunk the test holds
 * ({@link FarChunks}); one that belongs to no test or to two is nobody's, and is not blamed. A move is a drive when its length is
 * what the entity's {@code getDeltaMovement()} explains; a {@code setPos} or a teleport is not, and the gate leaves it alone (a
 * teleported entity is not stalled by the chunk it left). A test about a chunk that must not tick calls
 * {@link FarChunks#allowUnticked}.
 */
public final class EntityTickingGate {
    /** Blocks of slack on top of the velocity: a move is the velocity before friction, gravity and a clip against a wall. */
    private static final double SLACK = 0.05;

    /** How many times its velocity an entity may move in one tick and still be driven (friction and gravity change the velocity). */
    private static final double VELOCITY_FACTOR = 1.5;

    /** Where an entity was at the last scan, and how fast it was going. */
    private record Seen(Vec3 pos, double speed) {}

    /** Server thread only. */
    private static final Map<Entity, Seen> LAST_SEEN = new WeakHashMap<>();

    private static final Set<AABB> ALLOWED = new HashSet<>();

    /** The tests the gate has failed, by their bounds, for {@code EntityTickingGateTest} to read. */
    private static final Map<AABB, GameTestInfo> FAILED = new HashMap<>();

    private EntityTickingGate() {}

    /** Lets the test with these bounds drive into chunks that are not entity-ticking. */
    static void allow(AABB testBounds) {
        ALLOWED.add(testBounds);
    }

    /** Forgets the test's opt-out once it has finished. */
    static void forget(AABB testBounds) {
        ALLOWED.remove(testBounds);
    }

    /** Whether the test with these bounds has opted out. For {@code EntityTickingGateTest}. */
    public static boolean isAllowed(AABB testBounds) {
        return ALLOWED.contains(testBounds);
    }

    /** The test with these bounds, if the gate failed it. For {@code EntityTickingGateTest}. */
    public static @Nullable GameTestInfo failureOf(AABB testBounds) {
        return FAILED.get(testBounds);
    }

    /** Forgets that the gate failed the test with these bounds, so a stale failure of an earlier test cannot be read as this one's. */
    public static void clearFailure(AABB testBounds) {
        FAILED.remove(testBounds);
    }

    /**
     * What is wrong with a move of {@code who} from {@code from} to {@code to}, if anything: a drive into a chunk that is not
     * entity-ticking. A move longer than {@code speed} explains is a reposition, and is not a drive.
     */
    static Optional<String> problem(ServerLevel level, Entity who, Vec3 from, Vec3 to, double speed) {
        BlockPos entered = BlockPos.containing(to);
        ChunkPos chunk = ChunkPos.containing(entered);
        if (chunk.equals(ChunkPos.containing(BlockPos.containing(from))) || level.isPositionEntityTicking(entered)) {
            return Optional.empty();
        }
        if (to.distanceTo(from) > speed * VELOCITY_FACTOR + SLACK) {
            return Optional.empty();
        }
        return Optional.of(String.format(
                "%s drove from %s into chunk %s of %s, which is not entity-ticking, so the game stops it at the border."
                        + " Await every chunk the drive will enter with FarChunks.awaitAllEntityTicking(helper, level, positions, then)"
                        + " and start the drive in `then`; a test about a chunk that must not tick calls"
                        + " FarChunks.allowUnticked(helper, reason)",
                who.getName().getString(), from, chunk, level.dimension()));
    }

    /** Checks every pod and player in the levels of the running tests, and fails the test that owns the first to drive into a chunk that is not ticking. */
    public static void scan(Collection<GameTestInfo> running) {
        List<Running> tests = new ArrayList<>();
        Set<ServerLevel> levels = new HashSet<>();
        for (GameTestInfo info : running) {
            if (info.isDone()) {
                continue;
            }
            try {
                tests.add(new Running(info, info.getStructureBounds()));
                levels.add(info.getLevel());
            } catch (IllegalStateException noTestBlock) {
                // The runner finishes a test whose block entity is missing; it never ran its body.
            }
        }
        for (ServerLevel level : levels) {
            for (Entity entity : watched(level)) {
                Vec3 now = entity.position();
                Seen before = LAST_SEEN.put(
                        entity, new Seen(now, entity.getDeltaMovement().length()));
                if (before == null
                        || ChunkPos.containing(BlockPos.containing(now))
                                .equals(ChunkPos.containing(BlockPos.containing(before.pos())))) {
                    continue;
                }
                Running owner = owner(tests, level, before.pos(), now);
                if (owner == null || ALLOWED.contains(owner.bounds())) {
                    continue;
                }
                double speed =
                        Math.max(before.speed(), entity.getDeltaMovement().length());
                Optional<String> problem = problem(level, entity, before.pos(), now, speed);
                if (problem.isPresent()) {
                    FAILED.put(owner.bounds(), owner.info());
                    owner.info().fail(Component.literal(problem.get()));
                }
            }
        }
    }

    private record Running(GameTestInfo info, AABB bounds) {}

    private static List<Entity> watched(ServerLevel level) {
        List<Entity> found = new ArrayList<>(level.players());
        found.addAll(level.getEntities(EntityTypeTest.forClass(PodEntity.class), pod -> true));
        return found;
    }

    /** The one running test of {@code level} that owns the entity, by where it was, else by where it is; null when none or several do. */
    private static @Nullable Running owner(List<Running> tests, ServerLevel level, Vec3 before, Vec3 now) {
        Running byBefore = ownerOf(tests, level, before);
        return byBefore != null ? byBefore : ownerOf(tests, level, now);
    }

    private static @Nullable Running ownerOf(List<Running> tests, ServerLevel level, Vec3 at) {
        List<Running> inBounds = tests.stream()
                .filter(test -> test.info().getLevel() == level && test.bounds().contains(at))
                .toList();
        if (!inBounds.isEmpty()) {
            return inBounds.size() == 1 ? inBounds.getFirst() : null;
        }
        ChunkPos chunk = ChunkPos.containing(BlockPos.containing(at));
        List<Running> holders = tests.stream()
                .filter(test -> FarChunks.holds(test.bounds(), level, chunk.x(), chunk.z()))
                .toList();
        return holders.size() == 1 ? holders.getFirst() : null;
    }
}
