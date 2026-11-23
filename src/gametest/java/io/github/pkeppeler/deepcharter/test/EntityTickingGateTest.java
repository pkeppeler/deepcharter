package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.EntityTickingGate;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Server GameTests for {@link EntityTickingGate} (#1083). */
public class EntityTickingGateTest {
    /** Two chunks no other test forces, side by side along x. */
    private static final int CHUNK_X = 4060;

    private static final int CHUNK_Z = 4060;
    /** A block short of the border between the two chunks, and one past it. */
    private static final Vec3 BEFORE = new Vec3((CHUNK_X + 1) * 16 - 0.1, 70, CHUNK_Z * 16 + 8);

    private static final Vec3 AFTER = new Vec3((CHUNK_X + 1) * 16 + 0.1, 70, CHUNK_Z * 16 + 8);

    private static Entity pod(ServerLevel level) {
        return TestEntities.create(PodRegistry.POD, level);
    }

    /**
     * A pod that crosses into a chunk that does not tick is reported, naming the chunk and the fix: this is the stall that stopped a
     * driving pod dead at a border when fewer neighbours kept the next chunk ticking.
     */
    @GameTest
    public void aDriveIntoAChunkThatDoesNotTickIsAProblemNamingTheChunkAndTheFix(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Optional<String> problem = EntityTickingGate.problem(level, pod(level), BEFORE, AFTER);
        if (problem.isEmpty()) {
            throw helper.assertionException(
                    "a drive into the unloaded chunk %s,%s was not reported", CHUNK_X + 1, CHUNK_Z);
        }
        String message = problem.get();
        for (String part :
                List.of("[" + (CHUNK_X + 1) + ", " + CHUNK_Z + "]", "FarChunks.awaitEntityTicking", "allowUnticked")) {
            if (!message.contains(part)) {
                throw helper.assertionException("the message should contain %s: %s", part, message);
            }
        }
        helper.succeed();
    }

    /** A drive inside one chunk, and a move too long to be a drive (a teleport), are not reported even into a chunk that does not tick. */
    @GameTest
    public void aDriveWithinAChunkAndATeleportAreNotAProblem(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 inside = BEFORE.subtract(1, 0, 0);
        Vec3 far = BEFORE.add(EntityTickingGate.MAX_STEP + 1, 0, 0);
        if (EntityTickingGate.problem(level, pod(level), inside, BEFORE).isPresent()) {
            throw helper.assertionException("a drive inside one chunk was reported");
        }
        if (EntityTickingGate.problem(level, pod(level), BEFORE, far).isPresent()) {
            throw helper.assertionException(
                    "a teleport of more than %s blocks was reported", EntityTickingGate.MAX_STEP);
        }
        helper.succeed();
    }

    /** Once both chunks tick the same drive is fine: the fix the message names clears the problem. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS)
    public void theSameDriveIsNoProblemOnceTheChunkTicks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int[] ticking = {0};
        FarChunks.awaitEntityTicking(
                helper, level, List.of(BlockPos.containing(BEFORE), BlockPos.containing(AFTER)), index -> {
                    if (++ticking[0] < 2) {
                        return;
                    }
                    Optional<String> problem = EntityTickingGate.problem(level, pod(level), BEFORE, AFTER);
                    if (problem.isPresent()) {
                        throw helper.assertionException(
                                "the chunks tick, yet the drive was reported: %s", problem.get());
                    }
                    helper.succeed();
                });
    }

    /** An opt-out without a reason is refused: the reason is the point. */
    @GameTest
    public void anOptOutNeedsAReason(GameTestHelper helper) {
        try {
            FarChunks.allowUnticked(helper, " ");
        } catch (IllegalArgumentException expected) {
            helper.succeed();
            return;
        }
        throw helper.assertionException("allowUnticked accepted a blank reason");
    }
}
