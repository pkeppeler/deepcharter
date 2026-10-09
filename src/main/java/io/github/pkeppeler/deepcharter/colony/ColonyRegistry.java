package io.github.pkeppeler.deepcharter.colony;

/** Registers the colony blocks. {@link ColonySite} is a SavedData and needs no registration. */
public final class ColonyRegistry {
	private ColonyRegistry() {
	}

	public static void register() {
		ColonyBlocks.register();
	}
}
