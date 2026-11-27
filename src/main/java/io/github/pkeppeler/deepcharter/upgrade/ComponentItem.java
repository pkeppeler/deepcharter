package io.github.pkeppeler.deepcharter.upgrade;

import net.minecraft.world.item.Item;

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
}
