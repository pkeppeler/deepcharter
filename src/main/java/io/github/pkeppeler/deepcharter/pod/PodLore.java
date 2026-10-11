package io.github.pkeppeler.deepcharter.pod;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * The lore mark of a pod, the body of a {@code Versioned<PodLore>} (so no version field): whether it is the wreck that is
 * {@link Serials#FAMOUS_PROSPECTOR}. The world sets the mark when it places the wreck, and the hangar's restore reads it and
 * clears it, so the number is registered once. The pod's name is for display only (ADR 0027, amended 2026-10-11).
 *
 * @param famousProspector true from the wreck site's placing until a restore registers the pod under its own serial
 */
public record PodLore(boolean famousProspector) {
	/** The saved version; 1 is the first. */
	public static final int VERSION = 1;
	public static final PodLore NONE = new PodLore(false);
	public static final PodLore FAMOUS_PROSPECTOR = new PodLore(true);
	public static final MapCodec<PodLore> BODY = RecordCodecBuilder.mapCodec(instance -> instance.group(
			Codec.BOOL.fieldOf("famous_prospector").forGetter(PodLore::famousProspector)).apply(instance, PodLore::new));
}
