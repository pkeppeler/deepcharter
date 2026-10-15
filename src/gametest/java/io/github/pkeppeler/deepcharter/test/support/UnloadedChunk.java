package io.github.pkeppeler.deepcharter.test.support;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;

/**
 * One trip of a far chunk through "loaded, unloaded, loaded again", for a test of what a query that sees only loaded chunks gets wrong
 * (m2-rules.md: never gate gameplay on an entity query). A GameTest cannot reload the world, so the chunk stops being forced, the test
 * waits until it is not loaded (which saves it with its entities), and a force loads it again.
 *
 * <p>The test method calls {@link #cycle} and gives itself {@code 2 * FarChunks.AWAIT_BUDGET_TICKS} ticks, plus the test's own work.
 * Pick a column no other test uses: the chunk is left forced.
 */
public final class UnloadedChunk {
    private UnloadedChunk() {}

    /**
     * What the test does at each stage. Every stage runs on the server thread, in one tick.
     *
     * @param whileLoaded     runs once the chunk ticks: put the entities there
     * @param whileUnloaded   runs once the chunk is not loaded: ask the question that must not depend on it
     * @param backWhen        holds once the chunk's entities have loaded again
     * @param backWhat        what {@code backWhen} waits for and what is there instead, named in the failure after {@link FarChunks#WAIT_SECONDS} seconds
     * @param afterReload     runs once {@code backWhen} holds: check what loading did, and clean up
     */
    public record Stages(
            Runnable whileLoaded,
            Runnable whileUnloaded,
            BooleanSupplier backWhen,
            Supplier<String> backWhat,
            Runnable afterReload) {}

    /** Call from the test method, which succeeds when the last stage returns. */
    public static void cycle(GameTestHelper helper, ServerLevel level, BlockPos far, Stages stages) {
        int chunkX = far.getX() >> 4;
        int chunkZ = far.getZ() >> 4;
        int[] phase = {0};
        FarChunks.Deadline[] by = {null};
        TestTicks.each(helper, () -> {
            switch (phase[0]) {
                case 1 -> {
                    stages.whileLoaded().run();
                    level.setChunkForced(chunkX, chunkZ, false);
                    by[0] = FarChunks.deadline();
                    phase[0] = 2;
                }
                case 2 -> {
                    if (by[0].awaitUnloaded(helper, level, chunkX, chunkZ)) {
                        stages.whileUnloaded().run();
                        level.setChunkForced(chunkX, chunkZ, true);
                        by[0] = FarChunks.deadline();
                        phase[0] = 3;
                    }
                }
                case 3 -> {
                    boolean back = stages.backWhen().getAsBoolean();
                    by[0].await(
                            helper,
                            level,
                            back,
                            () -> "the chunk's entities did not load again: "
                                    + stages.backWhat().get());
                    if (back) {
                        phase[0] = 4;
                        stages.afterReload().run();
                        helper.succeed();
                    }
                }
                default -> {}
            }
        });
        FarChunks.awaitEntityTicking(helper, level, far, () -> phase[0] = 1);
    }
}
