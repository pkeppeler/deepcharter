package io.github.pkeppeler.deepcharter.client.pod;

import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.pod.Chassis;

/** The resource-pack ids of a chassis's look: three models a pack can replace (ADR 0033). */
public record PodSkins(Identifier hull, Identifier wreck, Identifier drill) {
	/** The look of {@code chassis}: its ids are derived from the chassis id, so a new chassis needs no edit here. */
	public static PodSkins of(Chassis chassis) {
		return new PodSkins(id(chassis.id()), id(chassis.id() + "_wreck"), id(chassis.id() + "_drill"));
	}

	private static Identifier id(String name) {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod/" + name);
	}
}
