package io.github.pkeppeler.deepcharter.upgrade;

import io.github.pkeppeler.deepcharter.pod.PodLoad;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * The "Lifts N" line of an engine part's tooltip (#1541): what {@link PodLoad#shownEngineLift} says its tier lifts, in the units of the
 * cockpit's LOAD gauge. A tooltip provider is not handed the stack, so the client adds this line from the tooltip event.
 */
public final class PartLift {
    private PartLift() {}

    /** The line for {@code stack}, or empty when it is not a labelled engine part. */
    public static Optional<Component> line(ItemStack stack) {
        if (!ComponentItems.trackOf(stack).equals(Optional.of(ComponentTrack.ENGINE))) {
            return Optional.empty();
        }
        return ComponentItems.labelOf(stack)
                .map(label -> Component.translatable(
                                "item.deepcharter.part.lift", PodLoad.shownEngineLift(label.tier()))
                        .withStyle(ChatFormatting.GRAY));
    }
}
