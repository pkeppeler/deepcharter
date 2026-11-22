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
 * The most tests one GameTest batch holds (#1026). A batch runs its tests at once and keeps every chunk they load until it ends, so
 * the live set grows with its size: 20 to 60 MB for each test that reaches the tall campaign level, through {@link TestLayers} or a
 * dimension, over a floor of 0.4 to 0.9 GB. Vanilla batches 50 and peaked at 1.5 to 1.7 GB. A test that reaches the campaign level
 * through {@link TestLayers} names no dimension, so no environment can bound it; the cap bounds every batch, with
 * {@code GameTestBatchFactoryMixin}, and {@code TestBatchesTest} checks the batches that came out. {@code gameTestHeap}
 * ({@code gradle/run-jvm.gradle.kts}) is measured with it: raise a cap and measure again.
 *
 * <p>A campaign environment ({@link CampaignEnvironments}) holds the heaviest tests, so its batches are smaller. 6 everywhere also
 * kept the peak under 1.45 GB, but short batches tick faster than the light and chunk threads work, and tests that race them failed
 * more often (#1049).
 */
public final class TestBatches {
    /** The cap of a batch of any other environment. */
    public static final int MAX_TESTS = 20;

    /** The cap of a batch of a {@link CampaignEnvironments} environment. */
    public static final int MAX_CAMPAIGN_TESTS = 6;

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
