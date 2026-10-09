package io.github.pkeppeler.deepcharter.client.pod;

import java.util.Map;
import java.util.TreeSet;

/**
 * What the pod renderer does with a bone of a pod model (#334). The role comes from the bone's name: one word of the
 * vocabulary below, alone or followed by {@code _} and a suffix ({@code tread_l}, {@code wheel_l2}). A name outside it is an
 * error, so a typo never turns a moving part into a still one.
 */
public enum BoneRole {
	/** Rides along with its parent. */
	FIXED,
	/** Aims the drill. Its rotation in the file is the idle pose; the game levels it to bore sideways and points it down to bore the floor. */
	DRILL_MOUNT,
	/** Spins about its own z axis while the pod drills. */
	DRILL_HEAD,
	/** Spins about its own z axis the other way from the drill head while the pod drills, so a cutter of two parts churns. */
	DRILL_RING,
	/** Spins about y: slowly while the pod has power, fast while it flies. */
	ROTOR,
	/** Spins about z while the pod has power. */
	FAN,
	/** Swings from pointing back (stowed) to pointing down while the pod flies. */
	THRUSTER,
	/** Shown only while the pod flies. */
	FLAME,
	/** Turns about x with the distance the pod drives. */
	WHEEL,
	/** Slides back along z with the distance the pod drives, one link pitch at a time, so the tread seems to run. */
	LINKS,
	/** A hip: swings about y with the stride. */
	LEG,
	/** Lifts its foot with the stride, turning about z. */
	THIGH;

	private static final Map<String, BoneRole> WORDS = Map.ofEntries(
			Map.entry("body", FIXED), Map.entry("hull", FIXED), Map.entry("canopy", FIXED), Map.entry("hatch", FIXED),
			Map.entry("lamps", FIXED), Map.entry("cutter", FIXED), Map.entry("tank", FIXED), Map.entry("exhaust", FIXED),
			Map.entry("fender", FIXED), Map.entry("tread", FIXED), Map.entry("mast", FIXED), Map.entry("duct", FIXED),
			Map.entry("frame", FIXED), Map.entry("strut", FIXED), Map.entry("shin", FIXED), Map.entry("foot", FIXED),
			Map.entry("drill_mount", DRILL_MOUNT), Map.entry("drill_head", DRILL_HEAD), Map.entry("drill_ring", DRILL_RING), Map.entry("rotor", ROTOR),
			Map.entry("fan", FAN), Map.entry("thruster", THRUSTER), Map.entry("flame", FLAME), Map.entry("wheel", WHEEL),
			Map.entry("links", LINKS), Map.entry("leg", LEG), Map.entry("thigh", THIGH));

	/** The role of the bone named {@code name}; throws if the name is not a vocabulary word, or a word and a suffix. */
	public static BoneRole of(String name) {
		String best = null;
		for (String word : WORDS.keySet()) {
			if ((name.equals(word) || name.startsWith(word + "_")) && (best == null || word.length() > best.length())) {
				best = word;
			}
		}
		if (best == null) {
			throw new IllegalArgumentException("'" + name + "' is not a pod bone: a bone is named one of " + new TreeSet<>(WORDS.keySet())
					+ ", alone or followed by _ and a suffix");
		}
		return WORDS.get(best);
	}
}
