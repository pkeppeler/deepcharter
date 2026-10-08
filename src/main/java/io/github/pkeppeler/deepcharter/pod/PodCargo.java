package io.github.pkeppeler.deepcharter.pod;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.command.FeatureCommands;
import io.github.pkeppeler.deepcharter.ore.OreCargoMenu;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;

/**
 * A pod's cargo bay: ore items, one per slot, each with a mass. Only the mod's ore is cargo: a vanilla ore or any
 * other stack is refused. The synced {@link PodData#CARGO_USED} and {@link PodData#CARGO_MASS} mirror the entries;
 * only this class writes them.
 *
 * <p>The saved cargo has a version ({@value #VERSION}). A save with no version is from before ore items: its entries
 * were vanilla ores, which are not cargo, so they are dropped and logged. A save of another version, or one with an
 * entry that does not decode, is kept as it was read and written back unchanged, and {@link #tryAdd} and {@link #dump}
 * throw until a build can read it. Nothing is dropped silently.
 */
public final class PodCargo {
	/** The version of the saved cargo; 1 is the first with ore items. */
	public static final int VERSION = 1;
	private static final String CARGO_KEY = "cargo";
	private static final String VERSION_KEY = "cargo_version";

	/** The stack and mass of an entry as saved, before it is known to be ore. */
	private record Saved(ItemStack stack, float mass) {
		static final Codec<Saved> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				ItemStack.CODEC.fieldOf("stack").forGetter(Saved::stack),
				Codec.FLOAT.fieldOf("mass").forGetter(Saved::mass)).apply(instance, Saved::new));
	}

	/** Saved cargo this build cannot read, held as it was read: its version if it had one, and its entries. */
	private record Unreadable(Optional<Integer> version, List<Tag> raw) {
	}

	/** One ore in one slot. The stack is the ore item; the mass is what it adds to the pod's cargo mass. */
	public record Entry(ItemStack stack, float mass) {
		/** A stack that is not ore, or a bad mass, is a decode error, never a throw: loading a pod must not crash. */
		static final Codec<Entry> CODEC = Saved.CODEC.flatXmap(
				saved -> {
					if (OreRegistry.typeOf(saved.stack()).isEmpty()) {
						return DataResult.error(() -> "only ore is cargo, not " + saved.stack());
					}
					if (!(saved.mass() >= 0f)) {
						return DataResult.error(() -> "ore mass must be a number, not negative: " + saved.mass());
					}
					return DataResult.success(new Entry(saved.stack(), saved.mass()));
				},
				entry -> DataResult.success(new Saved(entry.stack(), entry.mass())));

		public Entry {
			if (OreRegistry.typeOf(stack).isEmpty()) {
				throw new IllegalArgumentException("only ore is cargo, not " + stack);
			}
			if (!(mass >= 0f)) {
				throw new IllegalArgumentException("ore mass must be a number, not negative: " + mass);
			}
			stack = stack.copy();
		}

		/** The stack is mutable and has no value equality of its own, so compare what it holds. */
		@Override
		public boolean equals(Object other) {
			return other instanceof Entry entry && mass == entry.mass && ItemStack.matches(stack, entry.stack);
		}

		@Override
		public int hashCode() {
			return 31 * ItemStack.hashItemAndComponents(stack) + Float.hashCode(mass);
		}
	}

	private final List<Entry> entries = new ArrayList<>();
	/** The saved cargo this build cannot read, or null when the cargo is readable. */
	private Unreadable unreadable;
	private boolean discardLogged;

	/** Server only: adds one ore at its real mass if a slot is free, and returns whether it did. Anything but ore throws. */
	public boolean tryAdd(PodEntity pod, ItemStack ore) {
		return tryAdd(pod, ore, OreRegistry.typeOf(ore).map(OreType::mass)
				.orElseThrow(() -> new IllegalArgumentException("only ore is cargo, not " + ore)));
	}

	/** Server only: adds one ore at the given mass (not negative) if a slot is free, and returns whether it did. */
	public boolean tryAdd(PodEntity pod, ItemStack ore, float mass) {
		requireOwnServerPod(pod);
		requireReadable(pod);
		Entry entry = new Entry(ore, mass);
		if (entries.size() >= PodStats.of(pod).cargoSlots()) {
			return false;
		}
		entries.add(entry);
		sync(pod);
		return true;
	}

	/** How many of {@code ore} the bay holds; 0 while the saved cargo is unreadable. */
	public int count(OreType ore) {
		return entries.stream().filter(entry -> OreRegistry.typeOf(entry.stack()).orElseThrow() == ore).mapToInt(entry -> entry.stack().getCount()).sum();
	}

	/**
	 * Server only: removes up to {@code amount} (at least 1) of {@code ore}, in the bay's order, and returns how many it removed: fewer
	 * than asked when the bay holds fewer. An entry that holds more than is asked is shrunk and keeps its mass in proportion.
	 */
	public int take(PodEntity pod, OreType ore, int amount) {
		requireOwnServerPod(pod);
		requireReadable(pod);
		if (amount < 1) {
			throw new IllegalArgumentException("cannot take " + amount + " ore from the cargo");
		}
		int left = amount;
		for (ListIterator<Entry> it = entries.listIterator(); it.hasNext() && left > 0;) {
			Entry entry = it.next();
			if (OreRegistry.typeOf(entry.stack()).orElseThrow() != ore) {
				continue;
			}
			int held = entry.stack().getCount();
			if (held <= left) {
				it.remove();
				left -= held;
			} else {
				it.set(new Entry(entry.stack().copyWithCount(held - left), entry.mass() * (held - left) / held));
				left = 0;
			}
		}
		if (left < amount) {
			sync(pod);
		}
		return amount - left;
	}

	/** Server only: empties the bay and returns how many ore it held. */
	public int dump(PodEntity pod) {
		requireOwnServerPod(pod);
		requireReadable(pod);
		int dumped = entries.size();
		entries.clear();
		sync(pod);
		return dumped;
	}

	/** False while the saved cargo is unreadable: the bay then takes no ore, and {@link #tryAdd} and {@link #dump} throw. */
	public boolean isReadable() {
		return unreadable == null;
	}

	/** Logs, once for each load of the cargo, that a drilled ore was lost because the cargo is unreadable. */
	public void logDiscardedOre(PodEntity pod) {
		if (!discardLogged) {
			discardLogged = true;
			DeepCharter.LOGGER.error("Pod {}: cargo unreadable, drilled ore discarded", pod.getUUID());
		}
	}

	/** The bay's entries; empty while the saved cargo is unreadable. */
	public List<Entry> entries() {
		return List.copyOf(entries);
	}

	private void sync(PodEntity pod) {
		float mass = 0f;
		for (Entry entry : entries) {
			mass += entry.mass();
		}
		pod.setCargoUsed(entries.size());
		pod.setCargoMass(mass);
	}

	private void requireReadable(PodEntity pod) {
		if (unreadable != null) {
			throw new IllegalStateException("the cargo of pod " + pod.getUUID() + " was saved with version "
					+ unreadable.version().map(String::valueOf).orElse("none") + ", which this build cannot read; it is kept unchanged");
		}
	}

	private void requireOwnServerPod(PodEntity pod) {
		if (pod.cargo() != this) {
			throw new IllegalArgumentException("this cargo bay belongs to another pod");
		}
		if (pod.level().isClientSide()) {
			throw new IllegalStateException("cargo changes only on the server");
		}
	}

	/** Saves the cargo with the pod, with its version; unreadable cargo is written back as it was read. */
	public void save(ValueOutput output) {
		if (unreadable != null) {
			unreadable.version().ifPresent(version -> output.putInt(VERSION_KEY, version));
			ValueOutput.TypedOutputList<Dynamic<?>> raw = output.list(CARGO_KEY, Codec.PASSTHROUGH);
			unreadable.raw().forEach(tag -> raw.add(new Dynamic<>(NbtOps.INSTANCE, tag)));
			return;
		}
		output.putInt(VERSION_KEY, VERSION);
		ValueOutput.TypedOutputList<Entry> list = output.list(CARGO_KEY, Entry.CODEC);
		entries.forEach(list::add);
	}

	/** Restores the cargo saved by {@link #save} and derives the pod's synced counts from it. */
	public void load(ValueInput input, PodEntity pod) {
		entries.clear();
		unreadable = null;
		discardLogged = false;
		List<Tag> raw = new ArrayList<>();
		input.list(CARGO_KEY, Codec.PASSTHROUGH)
				.orElseThrow(() -> new IllegalStateException("saved pod has no '" + CARGO_KEY + "'"))
				.forEach(entry -> raw.add(entry.convert(NbtOps.INSTANCE).getValue()));
		Optional<Integer> version = input.getInt(VERSION_KEY);
		if (version.isEmpty()) {
			// Saved before ore items: every entry was a vanilla ore block, which is no longer cargo.
			if (!raw.isEmpty()) {
				DeepCharter.LOGGER.error("Pod {} was saved before ore items: dropped {} cargo entries, which were vanilla ores",
						pod.getUUID(), raw.size());
			}
			sync(pod);
			return;
		}
		List<Entry> decoded = new ArrayList<>();
		input.list(CARGO_KEY, Entry.CODEC).orElseThrow().forEach(decoded::add);
		if (version.get() != VERSION || decoded.size() < raw.size()) {
			unreadable = new Unreadable(version, raw);
			DeepCharter.LOGGER.error("Pod {} has cargo this build cannot read (version {}, {} of {} entries decode): kept unchanged, and the cargo refuses changes",
					pod.getUUID(), version.get(), decoded.size(), raw.size());
			sync(pod);
			return;
		}
		// Not checked against the slots: the bay's size is a stat that other features change, and a pod whose bay
		// shrank since it was saved must still load. A bay over its size refuses ore until it is under it again.
		entries.addAll(decoded);
		sync(pod);
	}

	public static void init() {
		FeatureCommands.register("pod", root -> root.then(Commands.literal("dump")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(PodCargo::dumpRiddenPod)));
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!(entity instanceof PodEntity pod) || !player.isSecondaryUseActive() || player.isSpectator()) {
				return InteractionResult.PASS;
			}
			if (player instanceof ServerPlayer serverPlayer) {
				OreCargoMenu.open(serverPlayer, pod);
			}
			return InteractionResult.SUCCESS;
		});
	}

	private static int dumpRiddenPod(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (!(source.getEntity() != null && source.getEntity().getVehicle() instanceof PodEntity pod)) {
			source.sendFailure(Component.translatable("deepcharter.pod.dump.no_pod"));
			return 0;
		}
		int dumped = pod.cargo().dump(pod);
		source.sendSuccess(() -> Component.translatable("deepcharter.pod.dump.success", dumped), true);
		return dumped;
	}
}
