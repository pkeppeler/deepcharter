package io.github.pkeppeler.deepcharter.pod;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import com.mojang.brigadier.context.CommandContext;

import io.github.pkeppeler.deepcharter.command.FeatureCommands;

/**
 * A pod's cargo bay: ore entries, one per slot, each with a mass. Ore is a cargo entry, not an item, for now.
 * The synced {@link PodData#CARGO_USED} and {@link PodData#CARGO_MASS} mirror the entries; only this class writes them.
 */
public final class PodCargo {
	private static final String CARGO_KEY = "cargo";

	/** One ore in one slot. */
	public record Entry(Block ore, float mass) {
		static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				BuiltInRegistries.BLOCK.byNameCodec().fieldOf("ore").forGetter(Entry::ore),
				Codec.FLOAT.fieldOf("mass").forGetter(Entry::mass)).apply(instance, Entry::new));

		public Entry {
			if (!(mass >= 0f)) {
				throw new IllegalArgumentException("ore mass must be a number, not negative: " + mass);
			}
		}
	}

	private final List<Entry> entries = new ArrayList<>();

	/** Server only: adds one ore of the default mass if a slot is free, and returns whether it did. */
	public boolean tryAdd(PodEntity pod, Block ore) {
		return tryAdd(pod, ore, PodTuning.DEFAULT.cargo().defaultOreMass());
	}

	/** Server only: adds one ore of the given mass (not negative) if a slot is free, and returns whether it did. */
	public boolean tryAdd(PodEntity pod, Block ore, float mass) {
		requireOwnServerPod(pod);
		if (entries.size() >= PodTuning.DEFAULT.cargo().slots()) {
			return false;
		}
		entries.add(new Entry(ore, mass));
		sync(pod);
		return true;
	}

	/** Server only: empties the bay and returns how many ore it held. */
	public int dump(PodEntity pod) {
		requireOwnServerPod(pod);
		int dumped = entries.size();
		entries.clear();
		sync(pod);
		return dumped;
	}

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

	private void requireOwnServerPod(PodEntity pod) {
		if (pod.cargo() != this) {
			throw new IllegalArgumentException("this cargo bay belongs to another pod");
		}
		if (pod.level().isClientSide()) {
			throw new IllegalStateException("cargo changes only on the server");
		}
	}

	/** Saves the cargo with the pod. */
	public void save(ValueOutput output) {
		ValueOutput.TypedOutputList<Entry> list = output.list(CARGO_KEY, Entry.CODEC);
		entries.forEach(list::add);
	}

	/** Restores the cargo saved by {@link #save} and derives the pod's synced counts from it. */
	public void load(ValueInput input, PodEntity pod) {
		entries.clear();
		ValueInput.TypedInputList<Entry> list = input.list(CARGO_KEY, Entry.CODEC)
				.orElseThrow(() -> new IllegalStateException("saved pod has no '" + CARGO_KEY + "'"));
		list.forEach(entries::add);
		if (entries.size() > PodTuning.DEFAULT.cargo().slots()) {
			throw new IllegalStateException("saved cargo has " + entries.size() + " ore, the bay holds "
					+ PodTuning.DEFAULT.cargo().slots());
		}
		sync(pod);
	}

	public static void init() {
		FeatureCommands.register("pod", root -> root.then(Commands.literal("dump")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(PodCargo::dumpRiddenPod)));
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
