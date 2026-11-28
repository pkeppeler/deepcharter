package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.colony.FounderStatue;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapter;
import io.github.pkeppeler.deepcharter.handbook.HandbookChapters;
import io.github.pkeppeler.deepcharter.handbook.HandbookProgressData;
import io.github.pkeppeler.deepcharter.layer.BreachEvents;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.market.WorkOrder;
import io.github.pkeppeler.deepcharter.market.WorkOrderData;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.wreck.Wrecks;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * What the M2 vertical slice ends in, for one charter. The evidence scenario {@code m2-slice} ends by asking for it, and the
 * server GameTest {@code M2SliceTest} replays the slice and asks for it too, so a chapter, a directive or an order added to the game
 * fails both until the slice is extended.
 */
public final class M2SliceEndState {
    /** The crossings that moved an entity to another level, by entity: in the campaign world a crossing moves nobody (ADR 0061). */
    private static final Map<UUID, List<String>> MOVED = new ConcurrentHashMap<>();

    private static final AtomicBoolean WATCHING = new AtomicBoolean();

    private M2SliceEndState() {}

    /** The failure of {@link #require} when the descent changed a player's dimension; a test asks for this type, not for a word of its message. */
    public static final class PlayerMoved extends AssertionError {
        private static final long serialVersionUID = 1L;

        PlayerMoved(String message) {
            super(message);
        }
    }

    /** Forgets the crossings recorded for {@code entity}, for a test that staged one on purpose. */
    public static void forgetCrossings(UUID entity) {
        MOVED.remove(entity);
    }

    /**
     * Starts recording the crossings that change an entity's level, so {@link #require} can say the descent changed none. Call it before
     * the slice runs (a static initialiser of the test or the scenario does); calling it again does nothing.
     */
    public static void watchCrossings() {
        if (WATCHING.compareAndSet(false, true)) {
            BreachEvents.CROSSED.register((entity, from, to, fromLayer, toLayer) -> {
                if (from != to) {
                    MOVED.computeIfAbsent(entity.getUUID(), id -> new CopyOnWriteArrayList<>())
                            .add(fromLayer + ">" + toLayer + " from "
                                    + from.dimension().identifier() + " to "
                                    + to.dimension().identifier());
                }
            });
        }
    }

    /**
     * Every directive of every shipped chapter (the test pack's sample chapter is not shipped) is done for {@code charter},
     * {@code prospector} is restored and registered to the charter, the Founder's hands work order is delivered in full, and the
     * statue has its hands, and the descent never changed anyone's dimension: no player was moved by a crossing and the Prospector is in a
     * campaign level ({@link #watchCrossings} must have been called before the slice).
     *
     * @throws AssertionError naming what is still open; a {@link PlayerMoved} when a crossing moved a player
     */
    public static void require(MinecraftServer server, CharterId charter, PodEntity prospector) {
        if (!WATCHING.get()) {
            throw new IllegalStateException(
                    "watchCrossings was not called before the slice, so a moved player would go unseen");
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // Only the slice's own charter: another test's player, crossing at the same time in the shared world, is
            // not the descent.
            if (Charters.readableCharterOf(server, player.getUUID())
                    .filter(own -> own.id().equals(charter))
                    .isEmpty()) {
                continue;
            }
            if (!MOVED.getOrDefault(player.getUUID(), List.of()).isEmpty()) {
                throw new PlayerMoved("the descent changed " + player.getName().getString() + "'s dimension: "
                        + MOVED.get(player.getUUID()));
            }
        }
        if (!LayerChain.isCampaign(prospector.level())) {
            throw new AssertionError("the Prospector ended in "
                    + prospector.level().dimension().identifier() + ", which is not a campaign level");
        }
        Set<Identifier> done = HandbookProgressData.get(server).completed(charter);
        List<Identifier> open = shippedDirectives(server).stream()
                .filter(id -> !done.contains(id))
                .toList();
        if (!open.isEmpty()) {
            throw new AssertionError("directives still open at the end of the slice: " + open);
        }
        if (Wrecks.isWreck(prospector)) {
            throw new AssertionError("the Prospector is still a wreck");
        }
        if (PodComponents.registration(prospector)
                .filter(registration -> registration.owner().equals(charter))
                .isEmpty()) {
            throw new AssertionError(
                    "the Prospector is not registered to the charter: " + PodComponents.registration(prospector));
        }
        WorkOrder order = WorkOrder.FOUNDERS_HANDS;
        int delivered = WorkOrderData.get(server).delivered(charter, order);
        if (delivered != order.quantity()) {
            throw new AssertionError(
                    "the Founder's hands order has " + delivered + " of " + order.quantity() + " delivered");
        }
        if (FounderStatue.hands(server).size() != 1) {
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
