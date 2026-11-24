package io.github.pkeppeler.deepcharter.terminal;

import io.github.pkeppeler.deepcharter.charter.Charter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The lines every terminal's view carries under its header ({@link TerminalView#notices}, #993): what a feature wants the player to read
 * before they act. A feature registers a {@link Source} once, and the server asks every source whenever it sends a view. The client draws
 * the lines in the order the sources were registered.
 */
public final class TerminalNotices {
    private static final List<Source> SOURCES = new ArrayList<>();
    /** What marks a line as good news: the screen draws it in its phosphor, and any other line in its amber. */
    private static final Style GOOD = Style.EMPTY.withColor(ChatFormatting.GREEN);

    private TerminalNotices() {}

    /**
     * The lines for the terminal at {@code pos} that {@code player} has open, on the server thread: none for a terminal the source has
     * nothing to say about. It must not change anything, and must not throw on unreadable saved data.
     */
    @FunctionalInterface
    public interface Source {
        List<Component> notices(MinecraftServer server, ServerPlayer player, Optional<Charter> charter, BlockPos pos);
    }

    /** {@code line} marked as good news. */
    public static Component good(MutableComponent line) {
        return line.withStyle(GOOD);
    }

    /** Whether {@code line} was marked by {@link #good}. */
    public static boolean isGood(Component line) {
        return Objects.equals(GOOD.getColor(), line.getStyle().getColor());
    }

    /** Adds a source. Call once, while mods initialise. */
    public static void register(Source source) {
        SOURCES.add(source);
    }

    /** What every source says for the terminal at {@code pos}. */
    public static List<Component> collect(
            MinecraftServer server, ServerPlayer player, Optional<Charter> charter, BlockPos pos) {
        List<Component> lines = new ArrayList<>();
        for (Source source : SOURCES) {
            lines.addAll(source.notices(server, player, charter, pos));
        }
        return List.copyOf(lines);
    }
}
