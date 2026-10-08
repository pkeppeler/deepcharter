package io.github.pkeppeler.deepcharter.hangar;

/** Registers the hangar console, its four parts, and the founding Mole's wiring. */
public final class HangarRegistry {
	private HangarRegistry() {
	}

	public static void register() {
		HangarParts.register();
		HangarTerminal.register();
		Hangar.register();
	}
}
