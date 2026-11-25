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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fails the running test whose pod, or the player riding it, drives into a chunk that is not entity-ticking (#1089):
 * the game stops such an entity dead at the border. {@code GameTestTickerMixin} calls {@link #scan} once per server
 * tick.
 *
 * <p>An entity is the test's own when its chunk lies in the test's structure bounds or in a chunk the test holds
 * ({@link FarChunks}); one that belongs to no test or to two is nobody's. A move is a drive when {@code
 * getDeltaMovement()} explains its length; a {@code setPos} or a teleport is not. A test about a chunk that must not
 * tick calls {@link FarChunks#allowUnticked}.
 */
public final class EntityTickingGate {
    /**
     * Blocks of slack on top of the velocity: a move is the velocity before friction, gravity and a clip against a
     * wall.
     */
    private static final double SLACK = 0.05;

    /**
     * How many times its velocity an entity may move in one tick and still be driven (friction and gravity change the
     * velocity).
     */
    private static final double VELOCITY_FACTOR = 1.5;

    /** Where an entity was at the last scan, and how fast it was going. */
    private record Seen(Vec3 pos, double speed) {}

    /** Server thread only. */
    private static final Map<Entity, Seen> LAST_SEEN = new WeakHashMap<>();

    private static final Set<AABB> ALLOWED = new HashSet<>();

    /**
     * The bounds of the tests whose failure {@code EntityTickingGateTest} will read; only these have their message
     * kept.
     */
    private static final Set<AABB> EXPECTED = new HashSet<>();

    /** The message the gate failed each of {@link #EXPECTED} with. */
    private static final Map<AABB, String> FAILED = new HashMap<>();

    private static final Logger LOGGER = LoggerFactory.getLogger(EntityTickingGate.class);

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

    /**
     * Keeps the message the gate fails the test with these bounds with, for {@link #failureOf}. For {@code
     * EntityTickingGateTest}.
     */
    public static void expectFailure(AABB testBounds) {
        EXPECTED.add(testBounds);
        FAILED.remove(testBounds);
    }

    /**
     * The message the gate failed the test with these bounds with, once it has; the entry is dropped when it is read.
     * For {@code EntityTickingGateTest}.
     */
    public static @Nullable String failureOf(AABB testBounds) {
        String message = FAILED.remove(testBounds);
        if (message != null) {
            EXPECTED.remove(testBounds);
        }
        return message;
    }

    /**
     * What is wrong with a move of {@code who} from {@code from} to {@code to}, if anything: a drive into a chunk that
     * is not entity-ticking. A move longer than {@code speed} explains is a reposition, and is not a drive.
     */
    private static Optional<String> problem(ServerLevel level, Entity who, Vec3 from, Vec3 to, double speed) {
        BlockPos entered = BlockPos.containing(to);
        ChunkPos chunk = ChunkPos.containing(entered);
        if (level.isPositionEntityTicking(entered) || to.distanceTo(from) > speed * VELOCITY_FACTOR + SLACK) {
            return Optional.empty();
        }
        return Optional.of(String.format(
                "%s drove from %s into chunk %s of %s, which is not entity-ticking, so the game stops it at the border."
                        + " Await every chunk the drive will enter with"
                        + " FarChunks.awaitAllEntityTicking(helper, level, positions, then)"
                        + " and start the drive in `then`; a test about a chunk that must not tick calls"
                        + " FarChunks.allowUnticked(helper, reason)",
                who.getName().getString(), from, chunk, level.dimension()));
    }

    /**
     * Checks every pod and player in the levels of the running tests, and fails the test that owns the first to drive
     * into a chunk that is not ticking.
     */
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
        Set<AABB> failed = new HashSet<>();
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
                if (owner == null) {
                    if (!level.isPositionEntityTicking(BlockPos.containing(now))) {
                        LOGGER.info(
                                "{} moved from {} into a chunk that is not entity-ticking,"
                                        + " and no running test holds either chunk",
                                entity.getName().getString(),
                                before.pos());
                    }
                    continue;
                }
                if (ALLOWED.contains(owner.bounds()) || failed.contains(owner.bounds())) {
                    continue;
                }
                double speed =
                        Math.max(before.speed(), entity.getDeltaMovement().length());
                Optional<String> problem = problem(level, entity, before.pos(), now, speed);
                if (problem.isPresent()) {
                    failed.add(owner.bounds());
                    if (EXPECTED.contains(owner.bounds())) {
                        FAILED.put(owner.bounds(), problem.get());
                    }
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

    /**
     * The one running test of {@code level} that owns the entity, by where it was, else by where it is; null when none
     * or several do.
     */
    private static @Nullable Running owner(List<Running> tests, ServerLevel level, Vec3 before, Vec3 now) {
        Running byBefore = ownerOf(tests, level, before);
        return byBefore != null ? byBefore : ownerOf(tests, level, now);
    }

    private static @Nullable Running ownerOf(List<Running> tests, ServerLevel level, Vec3 at) {
        ChunkPos chunk = ChunkPos.containing(BlockPos.containing(at));
        List<Running> inLevel =
                tests.stream().filter(test -> test.info().getLevel() == level).toList();
        int owner = ownerOf(
                inLevel.stream()
                        .map(test ->
                                new Claim(test.bounds(), FarChunks.holds(test.bounds(), level, chunk.x(), chunk.z())))
                        .toList(),
                at);
        return owner < 0 ? null : inLevel.get(owner);
    }

    /** What a running test claims of a position: its structure bounds, and whether it holds the position's chunk. */
    public record Claim(AABB bounds, boolean holdsChunk) {}

    /**
     * The index of the one claim that owns {@code at}, or -1 when none or several do. The tests whose bounds contain it
     * own it; if none does, the tests that hold its chunk do. One test only: two owners are nobody's, so no neighbour
     * is blamed.
     */
    public static int ownerOf(List<Claim> claims, Vec3 at) {
        List<Integer> inBounds = new ArrayList<>();
        List<Integer> holders = new ArrayList<>();
        for (int i = 0; i < claims.size(); i++) {
            if (claims.get(i).bounds().contains(at)) {
                inBounds.add(i);
            }
            if (claims.get(i).holdsChunk()) {
                holders.add(i);
            }
        }
        List<Integer> owners = inBounds.isEmpty() ? holders : inBounds;
        return owners.size() == 1 ? owners.getFirst() : -1;
    }
}
