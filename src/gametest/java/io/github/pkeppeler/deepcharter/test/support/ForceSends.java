package io.github.pkeppeler.deepcharter.test.support;

import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

/**
 * Counts vanilla's "standing on air - force-sending blocks below" lines (#1295), which {@code
 * ServerGamePacketListenerImpl} logs under its own logger, not this mod's. Attached once to the root logger and never
 * detached: same-named appenders collide.
 */
public final class ForceSends {
    private static final String MARKER = "standing on air - force-sending blocks below";
    private static final AtomicInteger COUNT = new AtomicInteger();

    static {
        AbstractAppender appender =
                new AbstractAppender("deepcharter-force-sends", null, null, true, Property.EMPTY_ARRAY) {
                    @Override
                    public void append(LogEvent event) {
                        if (event.getMessage().getFormattedMessage().contains(MARKER)) {
                            COUNT.incrementAndGet();
                        }
                    }
                };
        appender.start();
        ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).addAppender(appender);
    }

    private ForceSends() {}

    /** The lines logged since the JVM started. */
    public static int total() {
        return COUNT.get();
    }
}
