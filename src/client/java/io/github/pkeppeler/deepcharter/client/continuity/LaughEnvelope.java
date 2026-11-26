package io.github.pkeppeler.deepcharter.client.continuity;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.pkeppeler.deepcharter.DeepCharter;
import java.io.IOException;
import java.io.Reader;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * How loud the laugh is, moment by moment, 0 to 1: what the skull's jaw and glitches follow. Made with the sound by {@code
 * tools/sounds/okeh_laugh.py} and shipped beside it ({@code continuity/laugh_envelope.json}), so a pack that replaces the laugh replaces
 * this too. Its length is the laugh's, which is when the RETURN TO WORK button appears.
 *
 * @param framesPerSecond values a second
 * @param seconds         the laugh's length
 * @param values          the loudness, one value a frame
 */
@SuppressWarnings(
        "ArrayRecordComponent") // the values are never changed after load, and two envelopes are never compared
record LaughEnvelope(int framesPerSecond, double seconds, float[] values) {
    static final Identifier FILE =
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "continuity/laugh_envelope.json");
    /** What a missing or broken file is read as: a steady laugh of this long, so the sequence still ends in its button. */
    private static final double FALLBACK_SECONDS = 3.68;
    /**
     * The longest laugh a pack may ship: nothing can be skipped until it ends, so a longer one is read as broken. {@link
     * ContinuityScreen} also caps the whole sequence, whatever the data.
     */
    static final double MAX_SECONDS = 20.0;
    /** The most values a second a pack may give: far more than a frame rate, so a bigger number is a mistake. */
    private static final int MAX_FRAMES_PER_SECOND = 1000;

    private static final float FALLBACK_LOUDNESS = 0.5f;

    /** The loudness {@code seconds} into the laugh, between its frames; 0 outside it. */
    float at(double seconds) {
        if (seconds < 0 || seconds >= this.seconds || values.length == 0) {
            return 0f;
        }
        double frame = seconds * framesPerSecond;
        int index = Math.min(values.length - 1, (int) frame);
        int next = Math.min(values.length - 1, index + 1);
        float frac = (float) (frame - index);
        return values[index] * (1 - frac) + values[next] * frac;
    }

    /** The envelope the resource packs give, or a steady one, logged, when the file is missing or cannot be read. */
    static LaughEnvelope load(ResourceManager resources) {
        Optional<Resource> resource = resources.getResource(FILE);
        if (resource.isEmpty()) {
            DeepCharter.LOGGER.error("No {}: the Continuity Event's skull holds still", FILE);
            return steady();
        }
        try (Reader reader = resource.get().openAsReader()) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            JsonArray array = json.getAsJsonArray("values");
            int rate = json.get("framesPerSecond").getAsInt();
            // Gson reads leniently: NaN and Infinity load as numbers, so every bound is written to fail for them.
            double seconds = json.get("seconds").getAsDouble();
            if (!(rate > 0 && rate <= MAX_FRAMES_PER_SECOND)) {
                throw new IllegalStateException("framesPerSecond must be from 1 to " + MAX_FRAMES_PER_SECOND);
            }
            if (!(seconds > 0 && seconds <= MAX_SECONDS)) {
                throw new IllegalStateException(
                        "seconds must be more than 0 and at most " + MAX_SECONDS + ", got " + seconds);
            }
            float[] values = new float[array.size()];
            for (int i = 0; i < values.length; i++) {
                float value = array.get(i).getAsFloat();
                if (!Float.isFinite(value)) {
                    throw new IllegalStateException("value " + i + " is not a number");
                }
                values[i] = Math.clamp(value, 0f, 1f);
            }
            return new LaughEnvelope(rate, seconds, values);
        } catch (IOException | RuntimeException broken) {
            DeepCharter.LOGGER.error("{} cannot be read: the Continuity Event's skull holds still", FILE, broken);
            return steady();
        }
    }

    private static LaughEnvelope steady() {
        return new LaughEnvelope(1, FALLBACK_SECONDS, new float[] {FALLBACK_LOUDNESS, FALLBACK_LOUDNESS});
    }
}
