package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonyBlocks;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.colony.ColonyTuning;
import io.github.pkeppeler.deepcharter.colony.WorldFormat;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.StructureKind;
import io.github.pkeppeler.deepcharter.layer.StructureSite;
import io.github.pkeppeler.deepcharter.layer.gen.BandBiomeSource;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientTestWatchdog;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.DedicatedServerRelease;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.WorldTypeEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;

/**
 * Client GameTest for the cutover of #803 (slice 8 of #214): in a default-preset world the overworld is the campaign world. It checks the
 * level (from -2032, 2352 tall, the {@code deepcharter:bands} biome source), the colony (at the origin, its pad at y 63, players
 * respawn in the Continuity Office), the Conduit (from the deepest layer's floor up to its stack), the world-format stamp ({@link WorldFormat#CURRENT}), the sites of the chunks the
 * build loaded, that the presets give the same world (ADR 0036), that a saved world reopens with no experimental-settings screen, and
 * that a dedicated server runs it. The one-argument {@code TestLayers.dimension} has no server and is gone; its server form returns the
 * overworld here, the branch no server GameTest reaches (their overworld is flat).
 */
public class CampaignWorldClientTest implements FabricClientGameTest {
    /** The whole class: five worlds, a reopened one and a dedicated server are a long test by design. */
    private static final Duration LIMIT = Duration.ofMinutes(15);

    private static final int OVERWORLD_MIN_Y = -2032; // fixed-pos: the shipped overworld's floor, which this test pins
    private static final int OVERWORLD_HEIGHT = 2352;
    private static final int PAD_GROUND_Y = 63; // fixed-pos: ADR 0036's pad ground, which this test pins
    /** The Conduit runs through every layer (ADR 0016): its floor is the deepest layer's. */
    private static final int DEEPEST_CONDUIT_Y =
            CampaignBands.layer(CampaignBands.lastLayer()).minY();
    /** The columns read for the presets' fingerprint: on the pad, on its slope and far out. */
    private static final int[][] COLUMNS = {{0, 0}, {40, -25}, {-90, 60}, {300, 300}, {-700, 450}, {1200, -900}};
    /** Spacing cells either side of the origin whose sites touch the pad's chunks. */
    private static final int SITE_CELLS = 2;
    /** The pad is 64 blocks across, centred on the origin: its chunks. */
    private static final int PAD_CHUNK_RADIUS = 2;

    /** The presets of the new-world screen, whose overworld the pack's dimension replaces (ADR 0036). */
    private static final Map<String, ResourceKey<WorldPreset>> PRESETS = new LinkedHashMap<>();

    static {
        PRESETS.put("normal", WorldPresets.NORMAL);
        PRESETS.put("amplified", WorldPresets.AMPLIFIED);
        PRESETS.put("large_biomes", WorldPresets.LARGE_BIOMES);
        PRESETS.put("single_biome_surface", WorldPresets.SINGLE_BIOME_SURFACE);
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        ClientTestWatchdog.shared().arm(getClass().getSimpleName(), LIMIT);
        TestWorldSave save;
        String reference;
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(
                    context, "the player in the world", client -> client.player != null && client.level != null);
            TestServerContext server = singleplayer.getServer();
            require("the campaign overworld", server.computeOnServer(CampaignWorldClientTest::theOverworld));
            require("the colony", server.computeOnServer(CampaignWorldClientTest::theColony));
            require("the Conduit and the stamp", server.computeOnServer(CampaignWorldClientTest::theConduit));
            ClientWait.until(
                    context,
                    "a site in a loaded chunk, and the pad's chunks, on the server",
                    () -> server.computeOnServer(s -> padChunks()
                                    .allMatch(chunk ->
                                            s.overworld().getChunkSource().hasChunk(chunk.x(), chunk.z()))
                            && !sitesToCheck(s).isEmpty()),
                    () -> "the sites to check: "
                            + server.computeOnServer(s -> sitesToCheck(s).size()));
            require("the pad chunks' sites", server.computeOnServer(CampaignWorldClientTest::theSitesOfThePad));
            boolean clientSees = context.computeOnClient(client -> client.level != null
                    // level-height: the whole level against the issue's numbers, not one layer
                    && client.level.getMinY() == OVERWORLD_MIN_Y
                    && client.level.getHeight() == OVERWORLD_HEIGHT
                    && client.gui.screen() == null);
            if (!clientSees) {
                throw new AssertionError("the client's level should run from " + OVERWORLD_MIN_Y + ", "
                        + OVERWORLD_HEIGHT + " tall, with no screen open");
            }
            reference = server.computeOnServer(CampaignWorldClientTest::fingerprint);
            thePlayerRespawnsInTheContinuityOffice(context, server);
            save = singleplayer.getWorldSave();
        }

        // A saved world opens again with no experimental-settings screen: the open waits for the world, so a warning
        // screen that needs a
        // click ends it at the Fabric runner's timeout.
        try (TestSingleplayerContext reopened = save.open()) {
            ClientWait.until(context, "the reopened world", client -> client.player != null && client.level != null);
            if (context.computeOnClient(client -> client.gui.screen() != null)) {
                throw new AssertionError("a screen stands over the reopened world");
            }
            require(
                    "the reopened overworld",
                    reopened.getServer().computeOnServer(CampaignWorldClientTest::theOverworld));
        }

        for (Map.Entry<String, ResourceKey<WorldPreset>> preset : PRESETS.entrySet()) {
            thePresetGivesTheSameWorld(context, preset.getKey(), preset.getValue(), reference);
        }
        aDedicatedServerRunsTheCampaignWorld(context);
    }

    // ---------------------------------------------------------------------------------------------- server reads

    private static void require(String what, String problems) {
        if (!problems.isEmpty()) {
            throw new AssertionError(what + ": " + problems);
        }
    }

    /** From -2032, 2352 tall, on {@code deepcharter:bands}; and a layer's level is this one, which only a default-preset world can show. */
    private static String theOverworld(MinecraftServer server) {
        StringBuilder out = new StringBuilder();
        ServerLevel overworld = server.overworld();
        int minY = overworld.getMinY(); // level-height: the whole level against the issue's numbers, not one layer
        int height = overworld.getHeight(); // level-height: the whole level against the issue's numbers, not one layer
        if (minY != OVERWORLD_MIN_Y || height != OVERWORLD_HEIGHT) {
            out.append("the overworld runs from ")
                    .append(minY)
                    .append(" and is ")
                    .append(height)
                    .append(" tall; ");
        }
        if (!(overworld.getChunkSource().getGenerator().getBiomeSource() instanceof BandBiomeSource)) {
            out.append("its biome source is ")
                    .append(overworld.getChunkSource().getGenerator().getBiomeSource())
                    .append(", not deepcharter:bands; ");
        }
        if (!LayerChain.isCampaign(overworld)) {
            out.append("it is not a campaign level; ");
        }
        for (int layer = LayerChain.SURFACE; layer <= CampaignBands.lastLayer(); layer++) {
            if (!TestLayers.dimension(server, layer).equals(Level.OVERWORLD)
                    || TestLayers.level(server, layer) != overworld) {
                out.append("TestLayers does not return the overworld for layer ")
                        .append(layer)
                        .append("; ");
            }
        }
        return out.toString();
    }

    /** At the origin, its pad at y 63, and the Continuity Office its respawn point. */
    private static String theColony(MinecraftServer server) {
        StringBuilder out = new StringBuilder();
        ColonySite.Placed colony = Colony.placed(server).orElse(null);
        if (colony == null) {
            return "the colony was not built";
        }
        if (colony.center().getX() != 0 || colony.center().getZ() != 0) {
            out.append("the colony is centred on ")
                    .append(colony.center().toShortString())
                    .append(", not the origin; ");
        }
        if (colony.groundY() != PAD_GROUND_Y) {
            out.append("the pad's ground is y ")
                    .append(colony.groundY())
                    .append(", not ")
                    .append(PAD_GROUND_Y)
                    .append("; ");
        }
        BlockPos office = ColonyAnchor.CONTINUITY_OFFICE.in(colony.anchors());
        if (!Colony.respawnPoint(server).equals(java.util.Optional.of(GlobalPos.of(Level.OVERWORLD, office)))) {
            out.append("the respawn point is ")
                    .append(Colony.respawnPoint(server))
                    .append(", not the office ")
                    .append(office.toShortString())
                    .append("; ");
        }
        ServerLevel overworld = server.overworld();
        if (overworld.getBlockState(colony.center().below()).isAir()) {
            out.append("there is no ground under the pad's centre; ");
        }
        return out.toString();
    }

    /** From the deepest layer's floor up to the stack, and not below or above it; the stamp is the current format. */
    private static String theConduit(MinecraftServer server) {
        StringBuilder out = new StringBuilder();
        ColonySite.Placed colony = Colony.placed(server).orElse(null);
        if (colony == null) {
            return "the colony was not built";
        }
        ServerLevel level = server.overworld();
        BlockPos centre = ColonyAnchor.CONDUIT.in(colony.anchors());
        int radius = ColonyTuning.DEFAULT.conduitRadius();
        int top = colony.groundY() + ColonyTuning.DEFAULT.conduitStack();
        int gaps = 0;
        for (int x = centre.getX() - radius; x <= centre.getX() + radius; x++) {
            for (int z = centre.getZ() - radius; z <= centre.getZ() + radius; z++) {
                for (int y = DEEPEST_CONDUIT_Y; y <= top; y++) {
                    gaps += level.getBlockState(new BlockPos(x, y, z)).is(ColonyBlocks.CONDUIT) ? 0 : 1;
                }
                boolean below = level.getBlockState(new BlockPos(x, DEEPEST_CONDUIT_Y - 1, z))
                        .is(ColonyBlocks.CONDUIT);
                boolean above = level.getBlockState(new BlockPos(x, top + 1, z)).is(ColonyBlocks.CONDUIT);
                if (below || above) {
                    out.append("the Conduit runs past its ends at ")
                            .append(x)
                            .append(" ")
                            .append(z)
                            .append("; ");
                }
            }
        }
        if (gaps != 0) {
            out.append(gaps)
                    .append(" blocks of the casing from ")
                    .append(DEEPEST_CONDUIT_Y)
                    .append(" to ")
                    .append(top)
                    .append(" are missing; ");
        }
        if (!WorldFormat.get(server).format().equals(OptionalInt.of(WorldFormat.CURRENT))) {
            out.append("the world-format stamp is ")
                    .append(WorldFormat.get(server).format())
                    .append(", not ")
                    .append(WorldFormat.CURRENT)
                    .append("; ");
        }
        return out.toString();
    }

    /** The chunks of the pad. */
    private static Stream<ChunkPos> padChunks() {
        return IntStream.range(-PAD_CHUNK_RADIUS, PAD_CHUNK_RADIUS)
                .boxed()
                .flatMap(x ->
                        IntStream.range(-PAD_CHUNK_RADIUS, PAD_CHUNK_RADIUS).mapToObj(z -> new ChunkPos(x, z)));
    }

    /** Every site of both layers in the cells round the origin that touches the pad's chunks or lies whole in chunks the world holds. */
    private static List<StructureSite> sitesToCheck(MinecraftServer server) {
        ServerLevel level = server.overworld();
        BlockPos conduit = Colony.anchor(server, ColonyAnchor.CONDUIT).orElse(null);
        if (conduit == null) {
            return List.of();
        }
        Set<ChunkPos> pad = padChunks().collect(Collectors.toCollection(LinkedHashSet::new));
        List<StructureSite> found = new ArrayList<>();
        for (int layer = 1; layer <= CampaignBands.lastLayer(); layer++) {
            CampaignBands.Band band = CampaignBands.layer(layer);
            for (StructureKind kind : StructureKind.inLayer(layer)) {
                for (int cellX = -SITE_CELLS; cellX < SITE_CELLS; cellX++) {
                    for (int cellZ = -SITE_CELLS; cellZ < SITE_CELLS; cellZ++) {
                        StructureSite site = StructureSite.in(
                                level.getSeed(), kind, band.minY(), band.height(), cellX, cellZ, conduit);
                        List<ChunkPos> chunks = LayerStructuresTest.chunksOf(site);
                        boolean loaded = chunks.stream()
                                .allMatch(chunk -> level.getChunkSource().hasChunk(chunk.x(), chunk.z()));
                        if (loaded || chunks.stream().anyMatch(pad::contains)) {
                            found.add(site);
                        }
                    }
                }
            }
        }
        return found;
    }

    /**
     * The sites of the chunks the build loaded are whole: each has its blocks, its Note and its candle. Those are every site of both
     * layers in the cells round the origin that touches the pad's chunks, and every such site whose chunks the world holds already (the
     * build and the player's view loaded them, so they were drawn as the chunks came in, not asked for here).
     */
    private static String theSitesOfThePad(MinecraftServer server) {
        ServerLevel level = server.overworld();
        List<StructureSite> sites = sitesToCheck(server);
        if (sites.isEmpty()) {
            return "no site lies in a chunk the build or the player's view loaded, so the check proves nothing";
        }
        int checked = 0;
        List<String> unmet = new ArrayList<>();
        for (StructureSite site : sites) {
            for (ChunkPos chunk : LayerStructuresTest.chunksOf(site)) {
                level.getChunk(chunk.x(), chunk.z(), ChunkStatus.FULL);
            }
            CampaignBands.Band band = CampaignBands.layer(site.kind().layer());
            List<String> missing = LayerStructuresTest.unmet(
                    level, band.minY(), band.height(), site, LayerStructuresTest.census(level, site));
            checked++;
            if (!missing.isEmpty()) {
                unmet.add(site.kind() + " at " + site.origin().toShortString() + ": " + missing);
            }
        }
        return unmet.isEmpty() ? "" : unmet.size() + " of " + checked + " sites are not whole: " + unmet;
    }

    /** What two worlds must share: the level's shape, its generator, and the ground at columns from the pad to far out. */
    private static String fingerprint(MinecraftServer server) {
        ServerLevel level = server.overworld();
        RandomState random = level.getChunkSource().randomState();
        int minY = level.getMinY(); // level-height: a fingerprint of the whole level, not one layer
        int levelHeight = level.getHeight(); // level-height: a fingerprint of the whole level, not one layer
        StringBuilder out = new StringBuilder()
                .append(minY)
                .append('/')
                .append(levelHeight)
                .append(' ')
                .append(level.getChunkSource()
                        .getGenerator()
                        .getBiomeSource()
                        .getClass()
                        .getSimpleName());
        for (int[] column : COLUMNS) {
            int height = level.getChunkSource()
                    .getGenerator()
                    .getBaseHeight(column[0], column[1], Heightmap.Types.WORLD_SURFACE_WG, level, random);
            String biome = level.getChunkSource()
                    .getGenerator()
                    .getBiomeSource()
                    .createCachingResolver(random)
                    .getNoiseBiome(column[0] >> 2, PAD_GROUND_Y >> 2, column[1] >> 2)
                    .unwrapKey()
                    .map(key -> key.identifier().toString())
                    .orElse("?");
            out.append(' ').append(height).append('@').append(biome);
        }
        return out.toString();
    }

    // ---------------------------------------------------------------------------------------------- client-driven

    /**
     * A wreck kills the crew, and the crew respawn in the Continuity Office wherever they died. The player is put far from the office
     * first, so the move is seen, and a bed is not set, so this is the colony's respawn and not the world's.
     */
    private void thePlayerRespawnsInTheContinuityOffice(ClientGameTestContext context, TestServerContext server) {
        BlockPos office = server.computeOnServer(s ->
                ColonyAnchor.CONTINUITY_OFFICE.in(Colony.placed(s).orElseThrow().anchors()));
        Vec3 away = new Vec3(office.getX() + 150.5, 150, office.getZ() + 150.5);
        server.runOnServer(s -> {
            ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
            // Creative while the chunk loads: a survival player falls the 150 blocks to the ground and dies
            player.setGameMode(GameType.CREATIVE);
            player.teleportTo(s.overworld(), away.x, away.y, away.z, Set.of(), 0f, 0f, true);
        });
        // The arrival chunk is loaded before it ticks entities, and a pod added to it then is not in the level: the
        // wreck would have no crew to kill.
        BlockPos awayPos = BlockPos.containing(away);
        ClientWait.until(
                context,
                "the arrival chunk entity-ticking on the server",
                () -> server.computeOnServer(s -> s.overworld().isPositionEntityTicking(awayPos)),
                () -> server.computeOnServer(s -> {
                    ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
                    return "the chunk at " + awayPos.toShortString() + " is entity-ticking "
                            + s.overworld().isPositionEntityTicking(awayPos) + ", loaded "
                            + s.overworld().getChunkSource().hasChunk(awayPos.getX() >> 4, awayPos.getZ() >> 4)
                            + "; the player is at " + player.blockPosition().toShortString() + " in "
                            + player.gameMode();
                }));
        server.runOnServer(s -> {
            ServerPlayer player = s.getPlayerList().getPlayers().getFirst();
            player.setGameMode(GameType.SURVIVAL);
            PodEntity pod = TestEntities.create(PodRegistry.POD, s.overworld());
            pod.setPos(away);
            boolean added = s.overworld().addFreshEntity(pod);
            player.startRiding(pod, true, false);
            boolean held = s.overworld().getEntity(pod.getUUID()) == pod;
            if (!added || !held) {
                throw new AssertionError("the pod should be in the level before the wreck: addFreshEntity returned "
                        + added + ", the level holds it " + held);
            }
            if (player.getVehicle() != pod) {
                throw new AssertionError(
                        "the pilot should ride the pod before the wreck, rides " + player.getVehicle());
            }
            pod.damageHull(pod.maxHull());
            if (!player.isDeadOrDying()) {
                throw new AssertionError("a wreck should have killed the pilot");
            }
            player.connection.handleClientCommand(
                    new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        });
        // The tick after the respawn moves the crew to the office.
        context.waitTicks(10);
        Vec3 at = server.computeOnServer(
                s -> s.getPlayerList().getPlayers().getFirst().position());
        if (at.distanceToSqr(Vec3.atBottomCenterOf(office)) >= 4) {
            throw new AssertionError("the crew should respawn in the Continuity Office at " + office.toShortString()
                    + ", respawned at " + at);
        }
    }

    private void thePresetGivesTheSameWorld(
            ClientGameTestContext context, String name, ResourceKey<WorldPreset> preset, String reference) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .adjustSettings(state -> state.setWorldType(new WorldTypeEntry(state.getSettings()
                        .worldgenLoadContext()
                        .lookupOrThrow(Registries.WORLD_PRESET)
                        .getOrThrow(preset))))
                .create()) {
            ClientWait.until(
                    context, "the player in the world", client -> client.player != null && client.level != null);
            TestServerContext server = singleplayer.getServer();
            require(
                    "the " + name + " preset's overworld",
                    server.computeOnServer(CampaignWorldClientTest::theOverworld));
            String print = server.computeOnServer(CampaignWorldClientTest::fingerprint);
            if (!print.equals(reference)) {
                throw new AssertionError(
                        "the " + name + " preset gives another world:\n  " + print + "\n  not\n  " + reference);
            }
        }
    }

    private void aDedicatedServerRunsTheCampaignWorld(ClientGameTestContext context) {
        Properties properties = new Properties();
        properties.setProperty("max-players", "2");
        TestDedicatedServerContext server = DedicatedServerRelease.createServer(context, properties);
        try {
            TestDedicatedServerConnection connection = server.connect();
            try {
                require(
                        "the dedicated server's overworld",
                        server.computeOnServer(CampaignWorldClientTest::theOverworld));
                require("the dedicated server's colony", server.computeOnServer(CampaignWorldClientTest::theColony));
                if (context.computeOnClient(client -> client.level == null || client.gui.screen() != null)) {
                    throw new AssertionError(
                            "the client should be in the dedicated server's world with no screen over it");
                }
            } finally {
                connection.close();
            }
        } finally {
            server.close();
        }
    }
}
