package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.colony.WorldFormat;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;

/**
 * An old world for the client tests and the evidence scenario of #800: a world whose colony is built and whose world-format stamp is
 * gone, as every world made before the stamp is. It is made with the game's own code, not with a fixture file, so it needs no upkeep.
 */
public final class OldWorlds {
    private OldWorlds() {}

    /**
     * Makes a world, replaces its stamp through {@link WorldData} with one of format 0, saves it and closes it. The save holds its
     * colony and a stamp below {@link WorldFormat#LEGACY}, so it is refused, as is a world with no stamp at all (the legacy format).
     */
    public static TestWorldSave make(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getServer().runOnServer(server -> {
                if (!WorldFormat.get(server).format().equals(OptionalInt.of(WorldFormat.CURRENT))) {
                    throw new AssertionError(
                            "the new world's colony should have stamped it with " + WorldFormat.CURRENT);
                }
                WorldData.with(server, WorldFormat.TYPE, oldStamp(), () -> server.saveEverything(true, true, false));
                // Putting the world's own record back marks it dirty, and the stop would write the stamp over the
                // stripped one.
                WorldFormat.get(server).setDirty(false);
            });
            return singleplayer.getWorldSave();
        }
    }

    private static WorldFormat oldStamp() {
        CompoundTag saved = new CompoundTag();
        saved.putInt("version", WorldFormat.VERSION);
        saved.putInt("format", WorldFormat.LEGACY - 1);
        return WorldFormat.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
    }

    /** Leaves the refusal screen through its back button and the world list's, to the title screen, where a client test must end. */
    public static void backToTheTitleScreen(ClientGameTestContext context) {
        context.clickScreenButton("gui.back");
        ClientWait.screen(context, SelectWorldScreen.class);
        context.clickScreenButton("gui.back");
        ClientWait.screen(context, TitleScreen.class);
    }

    /** Opens the saved world from the world list's code path and waits for the refusal screen, which is an {@link AlertScreen}. */
    public static void open(ClientGameTestContext context, TestWorldSave save) {
        String levelId = save.getSaveDirectory().getFileName().toString();
        context.runOnClient(client -> client.createWorldOpenFlows()
                .openWorld(levelId, () -> client.gui.setScreen(new SelectWorldScreen(new TitleScreen()))));
        ClientWait.screen(context, AlertScreen.class);
    }

    /** The SHA-256 of every region file under the save, by its path in the save, to compare before and after. */
    public static Map<String, String> regionHashes(Path save) {
        Map<String, String> hashes = new TreeMap<>();
        try (Stream<Path> files = Files.walk(save)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".mca")).toList()) {
                hashes.put(
                        save.relativize(file).toString(), HexFormat.of().formatHex(sha256(Files.readAllBytes(file))));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return hashes;
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java has SHA-256", e);
        }
    }
}
