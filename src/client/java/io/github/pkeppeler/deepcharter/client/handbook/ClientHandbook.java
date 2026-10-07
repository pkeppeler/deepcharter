package io.github.pkeppeler.deepcharter.client.handbook;

import java.util.Collection;
import java.util.Set;

import net.minecraft.resources.Identifier;

/**
 * The directives the server last said this player's charter has completed. Empty when the player is on no charter. The handbook
 * screen (#66) reads it. Read and written on the client thread.
 */
public final class ClientHandbook {
	private static Set<Identifier> completed = Set.of();

	private ClientHandbook() {
	}

	public static Set<Identifier> completed() {
		return completed;
	}

	static void set(Collection<Identifier> newCompleted) {
		completed = Set.copyOf(newCompleted);
	}
}
