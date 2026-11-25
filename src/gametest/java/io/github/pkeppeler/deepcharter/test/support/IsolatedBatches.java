package io.github.pkeppeler.deepcharter.test.support;

/**
 * Batches of one test each, for a test whose subject is a world-global drain ({@code LayerStructures.drawPending}) that any test
 * firing a chunk load, {@code BEGUN} or {@code SERVER_STARTED} also runs. Tests of one batch run together and batches run one after
 * another, so a test in a batch of its own is the only one that can draw the chunks it waits on, and it cannot pass for another's work.
 */
public final class IsolatedBatches {
    public static final String STRUCTURE_DRAIN_SWAP = "deepcharter-test:isolated_structure_drain_swap";
    public static final String STRUCTURE_DRAIN_STARTED = "deepcharter-test:isolated_structure_drain_started";
    public static final String STRUCTURE_DRAIN_BEGUN = "deepcharter-test:isolated_structure_drain_begun";

    private IsolatedBatches() {}
}
