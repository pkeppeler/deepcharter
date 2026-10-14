package io.github.pkeppeler.deepcharter.client.ore;

import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import net.minecraft.client.gui.screens.MenuScreens;

public final class OreClientRegistry {
    private OreClientRegistry() {}

    public static void register() {
        MenuScreens.register(OreRegistry.CARGO_MENU, OreCargoScreen::new);
    }
}
