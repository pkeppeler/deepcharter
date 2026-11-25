package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyEvents;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.colony.WorldFormat;
import io.github.pkeppeler.deepcharter.colony.WorldFormatGate;
import io.github.pkeppeler.deepcharter.colony.WorldFormatGate.Refusal;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.SavedDataStorage;

/**
 * Server GameTests for #800: a colony stamps its world with the world format, and a world made in an older format, or whose stamp
 * cannot be read, is refused before its first level is made (ADR 0059). The tests swap the world's {@link WorldFormat} and
 * {@link ColonySite} through {@link WorldData}, and read the verdict of {@link WorldFormatGate#refusal}, which is what the server hook and
 * the singleplayer screen both ask.
 */
public class WorldFormatTest {
    private static final String REFUSED = "deepcharter.world_format.refused";
    private static final String UNREADABLE = "deepcharter.world_format.unreadable";

    /** The id and the saved form are permanent: a build that changed either would find no stamp in every world made so far. */
    @GameTest
    public void theStampsIdAndSavedFormAreFixed(GameTestHelper helper) {
        if (!WorldFormat.TYPE.id().toString().equals("deepcharter:world_format")) {
            throw failure(helper, "the saved-data id is permanent, got %s", WorldFormat.TYPE.id());
        }
        CompoundTag saved = new CompoundTag();
        saved.putInt("version", 1);
        saved.putInt("format", 1);
        WorldFormat read = WorldFormat.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        if (!read.isReadable() || !read.format().equals(OptionalInt.of(1))) {
            throw failure(helper, "version 1 with format 1 should read as the stamp 1, got %s", read.format());
        }
        if (!saved.equals(WorldFormat.CODEC.encodeStart(NbtOps.INSTANCE, read).getOrThrow())) {
            throw failure(helper, "the saved form of the stamp changed: expected %s", saved);
        }
        helper.succeed();
    }

    @GameTest
    public void aColonyBuiltWhenTheTestServerStartedIsStamped(GameTestHelper helper) {
        MinecraftServer server = server(helper);
        WorldFormat stamp = WorldFormat.get(server);
        if (!stamp.isReadable() || !stamp.format().equals(OptionalInt.of(WorldFormat.CURRENT))) {
            throw failure(helper, "the colony of the test world should carry the stamp %s", WorldFormat.CURRENT);
        }
        helper.succeed();
    }

    @GameTest
    public void aNewColonyGetsTheStamp(GameTestHelper helper) {
        MinecraftServer server = server(helper);
        ColonySite.Placed placed = Colony.placed(server).orElseThrow();
        WorldFormat fresh = new WorldFormat();
        WorldData.with(server, WorldFormat.TYPE, fresh, () -> {
            if (fresh.format().isPresent()) {
                throw failure(helper, "a fresh record has no stamp");
            }
            // The colony was built once, so it is not built again: the event fires again only here.
            ColonyEvents.BUILT.invoker().onBuilt(server, placed);
            if (!fresh.format().equals(OptionalInt.of(WorldFormat.CURRENT))) {
                throw failure(
                        helper, "BUILT should stamp the world with %s, got %s", WorldFormat.CURRENT, fresh.format());
            }
        });
        helper.succeed();
    }

    /**
     * The format is the campaign overworld's with layer 3, 3 (#972, ADR 0059): a world made before the stamp existed, with a colony, is
     * refused.
     */
    @GameTest
    public void theFormatOfThisBuildIsThreeAndAWorldWithNoStampIsRefusedAtIt(GameTestHelper helper) {
        MinecraftServer server = server(helper);
        if (WorldFormat.CURRENT != 3 || WorldFormat.LEGACY != 1) {
            throw failure(
                    helper,
                    "the format of the campaign overworld with layer 3 is 3 over the legacy 1, got %s over %s",
                    WorldFormat.CURRENT,
                    WorldFormat.LEGACY);
        }
        WorldData.with(server, WorldFormat.TYPE, new WorldFormat(), () -> expectRefused(helper, server, REFUSED));
        helper.succeed();
    }

    @GameTest
    public void aCurrentStampPasses(GameTestHelper helper) {
        MinecraftServer server = server(helper);
        WorldData.with(server, WorldFormat.TYPE, stamped(WorldFormat.CURRENT), () -> expectPass(helper, server));
        helper.succeed();
    }

    /** A stamp of a higher number than this build's is a newer build's world: the gate refuses lower numbers only. */
    @GameTest
    public void aHigherStampPasses(GameTestHelper helper) {
        MinecraftServer server = server(helper);
        WorldData.with(server, WorldFormat.TYPE, stamped(WorldFormat.CURRENT + 1), () -> expectPass(helper, server));
        helper.succeed();
    }

    /** An empty stamp counts as the legacy format. */
    @GameTest
    public void anEmptyStampIsRefusedOnceTheFormatIsRaised(GameTestHelper helper) {
        MinecraftServer server = server(helper);
        WorldData.with(server, WorldFormat.TYPE, new WorldFormat(), () -> expectRefused(helper, server, REFUSED));
        helper.succeed();
    }

    @GameTest
    public void anOlderStampIsRefusedOnceTheFormatIsRaised(GameTestHelper helper) {
        MinecraftServer server = server(helper);
        WorldData.with(
                server, WorldFormat.TYPE, stamped(WorldFormat.LEGACY), () -> expectRefused(helper, server, REFUSED));
        helper.succeed();
    }

    @GameTest
    public void anUnreadableStampIsRefused(GameTestHelper helper) {
        MinecraftServer server = server(helper);
        WorldFormat unreadable = unreadableStamp();
        WorldData.with(server, WorldFormat.TYPE, unreadable, () -> expectRefused(helper, server, UNREADABLE));
        helper.succeed();
    }

    /** The BUILT listener runs at server start: it must not throw on a record it cannot read, and must leave it as it is. */
    @GameTest
    public void aBuiltColonyLeavesAnUnreadableStampAloneAndDoesNotThrow(GameTestHelper helper) {
        MinecraftServer server = server(helper);
        ColonySite.Placed placed = Colony.placed(server).orElseThrow();
        WorldFormat unreadable = unreadableStamp();
        WorldData.with(server, WorldFormat.TYPE, unreadable, () -> {
            ColonyEvents.BUILT.invoker().onBuilt(server, placed);
            if (!UnreadableChecks.futureData()
                    .equals(WorldFormat.CODEC
                            .encodeStart(NbtOps.INSTANCE, unreadable)
                            .getOrThrow())) {
                throw failure(helper, "an unreadable stamp must be written back as it was read");
            }
        });
        helper.succeed();
    }

    @GameTest
    public void aWorldWithoutAColonyPasses(GameTestHelper helper) {
        MinecraftServer server = server(helper);
        WorldData.swap(server)
                .with(ColonySite.TYPE, new ColonySite())
                .with(WorldFormat.TYPE, new WorldFormat())
                .run(() -> expectPass(helper, server));
        helper.succeed();
    }

    /** A world whose colony was begun and not finished has no built colony yet: its build can be finished, and stamps the world. */
    @GameTest
    public void aWorldWhoseColonyBuildStoppedHalfWayPasses(GameTestHelper helper) {
        MinecraftServer server = server(helper);
        CompoundTag unfinished = ((CompoundTag) ColonySite.CODEC
                        .encodeStart(NbtOps.INSTANCE, ColonySite.get(server))
                        .getOrThrow())
                .copy();
        unfinished.getCompound("colony").orElseThrow().putBoolean("finished", false);
        ColonySite begun = ColonySite.CODEC.parse(NbtOps.INSTANCE, unfinished).getOrThrow();
        WorldData.swap(server)
                .with(ColonySite.TYPE, begun)
                .with(WorldFormat.TYPE, new WorldFormat())
                .run(() -> expectPass(helper, server));
        helper.succeed();
    }

    /**
     * The verdict from files on disk, as the singleplayer screen reads it before a server exists: a world saved with its stamp
     * passes, the same world with the stamp file gone is refused, and asking creates no file.
     */
    @GameTest
    public void theVerdictReadFromDiskNeedsTheStampFileAndWritesNothing(GameTestHelper helper) {
        MinecraftServer server = server(helper);
        Path dir = tempDir();
        try {
            try (SavedDataStorage writer = storage(server, dir)) {
                ColonySite colony = ColonySite.get(server);
                colony.setDirty();
                writer.set(ColonySite.TYPE, colony);
                writer.set(WorldFormat.TYPE, stamped(WorldFormat.CURRENT));
                writer.saveAndJoin();
            }
            try (SavedDataStorage reader = storage(server, dir)) {
                if (WorldFormatGate.refusal(reader).isPresent()) {
                    throw failure(helper, "a world saved with its stamp should pass");
                }
            }
            Path stampFile = dir.resolve("deepcharter").resolve("world_format.dat");
            Files.delete(stampFile);
            try (SavedDataStorage reader = storage(server, dir)) {
                Optional<Refusal> refusal = WorldFormatGate.refusal(reader);
                if (refusal.isEmpty() || !isMessage(refusal.get(), REFUSED)) {
                    throw failure(helper, "a world saved without its stamp file should be refused, got %s", refusal);
                }
                if (WorldFormatGate.refusal(reader, WorldFormat.LEGACY).isPresent()) {
                    throw failure(
                            helper, "a world with no stamp file is the legacy format, so it starts at that format");
                }
            }
            if (Files.exists(stampFile)) {
                throw failure(helper, "reading the verdict wrote the stamp file");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            deleteTree(dir);
        }
        helper.succeed();
    }

    /** The message points to PLAYING.md in every text the player can read, and the log line has the English reason. */
    @GameTest
    public void theMessagePointsToPlayingMd(GameTestHelper helper) {
        MinecraftServer server = server(helper);
        WorldData.with(server, WorldFormat.TYPE, new WorldFormat(), () -> {
            Refusal refusal = WorldFormatGate.refusal(server).orElseThrow();
            if (refusal.reason().isBlank()) {
                throw failure(helper, "a refusal names its reason for the log");
            }
            String text = refusal.message().getString();
            if (!text.contains("PLAYING.md")) {
                throw failure(helper, "the refusal text should point to PLAYING.md, got: %s", text);
            }
        });
        helper.succeed();
    }

    private static void expectPass(GameTestHelper helper, MinecraftServer server) {
        Optional<Refusal> refusal = WorldFormatGate.refusal(server);
        if (refusal.isPresent()) {
            throw failure(
                    helper,
                    "the world should start, got the refusal: %s",
                    refusal.get().reason());
        }
    }

    private static void expectRefused(GameTestHelper helper, MinecraftServer server, String key) {
        Optional<Refusal> refusal = WorldFormatGate.refusal(server);
        if (refusal.isEmpty()) {
            throw failure(helper, "the world should be refused with %s", key);
        }
        if (!isMessage(refusal.get(), key)) {
            throw failure(
                    helper,
                    "expected the message %s, got %s",
                    key,
                    refusal.get().message());
        }
    }

    private static boolean isMessage(Refusal refusal, String key) {
        return refusal.message().getContents() instanceof TranslatableContents contents
                && contents.getKey().equals(key);
    }

    private static WorldFormat unreadableStamp() {
        return WorldFormat.CODEC
                .parse(NbtOps.INSTANCE, UnreadableChecks.futureData())
                .getOrThrow();
    }

    /** A record as a world of that format saved it. */
    private static WorldFormat stamped(int format) {
        CompoundTag saved = new CompoundTag();
        saved.putInt("version", WorldFormat.VERSION);
        saved.putInt("format", format);
        return WorldFormat.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
    }

    private static MinecraftServer server(GameTestHelper helper) {
        return helper.getLevel().getServer();
    }

    private static SavedDataStorage storage(MinecraftServer server, Path dir) {
        return new SavedDataStorage(dir, server.getFixerUpper(), server.registryAccess());
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("world-format");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
