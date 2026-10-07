package io.github.pkeppeler.deepcharter.pod;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;

import io.github.pkeppeler.deepcharter.command.FeatureCommands;
import io.github.pkeppeler.deepcharter.ore.OreCargoMenu;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;

/**
 * A pod's cargo bay: ore items, one per slot, each with a mass. Only the mod's ore is cargo: a vanilla ore or any
 * other stack is refused. The synced {@link PodData#CARGO_USED} and {@link PodData#CARGO_MASS} mirror the entries;
 * only this class writes them.
 */
public final class PodCargo {
	private static final String CARGO_KEY = "cargo";

	/** One ore in one slot. The stack is the ore item; the mass is what it adds to the pod's cargo mass. */
	public record Entry(ItemStack stack, float mass) {
		static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				ItemStack.CODEC.fieldOf("stack").forGetter(Entry::stack),
				Codec.FLOAT.fieldOf("mass").forGetter(Entry::mass)).apply(instance, Entry::new));

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

	/** Server only: adds one ore at its real mass if a slot is free, and returns whether it did. Anything but ore throws. */
	public boolean tryAdd(PodEntity pod, ItemStack ore) {
		return tryAdd(pod, ore, OreRegistry.typeOf(ore).map(OreType::mass)
				.orElseThrow(() -> new IllegalArgumentException("only ore is cargo, not " + ore)));
	}

	/** Server only: adds one ore at the given mass (not negative) if a slot is free, and returns whether it did. */
	public boolean tryAdd(PodEntity pod, ItemStack ore, float mass) {
		requireOwnServerPod(pod);
		Entry entry = new Entry(ore, mass);
		if (entries.size() >= PodTuning.DEFAULT.cargo().slots()) {
			return false;
		}
		entries.add(entry);
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
