package io.github.pkeppeler.deepcharter.repair;

import java.util.Optional;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** One of the six items of the repair station. A right click uses it, and only the server decides what that does. */
public final class ConsumableItem extends Item {
	private final Consumable consumable;

	public ConsumableItem(Consumable consumable, Properties properties) {
		super(properties);
		this.consumable = consumable;
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (!(player instanceof ServerPlayer server)) {
			return InteractionResult.PASS;
		}
		ItemStack stack = server.getItemInHand(hand);
		Optional<Component> refusal = Consumables.use(server, consumable);
		if (refusal.isPresent()) {
			server.sendOverlayMessage(refusal.get());
			return InteractionResult.FAIL;
		}
		server.getCooldowns().addCooldown(stack, RepairTuning.DEFAULT.itemCooldownTicks());
		stack.consume(1, server);
		return InteractionResult.SUCCESS;
	}
}
