package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.test.mixin.ChunkTaskPriorityQueueAccess;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ChunkTaskPriorityQueue;
import net.minecraft.world.level.ChunkPos;

/**
 * Tasks queued at one chunk run in the order they were queued, whatever the chunk's level changes
 * ({@code ChunkTaskPriorityQueueMixin}, #1448, ADR 0105). Each case drives a queue of its own on the server thread, as the
 * dispatcher's one thread would.
 */
public class ChunkTaskQueueOrderTest {
    private static final ChunkPos A = new ChunkPos(3, -4);
    private static final ChunkPos B = new ChunkPos(-7, 11);
    private static final ChunkPos C = new ChunkPos(20, 20);

    /** A queue and the order its tasks ran in. */
    private static final class Run {
        final ChunkTaskPriorityQueue queue = new ChunkTaskPriorityQueue("chunk_task_queue_order_test");
        final List<String> ran = new ArrayList<>();

        void submit(String name, ChunkPos pos, int level) {
            ((ChunkTaskPriorityQueueAccess) queue).deepcharter$submit(() -> ran.add(name), pos.pack(), level);
        }

        void resort(int oldLevel, ChunkPos pos, int newLevel) {
            ((ChunkTaskPriorityQueueAccess) queue).deepcharter$resortChunkTasks(oldLevel, pos, newLevel);
        }

        /** Pops and runs every task, as the dispatcher does, and returns the order they ran in. */
        List<String> drain() {
            ChunkTaskPriorityQueue.TasksForChunk tasks;
            while ((tasks = queue.pop()) != null) {
                tasks.tasks().forEach(Runnable::run);
            }
            return ran;
        }
    }

    private static void expectOrder(GameTestHelper helper, Run run, List<String> expected, String what) {
        List<String> ran = run.drain();
        if (!ran.equals(expected)) {
            throw failure(helper, "%s: tasks ran %s, expected %s", what, ran, expected);
        }
        if (run.queue.hasWork()) {
            throw failure(helper, "%s: the queue still has work after it was drained", what);
        }
    }

    /**
     * The race, set deterministically: the worldgen dispatcher has already moved the chunk to level 5, so the light
     * dispatcher's resort reads 5 as the old level while the light task is still at 10.
     */
    @GameTest
    public void aResortThatReadsTheNewLevelKeepsTheChunksTasksInOrder(GameTestHelper helper) {
        Run run = new Run();
        run.submit("t1", A, 10);
        run.resort(5, A, 5);
        run.submit("t2", A, 5);
        expectOrder(helper, run, List.of("t1", "t2"), "a stale resort then a submit at the new level");
        helper.succeed();
    }

    /** Vanilla's own path: a resort from the right old level moves the chunk's tasks ahead of a chunk now below it. */
    @GameTest
    public void aResortFromTheRightLevelMovesTheTasks(GameTestHelper helper) {
        Run run = new Run();
        run.submit("a1", A, 10);
        run.submit("b1", B, 7);
        run.resort(10, A, 5);
        run.submit("a2", A, 5);
        expectOrder(helper, run, List.of("a1", "a2", "b1"), "a resort from 10 to 5");
        helper.succeed();
    }

    /** Other chunks keep their own levels and order when one chunk's tasks move, up or down. */
    @GameTest
    public void otherChunksAreUnaffected(GameTestHelper helper) {
        Run run = new Run();
        run.submit("a1", A, 10);
        run.submit("b1", B, 10);
        run.submit("c1", C, 20);
        run.resort(10, A, 2);
        run.submit("b2", B, 10);
        run.submit("c2", C, 20);
        expectOrder(helper, run, List.of("a1", "b1", "b2", "c1", "c2"), "chunk A moved up past B and C");

        Run down = new Run();
        down.submit("a1", A, 3);
        down.submit("b1", B, 8);
        down.resort(3, A, 12);
        down.submit("a2", A, 12);
        expectOrder(helper, down, List.of("b1", "a1", "a2"), "chunk A, the top chunk, moved down below B");
        helper.succeed();
    }

    /** A chunk queued again at a lower priority takes its earlier tasks with it, and the top level moves on. */
    @GameTest
    public void aSubmitAtLowerPriorityTakesTheChunksEarlierTasks(GameTestHelper helper) {
        Run run = new Run();
        run.submit("a1", A, 3);
        run.submit("b1", B, 8);
        run.submit("a2", A, 12);
        expectOrder(helper, run, List.of("b1", "a1", "a2"), "chunk A queued again at 12");
        helper.succeed();
    }
}
