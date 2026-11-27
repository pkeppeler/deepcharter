package io.github.pkeppeler.deepcharter.client.pod;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookKeys;
import io.github.pkeppeler.deepcharter.pod.PodDump;
import io.github.pkeppeler.deepcharter.pod.PodDumpPayload;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * The key (B by default, free in vanilla and in the mod, and away from the movement keys) that dumps the whole hold
 * from a seat (#1456). It must be held for {@link #HOLD_TICKS} ticks, with a bar filling on the action bar, so a
 * brush of the key dumps nothing; once full it asks the server once ({@link PodDumpPayload}), which decides, and
 * waits for the key to be let go.
 */
public final class PodDumpKeys {
    public static final KeyMapping DUMP = new KeyMapping(
            PodDump.KEY, InputConstants.Type.KEYBOARD, InputConstants.KEY_B, HandbookKeys.OPEN.getCategory());

    /** How long the key is held before the hold dumps: one second. */
    private static final int HOLD_TICKS = 20;
    /** Cells of the action bar's progress bar. */
    private static final int BAR_CELLS = 10;

    /** Ticks the key has been held, aboard with no screen open; past {@link #HOLD_TICKS} once the request is sent. */
    private static int held;

    private PodDumpKeys() {}

    public static void register() {
        KeyMappingHelper.registerKeyMapping(DUMP);
        ClientTickEvents.END_CLIENT_TICK.register(PodDumpKeys::tick);
    }

    private static void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) {
            held = 0;
            return;
        }
        boolean aboard = client.gui.screen() == null && player.getVehicle() instanceof PodEntity;
        if (!aboard || !DUMP.isDown()) {
            if (held > 0 && held < HOLD_TICKS) {
                player.sendOverlayMessage(Component.empty());
            }
            held = 0;
            return;
        }
        if (held >= HOLD_TICKS) {
            return;
        }
        held++;
        player.sendOverlayMessage(Component.translatable("deepcharter.pod.dump.holding", bar(held)));
        if (held == HOLD_TICKS) {
            ClientPlayNetworking.send(new PodDumpPayload());
        }
    }

    /** The progress bar for {@code ticks} of the hold: filled cells, then empty ones, in brackets. */
    private static String bar(int ticks) {
        int filled = ticks * BAR_CELLS / HOLD_TICKS;
        return "[" + "#".repeat(filled) + "-".repeat(BAR_CELLS - filled) + "]";
    }
}
