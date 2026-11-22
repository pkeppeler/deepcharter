package io.github.pkeppeler.deepcharter.test.support;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The most tests one GameTest batch holds (#1026). A batch runs its tests at once and keeps every chunk they load until it ends, so
 * the live set grows with its size: 20 to 60 MB for each test that reaches the tall campaign level, through {@link TestLayers} or a
 * dimension. Vanilla batches 50, and the batches that peaked held 6 to 12 thousand chunks of that level. A test that reaches the
 * campaign level through {@link TestLayers} names no dimension, so no environment can bound it; the cap bounds every batch, with
 * {@code GameTestBatchFactoryMixin}, and {@code TestBatchesTest} fails the run if the cap did not apply.
 *
 * <p>A campaign environment ({@link CampaignEnvironments}) holds the heaviest tests, so its batches are smaller. A cap lower than this
 * one makes batches shorter, so the server ticks faster than the light and chunk threads: tests that race them (#1026) failed more.
 */
public final class TestBatches {
    /** The cap of a batch of any other environment. {@code gameTestHeap} ({@code gradle/run-jvm.gradle.kts}) is measured with it. */
    public static final int MAX_TESTS = 20;

    /** The cap of a batch of a {@link CampaignEnvironments} environment. */
    public static final int MAX_CAMPAIGN_TESTS = 6;

    private static final Logger LOGGER = LoggerFactory.getLogger(TestBatches.class);

    private static final List<String> CAMPAIGN = Arrays.stream(CampaignEnvironments.class.getFields())
            .filter(field -> Modifier.isStatic(field.getModifiers()) && field.getType() == String.class)
            .map(TestBatches::valueOf)
            .toList();

    /** Each batch key the batching has sized, with the cap it was given. */
    private static final Map<String, Integer> SIZED = new ConcurrentHashMap<>();

    private TestBatches() {}

    /** Called by the batching with the key of the batch (its environment and dimension): the cap that replaces vanilla's 50. */
    public static int cap(String batchKey) {
        int cap = CAMPAIGN.stream().anyMatch(batchKey::contains) ? MAX_CAMPAIGN_TESTS : MAX_TESTS;
        if (SIZED.put(batchKey, cap) == null) {
            LOGGER.info("batches of {} hold at most {} tests", batchKey, cap);
        }
        return cap;
    }

    /** The cap of a batch whose environment is not known. */
    public static int cap() {
        return MAX_TESTS;
    }

    /** Every batch key the batching has sized in this JVM, with its cap. */
    public static Map<String, Integer> sized() {
        return Map.copyOf(SIZED);
    }

    /** The environments the campaign tests run in. */
    public static List<String> campaignEnvironments() {
        return CAMPAIGN;
    }

    private static String valueOf(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
