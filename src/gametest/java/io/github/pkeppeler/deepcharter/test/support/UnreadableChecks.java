package io.github.pkeppeler.deepcharter.test.support;

import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

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
	 * Swaps saved data of a future version in for the world's {@code type}, runs {@link #assertNoThrow} on the paths, and puts the
	 * world's own data back. Also asserts that the unreadable data is logged exactly once, and is written back as it was read.
	 * Everything runs inside the test's one tick, so no other test sees the swapped data.
	 */
	public static <T extends SavedData> void assertSavedDataNoThrow(GameTestHelper helper, String feature, MinecraftServer server,
			SavedDataType<T> type, Map<String, Runnable> paths) {
		CompoundTag future = futureData();
		T unreadable = type.codec().parse(NbtOps.INSTANCE, future).getOrThrow();
		T original = server.getDataStorage().computeIfAbsent(type);
		LogCapture log = LogCapture.start(type.id().toString());
		server.getDataStorage().set(type, unreadable);
		try {
			assertNoThrow(helper, feature, paths);
		} finally {
			server.getDataStorage().set(type, original);
		}
		List<String> errors = log.errors();
		if (errors.size() != 1) {
			throw helper.assertionException("%s: unreadable %s should be logged once, not %s times: %s", feature, type.id(), errors.size(), errors);
		}
		if (!future.equals(type.codec().encodeStart(NbtOps.INSTANCE, unreadable).getOrThrow())) {
			throw helper.assertionException("%s: unreadable %s must be written back unchanged", feature, type.id());
		}
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
