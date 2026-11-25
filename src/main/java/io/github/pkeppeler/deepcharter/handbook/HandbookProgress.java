package io.github.pkeppeler.deepcharter.handbook;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterEvents;
import io.github.pkeppeler.deepcharter.charter.Charters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Directive progress, which belongs to the charter: a directive one member completes is complete for everyone on it, and a
 * new member inherits what is done. A player on no charter has progress of their own ({@link PersonalHandbookData}), which
 * carries into a charter they found ({@link ProgressCarry}). A directive is complete when the advancement with its id is done for one online member.
 * Our own code reaches that through {@link Directives#fire}; a vanilla criterion reaches it by the poll in {@link #sweep}.
 * A directive {@link #sweep} records that finishes a chapter fires {@link HandbookEvents#CHAPTER_COMPLETED}.
 *
 * <p>A player's directive advancements mirror their current charter, or their own progress on none: on join, on founding or joining
 * a charter, and on leaving one, the player is given the advancements of the directives the charter has done and loses the
 * others. A joiner's own progress therefore completes none of the charter's steps, and what a player did on another charter
 * earns this one nothing.
 *
 * <p>Everything on a tick, join or sync path reads through {@link #readableData}: when the saved charters or the saved progress
 * are of a version this build cannot read, it logs once and does nothing, and {@link #completedFor} is empty.
 * {@link HandbookProgressData#completed} is an explicit call and throws instead.
 */
public final class HandbookProgress {
    private HandbookProgress() {}

    /** Syncs progress to clients, mirrors it into advancements, and polls vanilla criteria. */
    static void register() {
        HandbookSyncPayload.register();
        ServerPlayerEvents.JOIN.register(player -> refresh(player.level().getServer(), player));
        CharterEvents.FOUNDED.register((server, charter) -> refreshAll(server, charter.roster()));
        CharterEvents.REVIVED.register((server, charter, director) -> refreshAll(server, List.of(director)));
        CharterEvents.JOINED.register((server, charter, player) -> refreshAll(server, List.of(player)));
        CharterEvents.LEFT.register((server, charter, player) -> refreshAll(server, List.of(player)));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.tickCount % HandbookTuning.DEFAULT.progressPollTicks() == 0) {
                    sweep(player);
                }
            }
        });
    }

    /**
     * The directives complete for {@code player}: those of their charter, or their own when they are on none. Empty, and logged
     * once, while the saved charters or progress are unreadable.
     */
    public static Set<Identifier> completedFor(MinecraftServer server, UUID player) {
        Optional<HandbookProgressData> data = readableData(server);
        if (data.isEmpty()) {
            return Set.of();
        }
        Optional<Charter> charter = Charters.readableCharterOf(server, player);
        if (charter.isPresent()) {
            return data.get().completed(charter.get().id());
        }
        return readablePersonal(server)
                .map(personal -> personal.directives(player))
                .orElse(Set.of());
    }

    /**
     * Completes, for {@code player}'s charter, every directive whose advancement {@code player} has finished; a player on no
     * charter completes them for themselves ({@link PersonalHandbookData}). Does nothing while the saved charters or progress are
     * unreadable.
     */
    public static void sweep(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        Optional<HandbookProgressData> readable = readableData(server);
        if (readable.isEmpty()) {
            return;
        }
        Optional<Charter> charter = Charters.readableCharterOf(server, player.getUUID());
        if (charter.isEmpty()) {
            Set<Identifier> before = completedFor(server, player.getUUID());
            if (recordPersonal(server, player)) {
                HandbookSyncPayload.send(server, player);
                announceChapters(server, before, completedFor(server, player.getUUID()), List.of(player.getUUID()));
            }
            return;
        }
        HandbookProgressData data = readable.get();
        Set<Identifier> done = data.completed(charter.get().id());
        boolean changed = false;
        for (Identifier directive : HandbookChapters.directivesOrEmpty(server)) {
            if (done.contains(directive)) {
                continue;
            }
            AdvancementHolder advancement = server.getAdvancements().get(directive);
            if (advancement != null
                    && player.getAdvancements().getOrStartProgress(advancement).isDone()) {
                changed |= data.complete(charter.get().id(), directive);
            }
        }
        if (changed) {
            charter.get().roster().forEach(member -> HandbookSyncPayload.send(server, member));
            announceChapters(
                    server,
                    done,
                    data.completed(charter.get().id()),
                    charter.get().roster());
        }
    }

    /**
     * Fires {@link HandbookEvents#CHAPTER_COMPLETED} for each online one of {@code players} and each chapter that {@code after} completes
     * and {@code before} did not. Only {@link #sweep} calls this, so progress carried or mirrored, which it never records, completes no
     * chapter.
     */
    private static void announceChapters(
            MinecraftServer server, Set<Identifier> before, Set<Identifier> after, List<UUID> players) {
        for (Holder.Reference<HandbookChapter> chapter : HandbookChapters.all(server)) {
            List<Identifier> directives = chapter.value().directives().stream()
                    .map(HandbookChapter.Entry::id)
                    .toList();
            if (directives.isEmpty() || !after.containsAll(directives) || before.containsAll(directives)) {
                continue;
            }
            for (UUID id : players) {
                ServerPlayer online = server.getPlayerList().getPlayer(id);
                if (online != null) {
                    HandbookEvents.CHAPTER_COMPLETED
                            .invoker()
                            .onChapterCompleted(server, online, chapter.key().identifier());
                }
            }
        }
    }

    /**
     * Records, as {@code player}'s own, every directive whose advancement they have finished. Returns whether any was new. Does
     * nothing while the personal data is unreadable. The caller decides that the player is on no charter, or is about to carry
     * this into one ({@link ProgressCarry}).
     */
    static boolean recordPersonal(MinecraftServer server, ServerPlayer player) {
        Optional<PersonalHandbookData> personal = readablePersonal(server);
        if (personal.isEmpty()) {
            return false;
        }
        Set<Identifier> done = personal.get().directives(player.getUUID());
        boolean changed = false;
        for (Identifier directive : HandbookChapters.directivesOrEmpty(server)) {
            if (done.contains(directive)) {
                continue;
            }
            AdvancementHolder advancement = server.getAdvancements().get(directive);
            if (advancement != null
                    && player.getAdvancements().getOrStartProgress(advancement).isDone()) {
                changed |= personal.get().completeDirective(player.getUUID(), directive);
            }
        }
        return changed;
    }

    /** The personal data, or empty (logged once) when it is of a version this build cannot read. */
    static Optional<PersonalHandbookData> readablePersonal(MinecraftServer server) {
        return Optional.of(PersonalHandbookData.get(server)).filter(PersonalHandbookData::isReadable);
    }

    /** The progress data, or empty (logged once for each saved-data object) when the saved charters or the saved progress are unreadable. */
    static Optional<HandbookProgressData> readableData(MinecraftServer server) {
        if (!Charters.isReadable(server)) {
            return Optional.empty();
        }
        return Optional.of(HandbookProgressData.get(server)).filter(HandbookProgressData::isReadable);
    }

    private static void refreshAll(MinecraftServer server, Iterable<UUID> players) {
        for (UUID id : players) {
            ServerPlayer online = server.getPlayerList().getPlayer(id);
            if (online != null) {
                refresh(server, online);
            }
        }
    }

    private static void refresh(MinecraftServer server, ServerPlayer player) {
        mirror(server, player);
        HandbookSyncPayload.send(server, player);
    }

    /**
     * Makes {@code player}'s directive advancements match their charter: done for the directives the charter has completed, not
     * done for the rest. A player on no charter has the directives they completed on their own done.
     */
    private static void mirror(MinecraftServer server, ServerPlayer player) {
        Optional<HandbookProgressData> data = readableData(server);
        if (data.isEmpty()) {
            return;
        }
        Set<Identifier> done = completedFor(server, player.getUUID());
        for (Identifier directive : HandbookChapters.directivesOrEmpty(server)) {
            AdvancementHolder advancement = server.getAdvancements().get(directive);
            if (advancement == null) {
                continue;
            }
            AdvancementProgress progress = player.getAdvancements().getOrStartProgress(advancement);
            if (done.contains(directive)) {
                for (String criterion : toList(progress.getRemainingCriteria())) {
                    player.getAdvancements().award(advancement, criterion);
                }
            } else {
                for (String criterion : toList(progress.getCompletedCriteria())) {
                    player.getAdvancements().revoke(advancement, criterion);
                }
            }
        }
    }

    /** A copy: awarding and revoking change the progress being read. */
    private static List<String> toList(Iterable<String> criteria) {
        List<String> list = new ArrayList<>();
        criteria.forEach(list::add);
        return list;
    }
}
