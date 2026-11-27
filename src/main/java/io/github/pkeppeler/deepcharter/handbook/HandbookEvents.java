package io.github.pkeppeler.deepcharter.handbook;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Server-side events of the handbook feature. They fire from {@link HandbookProgress} after the progress is recorded. */
public final class HandbookEvents {
    /**
     * The last open directive of {@code chapter} was completed, so every directive of the chapter is now done for the player's charter, or
     * for the player alone on none. Fires once for each online member of the charter (or for the lone player), when the completion is
     * recorded. Never fires for progress a charter already had: not on joining a world or a charter, nor for the directives a founder
     * carries into a new charter.
     */
    public static final Event<ChapterCompleted> CHAPTER_COMPLETED =
            EventFactory.createArrayBacked(ChapterCompleted.class, listeners -> (server, player, chapter) -> {
                for (ChapterCompleted listener : listeners) {
                    listener.onChapterCompleted(server, player, chapter);
                }
            });

    private HandbookEvents() {}

    @FunctionalInterface
    public interface ChapterCompleted {
        void onChapterCompleted(MinecraftServer server, ServerPlayer player, Identifier chapter);
    }
}
