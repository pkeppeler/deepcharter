package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.handbook.FindResult;
import io.github.pkeppeler.deepcharter.handbook.HandbookTuning;
import io.github.pkeppeler.deepcharter.handbook.Notes;
import io.github.pkeppeler.deepcharter.handbook.PencilPath;
import io.github.pkeppeler.deepcharter.handbook.PencilPaths;
import io.github.pkeppeler.deepcharter.handbook.PencilPathsData;
import io.github.pkeppeler.deepcharter.handbook.PencilPlace;
import io.github.pkeppeler.deepcharter.handbook.PencilSyncPayload;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerStructures;
import io.github.pkeppeler.deepcharter.layer.StructureKind;
import io.github.pkeppeler.deepcharter.layer.StructureSite;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.TestCharters;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import io.netty.buffer.Unpooled;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for #849: a Note found draws its pencil path, the path completes when the charter reaches the place it points to
 * (the Prospector's wreck and the Kells' shaft, in the world; another Note), the progress is the charter's and is carried like Notes,
 * and the saved paths are versioned. The two shipped paths need the built world, so their tests run in the layer's level; the sharing
 * and carry rules run on a path that ends at another Note, which needs no world.
 */
public class PencilPathsTest {
    /** N07 draws it and finding N09 ends it: the shipped paths' sharing and carry, with no world to build. */
    private static final PencilPath TO_ANOTHER_NOTE = new PencilPath("test_to_note", 7, new PencilPlace.Note(9));

    private static final List<PencilPath> ONLY_THAT_PATH = List.of(TO_ANOTHER_NOTE);

    /** A mock player in survival: the server's test players start in creative, and a creative player completes no path. */
    private static MockPlayer survivor(GameTestHelper helper, String name) {
        MockPlayer mock = MockPlayers.join(helper, name);
        mock.player().setGameMode(GameType.SURVIVAL);
        return mock;
    }

    private static void find(GameTestHelper helper, ServerPlayer player, int number, FindResult expected) {
        FindResult result = Notes.find(player, Notes.id(number));
        if (result != expected) {
            throw helper.assertionException(
                    "finding N%02d as %s should be %s, was %s",
                    number, player.getGameProfile().name(), expected, result);
        }
    }

    private static Set<Identifier> completed(MinecraftServer server, ServerPlayer player) {
        return PencilPaths.completedFor(server, player.getUUID());
    }

    @GameTest
    public void theShippedPathsArePinned(GameTestHelper helper) {
        List<String> ids =
                PencilPaths.ALL.stream().map(path -> path.id().toString()).toList();
        if (!ids.equals(List.of("deepcharter:pencil/ones_below", "deepcharter:pencil/old_shaft"))) {
            throw helper.assertionException("the shipped path ids changed: %s", ids);
        }
        List<Integer> notes = PencilPaths.ALL.stream().map(PencilPath::fromNote).toList();
        if (!notes.equals(List.of(7, 10))) {
            throw helper.assertionException("the Notes that draw the shipped paths changed: %s", notes);
        }
        if (!(PencilPaths.ONES_BELOW.place() instanceof PencilPlace.Wreck)
                || !PencilPaths.OLD_SHAFT.place().equals(new PencilPlace.Structure(StructureKind.KELLS_SHAFT))) {
            throw helper.assertionException(
                    "the shipped paths end at the Prospector's wreck and the Kells' shaft, not %s and %s",
                    PencilPaths.ONES_BELOW.place(), PencilPaths.OLD_SHAFT.place());
        }
        for (PencilPath path : PencilPaths.ALL) {
            if (!net.minecraft.locale.Language.getInstance().has(path.hintKey())
                    || !net.minecraft.locale.Language.getInstance().has(path.placeKey())) {
                throw helper.assertionException(
                        "%s needs the lang keys %s and %s", path.id(), path.hintKey(), path.placeKey());
            }
        }
        helper.succeed();
    }

    @GameTest
    public void findingANoteDrawsItsPathForTheWholeCharterAndNoOtherNoteDoes(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer director = survivor(helper, "Director").player();
        ServerPlayer crew = survivor(helper, "Crew").player();
        ServerPlayer outsider = survivor(helper, "Outsider").player();
        CharterId id = TestCharters.found(helper, server, director);
        TestCharters.join(helper, server, id, director, crew);
        if (!PencilPaths.drawnFor(server, director.getUUID()).isEmpty()) {
            throw helper.assertionException("with no Note found nothing is drawn");
        }

        // N05 is the survey stake tag: a Note, and no pencil path starts at it.
        find(helper, director, 5, FindResult.FOUND);
        if (!PencilPaths.drawnFor(server, director.getUUID()).isEmpty()) {
            throw helper.assertionException("a Note that no path starts at draws nothing");
        }
        find(helper, director, 7, FindResult.FOUND);
        for (ServerPlayer member : List.of(director, crew)) {
            if (!PencilPaths.drawnFor(server, member.getUUID()).equals(List.of(PencilPaths.ONES_BELOW))) {
                throw helper.assertionException(
                        "N07 draws its path for %s: %s",
                        member.getGameProfile().name(), PencilPaths.drawnFor(server, member.getUUID()));
            }
        }
        if (!PencilPaths.drawnFor(server, outsider.getUUID()).isEmpty()) {
            throw helper.assertionException("a path is drawn for the charter that found the Note, not for anyone else");
        }
        find(helper, crew, 10, FindResult.FOUND);
        if (!PencilPaths.drawnFor(server, director.getUUID()).equals(PencilPaths.ALL)) {
            throw helper.assertionException(
                    "N10 draws the second path, in the sheet's order: %s",
                    PencilPaths.drawnFor(server, director.getUUID()));
        }
        if (!completed(server, director).isEmpty()) {
            throw helper.assertionException("a path starts open: finding the Note completes nothing");
        }
        helper.succeed();
    }

    @GameTest
    public void aPathEndingAtAnotherNoteCompletesWhenTheCharterFindsIt(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer directorMock = survivor(helper, "Director");
        ServerPlayer director = directorMock.player();
        ServerPlayer crew = survivor(helper, "Crew").player();
        ServerPlayer rival = survivor(helper, "Rival").player();
        directorMock.announceChannel(PencilSyncPayload.TYPE);
        CharterId id = TestCharters.found(helper, server, director);
        TestCharters.join(helper, server, id, director, crew);
        TestCharters.found(helper, server, rival);
        find(helper, director, 7, FindResult.FOUND);

        PencilPaths.poll(server, crew, ONLY_THAT_PATH);
        if (!completed(server, director).isEmpty()) {
            throw helper.assertionException("the other Note is not found yet, so the path is open");
        }
        // The rival charter finds the target Note but never the one that draws the path: it has no such path to
        // complete.
        find(helper, rival, 9, FindResult.FOUND);
        PencilPaths.poll(server, rival, ONLY_THAT_PATH);
        if (!completed(server, rival).isEmpty()) {
            throw helper.assertionException("a charter that never found N07 has no path to complete");
        }

        find(helper, crew, 9, FindResult.FOUND);
        PencilPaths.poll(server, crew, ONLY_THAT_PATH);
        for (ServerPlayer member : List.of(director, crew)) {
            if (!completed(server, member).equals(Set.of(TO_ANOTHER_NOTE.id()))) {
                throw helper.assertionException(
                        "the path is complete for the whole charter, not for the member who found the place: %s has %s",
                        member.getGameProfile().name(), completed(server, member));
            }
        }
        if (!completed(server, rival).isEmpty()) {
            throw helper.assertionException("another charter's progress does not follow");
        }
        if (!PencilSyncPayload.of(server, director.getUUID()).completed().equals(List.of(TO_ANOTHER_NOTE.id()))) {
            throw helper.assertionException("the sync lists the charter's completed paths");
        }
        // The mock reads the channel a tick late, so look after the packet would have arrived.
        helper.runAfterDelay(5, () -> {
            List<PencilSyncPayload> sent = directorMock.payloadsOf(PencilSyncPayload.class);
            if (sent.isEmpty() || !sent.getLast().completed().contains(TO_ANOTHER_NOTE.id())) {
                throw helper.assertionException("the Director is told the path is complete, was sent %s", sent);
            }
            helper.succeed();
        });
    }

    @GameTest
    public void aSpectatorOrCreativePlayerCompletesNothing(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = survivor(helper, "Visitor").player();
        find(helper, player, 7, FindResult.NO_CHARTER);
        find(helper, player, 9, FindResult.NO_CHARTER);
        for (GameType mode : List.of(GameType.CREATIVE, GameType.SPECTATOR)) {
            player.setGameMode(mode);
            PencilPaths.poll(server, player, ONLY_THAT_PATH);
            if (!completed(server, player).isEmpty()) {
                throw helper.assertionException("a player in %s completes no path", mode);
            }
        }
        // Control: the same player, in survival, with the same Notes, completes it, so the two refusals above were the
        // game mode.
        player.setGameMode(GameType.SURVIVAL);
        PencilPaths.poll(server, player, ONLY_THAT_PATH);
        if (!completed(server, player).equals(Set.of(TO_ANOTHER_NOTE.id()))) {
            throw helper.assertionException("a survival player completes it, has %s", completed(server, player));
        }
        helper.succeed();
    }

    @GameTest
    public void aPlayerOnNoCharterKeepsTheirOwnPathsAndFoundingAndJoiningCarryThem(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer founder = survivor(helper, "Founder").player();
        ServerPlayer joiner = survivor(helper, "Joiner").player();
        ServerPlayer bystander = survivor(helper, "Bystander").player();
        for (ServerPlayer loner : List.of(founder, joiner)) {
            find(helper, loner, 7, FindResult.NO_CHARTER);
            find(helper, loner, 9, FindResult.NO_CHARTER);
            PencilPaths.poll(loner.level().getServer(), loner, ONLY_THAT_PATH);
        }
        if (!completed(server, founder).equals(Set.of(TO_ANOTHER_NOTE.id()))
                || !completed(server, bystander).isEmpty()) {
            throw helper.assertionException("a player on no charter completes a path for themselves alone");
        }

        CharterId id = TestCharters.found(helper, server, founder);
        if (!PencilPathsData.get(server).charter(id).equals(Set.of(TO_ANOTHER_NOTE.id()))) {
            throw helper.assertionException("founding carries the founder's completed paths into the charter");
        }
        // The charter has its own Notes and paths now; the joiner's carry in and out of the same set.
        ServerPlayer lateJoiner = survivor(helper, "Late").player();
        TestCharters.join(helper, server, id, founder, lateJoiner);
        if (!completed(server, lateJoiner).equals(Set.of(TO_ANOTHER_NOTE.id()))) {
            throw helper.assertionException("a joiner has the charter's completed paths");
        }
        PencilPathsData.get(server).addToPlayer(joiner.getUUID(), List.of(PencilPaths.OLD_SHAFT.id()));
        TestCharters.join(helper, server, id, founder, joiner);
        if (!PencilPathsData.get(server).charter(id).equals(Set.of(TO_ANOTHER_NOTE.id(), PencilPaths.OLD_SHAFT.id()))) {
            throw helper.assertionException("joining adds the joiner's paths to the charter's");
        }
        Charters.leave(server, joiner.getUUID());
        if (!completed(server, joiner).equals(Set.of(TO_ANOTHER_NOTE.id(), PencilPaths.OLD_SHAFT.id()))) {
            throw helper.assertionException(
                    "a leaver has their own paths again, not the charter's: %s", completed(server, joiner));
        }
        if (!completed(server, bystander).isEmpty()) {
            throw helper.assertionException("nobody else gains a path");
        }
        helper.succeed();
    }

    @GameTest
    public void aDormantCharterKeepsItsPathsAndRevivingAddsTheRevivingPlayersOwn(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer director = survivor(helper, "Director").player();
        CharterId id = TestCharters.found(helper, server, director);
        PencilPathsData.get(server).addToCharter(id, List.of(PencilPaths.ONES_BELOW.id()));
        Charters.leave(server, director.getUUID());
        PencilPathsData.get(server).addToPlayer(director.getUUID(), List.of(PencilPaths.OLD_SHAFT.id()));

        Charters.revive(server, director.getUUID(), id).ifPresent(refusal -> {
            throw helper.assertionException("reviving should succeed, was refused: %s", refusal);
        });

        if (!PencilPathsData.get(server)
                .charter(id)
                .equals(Set.of(PencilPaths.ONES_BELOW.id(), PencilPaths.OLD_SHAFT.id()))) {
            throw helper.assertionException(
                    "the dormant charter keeps its paths and takes the reviver's: %s",
                    PencilPathsData.get(server).charter(id));
        }
        helper.succeed();
    }

    /**
     * The wreck and the shaft, in the layer's level: standing in them, or within the reach of them, completes the path whose Note the
     * charter found. The edge cell is inside: the reach is in, one block more is out.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 200)
    public void reachingTheWreckAndTheShaftCompletesTheirPaths(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel layer = TestLayers.level(server, StructureKind.WRECK.layer());
        BlockPos conduit = Colony.anchor(server, ColonyAnchor.CONDUIT)
                .orElseThrow(() -> helper.assertionException("the colony was not built"));
        StructureSite wreck =
                LayerStructures.prospector(layer).orElseThrow(() -> helper.assertionException("no Prospector site"));
        LayerChain.Band band = TestLayers.band(layer, StructureKind.KELLS_SHAFT.layer());
        StructureSite shaft = LayerStructures.nearest(
                layer.getSeed(), StructureKind.KELLS_SHAFT, band.minY(), band.height(), conduit, conduit);
        int reach = HandbookTuning.DEFAULT.pencilReachBlocks();
        BoundingBox shaftBounds = shaft.bounds();
        BlockPos shaftIn = new BlockPos(
                shaftBounds.maxX() + reach,
                shaft.origin().getY() + 1,
                shaft.origin().getZ());
        BlockPos shaftOut = shaftIn.east();
        List<BlockPos> places = List.of(wreck.origin(), shaft.origin(), shaftIn, shaftOut);
        boolean[] arrived = new boolean[places.size()];
        FarChunks.awaitEntityTicking(helper, layer, places, index -> arrived[index] = true);
        helper.startSequence()
                .thenWaitUntil(() -> {
                    for (boolean here : arrived) {
                        if (!here) {
                            throw helper.assertionException("waiting for the wreck's and the shaft's chunks");
                        }
                    }
                })
                .thenExecute(() -> reachPlaces(helper, server, layer, wreck, shaft, shaftIn, shaftOut))
                .thenSucceed();
    }

    private static void reachPlaces(
            GameTestHelper helper,
            MinecraftServer server,
            ServerLevel layer,
            StructureSite wreck,
            StructureSite shaft,
            BlockPos shaftIn,
            BlockPos shaftOut) {
        MockPlayer directorMock = survivor(helper, "Director");
        MockPlayer crewMock = survivor(helper, "Crew");
        MockPlayer rivalMock = survivor(helper, "Rival");
        for (MockPlayer mock : List.of(directorMock, crewMock, rivalMock)) {
            // Standing in rock between the teleport and the end of the test must not hurt the mock.
            mock.player().setPermanentlyInvulnerable(true);
        }
        ServerPlayer director = directorMock.player();
        ServerPlayer crew = crewMock.player();
        ServerPlayer rival = rivalMock.player();
        CharterId id = TestCharters.found(helper, server, director);
        TestCharters.join(helper, server, id, director, crew);
        TestCharters.found(helper, server, rival);

        // No Note, no path: standing in the wreck before N07 is found completes nothing.
        crewMock.teleportTo(layer, Vec3.atBottomCenterOf(wreck.origin()), 0, 0);
        PencilPaths.poll(server, crew);
        if (!completed(server, crew).isEmpty()) {
            throw helper.assertionException(
                    "the wreck completes nothing before N07 is found: %s", completed(server, crew));
        }

        find(helper, director, 7, FindResult.FOUND);
        PencilPaths.poll(server, crew);
        for (ServerPlayer member : List.of(director, crew)) {
            if (!completed(server, member).equals(Set.of(PencilPaths.ONES_BELOW.id()))) {
                throw helper.assertionException(
                        "standing at the wreck completes its path for the charter: %s has %s",
                        member.getGameProfile().name(), completed(server, member));
            }
        }

        // The rival charter found the same Note, and stands in the wreck as a creative player: nothing completes. In
        // survival it does.
        find(helper, rival, 7, FindResult.FOUND);
        rival.setGameMode(GameType.CREATIVE);
        rivalMock.teleportTo(layer, Vec3.atBottomCenterOf(wreck.origin()), 0, 0);
        PencilPaths.poll(server, rival);
        if (!completed(server, rival).isEmpty()) {
            throw helper.assertionException("a creative player at the wreck completes nothing");
        }
        rival.setGameMode(GameType.SURVIVAL);
        PencilPaths.poll(server, rival);
        if (!completed(server, rival).equals(Set.of(PencilPaths.ONES_BELOW.id()))) {
            throw helper.assertionException(
                    "a survival player at the wreck completes it: %s", completed(server, rival));
        }

        // The same column on the surface is not the place: the overworld holds no layer 2.
        ServerLevel overworld = helper.getLevel();
        if (new PencilPlace.Wreck().reached(overworld, wreck.origin(), Set.of())
                || new PencilPlace.Structure(StructureKind.KELLS_SHAFT).reached(overworld, shaft.origin(), Set.of())) {
            throw helper.assertionException("a level that holds no layer 2 is never the place");
        }

        // The shaft: one block past the reach is out, the reach itself is in, and the path needs N10.
        find(helper, director, 10, FindResult.FOUND);
        crewMock.teleportTo(layer, Vec3.atBottomCenterOf(shaftOut), 0, 0);
        PencilPaths.poll(server, crew);
        if (completed(server, crew).contains(PencilPaths.OLD_SHAFT.id())) {
            throw helper.assertionException(
                    "%s is one block past the reach of the shaft: the path stays open", shaftOut.toShortString());
        }
        crewMock.teleportTo(layer, Vec3.atBottomCenterOf(shaftIn), 0, 0);
        PencilPaths.poll(server, crew);
        if (!completed(server, director).contains(PencilPaths.OLD_SHAFT.id())) {
            throw helper.assertionException(
                    "%s is at the reach of the shaft: the path is complete for the charter, has %s",
                    shaftIn.toShortString(), completed(server, director));
        }
        if (completed(server, rival).contains(PencilPaths.OLD_SHAFT.id())) {
            throw helper.assertionException("the rival never found N10 and stood nowhere near the shaft");
        }
    }

    @GameTest
    public void completedPathsAreSavedWithTheirVersionAndAnOlderOrNewerFormatIsKept(GameTestHelper helper) {
        UUID player = UUID.randomUUID();
        CharterId charter = CharterId.random();
        PencilPathsData data = new PencilPathsData();
        data.addToPlayer(player, List.of(PencilPaths.ONES_BELOW.id()));
        data.addToCharter(charter, List.of(PencilPaths.OLD_SHAFT.id(), PencilPaths.ONES_BELOW.id()));

        Tag saved = PencilPathsData.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
        if (!(saved instanceof CompoundTag compound) || compound.getIntOr("version", -1) != PencilPathsData.VERSION) {
            throw helper.assertionException(
                    "the saved form carries version %s, got %s", PencilPathsData.VERSION, saved);
        }
        PencilPathsData loaded =
                PencilPathsData.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        if (!loaded.player(player).equals(Set.of(PencilPaths.ONES_BELOW.id()))
                || !loaded.charter(charter).equals(Set.of(PencilPaths.OLD_SHAFT.id(), PencilPaths.ONES_BELOW.id()))) {
            throw helper.assertionException("the saved paths should load as they were saved");
        }
        if (!PencilPathsData.CODEC
                .encodeStart(NbtOps.INSTANCE, loaded)
                .getOrThrow()
                .equals(saved)) {
            throw helper.assertionException("a load and a save leave the saved form as it was");
        }

        // Version 1 as it is written to disk, spelled out: a later build must still read it.
        if (!(saved instanceof CompoundTag current)
                || current.getListOrEmpty("charters").size() != 1) {
            throw helper.assertionException("the version 1 form has a charters list of one entry: %s", saved);
        }
        List<String> savedPaths = new java.util.ArrayList<>();
        current.getListOrEmpty("charters")
                .getCompoundOrEmpty(0)
                .getListOrEmpty("paths")
                .forEach(path -> savedPaths.add(path.asString().orElse("?")));
        if (!savedPaths.equals(List.of("deepcharter:pencil/old_shaft", "deepcharter:pencil/ones_below"))) {
            throw helper.assertionException("the version 1 form lists a charter's paths by id, got %s", savedPaths);
        }

        // A newer format is read as unreadable, kept as it is, and never replaced by an older build's data.
        PencilPathsData future = PencilPathsData.CODEC
                .parse(NbtOps.INSTANCE, UnreadableChecks.futureData())
                .getOrThrow();
        if (future.isReadable()) {
            throw helper.assertionException(
                    "data of version %s is not readable by version 1", UnreadableChecks.FUTURE_VERSION);
        }
        if (!PencilPathsData.CODEC
                .encodeStart(NbtOps.INSTANCE, future)
                .getOrThrow()
                .equals(UnreadableChecks.futureData())) {
            throw helper.assertionException("unreadable paths are written back as they were read");
        }
        helper.succeed();
    }

    @GameTest
    public void unreadablePathsNeverThrowAndNothingCompletes(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer player = survivor(helper, "Reader").player();
        find(helper, player, 7, FindResult.NO_CHARTER);
        find(helper, player, 9, FindResult.NO_CHARTER);
        Map<String, Runnable> paths = new LinkedHashMap<>();
        paths.put("poll", () -> PencilPaths.poll(server, player, ONLY_THAT_PATH));
        paths.put("poll of the shipped paths", () -> PencilPaths.poll(server, player));
        paths.put("completed", () -> {
            if (!PencilPaths.completedFor(server, player.getUUID()).isEmpty()) {
                throw helper.assertionException("unreadable paths read as empty");
            }
        });
        paths.put("drawn", () -> PencilPaths.drawnFor(server, player.getUUID()));
        paths.put("sync", () -> {
            if (!PencilSyncPayload.of(server, player.getUUID()).completed().isEmpty()) {
                throw helper.assertionException("unreadable paths sync as empty");
            }
        });
        // The carry runs on founding; the refusal on the second run (already on a charter) is not the point here.
        paths.put("founding", () -> Charters.found(server, player.getUUID(), "Unreadable " + UUID.randomUUID()));
        UnreadableChecks.assertSavedDataNoThrow(helper, "pencil paths", server, PencilPathsData.TYPE, paths);
        helper.succeed();
    }

    @GameTest
    public void theSyncPayloadSurvivesTheWire(GameTestHelper helper) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                Unpooled.buffer(), helper.getLevel().getServer().registryAccess());
        PencilSyncPayload.CODEC.encode(
                buf, new PencilSyncPayload(List.of(PencilPaths.OLD_SHAFT.id(), PencilPaths.ONES_BELOW.id())));
        PencilSyncPayload decoded = PencilSyncPayload.CODEC.decode(buf);
        if (!decoded.completed().equals(List.of(PencilPaths.OLD_SHAFT.id(), PencilPaths.ONES_BELOW.id()))) {
            throw helper.assertionException("the payload should decode as it was encoded, got %s", decoded);
        }
        helper.succeed();
    }
}
