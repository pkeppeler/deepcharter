package io.github.pkeppeler.deepcharter.pod;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * The lore mark of a pod, the body of a {@code Versioned<PodLore>}: whether it is the wreck that holds
 * {@link Serials#FAMOUS_PROSPECTOR} (ADR 0027, amended 2026-10-11).
 *
 * @param famousProspector true from the wreck's placing until a restore registers it
 */
public record PodLore(boolean famousProspector) {
    /** The saved version; 1 is the first. */
    public static final int VERSION = 1;

    public static final PodLore NONE = new PodLore(false);
    public static final PodLore FAMOUS_PROSPECTOR = new PodLore(true);
    public static final MapCodec<PodLore> BODY = RecordCodecBuilder.mapCodec(
            instance -> instance.group(Codec.BOOL.fieldOf("famous_prospector").forGetter(PodLore::famousProspector))
                    .apply(instance, PodLore::new));
}
