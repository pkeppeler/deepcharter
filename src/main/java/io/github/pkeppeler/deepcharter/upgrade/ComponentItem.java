package io.github.pkeppeler.deepcharter.upgrade;

import java.util.function.Consumer;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/** One track's part as an item. What tier it is, and whose, is the {@link PartLabel} on the stack. */
public final class ComponentItem extends Item {
	private final ComponentTrack track;

	ComponentItem(ComponentTrack track, Properties properties) {
		super(properties);
		this.track = track;
	}

	public ComponentTrack track() {
		return track;
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> consumer, TooltipFlag flag) {
		PartLabel label = stack.get(ComponentItems.LABEL);
		if (label == null) {
			consumer.accept(Component.translatable("item.deepcharter.part.unlabelled"));
			return;
		}
		consumer.accept(Component.translatable("item.deepcharter.part.tier", label.tier()));
		consumer.accept(Component.translatable("item.deepcharter.part.serial", label.serial()));
	}
}
