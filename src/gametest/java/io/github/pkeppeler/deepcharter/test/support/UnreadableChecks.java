package io.github.pkeppeler.deepcharter.test.support;

import java.util.Map;

import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;

import io.github.pkeppeler.deepcharter.attachment.Versioned;

/**
 * Runs a feature's tick, join and callback paths against saved data this build cannot read, and asserts that none throws.
 * Every feature with versioned data has one test that uses it. Explicit calls and commands may throw, and are not run here.
 */
public final class UnreadableChecks {
	/** The version the "unreadable" data claims: far above any this build reads. */
	public static final int FUTURE_VERSION = 99;

	private UnreadableChecks() {
	}

	/** Saved data of a future version, holding a field this build does not know. */
	public static CompoundTag futureData() {
		CompoundTag future = new CompoundTag();
		future.putInt(Versioned.VERSION_KEY, FUTURE_VERSION);
		future.putString("added-in-v99", "kept");
		return future;
	}

	/** Makes {@code type} on {@code owner} unreadable, as it is after a load of a world saved by a newer build. */
	public static <T> void makeUnreadable(AttachmentTarget owner, AttachmentType<Versioned<T>> type) {
		owner.setAttached(type, new Versioned.Unreadable<>(futureData()));
	}

	/**
	 * Runs every path twice, so a path that only throws on its second visit (a log-once guard) is caught too. Fails the test,
	 * naming the feature and the path, on the first exception.
	 *
	 * @param paths the paths by name, in the order to run them
	 */
	public static void assertNoThrow(GameTestHelper helper, String feature, Map<String, Runnable> paths) {
		for (int visit = 1; visit <= 2; visit++) {
			for (Map.Entry<String, Runnable> path : paths.entrySet()) {
				try {
					path.getValue().run();
				} catch (RuntimeException | Error e) {
					RuntimeException failure = helper.assertionException("%s: the %s path threw on unreadable data (visit %s): %s",
							feature, path.getKey(), visit, e);
					failure.initCause(e);
					throw failure;
				}
			}
		}
	}
}
