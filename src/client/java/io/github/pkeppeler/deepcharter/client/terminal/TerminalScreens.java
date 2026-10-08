package io.github.pkeppeler.deepcharter.client.terminal;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;

/**
 * Which screen a terminal opens. An unrepaired terminal always opens the generic {@link TerminalScreen}, offline. A repaired one
 * opens the screen its feature registered with {@link #register}, or the generic online screen when there is none.
 */
public final class TerminalScreens {
	private static final Map<TerminalType, Function<TerminalView, ? extends Screen>> ONLINE = new HashMap<>();

	private TerminalScreens() {
	}

	/** Sets the screen {@code type} opens once it is repaired. The view says which terminal, and what the server sent. Once per type. */
	public static void register(TerminalType type, Function<TerminalView, ? extends Screen> online) {
		if (ONLINE.putIfAbsent(type, online) != null) {
			throw new IllegalArgumentException("terminal " + type.id() + " already has an online screen");
		}
	}

	/** Shows {@code view}: updates the open screen if it accepts the view, or opens a new one. Call on the client thread. */
	static void show(TerminalView view) {
		Minecraft client = Minecraft.getInstance();
		if (client.gui.screen() instanceof TerminalViewScreen open && open.accepts(view)) {
			open.update(view);
			return;
		}
		TerminalType type = TerminalTypes.get(view.type()).orElseThrow(() -> new IllegalStateException("unknown terminal type " + view.type()));
		client.gui.setScreen(view.repaired() ? ONLINE.getOrDefault(type, TerminalScreen::new).apply(view) : new TerminalScreen(view));
	}
}
