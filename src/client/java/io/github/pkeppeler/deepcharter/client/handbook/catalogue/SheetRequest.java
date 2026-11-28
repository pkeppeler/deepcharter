package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import net.minecraft.world.item.Item;

/**
 * A spec sheet asked for.
 *
 * @param item whose sheet it is
 * @param latest whether it is the latest sheet offered, opened by the key with no item under the cursor; the sheet says
 *     LATEST so the player knows it is not the item they pointed at
 * @param keyHeld whether the spec sheet key opened it and may still be down: a held key repeats as presses, which the
 *     sheet ignores until the key is let go, so holding it does not open the sheet and close it again
 */
record SheetRequest(Item item, boolean latest, boolean keyHeld) {
    /** A sheet the player or the game asked for by name: the SPEC SHEET button, a link, a test. */
    static SheetRequest of(Item item) {
        return new SheetRequest(item, false, false);
    }
}
