package io.github.pkeppeler.deepcharter.ore;

import net.minecraft.world.item.Item;

/** One ore as an item: heavy, and never stacked. Its {@link OreWeight} component is what its tooltip shows. */
public final class OreItem extends Item {
    private final OreType type;

    OreItem(OreType type, Properties properties) {
        super(properties.component(OreRegistry.WEIGHT, OreWeight.INSTANCE));
        this.type = type;
    }

    public OreType type() {
        return type;
    }
}
