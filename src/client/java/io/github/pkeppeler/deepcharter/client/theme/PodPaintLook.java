package io.github.pkeppeler.deepcharter.client.theme;

import java.util.ArrayList;
import java.util.List;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.theme.ThemeData;

/**
 * The paint colours of the pods' hulls ({@code theme/pod.json}, #383): {@code paintCount} colours, {@code paint0} to {@code paint<n-1>}.
 * A charter's pods carry the one its id picks, so every pod of a charter is one colour and a pack restyles the palette on F3+T. A pack
 * adds a colour by raising {@code paintCount} and naming the new key.
 *
 * @param paints the hull paint colours as opaque ARGB, never empty
 */
public record PodPaintLook(List<Integer> paints) {
	/** The most colours a palette holds. */
	public static final int MAX_PAINTS = 64;

	public PodPaintLook {
		if (paints.isEmpty()) {
			throw new IllegalArgumentException("the pod paint palette needs a colour");
		}
		paints = List.copyOf(paints);
	}

	public static PodPaintLook current() {
		return UiTheme.current().pod();
	}

	public static PodPaintLook of(ThemeData d) {
		int count = d.integer("paintCount", 1, MAX_PAINTS);
		List<Integer> paints = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			paints.add(d.color("paint" + i));
		}
		return new PodPaintLook(paints);
	}

	/** The palette slot of the charter {@code owner}: the same for every pod it owns, and spread over the palette across charters. */
	public int slotOf(CharterId owner) {
		return Math.floorMod(owner.value().hashCode(), paints.size());
	}

	/** The paint colour of the charter {@code owner}, opaque ARGB. */
	public int paintOf(CharterId owner) {
		return paints.get(slotOf(owner));
	}
}
