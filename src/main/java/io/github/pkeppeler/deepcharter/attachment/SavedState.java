package io.github.pkeppeler.deepcharter.attachment;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import net.minecraft.resources.Identifier;

import io.github.pkeppeler.deepcharter.DeepCharter;

/**
 * The state of a versioned {@code SavedData}: either the live value, or saved data of a version this build cannot read, kept
 * untouched. It is {@link Versioned#readable} for SavedData, which is not an attachment target.
 *
 * <p>A SavedData class holds one, built in its constructors, and reaches its value through it:
 * <ul>
 *   <li>{@link #readable} (and {@link #isReadable}) on a tick, join, sync or gameplay-callback path: never throws, logs an
 *       unreadable value once for each data object (one for each server), and the caller skips.</li>
 *   <li>{@link #orThrow} in the class's own explicit operations: throws, naming the data and the saved version, so the saved data
 *       is never overwritten.</li>
 *   <li>{@link #versioned} in the codec's encoder: an unreadable value is written back as it was read.</li>
 * </ul>
 *
 * @param <T> the live value, such as the map the class works on
 */
public final class SavedState<T> {
	private final Identifier id;
	private final int currentVersion;
	/** The live value, or null when the saved data is unreadable. */
	private T value;
	/** The saved data as read, or null when it is readable. */
	private final Versioned.Unreadable<?> unreadable;
	private final AtomicBoolean logged = new AtomicBoolean();

	private SavedState(Identifier id, int currentVersion, T value, Versioned.Unreadable<?> unreadable) {
		this.id = id;
		this.currentVersion = currentVersion;
		this.value = value;
		this.unreadable = unreadable;
	}

	/** New data with nothing saved yet. */
	public static <T> SavedState<T> fresh(Identifier id, int currentVersion, T empty) {
		return new SavedState<>(id, currentVersion, empty, null);
	}

	/** Data as the codec read it: {@code toValue} builds the live value of a readable body. */
	public static <B, T> SavedState<T> load(Identifier id, int currentVersion, Versioned<B> loaded, Function<B, T> toValue) {
		return switch (loaded) {
			case Versioned.Readable<B> readable -> new SavedState<>(id, currentVersion, toValue.apply(readable.value()), null);
			case Versioned.Unreadable<B> raw -> new SavedState<>(id, currentVersion, null, raw);
		};
	}

	/** The live value, or empty when the saved data is unreadable (logged once). Never throws. */
	public Optional<T> readable() {
		if (unreadable != null && logged.compareAndSet(false, true)) {
			DeepCharter.LOGGER.error("The saved {} has version {}, which this build cannot read (it reads {}): it is skipped and the saved data is kept",
					id, unreadable.version(), currentVersion);
		}
		return Optional.ofNullable(value);
	}

	/** True when the saved data can be read; logs once when not. Never throws. */
	public boolean isReadable() {
		return readable().isPresent();
	}

	/** The live value. Throws if the saved data is unreadable: for the class's own explicit operations. */
	public T orThrow() {
		if (unreadable != null) {
			throw new IllegalStateException("the saved " + id + " has version " + unreadable.version()
					+ " that this build cannot read (it reads " + currentVersion + ")");
		}
		return value;
	}

	/** Replaces the live value, for a value that is not mutated in place. Throws if the saved data is unreadable. */
	public void set(T newValue) {
		orThrow();
		value = newValue;
	}

	/** What the codec writes: the live value as a body, or the unreadable data as it was read. */
	@SuppressWarnings("unchecked")
	public <B> Versioned<B> versioned(Function<T, B> toBody) {
		if (unreadable != null) {
			return (Versioned<B>) unreadable;
		}
		return Versioned.of(toBody.apply(value));
	}
}
