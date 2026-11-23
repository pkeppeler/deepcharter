package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.pod.PodEntity;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Fails a running test whose pod or player drives into a chunk that is not entity-ticking (#1083). The game stops such an entity dead at
 * the border, so the test sees a pod that "covered 0.0 blocks" and fails, or worse passes, depending on which neighbour tests keep the
 * adjacent chunks loaded. {@code GameTestTickerMixin} calls {@link #scan} once per server tick for every running test.
 *
 * <p>A step of at most {@link #MAX_STEP} blocks across a chunk border is a drive; a longer one is a teleport, which the gate leaves
 * alone (a teleported entity is not stalled by the chunk it left). A test that crosses into a chunk on purpose calls
 * {@link FarChunks#allowUnticked}.
 */
public final class EntityTickingGate {
    /** The longest move between two scans that counts as a drive, not a teleport. A pod covers 0.2 blocks a tick. */
    public static final double MAX_STEP = 4;

    /** How far past a test's structure a pod or player is still its own. */
    private static final double REACH = 24;

    /** Where each entity was at the last scan. Server thread only. */
    private static final Map<Entity, Vec3> LAST_SEEN = new WeakHashMap<>();

    private static final Set<AABB> ALLOWED = new HashSet<>();

    private EntityTickingGate() {}

    /** Lets the test with these bounds drive into chunks that are not entity-ticking. */
    static void allow(AABB testBounds) {
        ALLOWED.add(testBounds);
    }

    /** Forgets the test's opt-out once it has finished. */
    static void forget(AABB testBounds) {
        ALLOWED.remove(testBounds);
    }

    /**
     * What is wrong with a drive from {@code from} to {@code to} in {@code level}, if anything: the move crosses into a chunk that is
     * not entity-ticking. Public for {@code EntityTickingGateTest}.
     */
    public static Optional<String> problem(ServerLevel level, Entity who, Vec3 from, Vec3 to) {
        if (to.distanceToSqr(from) > MAX_STEP * MAX_STEP) {
            return Optional.empty();
        }
        BlockPos entered = BlockPos.containing(to);
        ChunkPos chunk = ChunkPos.containing(entered);
        if (chunk.equals(ChunkPos.containing(BlockPos.containing(from))) || level.isPositionEntityTicking(entered)) {
            return Optional.empty();
        }
        return Optional.of(String.format(
                "%s drove from %s into chunk %s of %s, which is not entity-ticking, so the game stops it at the border."
                        + " Await every chunk the drive or the ring will enter with FarChunks.awaitEntityTicking(helper, level, positions, then)"
                        + " and start the drive in `then`, or call FarChunks.allowUnticked(helper, reason) if the test is about a chunk"
                        + " that does not tick",
                who.getName().getString(), from, chunk, level.dimension()));
    }

    /** Checks every pod and player near a running test, and fails the test on the first that drove into a chunk that is not ticking. */
    public static void scan(GameTestInfo info) {
        if (info.isDone()) {
            return;
        }
        AABB bounds;
        try {
            bounds = info.getStructureBounds();
        } catch (IllegalStateException noTestBlock) {
            return;
        }
        ServerLevel level = info.getLevel();
        for (Entity entity : level.getEntities((Entity) null, bounds.inflate(REACH), EntityTickingGate::watched)) {
            Vec3 now = entity.position();
            Vec3 before = LAST_SEEN.put(entity, now);
            if (before == null || ALLOWED.contains(bounds)) {
                continue;
            }
            Optional<String> problem = problem(level, entity, before, now);
            if (problem.isPresent()) {
                info.fail(Component.literal(problem.get()));
                return;
            }
        }
    }

    private static boolean watched(Entity entity) {
        return entity instanceof PodEntity || entity instanceof ServerPlayer;
    }
}
