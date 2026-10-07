package io.github.pkeppeler.deepcharter.upgrade;

/**
 * Tunables for the upgrade feature, read as {@code UpgradeTuning.DEFAULT.thing()}. Add one
 * component per tunable and give it its value where {@code DEFAULT} is built.
 */
public record UpgradeTuning() {
	public static final UpgradeTuning DEFAULT = new UpgradeTuning();
}
