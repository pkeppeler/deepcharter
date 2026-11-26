package io.github.pkeppeler.deepcharter.client.terminal;

import io.github.pkeppeler.deepcharter.client.sound.TypewriterSound;
import io.github.pkeppeler.deepcharter.client.ui.CrtMenuScreen;
import io.github.pkeppeler.deepcharter.terminal.RepairMenu;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * An unrepaired terminal's screen: its part slots, each showing the part that goes there until it is in, over the
 * player's inventory. The text says to insert every part, or, while the terminal before it is broken, which to repair
 * first, and it follows the server when that terminal is repaired while the screen is open. Every placement is the
 * server's to allow ({@link RepairMenu}).
 */
public final class RepairScreen extends CrtMenuScreen<RepairMenu> {
    public RepairScreen(RepairMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, intro(menu), new TypewriterSound());
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        retype(intro(menu));
    }

    private static Component intro(RepairMenu menu) {
        if (menu.unlocked()) {
            return Component.translatable("screen.deepcharter.terminal.offline");
        }
        TerminalType first = menu.type().prerequisite().orElseThrow();
        return Component.translatable(
                "screen.deepcharter.terminal.offline_locked",
                Component.translatable(first.block().getDescriptionId())
                        .getString()
                        .toUpperCase(Locale.ROOT));
    }
}
