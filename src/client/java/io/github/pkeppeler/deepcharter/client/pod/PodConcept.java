package io.github.pkeppeler.deepcharter.client.pod;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * The Mole concepts, for the user to pick one before #243 builds it: round 1 (#334) and round 2 (#352), the Capsule with the
 * Borer's cutter made giant. A concept is drawn only when the dev switch {@value #PROPERTY} names it; without the switch the Mole
 * keeps its shipping look ({@link PodRenderer}).
 */
public enum PodConcept {
	CAPSULE(1),
	BORER(1),
	STRIDER(1),
	GYRO(1),
	FULL_FACE(2),
	SMALL_CAB(2),
	STEPPED(2),
	BOOM(2);

	/** The system property that picks a concept for the Mole. It is read whenever the renderers are built, which is on every resource reload. */
	public static final String PROPERTY = "deepcharter.podConcept";

	private final int round;

	PodConcept(int round) {
		this.round = round;
	}

	/** Which set of concepts the user was shown it in: 1 for #334, 2 for #352. */
	public int round() {
		return round;
	}

	/** The concepts of {@code round}, in order. */
	public static List<PodConcept> ofRound(int round) {
		return Arrays.stream(values()).filter(concept -> concept.round == round).toList();
	}

	/** The concept's name in the switch and in its file names. */
	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}

	/** The Bedrock geometry, at the path GeckoLib reads, so #243 can keep the file. */
	public Identifier model() {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "geckolib/models/pod/concepts/" + id() + ".geo.json");
	}

	public Identifier texture() {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "textures/entity/pod/mole/" + id() + ".png");
	}

	/** The lamp layer, drawn full bright over the texture while the pod has power. */
	public Identifier glowmask() {
		return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "textures/entity/pod/mole/" + id() + "_glowmask.png");
	}

	/** The concept the switch names, or empty when it is unset or blank. A name that is no concept throws. */
	public static Optional<PodConcept> selected() {
		String value = System.getProperty(PROPERTY, "").strip();
		if (value.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(Arrays.stream(values()).filter(concept -> concept.id().equals(value)).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("-D" + PROPERTY + "=" + value + " names no pod concept; the concepts are "
						+ Arrays.stream(values()).map(PodConcept::id).toList())));
	}
}
