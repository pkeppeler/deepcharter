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

    private CampaignEnvironments() {}
}
