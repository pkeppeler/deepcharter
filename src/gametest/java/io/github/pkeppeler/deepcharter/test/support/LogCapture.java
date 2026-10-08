package io.github.pkeppeler.deepcharter.test.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import io.github.pkeppeler.deepcharter.DeepCharter;

/** Attached once and never detached: same-named appenders on one logger collide. */
public final class LogCapture {
	private static final Appender APPENDER = Appender.attach();

	private final int from;
	private final String key;

	private LogCapture(int from, String key) {
		this.from = from;
		this.key = key;
	}

	/** Marks now. {@link #errors} then returns the ERROR messages logged after this call that contain {@code key}. */
	public static LogCapture start(String key) {
		if (key.isEmpty()) {
			throw new IllegalArgumentException("a log capture needs a key that is unique to its test");
		}
		return new LogCapture(APPENDER.events.size(), key);
	}

	public List<String> errors() {
		return messages(Level.ERROR);
	}

	/** The WARN messages logged after {@link #start} that contain the key. */
	public List<String> warnings() {
		return messages(Level.WARN);
	}

	private List<String> messages(Level level) {
		List<Event> events = APPENDER.events;
		return events.subList(from, events.size()).stream()
				.filter(event -> event.level() == level && event.message().contains(key))
				.map(Event::message)
				.toList();
	}

	private record Event(Level level, String message) {
	}

	private static final class Appender extends AbstractAppender {
		private final List<Event> events = new CopyOnWriteArrayList<>();

		private Appender() {
			super("deepcharter-log-capture", null, null, true, Property.EMPTY_ARRAY);
		}

		static Appender attach() {
			Appender appender = new Appender();
			appender.start();
			((org.apache.logging.log4j.core.Logger) LogManager.getLogger(DeepCharter.MOD_ID)).addAppender(appender);
			return appender;
		}

		@Override
		public void append(LogEvent event) {
			events.add(new Event(event.getLevel(), event.getMessage().getFormattedMessage()));
		}
	}
}
