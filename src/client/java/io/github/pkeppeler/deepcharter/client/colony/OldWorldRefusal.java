package io.github.pkeppeler.deepcharter.client.colony;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.colony.WorldFormatGate;
import io.github.pkeppeler.deepcharter.colony.WorldFormatGate.Refusal;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.WorldStem;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource.LevelStorageAccess;
import net.minecraft.world.level.storage.SavedDataStorage;

/**
 * Refuses an old world from the singleplayer world list, before its server exists (ADR 0059). A server that fails to start is a
 * crash report to a singleplayer client, so the check runs here, on the world's saved data, and shows a message screen instead.
 * {@code WorldOpenFlows.openWorldCheckWorldStemCompatibility} calls it first: it has the world's storage and registries, has started nothing, and runs before vanilla's backup prompt, so a player is never asked to back up a world that is then refused.
 */
public final class OldWorldRefusal {
    private OldWorldRefusal() {}

    /**
     * Shows the refusal screen and returns true when the world must not start. The world's storage and resources are closed, so
     * the world can be opened again, and nothing is written to the world. The screen's button runs {@code onCancel}, as vanilla's
     * own refusals do: it returns to the world list.
     */
    public static boolean refuse(Minecraft client, LevelStorageAccess access, WorldStem worldStem, Runnable onCancel) {
        Optional<Refusal> refusal = read(access, worldStem);
        if (refusal.isEmpty()) {
            return false;
        }
        DeepCharter.LOGGER.error(
                "Refusing to open the world {}: {}",
                access.getLevelId(),
                refusal.get().reason());
        worldStem.close();
        access.safeClose();
        // Vanilla's screen, so the layout gate for the mod's own screens (test_screen_layout_gate.py) has nothing
        // to check.
        Screen message = new AlertScreen(
                onCancel,
                Component.translatable("deepcharter.world_format.refused.title"),
                refusal.get().message());
        client.gui.setScreen(message);
        return true;
    }

    private static Optional<Refusal> read(LevelStorageAccess access, WorldStem worldStem) {
        // The server builds its storage the same way; this one is read, never written: nothing is dirty, so closing it
        // saves nothing.
        try (SavedDataStorage storage = new SavedDataStorage(
                access.getLevelPath(LevelResource.DATA),
                DataFixers.getDataFixer(),
                worldStem.registries().compositeAccess())) {
            return WorldFormatGate.refusal(storage);
        }
    }
}
