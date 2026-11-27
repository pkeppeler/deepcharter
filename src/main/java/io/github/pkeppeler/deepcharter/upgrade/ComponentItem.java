package io.github.pkeppeler.deepcharter.upgrade;

import io.github.pkeppeler.deepcharter.pod.PodLoad;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * One track's part as an item. What tier it is, and whose, is the {@link PartLabel} on the stack. An engine part's tooltip also says
 * what it lifts, in the units of the cockpit's LOAD gauge (#1541).
 */
public final class ComponentItem extends Item {
    private final ComponentTrack track;

    ComponentItem(ComponentTrack track, Properties properties) {
        super(properties);
        this.track = track;
    }

    public ComponentTrack track() {
        return track;
    }

    /** The lift line of an engine part with a label: {@link PodLoad#shownEngineLift} of its tier. */
    @Override
    public void appendHoverText(
            ItemStack stack,
            Item.TooltipContext context,
            TooltipDisplay display,
            Consumer<Component> consumer,
            TooltipFlag flag) {
        super.appendHoverText(stack, context, display, consumer, flag);
        if (track == ComponentTrack.ENGINE) {
            ComponentItems.labelOf(stack)
                    .ifPresent(label -> consumer.accept(
                            Component.translatable("item.deepcharter.part.lift", PodLoad.shownEngineLift(label.tier()))
                                    .withStyle(ChatFormatting.GRAY)));
        }
    }
}
