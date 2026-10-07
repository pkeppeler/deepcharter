package io.github.pkeppeler.deepcharter.ore;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

import io.github.pkeppeler.deepcharter.DeepCharter;

/** Carried ore slows a player on foot (SPEC section 4), so that the pod is the hauler. */
public final class OreEncumbrance {
	private static final Identifier LOAD = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "ore_load");

	private OreEncumbrance() {
	}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(server -> server.getPlayerList().getPlayers().forEach(OreEncumbrance::apply));
	}

	/** Total mass of the ore in every inventory slot, in pod units. */
	public static float carriedMass(ServerPlayer player) {
		float mass = 0f;
		for (ItemStack stack : player.getInventory()) {
			mass += OreRegistry.typeOf(stack).map(OreType::mass).orElse(0f) * stack.getCount();
		}
		return mass;
	}

	private static void apply(ServerPlayer player) {
		OreTuning tuning = OreTuning.DEFAULT;
		double slowdown = Math.min(tuning.maxSlowdown(), carriedMass(player) * tuning.slowdownPerMass());
		AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
		if (slowdown <= 0) {
			speed.removeModifier(LOAD);
		} else {
			speed.addOrUpdateTransientModifier(new AttributeModifier(LOAD, -slowdown, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		}
	}
}
