package io.github.pkeppeler.deepcharter.test.support;

import java.lang.reflect.Field;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.client.upgrade.UpgradeScreen;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;

/**
 * Which track the upgrade screen shows as chosen. The choice shows only as the lit track button ({@code CrtButton.marked}): tracks with the
 * same ladder (drill, hull, engine, fuel tank) list identical tier buttons, so no drawn text tells them apart. The test reads the mark from
 * the widget tree, by reflection, so production keeps no member for it. Client thread.
 */
public final class UpgradeShown {
	private static final Field MARKED = markedField();

	private UpgradeShown() {
	}

	/** True when the lit track button is the one of {@code track} and the screen lists that track's tiers: a button for each track, each tier, and Close. */
	public static boolean isChosen(Screen screen, ComponentTrack track) {
		String label = Component.translatable("deepcharter.upgrade.track." + track.id()).getString() + "  T";
		boolean lit = ClientChecks.buttons(screen).stream()
				.anyMatch(button -> button instanceof CrtButton crt && marked(crt) && crt.getMessage().getString().startsWith(label));
		int trackButtons = screen instanceof UpgradeScreen upgrade ? upgrade.visibleTracks() : ComponentTrack.values().length;
		return lit && ClientChecks.buttons(screen).size() == trackButtons + track.maxTier() + 1;
	}

	/** Scrolls the upgrade screen's track list so that the button of {@code track} is shown (the list scrolls when the tracks do not fit). Client thread. */
	public static void reveal(UpgradeScreen screen, ComponentTrack track) {
		screen.scrollTo(track.ordinal());
	}

	private static boolean marked(CrtButton button) {
		try {
			return MARKED.getBoolean(button);
		} catch (IllegalAccessException e) {
			throw new AssertionError("cannot read CrtButton.marked", e);
		}
	}

	private static Field markedField() {
		try {
			Field field = CrtButton.class.getDeclaredField("marked");
			field.setAccessible(true);
			return field;
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("CrtButton has no field marked; the upgrade tests read the chosen track by name", e);
		}
	}
}
