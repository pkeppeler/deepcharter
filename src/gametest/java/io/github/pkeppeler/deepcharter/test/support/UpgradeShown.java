package io.github.pkeppeler.deepcharter.test.support;

import net.minecraft.client.gui.screens.Screen;

import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/** What the upgrade screen shows for a chosen track, read from its widget tree. */
public final class UpgradeShown {
	private UpgradeShown() {
	}

	/**
	 * True when the screen lists the tiers of {@code track}: a button for each track, one for each of its tiers, and Close. Choosing a
	 * track rebuilds the right column with that track's tiers, so this is how the choice shows.
	 */
	public static boolean listsTiersOf(Screen screen, ComponentTrack track) {
		return ClientChecks.buttons(screen).size() == ComponentTrack.values().length + track.maxTier() + 1;
	}
}
