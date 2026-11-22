package io.github.pkeppeler.deepcharter.test.support;

/**
 * The GameTest environments the campaign world's tests run in (#957). A batch holds the tests of one environment, up to 50, and all of
 * them run at once, so one batch of every campaign test kept about 150 forced chunks of the 2,352-row world live together. Each
 * environment is a batch of its own, so the world's chunks of one group are free to unload before the next group starts. A campaign
 * test names its group in {@code @GameTest(dimension = ..., environment = ...)}; the JSON of each is under
 * {@code data/deepcharter-test/test_environment/}.
 */
public final class CampaignEnvironments {
    public static final String A = "deepcharter-test:campaign_a";
    public static final String B = "deepcharter-test:campaign_b";
    public static final String C = "deepcharter-test:campaign_c";
    public static final String D = "deepcharter-test:campaign_d";

    private CampaignEnvironments() {}
}
