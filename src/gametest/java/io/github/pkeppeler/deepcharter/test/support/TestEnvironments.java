package io.github.pkeppeler.deepcharter.test.support;

/**
 * The GameTest environments of this mod (data/deepcharter-test/test_environment). The runner batches tests by environment and dimension,
 * up to 50 to a batch, and runs the batches one after another; tests of a batch share one tick thread.
 */
public final class TestEnvironments {
    private TestEnvironments() {}

    /**
     * For a test that generates a tall world in volume on the server thread (a survey of dozens of chunks of {@code campaign_sample}, 4096
     * height columns): one tick of it takes seconds to minutes, and a test with a wall-clock deadline in the same batch (the light holds of
     * {@code StructureLightBorderTest} and {@code RoomCarverSendTest}) runs out of time while it waits. Heavy tests share a batch only with
     * each other. A heavy test keeps its dimension.
     */
    public static final String HEAVY = "deepcharter-test:heavy";
}
