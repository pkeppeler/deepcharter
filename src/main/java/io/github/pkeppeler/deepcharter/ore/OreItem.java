package io.github.pkeppeler.deepcharter.ore;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * One ore as an item: heavy, and never stacked. Its {@link OreWeight} component is what its tooltip says about being heavy, and
 * its tooltip names its mass in the units the pod's load is counted in (#1538).
 */
public final class OreItem extends Item {
    private final OreType type;

    OreItem(OreType type, Properties properties) {
        super(properties.component(OreRegistry.WEIGHT, OreWeight.INSTANCE));
        this.type = type;
    }

    public OreType type() {
        return type;
    }

    /**
     * The mass line: what {@link OreEncumbrance#massOf} counts for this stack (its {@link DrilledMass} when it has one), shown as
     * {@link OreEncumbrance#shown} rounds it, which is also how the pod screen shows the hold. Ore never stacks, so this is one item's mass.
     */
    @Override
    public void appendHoverText(
            ItemStack stack,
            Item.TooltipContext context,
            TooltipDisplay display,
            Consumer<Component> consumer,
            TooltipFlag flag) {
        super.appendHoverText(stack, context, display, consumer, flag);
        consumer.accept(Component.translatable(
                        "deepcharter.ore.tooltip.mass", OreEncumbrance.shown(OreEncumbrance.massOf(stack)))
                .withStyle(ChatFormatting.GRAY));
    }
}
