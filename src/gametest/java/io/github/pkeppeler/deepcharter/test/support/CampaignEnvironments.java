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

    /** A batch of its own for a case that loads its chunks on the server thread (a same-tick {@code WorldData.with}), which would starve a test beside it (#1016). */
    public static final String SYNC_CHUNK_LOAD = "deepcharter-test:campaign_sync_chunk_load";

    /**
     * A batch of its own for {@code OnFootForceSendTest}, which counts a log line of every player on the server
     * ({@link ForceSends}): a player of a test beside it would add to the count (#1317).
     */
    public static final String FORCE_SENDS = "deepcharter-test:campaign_force_sends";

    private CampaignEnvironments() {}
}
