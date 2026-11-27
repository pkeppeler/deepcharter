package io.github.pkeppeler.deepcharter.menu;

import net.minecraft.world.SimpleContainer;

/**
 * The container of a {@link SlottedMenu}'s slots that show saved state rather than hold items: the server's menu
 * refills it from that state, so it is never a store. A slot over it must lock ({@link SlotRule#locking()}); otherwise
 * a stack taken out would be refilled from the state, and the item duped.
 */
public final class MirrorContainer extends SimpleContainer {
    public MirrorContainer(int size) {
        super(size);
    }
}
