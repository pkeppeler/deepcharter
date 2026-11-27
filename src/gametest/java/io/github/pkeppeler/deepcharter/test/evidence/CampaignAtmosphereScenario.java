package io.github.pkeppeler.deepcharter.test.evidence;

import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.CAMPAIGN;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.MASS;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.ROOM_HEIGHT;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.SITE;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.SKY_DUSK;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.SKY_NIGHT;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.SPACING;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.TEST_PRESET;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.TODAY;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.ZONE;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.build;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.buildRoom;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.ground;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.layer1Floor;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.requireZone;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.stand;
import static io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.todayFloor;

import com.mojang.datafixers.util.Pair;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes.Holds;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.EvidenceWorld;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestColony;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.WorldTypeEntry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "m3-campaign-atmosphere" (#798, campaign world plan #214): stills for the user to decide how the layer look is carried
 * by the campaign type, {@code campaign_sample}. The scenes are those of {@code LayerAtmosphereClientTest} ({@link
 * io.github.pkeppeler.deepcharter.test.support.AtmosphereScenes}). Three worlds, in this order:
 *
 * <ol>
 *   <li>A world of the {@code flat_all_dimensions} preset, with today's layer 1 beside the campaign type: a lit room's wall, floor and
 *       ceiling, the unlit wall, a lampless figure at light 0, the bottom of a shaft at dusk and at night, and (campaign only: today's
 *       layers have no surface above them) five stills of a descent through the surface floor.
 *   <li>The design tour's world with today's surface: the colony and a mesa.
 *   <li>The same world with its blocks shaded as {@code cardinal_light: nether} shades them ({@link EvidencePins#cardinalLightNether}), to show what the whole world
 *       would look like with that shading.
 * </ol>
 *
 * A pair of stills is named {@code <scene>-today} and {@code <scene>-campaign} (or {@code -nether}), so that a pair is two files of one
 * stem. The scenario ends on the title screen.
 */
public class CampaignAtmosphereScenario extends EvidenceScenario {
    private static final double EYE = 1.62;
    private static final float NORTH = 180f;
    private static final float DOWN = 90f;
    private static final float UP = -90f;
    /** The figure stands this many blocks north of the middle of its room, facing south, toward the camera. */
    private static final int FIGURE_AHEAD = 2;

    /** The descent's shaft is this far from its middle to its wall, so that the camera is two blocks from the wall it faces. */
    private static final int SHAFT_HALF = 2;
    /** The descent's stills are at the surface floor's Y and at these offsets from it, top to bottom: the blend of a biome edge reaches about 16 blocks. */
    private static final List<Integer> DESCENT_OFFSETS = List.of(16, 8, 0, -8, -16);
    /** The shaft goes this far below the lowest still. */
    private static final int SHAFT_BELOW = 8;

    /** The design tour's world, so the colony and the mesa are the ones the tour shows. */
    private static final String SEED = "deepcharter-design-tour";

    /** The surface's mesa biome: the mesa of the stills is the closest one, found as {@code /locate biome} finds it. */
    private static final ResourceKey<Biome> MESA =
            ResourceKey.create(Registries.BIOME, Identifier.fromNamespaceAndPath("deepcharter", "mesa_country"));

    private static final int MESA_SEARCH_RADIUS = 6_400;

    private static final int MESA_SEARCH_HSTEP = 32;
    private static final int MESA_SEARCH_VSTEP = 64;
    /** The camera over the mesa: this far south of it, and at least this far over its top. */
    private static final int MESA_BACK = 70;

    private static final int MESA_RISE = 20;
    /** And clear of the ground under the camera by this much. */
    private static final int MESA_CLEARANCE = 14;
    /** The colony's aerial view: south of its middle and above it, looking at the middle. */
    private static final double AERIAL_SOUTH = 62;

    private static final double AERIAL_HEIGHT = 34;
    /** The plaza view: west of the Continuity Office, a little over the paving, looking into it (the first still of {@code m2-colony}). */
    private static final double PLAZA_WEST = 10;

    private static final double PLAZA_HEIGHT = 2.5;
    private static final double OFFICE_HEIGHT = 1.5;

    /** Looks in a row at which the surface must be ready for a still. */
    private static final int READY_POLLS_STABLE = 3;
    /** Ticks between two looks at whether the surface is ready for a still. */
    private static final int READY_POLL_TICKS = 10;

    private static final int ROOMS_FROM = 10;

    /** How many rooms the scenario has built: each has a place of its own. */
    private int rooms;

    /** Where the colony and the mesa are, to check that the two surface worlds show the same places. */
    private record Places(BlockPos colony, BlockPos mesa) {}

    @Override
    protected String name() {
        return "m3-campaign-atmosphere";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try {
            pinTheClient();
            layersAndCampaign(context);
            Places today = surface(context, false, "today");
            Places nether = surface(context, true, "nether");
            ClientChecks.require(
                    today.equals(nether),
                    "the two surface worlds show the same places, today's " + today + " and nether's " + nether);
        } finally {
            EvidencePins.reset();
        }
        ClientWait.screen(context, TitleScreen.class);
    }

    /** Holds still what moves on client ticks and frames ({@code evidence.md}). */
    private static void pinTheClient() {
        EvidencePins.spritesFrozen = true;
        EvidencePins.vignetteHidden = true;
        EvidencePins.itemNameHidden = true;
        EvidencePins.lightFlickerOff = true;
        EvidencePins.handsHidden = true;
        EvidencePins.entitiesPosed = true;
        EvidencePins.figureSpawningOff = true;
        EvidencePins.figureAnimationAge = 0f;
    }

    // ------------------------------------------------------------------------------------------ layers and campaign

    private void layersAndCampaign(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .adjustSettings(state -> state.setWorldType(new WorldTypeEntry(state.getSettings()
                        .worldgenLoadContext()
                        .lookupOrThrow(Registries.WORLD_PRESET)
                        .getOrThrow(TEST_PRESET))))
                .create()) {
            awaitWorld(context);
            EvidenceWorld.pin(context, singleplayer);
            TestServerContext server = singleplayer.getServer();
            server.runOnServer(s -> player(s).setGameMode(GameType.SPECTATOR));
            ClientChecks.withHudHidden(context, () -> {
                litRoomAndFigure(context, server);
                shaftBottom(context, singleplayer, server);
                descent(context, server);
            });
        }
    }

    /**
     * The lit room in both, three views of it lit, the unlit wall, and a figure in the dark room. Each still has a room of its own, built
     * just before the camera goes to it: a room that waits unloaded for some stills was read back with the wrong light in some runs.
     */
    private void litRoomAndFigure(ClientGameTestContext context, TestServerContext server) {
        ResourceKey<Level> layer1 = TODAY;
        record View(String name, float pitch) {}
        for (View view : List.of(new View("wall", 0), new View("floor", DOWN), new View("ceiling", UP))) {
            roomStill(context, server, layer1, true, view.pitch(), "lit-room-" + view.name() + "-today");
            roomStill(context, server, CAMPAIGN, true, view.pitch(), "lit-room-" + view.name() + "-campaign");
        }
        roomStill(context, server, layer1, false, 0, "unlit-room-wall-today");
        roomStill(context, server, CAMPAIGN, false, 0, "unlit-room-wall-campaign");
        // A lampless figure, whole, in each dark room: at light 0 it is lit by the dimension type's ambient
        // light alone.
        figureStill(context, server, layer1, "figure-light-0-today");
        figureStill(context, server, CAMPAIGN, "figure-light-0-campaign");
    }

    /** Builds a room in {@code dimension} at the next place, in the zone's biome, and takes the still of it. */
    private void roomStill(
            ClientGameTestContext context,
            TestServerContext server,
            ResourceKey<Level> dimension,
            boolean lit,
            float pitch,
            String name) {
        try (Holds holds = Holds.of(server)) {
            BlockPos room = nextRoom(context, server, holds, dimension, lit);
            still(context, server, dimension, room, pitch, name);
            if (lit && dimension.equals(CAMPAIGN)) {
                netherShaded(context, server, room, pitch, name + "-nether");
            }
        }
    }

    /**
     * The same room again with {@link EvidencePins#cardinalLightNether} on, so that the one thing that differs from the still before it is
     * the shading of the faces: the room's sections are marked dirty to be meshed again with it, and again when the switch is off.
     */
    private void netherShaded(
            ClientGameTestContext context, TestServerContext server, BlockPos room, float pitch, String name) {
        EvidencePins.cardinalLightNether = true;
        try {
            remesh(context, room);
            still(context, server, CAMPAIGN, room, pitch, name);
        } finally {
            EvidencePins.cardinalLightNether = false;
            remesh(context, room);
        }
    }

    private static void remesh(ClientGameTestContext context, BlockPos room) {
        context.runOnClient(client -> ClientLight.clientLevel(client)
                .setSectionRangeDirty(
                        SectionPos.blockToSectionCoord(room.getX() - MASS),
                        SectionPos.blockToSectionCoord(room.getY() - 1),
                        SectionPos.blockToSectionCoord(room.getZ() - MASS),
                        SectionPos.blockToSectionCoord(room.getX() + MASS),
                        SectionPos.blockToSectionCoord(room.getY() + ROOM_HEIGHT),
                        SectionPos.blockToSectionCoord(room.getZ() + MASS)));
        ClientWait.until(
                context, "the room's sections meshed", client -> client.levelRenderer.hasRenderedAllSections());
    }

    private void figureStill(
            ClientGameTestContext context, TestServerContext server, ResourceKey<Level> dimension, String name) {
        try (Holds holds = Holds.of(server)) {
            BlockPos room = nextRoom(context, server, holds, dimension, false);
            stillWithFigure(context, server, dimension, room, name);
        }
    }

    /** The X of the next room: past the shaft and the descent, a place of its own for each. */
    private int nextRoomX() {
        return SITE + (ROOMS_FROM + ++rooms) * SPACING;
    }

    /** The next room: a room of today's layer 1 or of the campaign type, at a place of its own, and in the zone's biome in both. */
    private BlockPos nextRoom(
            ClientGameTestContext context,
            TestServerContext server,
            Holds holds,
            ResourceKey<Level> dimension,
            boolean lit) {
        int x = nextRoomX();
        boolean campaign = dimension.equals(CAMPAIGN);
        BlockPos floor = campaign ? layer1Floor(x) : todayFloor(x);
        BlockPos room =
                buildRoom(server, holds, dimension, floor, campaign ? Optional.of(ZONE) : Optional.empty(), lit);
        requireZone(server, dimension, room);
        awaitLight(context, server);
        return room;
    }

    /** The bottom of an open shaft under the campaign type's sky, next to today's sealed room, with the sky timeline at dusk and at night. */
    private void shaftBottom(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, TestServerContext server) {
        try (Holds holds = Holds.of(server)) {
            int x = SITE;
            ResourceKey<Level> layer1 = TODAY;
            BlockPos shaft = build(server, holds, CAMPAIGN, ground(server, x), Optional.of(ZONE), true);
            BlockPos room = build(server, holds, layer1, todayFloor(x + SPACING), Optional.empty(), false);
            awaitLight(context, server);
            requireZone(server, CAMPAIGN, shaft);
            requireZone(server, layer1, room);
            record SkyTime(String name, long ticks) {}
            for (SkyTime sky : List.of(new SkyTime("dusk", SKY_DUSK), new SkyTime("night", SKY_NIGHT))) {
                EvidenceWorld.skyPhase(context, singleplayer, sky.ticks());
                still(context, server, layer1, room, 0, "shaft-bottom-" + sky.name() + "-today");
                still(context, server, CAMPAIGN, shaft, 0, "shaft-bottom-" + sky.name() + "-campaign");
            }
            // The descent is read by the light of the day's brightest sky.
        }
        EvidenceWorld.skyPhase(context, singleplayer, SKY_DUSK);
    }

    /**
     * A descent through the surface floor in a shaft cut from the ground, in the campaign type alone: a still at the floor's Y, at 16 and 8
     * blocks above it and at 8 and 16 below, looking at the shaft's wall. The biome of the band changes at the floor, and the camera blends
     * biomes over about 16 blocks.
     */
    private void descent(ClientGameTestContext context, TestServerContext server) {
        int x = SITE + 6 * SPACING;
        BlockPos top = ground(server, x);
        int edge = CampaignBands.SURFACE.minY();
        int lowest = edge - DESCENT_OFFSETS.getFirst() - SHAFT_BELOW;
        // The shaft is a tube of one rock from its bottom to the ground, so that the wall is the same block at the same
        // distance in every still
        // and only the biome blend changes: past the surface floor the natural terrain is layer 1's open space.
        server.runOnServer(s -> {
            ServerLevel level = ClientLight.serverLevel(s, CAMPAIGN);
            RoomCarver.carve(
                    level,
                    new BlockPos(x - SHAFT_HALF - 1, lowest - 1, SITE - SHAFT_HALF - 1),
                    new BlockPos(x + SHAFT_HALF + 1, top.getY() + 1, SITE + SHAFT_HALF + 1),
                    LayerBlocks.layerGround(1).defaultBlockState());
            RoomCarver.carve(
                    level,
                    new BlockPos(x - SHAFT_HALF, lowest, SITE - SHAFT_HALF),
                    new BlockPos(x + SHAFT_HALF, top.getY() + 1, SITE + SHAFT_HALF),
                    Blocks.AIR.defaultBlockState());
        });
        awaitLight(context, server);
        int step = 0;
        for (int offset : DESCENT_OFFSETS) {
            step++;
            BlockPos at = new BlockPos(x, edge + offset, SITE);
            String where = offset == 0
                    ? "at-the-edge"
                    : Math.abs(offset) + (offset > 0 ? "-above-the-edge" : "-below-the-edge");
            still(context, server, CAMPAIGN, at, 0, "descent-" + step + "-" + where);
        }
    }

    /**
     * Waits until the server's light engine is idle after a scene is built. A room that nobody is in is unloaded and saved at once, and one
     * saved before the engine has used its light block is read back dark, on the server too: the same lit room read 14.0 in one run and
     * 9.7, the unlit room's value, in the next.
     */
    private static void awaitLight(ClientGameTestContext context, TestServerContext server) {
        ClientWait.until(
                context,
                "the server's light engine to be idle",
                () -> ClientLight.serverLightIdle(context, server),
                () -> "light work pending");
    }

    /** Stands the camera at {@code floor} facing north at {@code pitch}, takes the still and a frame. */
    private void still(
            ClientGameTestContext context,
            TestServerContext server,
            ResourceKey<Level> dimension,
            BlockPos floor,
            float pitch,
            String name) {
        stand(context, server, dimension, floor, NORTH, pitch, name);
        screenshot(context, name);
        ClientLight.logStillLight(context, server, dimension, name, floor.above());
        frame(context);
    }

    /**
     * Stands the camera in the dark room, then puts the figure in it: a figure spawned before the camera arrives is in a chunk the
     * server has let go, and the client never gets it.
     */
    private void stillWithFigure(
            ClientGameTestContext context,
            TestServerContext server,
            ResourceKey<Level> dimension,
            BlockPos floor,
            String name) {
        stand(context, server, dimension, floor, NORTH, 0, name);
        int figure = spawnFigure(server, dimension, floor);
        ClientWait.until(
                context,
                "the figure of " + name + " on the client",
                client -> client.level != null && client.level.getEntity(figure) instanceof LamplessFigure);
        // tick-wait: the figure's first frame draws a few ticks after the client has it; nothing reports it
        context.waitTicks(20);
        screenshot(context, name);
        frame(context);
    }

    /** A lampless figure with no AI, {@value #FIGURE_AHEAD} blocks north of {@code floor}, facing south. */
    private static int spawnFigure(TestServerContext server, ResourceKey<Level> dimension, BlockPos floor) {
        int[] id = {0};
        server.runOnServer(s -> {
            ServerLevel level = ClientLight.serverLevel(s, dimension);
            LamplessFigure figure = TestEntities.create(CreatureRegistry.LAMPLESS_FIGURE, level);
            figure.setNoAi(true);
            figure.setPersistenceRequired();
            figure.setPos(Vec3.atBottomCenterOf(floor.relative(Direction.NORTH, FIGURE_AHEAD)));
            figure.setHeading(Direction.SOUTH);
            level.addFreshEntity(figure);
            id[0] = figure.getId();
        });
        return id[0];
    }

    // ------------------------------------------------------------------------------------------------------ surface

    /**
     * The colony and a mesa in a world of the design tour's seed, the game's own world. {@code nether}: its blocks are shaded as {@code
     * cardinal_light: nether} shades them; the switch is on before the world opens, so that every section is meshed with it. {@code look}
     * ends the names of the stills.
     */
    private Places surface(ClientGameTestContext context, boolean nether, String look) {
        EvidencePins.cardinalLightNether = nether;
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(state -> state.setSeed(SEED))
                .create()) {
            awaitWorld(context);
            EvidenceWorld.pin(context, singleplayer);
            TestServerContext server = singleplayer.getServer();
            server.runOnServer(s -> player(s).setGameMode(GameType.SPECTATOR));
            ClientWait.until(
                    context,
                    "the colony built",
                    () -> server.computeOnServer(s ->
                            Colony.placed(s).map(ColonySite.Placed::finished).orElse(false)),
                    () -> "no finished colony");
            ColonySite.Placed colony = server.computeOnServer(
                    s -> Colony.placed(s).orElseThrow(() -> new AssertionError("the colony was not built")));
            BlockPos mesa = server.computeOnServer(s -> closestMesa(s, colony.center()));
            Vec3 centre = Vec3.atCenterOf(colony.center());
            Vec3 office = Vec3.atCenterOf(TestColony.anchor(colony, ColonyAnchor.CONTINUITY_OFFICE));
            ClientChecks.withHudHidden(context, () -> {
                surfaceStill(
                        context, server, centre.add(0, AERIAL_HEIGHT, AERIAL_SOUTH), centre, "colony-aerial-" + look);
                surfaceStill(
                        context,
                        server,
                        office.add(-PLAZA_WEST, PLAZA_HEIGHT, 0),
                        office.add(0, OFFICE_HEIGHT, 0),
                        "colony-plaza-" + look);
                Vec3 mesaTop = Vec3.atBottomCenterOf(mesa);
                int groundAtCamera = server.computeOnServer(s -> TestLayers.level(s, TestLayers.SURFACE)
                        .getHeight(Heightmap.Types.MOTION_BLOCKING, mesa.getX(), mesa.getZ() + MESA_BACK));
                double eyeY = Math.max(groundAtCamera + MESA_CLEARANCE, mesa.getY() + MESA_RISE);
                surfaceStill(
                        context,
                        server,
                        new Vec3(mesaTop.x, eyeY, mesaTop.z + MESA_BACK),
                        mesaTop.add(0, MESA_RISE / 2.0, 0),
                        "mesa-" + look);
            });
            return new Places(colony.center(), mesa);
        } finally {
            EvidencePins.cardinalLightNether = false;
        }
    }

    /** The top of the ground at the middle of the closest mesa_country biome to {@code from}, found as {@code /locate biome} finds one. */
    private static BlockPos closestMesa(MinecraftServer server, BlockPos from) {
        ServerLevel level = TestLayers.level(server, TestLayers.SURFACE);
        Pair<BlockPos, Holder<Biome>> found = level.findClosestBiome3d(
                biome -> biome.is(MESA), from, MESA_SEARCH_RADIUS, MESA_SEARCH_HSTEP, MESA_SEARCH_VSTEP);
        if (found == null) {
            throw new AssertionError("no mesa_country biome within " + MESA_SEARCH_RADIUS + " blocks of " + from);
        }
        BlockPos at = found.getFirst();
        return new BlockPos(
                at.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING, at.getX(), at.getZ()), at.getZ());
    }

    /** Flies the camera to {@code eye} looking at {@code target}, waits for the surface to be ready, and takes the still and a frame. */
    private void surfaceStill(
            ClientGameTestContext context, TestServerContext server, Vec3 eye, Vec3 target, String name) {
        Vec3 d = target.subtract(eye);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
        server.runOnServer(s -> {
            ServerPlayer player = player(s);
            player.setNoGravity(true);
            player.teleportTo(
                    TestLayers.level(s, TestLayers.SURFACE), eye.x, eye.y - EYE, eye.z, Set.of(), yaw, pitch, true);
        });
        // The camera must be where the still says and looking where it says: a camera a block short makes
        // another picture.
        ClientWait.until(
                context,
                "the camera at the eye of " + name,
                client -> TestLayers.clientIsIn(client, TestLayers.SURFACE)
                        && ClientSession.player(client).distanceToSqr(eye.x, eye.y - EYE, eye.z) < 0.0001
                        && Math.abs(Mth.wrapDegrees(ClientSession.player(client).getYRot() - yaw)) < 0.01f
                        && Math.abs(ClientSession.player(client).getXRot() - pitch) < 0.01f,
                client -> "the player at " + ClientSession.player(client).position());
        awaitSurfaceReady(context, server, name);
        screenshot(context, name);
        frame(context);
    }

    /**
     * Waits, on the wall clock, until the server's light engine is idle and the client holds every chunk of its render distance, has
     * rendered every section and has no mob or item in view, for {@value #READY_POLLS_STABLE} looks in a row. The mobs that chunk
     * generation has put in view are discarded on each look: the surface of a world has cows, and where they stand differs by run.
     */
    private void awaitSurfaceReady(ClientGameTestContext context, TestServerContext server, String name) {
        int[] stable = {0};
        ClientWait.until(
                context,
                "the surface ready for " + name,
                () -> {
                    server.runOnServer(CampaignAtmosphereScenario::discardStrays);
                    context.runOnClient(client -> client.particleEngine.clearParticles());
                    // tick-wait: a poll's gap; the conditions after it are what is awaited
                    context.waitTicks(READY_POLL_TICKS);
                    boolean ready = ClientLight.serverLightIdle(context, server)
                            && context.computeOnClient(CampaignAtmosphereScenario::clientReady);
                    stable[0] = ready ? stable[0] + 1 : 0;
                    return stable[0] >= READY_POLLS_STABLE;
                },
                () -> "chunks, sections, light or strays not settled");
    }

    private static void discardStrays(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            List<Entity> strays = new ArrayList<>();
            level.getAllEntities().forEach(entity -> {
                if (isStray(entity)) {
                    strays.add(entity);
                }
            });
            strays.forEach(Entity::discard);
        }
    }

    private static boolean isStray(Entity entity) {
        return entity instanceof Mob || entity instanceof ItemEntity;
    }

    /** True when the client holds every chunk of its render distance round the player, has rendered every section, and draws no stray. */
    private static boolean clientReady(Minecraft client) {
        for (Entity entity : ClientLight.clientLevel(client).entitiesForRendering()) {
            if (isStray(entity)) {
                return false;
            }
        }
        int radius = client.options.getEffectiveRenderDistance();
        int centreX = ClientSession.player(client).chunkPosition().x();
        int centreZ = ClientSession.player(client).chunkPosition().z();
        for (int cx = centreX - radius; cx <= centreX + radius; cx++) {
            for (int cz = centreZ - radius; cz <= centreZ + radius; cz++) {
                if (ClientLight.clientLevel(client).getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false)
                        == null) {
                    return false;
                }
            }
        }
        return client.levelRenderer.hasRenderedAllSections();
    }

    private static ServerPlayer player(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }
}
