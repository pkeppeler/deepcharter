package io.github.pkeppeler.deepcharter.handbook;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterEvents;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.layer.StructureKind;
import io.github.pkeppeler.deepcharter.layer.StructureSite;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Pencil paths (ADR 0065, SPEC "After layer 2"): company directives end at layer 2, and past it the map continues in pencil. Each
 * Note a charter has found draws the path of earlier miners that starts at it ({@link #ALL}), and the path completes when anyone on
 * the charter reaches the place it points to ({@link PencilPlace}). The place is the payoff: there is no claim button and no reward,
 * and a pencil path is not a Directive ({@link Directives} knows nothing of it).
 *
 * <p>Progress is charter-shared like the Notes: a charter's completed paths are in {@link PencilPathsData}, a player on no charter
 * has their own, and {@link ProgressCarry} moves them into a charter the player founds or joins. A player reaches a place by being
 * there: {@link #poll} runs every {@link HandbookTuning#triggerPollTicks()} ticks for each online survival or adventure player, so a
 * spectator or a creative player completes nothing. Only the levels that hold the place's layer can answer yes, so the surface, the
 * Nether and the End never complete a path in a layer.
 *
 * <p>A wax cylinder payoff (#864, #871) is one more {@link PencilPlace}, once the item is on main.
 *
 * <p>Everything on a tick, join or sync path reads through {@link #readableData}: when the saved charters, Notes or paths are of a
 * version this build cannot read, it logs once and does nothing, and {@link #completedFor} is empty.
 */
public final class PencilPaths {
    /**
     * N07, the prayer card in the Deep Claim ("Keep the ones below"), draws the way to PROSPECTOR-0002's wreck, the lamp left
     * burning in Prospector's Run.
     */
    public static final PencilPath ONES_BELOW = new PencilPath("ones_below", 7, new PencilPlace.Wreck());

    /**
     * N10, Ines's log at the wreck ("I'm taking our old shaft down on foot"), draws the way to the Kells' shaft at the end of the
     * rail line.
     */
    public static final PencilPath OLD_SHAFT =
            new PencilPath("old_shaft", 10, new PencilPlace.Structure(StructureKind.KELLS_SHAFT));

    /** Every pencil path, in the order the sheet draws them. */
    public static final List<PencilPath> ALL = List.of(ONES_BELOW, OLD_SHAFT);

    private PencilPaths() {}

    /** Polls for places reached, and syncs the completed paths to clients: on join, and when a player founds, joins or leaves a charter. */
    static void register() {
        PencilSyncPayload.register();
        ServerPlayerEvents.JOIN.register(
                player -> PencilSyncPayload.send(player.level().getServer(), player));
        CharterEvents.FOUNDED.register((server, charter) -> sendAll(server, charter.roster()));
        CharterEvents.REVIVED.register((server, charter, director) -> sendAll(server, List.of(director)));
        CharterEvents.JOINED.register((server, charter, player) -> sendAll(server, List.of(player)));
        CharterEvents.LEFT.register((server, charter, player) -> sendAll(server, List.of(player)));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % HandbookTuning.DEFAULT.triggerPollTicks() == 0) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    poll(server, player);
                }
            }
        });
    }

    /**
     * The pencil paths complete for {@code player}: those of their charter, or their own when they are on none. Empty, and logged
     * once, while the saved charters or paths are unreadable.
     */
    public static Set<Identifier> completedFor(MinecraftServer server, UUID player) {
        Optional<PencilPathsData> data = readableData(server);
        if (data.isEmpty()) {
            return Set.of();
        }
        return Charters.readableCharterOf(server, player)
                .map(charter -> data.get().charter(charter.id()))
                .orElseGet(() -> data.get().player(player));
    }

    /** The paths drawn once the Notes {@code found} are found: those of {@link #ALL} whose Note is among them, in the sheet's order. */
    public static List<PencilPath> drawnFrom(Collection<Identifier> found) {
        return ALL.stream().filter(path -> found.contains(path.from())).toList();
    }

    /**
     * Completes, for {@code player}'s charter, each drawn path whose place {@code player} has reached from where they stand, and
     * tells the charter's online members. Does nothing for a spectator or a creative player, and while any saved data is unreadable.
     */
    public static void poll(MinecraftServer server, ServerPlayer player) {
        poll(server, player, ALL);
    }

    /** As {@link #poll(MinecraftServer, ServerPlayer)}, over {@code paths}: a test passes its own, as the shipped ones need a built world. */
    public static void poll(MinecraftServer server, ServerPlayer player, List<PencilPath> paths) {
        if (player.isSpectator() || player.isCreative()) {
            return;
        }
        Optional<PencilPathsData> data = readableData(server);
        if (data.isEmpty()) {
            return;
        }
        Set<Identifier> found = Set.copyOf(Notes.foundFor(server, player.getUUID()));
        Set<Identifier> done = completedFor(server, player.getUUID());
        for (PencilPath path : paths) {
            if (!done.contains(path.id())
                    && found.contains(path.from())
                    && path.place().reached(player.level(), player.blockPosition(), found)) {
                complete(server, data.get(), player, path);
            }
        }
    }

    private static void complete(MinecraftServer server, PencilPathsData data, ServerPlayer player, PencilPath path) {
        Optional<Charter> charter = Charters.readableCharterOf(server, player.getUUID());
        if (charter.isPresent()) {
            if (data.addToCharter(charter.get().id(), List.of(path.id()))) {
                sendAll(server, charter.get().roster());
            }
        } else if (data.addToPlayer(player.getUUID(), List.of(path.id()))) {
            PencilSyncPayload.send(server, player);
        }
    }

    /** Whether {@code pos} is in {@code site}'s bounds or within {@link HandbookTuning#pencilReachBlocks()} blocks of them. */
    static boolean near(StructureSite site, BlockPos pos) {
        return site.bounds()
                .inflatedBy(HandbookTuning.DEFAULT.pencilReachBlocks())
                .isInside(pos);
    }

    /** Pins a player's own completed paths into a charter they found or join ({@link ProgressCarry}). Does nothing while the data is unreadable. */
    static void carry(MinecraftServer server, CharterId charter, UUID player) {
        readableData(server).ifPresent(data -> data.addToCharter(charter, data.player(player)));
    }

    /** The saved paths, or empty (logged once for each saved-data object) when they, the saved Notes or the saved charters are unreadable. */
    private static Optional<PencilPathsData> readableData(MinecraftServer server) {
        if (!Charters.isReadable(server)) {
            return Optional.empty();
        }
        return Optional.of(PencilPathsData.get(server)).filter(PencilPathsData::isReadable);
    }

    private static void sendAll(MinecraftServer server, Iterable<UUID> players) {
        for (UUID id : players) {
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            if (online != null) {
                PencilSyncPayload.send(server, online);
            }
        }
    }
}
