package io.github.pkeppeler.deepcharter.test.support;

/**
 * The environments of the campaign world's tests (#957). Each is a batch of its own, so one batch of every campaign test no longer keeps
 * the world's chunks live together. A test names one in {@code @GameTest(dimension = ..., environment = ...)}.
 */
public final class CampaignEnvironments {
    public static final String A = "deepcharter-test:campaign_a";
    public static final String B = "deepcharter-test:campaign_b";
    public static final String C = "deepcharter-test:campaign_c";
    public static final String D = "deepcharter-test:campaign_d";

    /**
     * A batch of its own for a one-tick {@code WorldData.with} swap that loads chunks on the server thread (#1016): the
     * load also finishes the pending loads of tests beside it under the swapped data. Loaders: a direct
     * {@code getChunk}, {@code FounderStatue.restoreHands}, {@code Teleports} and {@code ColonyBuilder}.
     * {@code EntityQueryAuditTest}'s two hands-given-again cases swap round {@code restoreHands} but wait many ticks
     * for unloads ({@code UnloadedChunk.cycle}), so they would bring pending loads into this batch and stay in the
     * default one.
     */
    public static final String SYNC_CHUNK_LOAD = "deepcharter-test:campaign_sync_chunk_load";

    private CampaignEnvironments() {}
}
