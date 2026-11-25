package io.github.pkeppeler.deepcharter.sound.ambience;

import io.github.pkeppeler.deepcharter.sound.DeepSound;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.sounds.SoundEvent;

/**
 * The fragments, found by the names of their sound events: {@code ambience.<tier>.<fragment>} is one of a tier's pool,
 * {@code ambience.horn.<fragment>} is one of the horn's own pool (#946), and {@link DeepSound#AMBIENCE_HORN_WARMUP} is the horn's crackle, in no pool. A fragment is not an enum of its own: it is a line in
 * {@code DeepSound}, {@code sounds.json} and {@code tools/sounds/ambience.py}, which tests keep in step. Nothing here is a cylinder or a
 * loot entry: the wax cylinders ({@code Recording}) and the ambience share a pipeline and nothing else.
 */
public final class AmbienceFragments {
    private static final String PREFIX = "ambience.";
    /** The name of the punch clock's fragments in the broken tier's pool. */
    private static final String CLOCK = "clock";

    private static final Map<AmbienceTier, List<String>> POOLS = new EnumMap<>(AmbienceTier.class);
    private static final Map<String, DeepSound> SOUNDS = new HashMap<>();
    private static final String HORN_PREFIX = PREFIX + "horn.";
    private static final List<String> HORN_POOL = new ArrayList<>();
    private static final Map<String, DeepSound> HORN_SOUNDS = new HashMap<>();

    static {
        for (DeepSound sound : DeepSound.values()) {
            String path = sound.event().location().getPath();
            if (path.startsWith(HORN_PREFIX)) {
                String fragment = path.substring(HORN_PREFIX.length());
                HORN_POOL.add(fragment);
                HORN_SOUNDS.put(fragment, sound);
            }
            for (AmbienceTier tier : AmbienceTier.values()) {
                String tierPrefix = PREFIX + tier.id() + ".";
                if (path.startsWith(tierPrefix)) {
                    String fragment = path.substring(tierPrefix.length());
                    POOLS.computeIfAbsent(tier, t -> new ArrayList<>()).add(fragment);
                    SOUNDS.put(tier.id() + "." + fragment, sound);
                }
            }
        }
        // The punch clock's deep fragments (#938) are one more voice of the broken tier, named "clock" in it.
        POOLS.computeIfAbsent(AmbienceTier.BROKEN, t -> new ArrayList<>()).add(CLOCK);
        SOUNDS.put(AmbienceTier.BROKEN.id() + "." + CLOCK, DeepSound.AMBIENCE_CLOCK_FRAGMENT);
    }

    private AmbienceFragments() {}

    /** The fragments of each tier, by name. */
    public static Map<AmbienceTier, List<String>> pools() {
        return POOLS;
    }

    /** The punch clock's word for an hour, 1 to 12 but ten, which the recording skips. */
    public static SoundEvent clockHour(int hour) {
        return DeepSound.valueOf("AMBIENCE_CLOCK_HOUR_" + hour).event();
    }

    /** The fragments only a Company horn plays (#946), by name: in no tier's pool, so the dark cannot name them. */
    public static List<String> hornPool() {
        return HORN_POOL;
    }

    /** The sound event of a fragment of the horn's own pool. */
    public static SoundEvent hornSound(String fragment) {
        DeepSound sound = HORN_SOUNDS.get(fragment);
        if (sound == null) {
            throw new IllegalArgumentException("No fragment " + fragment + " in the horn's pool");
        }
        return sound.event();
    }

    /** The sound event of a fragment in a tier. */
    public static SoundEvent sound(AmbienceTier tier, String fragment) {
        DeepSound sound = SOUNDS.get(tier.id() + "." + fragment);
        if (sound == null) {
            throw new IllegalArgumentException("No fragment " + fragment + " in the tier " + tier);
        }
        return sound.event();
    }
}
