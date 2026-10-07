package io.github.pkeppeler.deepcharter.ore;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;

import io.github.pkeppeler.deepcharter.DeepCharter;

/** Carried ore slows a player on foot (SPEC section 4), so that the pod is the hauler. Ore in a shulker box or a bundle counts. */
public final class OreEncumbrance {
	private static final Identifier LOAD = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "ore_load");

	private OreEncumbrance() {
	}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> server.getPlayerList().getPlayers().forEach(OreEncumbrance::apply));
	}

	/** Total mass of the ore the player carries: every inventory slot, the offhand and armor included, and the contents of containers. */
	public static float carriedMass(ServerPlayer player) {
		float mass = 0f;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			mass += massOf(player.getInventory().getItem(slot));
		}
		return mass;
	}

	/** The mass of a stack's own ore and of everything inside it. */
	static float massOf(ItemStack stack) {
		float mass = OreRegistry.typeOf(stack).map(OreType::mass).orElse(0f) * stack.getCount();
		ItemContainerContents container = stack.get(DataComponents.CONTAINER);
		if (container != null) {
			mass += (float) container.nonEmptyItemCopyStream().mapToDouble(OreEncumbrance::massOf).sum();
		}
		BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
		if (bundle != null) {
			mass += (float) bundle.itemCopies().mapToDouble(OreEncumbrance::massOf).sum();
		}
		return mass;
	}

	private static void apply(ServerPlayer player) {
		OreTuning tuning = OreTuning.DEFAULT;
		double slowdown = Math.min(tuning.maxSlowdown(), carriedMass(player) * tuning.slowdownPerMass());
		AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
		// Setting a modifier marks the attribute dirty, which sends a packet, even when the value is the same.
		AttributeModifier current = speed.getModifier(LOAD);
		if (slowdown <= 0) {
			if (current != null) {
				speed.removeModifier(LOAD);
			}
		} else if (current == null || current.amount() != -slowdown) {
			speed.addOrUpdateTransientModifier(new AttributeModifier(LOAD, -slowdown, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		}
	}
}
