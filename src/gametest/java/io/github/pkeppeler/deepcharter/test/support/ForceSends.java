package io.github.pkeppeler.deepcharter.test.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * Counts vanilla's "standing on air - force-sending blocks below" lines (#1317): the server found a client on foot reporting on-ground
 * with no collision under its last move. Attached once and never detached, like {@link LogCapture}.
 */
public final class ForceSends {
    private static final Appender APPENDER = Appender.attach();

    private final int from;
    private final String player;

    private ForceSends(int from, String player) {
        this.from = from;
        this.player = player;
    }

    /** Marks now; {@link #count} then counts the lines logged for {@code player} after this call. */
    public static ForceSends start(String player) {
        return new ForceSends(APPENDER.lines.size(), player);
    }

    public int count() {
        List<String> lines = APPENDER.lines;
        return (int) lines.subList(from, lines.size()).stream()
                .filter(line -> line.contains(player))
                .count();
    }

    private static final class Appender extends AbstractAppender {
        private final List<String> lines = new CopyOnWriteArrayList<>();

        private Appender() {
            super("deepcharter-force-sends", null, null, true, Property.EMPTY_ARRAY);
        }

        static Appender attach() {
            Appender appender = new Appender();
            appender.start();
            ((org.apache.logging.log4j.core.Logger) LogManager.getLogger(ServerGamePacketListenerImpl.class))
                    .addAppender(appender);
            return appender;
        }

        @Override
        public void append(LogEvent event) {
            String message = event.getMessage().getFormattedMessage();
            if (message.contains("standing on air")) {
                lines.add(message);
            }
        }
    }
}
