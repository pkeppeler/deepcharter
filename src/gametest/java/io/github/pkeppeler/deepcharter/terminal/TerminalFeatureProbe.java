package io.github.pkeppeler.deepcharter.terminal;

import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;

/** Lives in the package of {@link TerminalFeatures} to read its package-private codec lookup. */
public final class TerminalFeatureProbe {
	private static final Identifier UNREGISTERED = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "no_such_terminal_type");

	private TerminalFeatureProbe() {
	}

	/** True when {@code type} registered a view feature: an unregistered id shares one codec, a registered one has its own. */
	public static boolean registersFeature(TerminalType type) {
		return TerminalFeatures.codec(type.id()) != TerminalFeatures.codec(UNREGISTERED);
	}
}
