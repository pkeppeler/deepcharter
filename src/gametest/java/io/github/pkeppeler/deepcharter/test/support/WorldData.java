package io.github.pkeppeler.deepcharter.test.support;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * The one way a test swaps a world-global saved record ({@code getDataStorage().set(TYPE, fresh)}). It captures the world's
 * record, sets the fresh one and runs the body in a {@code try}, and puts the world's back in the {@code finally}. All of it
 * happens synchronously, on the caller's tick, so no code can throw between the swap and the {@code try}, and no other test sees
 * the swap. {@code gradle/gametest.gradle} fails the build for a direct {@code getDataStorage().set(} call elsewhere.
 *
 * <p>Several records swap together with {@link #swap}. The body of a swap that must outlive its tick (a poll across listener
 * phases) cannot use this class; see the allow-list in {@code gradle/gametest.gradle}.
 */
public final class WorldData {
	/** Who swapped each record last, for {@link WorldDataGuard}'s message. */
	private static final Map<SavedDataType<?>, String> LAST_SWAPPER = new HashMap<>();

	/** How many swaps hold each record right now. */
	private static final Map<SavedDataType<?>, Integer> ACTIVE = new HashMap<>();

	private WorldData() {
	}

	/** Runs {@code body} with {@code fresh} swapped in for the world's {@code type}. */
	public static <T extends SavedData> void with(MinecraftServer server, SavedDataType<T> type, T fresh, Runnable body) {
		swap(server).with(type, fresh).run(body);
	}

	/** Runs {@code body} with {@code fresh} swapped in for the world's {@code type}, and returns what it returns. */
	public static <T extends SavedData, R> R call(MinecraftServer server, SavedDataType<T> type, T fresh, Supplier<R> body) {
		return swap(server).with(type, fresh).call(body);
	}

	/**
	 * Replaces the record that an enclosing {@link #with} or {@link #swap} holds for {@code type}, for the rest of that scope: the
	 * scope still puts the world's own back. Throws when no scope holds {@code type}, because the replacement would then leak.
	 */
	public static <T extends SavedData> void replace(MinecraftServer server, SavedDataType<T> type, T record) {
		if (ACTIVE.getOrDefault(type, 0) == 0) {
			throw new IllegalStateException("No swap holds " + type.id() + ", so replacing it would leak into the world. Use WorldData.with.");
		}
		server.getDataStorage().set(type, record);
		LAST_SWAPPER.put(type, caller());
	}

	/** Starts a swap of several records at once. */
	public static Swap swap(MinecraftServer server) {
		return new Swap(server);
	}

	/** The first caller outside this class that swapped {@code type} last, if any did: a test method, or a wrapper such as {@code withFreshWorld}. */
	static Optional<String> lastSwapper(SavedDataType<?> type) {
		return Optional.ofNullable(LAST_SWAPPER.get(type));
	}

	static void forgetSwappers() {
		LAST_SWAPPER.clear();
	}

	private static String caller() {
		return StackWalker.getInstance().walk(frames -> frames
				.filter(frame -> !frame.getClassName().equals(WorldData.class.getName()) && !frame.getClassName().startsWith(WorldData.class.getName() + "$"))
				.findFirst()
				.map(frame -> frame.getClassName().substring(frame.getClassName().lastIndexOf('.') + 1) + "." + frame.getMethodName())
				.orElse("an unknown test"));
	}

	/** A set of records to swap, in the order given. */
	public static final class Swap {
		private final MinecraftServer server;
		private final List<Entry<?>> entries = new ArrayList<>();

		private Swap(MinecraftServer server) {
			this.server = server;
		}

		public <T extends SavedData> Swap with(SavedDataType<T> type, T fresh) {
			entries.add(new Entry<>(type, fresh));
			return this;
		}

		public void run(Runnable body) {
			call(() -> {
				body.run();
				return null;
			});
		}

		public <R> R call(Supplier<R> body) {
			String swapper = caller();
			List<Runnable> restores = new ArrayList<>();
			R result;
			try {
				for (Entry<?> entry : entries) {
					restores.add(entry.apply(server, swapper));
				}
				result = body.get();
			} catch (Throwable thrown) {
				restoreAll(restores, thrown);
				throw thrown;
			}
			restoreAll(restores, null);
			return result;
		}

		/** Puts every record back, in reverse order, even if one restore throws. A restore failure is suppressed on {@code primary}, or thrown when there is none. */
		private static void restoreAll(List<Runnable> restores, Throwable primary) {
			RuntimeException failed = null;
			for (int i = restores.size() - 1; i >= 0; i--) {
				try {
					restores.get(i).run();
				} catch (RuntimeException e) {
					if (primary != null) {
						primary.addSuppressed(e);
					} else if (failed == null) {
						failed = e;
					} else {
						failed.addSuppressed(e);
					}
				}
			}
			if (failed != null) {
				throw failed;
			}
		}
	}

	private record Entry<T extends SavedData>(SavedDataType<T> type, T fresh) {
		/** Swaps {@code fresh} in and returns the action that puts the world's record back. */
		Runnable apply(MinecraftServer server, String swapper) {
			T original = server.getDataStorage().computeIfAbsent(type);
			server.getDataStorage().set(type, fresh);
			LAST_SWAPPER.put(type, swapper);
			ACTIVE.merge(type, 1, Integer::sum);
			return () -> {
				ACTIVE.merge(type, -1, Integer::sum);
				server.getDataStorage().set(type, original);
			};
		}
	}
}
