package io.github.pkeppeler.deepcharter.client.pod;

import java.util.List;

import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.pod.Chassis;

/**
 * The resource-pack ids of a chassis's look (ADR 0033). Each id names three files that a pack can replace, and no Java:
 * {@code assets/deepcharter/items/<path>.json} (the item model definition the renderer asks for),
 * {@code assets/deepcharter/models/<path>.json} (the Java block/item model, which Blockbench exports) and the textures
 * that model names.
 *
 * @param hull  the pod
 * @param wreck the pod as a wreck (#67), dark and powered off
 * @param drill the drill, a separate part that the renderer turns while the pod drills
 */
public record PodSkins(Identifier hull, Identifier wreck, Identifier drill) {
	private static final PodSkins MOLE = build(Chassis.MOLE);
	private static final PodSkins PROSPECTOR = build(Chassis.PROSPECTOR);

	private static PodSkins build(Chassis chassis) {
		return new PodSkins(id(chassis.id()), id(chassis.id() + "_wreck"), id(chassis.id() + "_drill"));
	}

	private static Identifier id(String name) {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "pod/" + name);
	}

	/** The look of {@code chassis}; a chassis with no look here throws. */
	public static PodSkins of(Chassis chassis) {
		if (chassis == Chassis.MOLE) {
			return MOLE;
		}
		if (chassis == Chassis.PROSPECTOR) {
			return PROSPECTOR;
		}
		throw new IllegalArgumentException("no pod skin for chassis: " + chassis.id());
	}

	/** Every id of the look, in the order hull, wreck, drill. */
	public List<Identifier> all() {
		return List.of(hull, wreck, drill);
	}
}
