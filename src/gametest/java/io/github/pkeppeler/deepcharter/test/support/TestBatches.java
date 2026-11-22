package io.github.pkeppeler.deepcharter.test.support;

/**
 * The most tests one GameTest batch holds (#1026). A batch runs its tests at once and keeps every chunk they load until it ends, so
 * the live set grows with its size: 20 to 60 MB for each test that reaches the tall campaign level, through {@link TestLayers} or a
 * dimension. Vanilla batches 50, and the batches that peaked held 6 to 12 thousand chunks of that level. A test that reaches the
 * campaign level through {@link TestLayers} names no dimension, so no environment can bound it; the cap bounds every batch, with
 * {@code GameTestBatchFactoryMixin}, and {@code TestBatchesTest} fails the run if the cap did not apply.
 */
public final class TestBatches {
    /** The cap. {@code gameTestHeap} ({@code gradle/run-jvm.gradle.kts}) is measured with it. */
    public static final int MAX_TESTS = 6;

    private static volatile boolean applied;

    private TestBatches() {}

    /** Called by the batching: the cap that replaces vanilla's 50, and the proof that the batching asked for it. */
    public static int cap() {
        applied = true;
        return MAX_TESTS;
    }

    /** Whether the runner's batching has asked for the cap in this JVM. */
    public static boolean applied() {
        return applied;
    }
}
