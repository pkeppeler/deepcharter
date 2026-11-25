package io.github.pkeppeler.deepcharter.client.handbook.catalogue;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookKeys;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

/**
 * The catalogue's keys, rebindable, in the mod's key category ({@link HandbookKeys}): R on an item shows its recipes, U its uses and A stars
 * it, and O shows or hides the panels. R is also the pilot's lining key, which is used only in a pod with no screen open, so the two never meet.
 * J opens the latest spec sheet the server offered.
 */
public final class CatalogueKeys {
    public static final KeyMapping RECIPES = key("recipes", InputConstants.KEY_R);
    public static final KeyMapping USES = key("uses", InputConstants.KEY_U);
    public static final KeyMapping FAVOURITE = key("favourite", InputConstants.KEY_A);
    public static final KeyMapping SPEC_SHEET = key("spec_sheet", InputConstants.KEY_J);
    public static final KeyMapping TOGGLE = key("toggle", InputConstants.KEY_O);

    private CatalogueKeys() {}

    private static KeyMapping key(String name, int code) {
        return new KeyMapping(
                "key.deepcharter.catalogue." + name,
                InputConstants.Type.KEYBOARD,
                code,
                HandbookKeys.OPEN.getCategory());
    }

    static void register() {
        KeyMappingHelper.registerKeyMapping(RECIPES);
        KeyMappingHelper.registerKeyMapping(USES);
        KeyMappingHelper.registerKeyMapping(FAVOURITE);
        KeyMappingHelper.registerKeyMapping(SPEC_SHEET);
        KeyMappingHelper.registerKeyMapping(TOGGLE);
    }
}
