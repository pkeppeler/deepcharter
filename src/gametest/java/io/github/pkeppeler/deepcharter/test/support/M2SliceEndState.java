package io.github.pkeppeler.deepcharter.test.support;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.colony.FounderStatue;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapters;
import io.github.pkeppeler.deepcharter.handbook.HandbookProgress;
import io.github.pkeppeler.deepcharter.market.WorkOrder;
import io.github.pkeppeler.deepcharter.market.WorkOrderData;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;

/**
 * What the M2 vertical slice ends in, for one charter. The evidence scenario {@code m2-slice} ends by asking for it, and the
 * server GameTest {@code M2SliceTest} replays the slice and asks for it too, so a chapter, a directive or an order added to the game
 * fails both until the slice is extended.
 */
public final class M2SliceEndState {
	private M2SliceEndState() {
	}

	/**
	 * Every directive of every shipped chapter (the test pack's sample chapter is not shipped) is done for {@code charter},
	 * {@code prospector} is restored and registered to the charter, the Founder's hands work order is delivered in full, and the
	 * statue has its hands.
	 *
	 * @throws AssertionError naming what is still open
	 */
	public static void require(MinecraftServer server, CharterId charter, PodEntity prospector) {
		Set<Identifier> done = HandbookProgress.completed(server, charter);
		List<Identifier> open = shippedDirectives(server).stream().filter(id -> !done.contains(id)).toList();
		if (!open.isEmpty()) {
			throw new AssertionError("directives still open at the end of the slice: " + open);
		}
		if (Wrecks.isWreck(prospector)) {
			throw new AssertionError("the Prospector is still a wreck");
		}
		if (PodComponents.registration(prospector).filter(registration -> registration.owner().equals(charter)).isEmpty()) {
			throw new AssertionError("the Prospector is not registered to the charter: " + PodComponents.registration(prospector));
		}
		WorkOrder order = WorkOrder.FOUNDERS_HANDS;
		int delivered = WorkOrderData.get(server).delivered(charter, order);
		if (delivered != order.quantity()) {
			throw new AssertionError("the Founder's hands order has " + delivered + " of " + order.quantity() + " delivered");
		}
		boolean hands = FounderStatue.handPositions(server)
				.map(positions -> positions.stream().allMatch(pos -> server.overworld().getBlockState(pos).equals(FounderStatue.hand())))
				.orElse(false);
		if (!hands) {
			throw new AssertionError("the Founder statue has no hands");
		}
	}

	/** The directive ids of the chapters the game ships. */
	public static Set<Identifier> shippedDirectives(MinecraftServer server) {
		return HandbookChapters.all(server.registryAccess()).stream()
				.filter(chapter -> !chapter.key().identifier().getPath().equals("sample"))
				.flatMap(chapter -> chapter.value().directives().stream())
				.map(HandbookChapter.Entry::id)
				.collect(Collectors.toSet());
	}
}
