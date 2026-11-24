package io.github.pkeppeler.deepcharter.test.support;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The most tests one GameTest batch holds (#1026). A batch keeps every chunk its tests load until it ends, so the live set grows with
 * its size: 20 to 60 MB for each test that reaches the tall campaign level over a floor of 0.4 to 0.9 GB. Vanilla's 50 peaked at 1.5
 * to 1.7 GB. A test that reaches the campaign level through {@link TestLayers} names no dimension, so no environment bounds it; this
 * cap does, through {@code GameTestBatchFactoryMixin}, and {@code TestBatchesTest} checks the result. Raise a cap and re-measure
 * {@code gameTestHeap} ({@code gradle/run-jvm.gradle.kts}).
 *
 * <p>40 rather than 20 (#823): fewer batches cost 5% of the suite (708 s against 745 s, a spread of 20 s), for 7% more live heap (1907 MB
 * against 1781 MB at 1650 tests). A cap of 12 for a campaign environment saved nothing beyond that spread, so it stays 6.
 *
 * <p>A campaign environment ({@link CampaignEnvironments}) holds the heaviest tests, so its batches are smaller. 6 everywhere also
 * kept the peak under 1.45 GB, but tests that race the light and chunk threads failed more often in short batches (#1049).
 */
public final class TestBatches {
    private static final int MAX_TESTS = 40;

    private static final int MAX_CAMPAIGN_TESTS = 6;

    private static final Logger LOGGER = LoggerFactory.getLogger(TestBatches.class);

    private static final List<String> CAMPAIGN = Arrays.stream(CampaignEnvironments.class.getFields())
            .filter(field -> Modifier.isStatic(field.getModifiers()) && field.getType() == String.class)
            .map(TestBatches::valueOf)
            .toList();

    /** The most tests the batching put in one batch of each environment, by environment id. */
    private static final Map<String, Integer> LARGEST = new ConcurrentHashMap<>();

    private TestBatches() {}

    /** Called by the batching with the key of the batch: the cap that replaces vanilla's 50. */
    public static int cap(BatchKeyView key) {
        String environment = environmentId(key.environment());
        int cap = limit(environment);
        LOGGER.info("batches of {} hold at most {} tests", environment, cap);
        return cap;
    }

    /** The most tests a batch of {@code environment} may hold. */
    public static int limit(String environment) {
        return CAMPAIGN.contains(environment) ? MAX_CAMPAIGN_TESTS : MAX_TESTS;
    }

    /** Called with the batches the runner made. */
    public static void record(List<GameTestBatch> batches) {
        for (GameTestBatch batch : batches) {
            LARGEST.merge(
                    environmentId(batch.environment()), batch.gameTestInfos().size(), Math::max);
        }
    }

    /** The most tests one batch of each environment holds, by environment id, over the batches the runner made in this JVM. */
    public static Map<String, Integer> largest() {
        return Map.copyOf(LARGEST);
    }

    private static String environmentId(Holder<?> environment) {
        return environment.unwrapKey().orElseThrow().identifier().toString();
    }

    private static String valueOf(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
