package io.github.pkeppeler.deepcharter.test.evidence;

import static io.github.pkeppeler.deepcharter.test.support.TourShots.EYE;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.charter.terminal.ContractScreen;
import io.github.pkeppeler.deepcharter.client.fuel.FuelPumpScreen;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookPage;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreen;
import io.github.pkeppeler.deepcharter.client.handbook.HandbookScreenTuning;
import io.github.pkeppeler.deepcharter.client.hangar.HangarScreen;
import io.github.pkeppeler.deepcharter.client.market.OreProcessorScreen;
import io.github.pkeppeler.deepcharter.client.ore.OreCargoScreen;
import io.github.pkeppeler.deepcharter.client.repair.RepairStationScreen;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalScreen;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalViewScreen;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.client.upgrade.UpgradeScreen;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.creature.CreatureRegistry;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import io.github.pkeppeler.deepcharter.handbook.HandbookItems;
import io.github.pkeppeler.deepcharter.handbook.HandbookVisibility;
import io.github.pkeppeler.deepcharter.handbook.NoteBlock;
import io.github.pkeppeler.deepcharter.hangar.HangarParts;
import io.github.pkeppeler.deepcharter.layer.BreachService;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerStructures;
import io.github.pkeppeler.deepcharter.layer.LightSync;
import io.github.pkeppeler.deepcharter.layer.StructureKind;
import io.github.pkeppeler.deepcharter.layer.StructureSite;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.test.ScannerHudTest;
import io.github.pkeppeler.deepcharter.test.support.Arrival;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientLight.Box;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.EvidenceWorld;
import io.github.pkeppeler.deepcharter.test.support.HangarConsole;
import io.github.pkeppeler.deepcharter.test.support.HangarPods;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ShippedHandbook;
import io.github.pkeppeler.deepcharter.test.support.TerminalParts;
import io.github.pkeppeler.deepcharter.test.support.TestColony;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TourShots;
import io.github.pkeppeler.deepcharter.test.support.TourShots.Subject;
import io.github.pkeppeler.deepcharter.test.support.UseEntity;
import io.github.pkeppeler.deepcharter.transmission.Transmission;
import io.github.pkeppeler.deepcharter.transmission.Transmissions;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Evidence scenario "design-tour" for #223: a fixed set of named stills of everything in the game as it looks today, so the design
 * overhaul can shoot the same views afterwards and show before and after. It is a reference shoot, not a story: the player is a
 * creative, invulnerable camera. The still names are the file names, and docs/design/current-state.md embeds them. A short orbit of
 * the colony supplies the frames of the GIF.
 *
 * <p>Every view is derived from the world, never from fixed coordinates: the colony views are offsets from the {@link ColonyAnchor}
 * of the building they show (the statue for the square), and the structure views are offsets in the axes of the structure site. A
 * building that the overhaul moves, so moves its views with it. Each still that has a subject (the chapel candle, the statue
 * column, a terminal, a lantern) is taken through {@link #shoot}, which first checks that a block of the subject stands within a few
 * blocks of the point looked at, and that nothing else stands in the line of sight. When it does not, the tour throws an
 * {@link AssertionError} that names the still, so that a moved or redrawn building fails the run, and no wrong picture is filed
 * as the "after".
 *
 * <p>Two runs of one commit give the same stills (tools/diff-stills compares them): the world is pinned by {@link EvidenceWorld#pin} (seed,
 * clock, weather, random ticks, mob spawning, particles) and every still goes through {@link Run#settle} first, which clears the mobs
 * and the particles, parks the cursor off the window, and waits until the chunks have rendered. {@link Run#pinTheClient} holds still
 * what moves on client ticks (lava, light flicker, the HUD vignette, the rider's arm, entity animation), and the charter has a fixed
 * id so that the pods are painted the same colour. See .claude/skills/deepcharter-conventions/evidence.md for what still differs.
 *
 * <p>The order: handbook and item gallery, the surface by day, dusk and night, the colony, the terminal screens, the pods by day,
 * a dark room (pods lit and unlit, the lampless figure), the HUDs, layer 1, the breach into layer 2, layer 2 and its structures, the
 * last transmissions, then layer 3: a cavern of each zone. Layer 3 comes last, so its chunks generate after every other still is
 * taken and no earlier still changes for it. {@link HollowsTourScenario} shoots the layer 3 stops alone.
 */
public class DesignTourScenario extends EvidenceScenario {
    private static final int ORBIT_FRAMES = 26;
    private static final int ORBIT_RADIUS = 58;
    private static final int ORBIT_HEIGHT = 34;
    /** The sealed dark room is built this far from the colony, at this height: no sky reaches it. */
    private static final int ROOM_OFFSET = 300;

    private static final int ROOM_Y = SKY_SLAB_Y;
    private static final int ROOM_RADIUS = 9;
    private static final int ROOM_HEIGHT = 7;
    private static final int LAYER_X = 2000;
    private static final int LAYER_Z = 2000;
    /** Layer 3's views are searched in the chunks this far round the layer's start column, for a floor with this long a view. */
    private static final int HOLLOWS_CHUNKS = 3;

    private static final int HOLLOWS_RUN = 32;
    /** A layer 3 view looks this many blocks ahead, and this much down for each block ahead. */
    private static final int HOLLOWS_LOOK = 16;

    private static final double HOLLOWS_DROP = 0.15;
    /** Ticks a layer 3 air still gives a zone's ambient particles to fill the view: they appear a few a tick round the camera. */
    private static final int HOLLOWS_AIR_TICKS = 40;

    private static final int ITEMS_PER_PAGE = 36;
    private static final long FUNDS = 5_000;
    private static final int TERMINAL_TYPING_TICKS = 140;
    /** The wall-clock limit of one settle. A world that has not settled by then fails the run, naming the still. */
    private static final long SETTLE_LIMIT_NANOS = 90_000_000_000L;
    /** Ticks between two looks at whether the world has settled. */
    private static final int SETTLE_POLL_TICKS = 2;
    /** The world counts as settled after this many looks in a row that find it ready: light and meshes arrive some ticks late. */
    private static final int SETTLE_STABLE_POLLS = 5;
    /** Where the cursor is parked when a screen is open: well outside the window, so that no slot or button is hovered. */
    private static final double OFF_SCREEN = -10_000;
    /** The tour's charter has this id on every run, so that its pods are painted the same colour on every run (ADR 0041: the slot is the id's hash). */
    private static final CharterId TOUR_CHARTER = new CharterId(new UUID(0L, 0L));

    /** A room carved by the tour, in its dimension. */
    private record LitRoom(ResourceKey<Level> dimension, Box box) {}

    /** A view of a zone of layer 3: the zone's name, and where the camera stands and looks. */
    private record HollowsView(String zone, Vec3 eye, Vec3 target) {}

    private final Arrival<CharterId> charter = new Arrival<>("charter", "setUpCamera() founds it");
    /** The rooms the tour carved whose light settle waits on: it waits until the client reads the light the server holds in each room it is in. */
    private final List<LitRoom> litRooms = new CopyOnWriteArrayList<>();
    /** The mobs the tour made itself (the lampless figures it poses): settle leaves them, and clears every other mob. */
    private final Set<UUID> tourMobs = ConcurrentHashMap.newKeySet();

    @Override
    protected String name() {
        return "design-tour";
    }

    /** The stops a run of the tour shoots. */
    enum Stops {
        ALL,
        /** Only layer 3's caverns, after the world, the colony and the camera are set up as for the whole tour. */
        LAYER_THREE
    }

    @Override
    protected void run(ClientGameTestContext context) {
        tour(context, this, Stops.ALL);
    }

    /** Shoots {@code stops} of the tour, filing the stills and frames under {@code owner}'s name. */
    void tour(ClientGameTestContext context, EvidenceScenario owner, Stops stops) {
        ClientChecks.withHudHidden(context, () -> runHidden(context, owner, stops));
    }

    private void runHidden(ClientGameTestContext context, EvidenceScenario owner, Stops stops) {
        // A real world, as a new player gets one: the vanilla terrain of the seed, not the flat world of the
        // other scenarios.
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(state -> state.setSeed("deepcharter-design-tour"))
                .create()) {
            awaitWorld(context);
            context.waitTicks(80);
            ColonySite.Placed colony = singleplayer
                    .getServer()
                    .computeOnServer(
                            server -> Colony.placed(server).orElseThrow(() -> new AssertionError("no colony")));
            new Run(context, singleplayer, colony, owner, stops).play();
        } finally {
            EvidencePins.reset();
        }
    }

    /** Where the camera is, and the pod it rides if it rides one: a pod still gliding to a halt is not settled. */
    private static String pose(Minecraft client) {
        Entity camera = ClientSession.camera(client);
        String pose = camera.position() + " " + camera.getYRot() + " " + camera.getXRot();
        Entity vehicle = camera.getVehicle();
        return vehicle == null ? pose : pose + " " + vehicle.position() + " " + vehicle.getYRot();
    }

    private final class Run {
        private final ClientGameTestContext ctx;
        private final TestSingleplayerContext sp;
        private final ColonySite.Placed colony;
        private final EvidenceScenario owner;
        private final Stops stops;

        Run(
                ClientGameTestContext ctx,
                TestSingleplayerContext sp,
                ColonySite.Placed colony,
                EvidenceScenario owner,
                Stops stops) {
            this.ctx = ctx;
            this.sp = sp;
            this.colony = colony;
            this.owner = owner;
            this.stops = stops;
        }

        private void play() {
            EvidenceWorld.pin(ctx, sp);
            if (stops == Stops.LAYER_THREE) {
                pinTheClient();
                setUpCamera();
                layerThree();
                return;
            }
            // The handbook and item stills have the HUD under their screen; it is hidden from setUpCamera on.
            ClientChecks.withHudShown(ctx, () -> {
                pinTheClient();
                handbook();
                itemGallery();
            });
            setUpCamera();
            surface();
            colonyTour();
            terminalScreens();
            podsByDay();
            blockGallery();
            darkRoom();
            hudsOnTheSurface();
            layerOne();
            breachToLayerTwo();
            layerTwo();
            remainingTransmissions();
            layerThree();
        }

        /**
         * Holds still what moves on client ticks and frames and that no wait can settle (issue 309). The switches are in
         * {@link EvidencePins}.
         */
        private void pinTheClient() {
            EvidencePins.spritesFrozen = true;
            EvidencePins.vignetteHidden = true;
            EvidencePins.itemNameHidden = true;
            EvidencePins.lightFlickerOff = true;
            EvidencePins.handsHidden = true;
            EvidencePins.entitiesPosed = true;
            EvidencePins.figureSpawningOff = true;
            EvidencePins.figureAnimationAge = 0f;
            // The textures go back to their first frame on the next client tick.
            ctx.waitTicks(2);
        }

        // ------------------------------------------------------------------------------------------------ determinism

        /**
         * A mob the tour did not make, or a loose item or orb: a cow of the world, a lampless figure that the rails spawned on their own.
         * The tour never shows one, because where it walks is different every run.
         */
        private boolean isStray(Entity entity) {
            return (entity instanceof Mob && !tourMobs.contains(entity.getUUID()))
                    || entity instanceof ItemEntity
                    || entity instanceof ExperienceOrb;
        }

        /**
         * Gets the world ready for a still: clears the mobs that chunk generation has put in view since the last one, clears the
         * particles, parks the cursor off the window when a screen is open (so no slot or button is hovered and no tooltip shows), and
         * waits until the client has removed the mobs and rendered every chunk section. The wait ends on those conditions, and fails
         * with an {@link AssertionError} naming the still after {@link #SETTLE_LIMIT_NANOS} of wall-clock time; it never counts ticks.
         */
        private void settle(String stillName) {
            long deadline = System.nanoTime() + SETTLE_LIMIT_NANOS;
            int stable = 0;
            String lastPose = null;
            while (true) {
                serverDo(server -> {
                    for (ServerLevel level : server.getAllLevels()) {
                        List<Entity> strays = new ArrayList<>();
                        level.getAllEntities().forEach(entity -> {
                            if (isStray(entity)) {
                                strays.add(entity);
                            }
                        });
                        strays.forEach(Entity::discard);
                    }
                });
                ctx.runOnClient(client -> client.particleEngine.clearParticles());
                if (ctx.computeOnClient(client -> client.gui.screen() != null)) {
                    ctx.getInput().setCursorPos(OFF_SCREEN, OFF_SCREEN);
                }
                ctx.waitTicks(SETTLE_POLL_TICKS);
                boolean lit = serverGet(server -> {
                    for (ServerLevel level : server.getAllLevels()) {
                        if (level.getLightEngine().hasLightWork()) {
                            return false;
                        }
                    }
                    return true;
                });
                boolean ready = lit
                        && ctx.computeOnClient(client -> {
                            for (Entity entity : ClientLight.clientLevel(client).entitiesForRendering()) {
                                if (isStray(entity)) {
                                    return false;
                                }
                            }
                            return chunksLoaded(client) && client.levelRenderer.hasRenderedAllSections();
                        });
                if (ready) {
                    ready = roomsInStep().isEmpty();
                }
                String pose = ctx.computeOnClient(DesignTourScenario::pose);
                stable = ready && pose.equals(lastPose) ? stable + 1 : 0;
                lastPose = pose;
                if (stable >= SETTLE_STABLE_POLLS) {
                    return;
                }
                if (System.nanoTime() > deadline) {
                    String light = roomsInStep()
                            .map(mismatch -> " or the light of a carved room on the client differs from the server's ("
                                    + mismatch + ")")
                            .orElse("");
                    throw new AssertionError(
                            "still " + stillName + ": the world did not settle (mobs gone, chunks rendered)" + light
                                    + " in " + SETTLE_LIMIT_NANOS / 1_000_000_000L + " s");
                }
            }
        }

        /**
         * Empty when the client reads, in each carved room it holds, the light the server holds; otherwise what differs. A room is carved
         * with {@link LightSync#holdSendUntilLit} after it, so the client is not sent its chunk before the light is computed (issue 427, found
         * in the dark room of PR 422); a room the client still reads wrong is a failure of that, and settle names it. A room in another
         * dimension, or whose chunks the client has dropped since the camera left it, is not checked.
         */
        private Optional<String> roomsInStep() {
            for (LitRoom room : litRooms) {
                if (ClientLight.clientHolds(ctx, room.dimension(), room.box())) {
                    Optional<String> mismatch =
                            ClientLight.describeMismatch(ctx, sp.getServer(), room.dimension(), room.box());
                    if (mismatch.isPresent()) {
                        return mismatch;
                    }
                }
            }
            return Optional.empty();
        }

        /** True when the client holds every chunk of the square of its render distance round the player: the far trees are in the picture. */
        private static boolean chunksLoaded(Minecraft client) {
            int radius = client.options.getEffectiveRenderDistance();
            int centreX = ClientSession.player(client).chunkPosition().x();
            int centreZ = ClientSession.player(client).chunkPosition().z();
            for (int x = centreX - radius; x <= centreX + radius; x++) {
                for (int z = centreZ - radius; z <= centreZ + radius; z++) {
                    if (ClientLight.clientLevel(client).getChunkSource().getChunk(x, z, ChunkStatus.FULL, false)
                            == null) {
                        return false;
                    }
                }
            }
            return true;
        }

        // ------------------------------------------------------------------------------------------------
        // handbook, items

        private void handbook() {
            ClientWait.until(ctx, "the handbook in the hotbar", client -> handbookSlot(client) >= 0);
            ctx.runOnClient(
                    client -> ClientSession.player(client).getInventory().setSelectedSlot(handbookSlot(client)));
            ctx.getInput().pressKey(options -> options.keyUse);
            ClientWait.screen(ctx, HandbookScreen.class);
            ctx.waitTicks(HandbookScreenTuning.current().flipTicks() + 10);
            still("handbook-opened-from-the-item");
            ctx.setScreen(() -> null);

            // Built as HandbookScreen.open builds it: the shipped chapters only, with the progress the server synced.
            List<HandbookPage> pages = ctx.computeOnClient(ShippedHandbook::pages);
            ctx.setScreen(() -> new HandbookScreen(pages, id -> true, id -> {}, List.of()));
            ClientWait.screen(ctx, HandbookScreen.class);
            HandbookScreen screen = ctx.computeOnClient(client -> ClientSession.screen(client, HandbookScreen.class));
            Set<String> seen = new HashSet<>();
            for (int index = 0; index < pages.size(); index++) {
                HandbookPage page = pages.get(index);
                String kind = page.getClass().getSimpleName().toLowerCase(Locale.ROOT);
                boolean classified =
                        page instanceof HandbookPage.Chapter chapter && chapter.visibility() != HandbookVisibility.FULL;
                String key = classified ? "chapter-classified" : kind;
                if (!seen.add(key) || (page instanceof HandbookPage.Contents contents && contents.part() > 1)) {
                    continue;
                }
                int target = index;
                ctx.runOnClient(client -> screen.goTo(target));
                ctx.waitTicks(HandbookScreenTuning.current().flipTicks() + 6);
                still("handbook-page-" + key);
            }
            ctx.runOnClient(client -> screen.showNotes());
            ctx.waitTicks(6);
            still("handbook-notes-tab");
            ctx.setScreen(() -> null);
        }

        private static int handbookSlot(Minecraft client) {
            for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
                if (HandbookItems.isHandbook(
                        ClientSession.player(client).getInventory().getItem(slot))) {
                    return slot;
                }
            }
            return -1;
        }

        /** Every item of the mod in the inventory screen, 36 to a page. Block items show their block model. */
        private void itemGallery() {
            List<Item> items = BuiltInRegistries.ITEM.keySet().stream()
                    .filter(id -> id.getNamespace().equals(DeepCharter.MOD_ID))
                    .sorted()
                    .map(BuiltInRegistries.ITEM::getValue)
                    .toList();
            for (int page = 0; page * ITEMS_PER_PAGE < items.size(); page++) {
                int first = page * ITEMS_PER_PAGE;
                serverDo(server -> {
                    ServerPlayer player = player(server);
                    player.getInventory().clearContent();
                    for (int slot = 0; slot < ITEMS_PER_PAGE && first + slot < items.size(); slot++) {
                        player.getInventory().setItem(slot, new ItemStack(items.get(first + slot)));
                    }
                });
                ctx.waitTicks(4);
                ctx.setScreen(() -> new InventoryScreen(ClientSession.player(Minecraft.getInstance())));
                ClientWait.screen(ctx, InventoryScreen.class);
                ctx.waitTicks(6);
                still("items-gallery-" + (page + 1));
                ctx.setScreen(() -> null);
            }
        }

        // ------------------------------------------------------------------------------------------------
        // surface, colony

        private void setUpCamera() {
            serverDo(server -> {
                ServerPlayer player = player(server);
                player.setGameMode(GameType.CREATIVE);
                player.getInventory().clearContent();
                player.getAbilities().mayfly = true;
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                player.setPermanentlyInvulnerable(true);
                if (Charters.found(server, player.getUUID(), "Design Tour Co.", TOUR_CHARTER)
                        .isPresent()) {
                    throw new AssertionError("founding the charter should succeed");
                }
                charter.set(Charters.charterOfOrThrow(server, player.getUUID())
                        .orElseThrow()
                        .id());
                Charters.deposit(server, charter.get(), FUNDS);
            });
            ctx.runOnClient(client -> {
                client.options.setCameraType(CameraType.FIRST_PERSON);
                setHidden(client, true);
            });
        }

        /** A point {@code dx} east and {@code dz} south of the middle of the square (the foot of its plinth), {@code h} above the square's paving. */
        private Vec3 p(double dx, double h, double dz) {
            return TourShots.p(colony, dx, h, dz);
        }

        /** A point {@code dx} east, {@code dy} up and {@code dz} south of the bottom centre of the block of {@code anchor}. */
        private Vec3 rel(ColonyAnchor anchor, double dx, double dy, double dz) {
            return TourShots.rel(colony, anchor, dx, dy, dz);
        }

        /** Takes the named still of {@code target} from {@code eye}, after checking that the {@code subject} is really there. */
        private void shoot(String stillName, int layer, Vec3 eye, Vec3 target, int wait, Subject subject) {
            view(layer, eye, target, wait);
            verify(stillName, layer, eye, target, subject);
            still(stillName);
        }

        private void shoot(TourShots.Shot shot) {
            shoot(shot.name(), 0, shot.eye(), shot.target(), shot.settleTicks(), shot.subject());
        }

        /**
         * Throws an {@link AssertionError} naming the still when the world has moved the subject
         * ({@link TourShots#problem}). A building that moves in the overhaul then fails the tour loudly, and does
         * not make it shoot the wrong thing.
         */
        private void verify(String stillName, int layer, Vec3 eye, Vec3 target, Subject subject) {
            String problem =
                    serverGet(server -> TourShots.problem(TestLayers.level(server, layer), eye, target, subject));
            if (problem != null) {
                throw new AssertionError("still " + stillName + ": " + problem);
            }
        }

        /** Throws, naming the still, if a building or a hill now stands where the pods are put: the 9 x 9 blocks round {@code stage}. */
        private void expectOpenGround(String stillName, Vec3 stage) {
            BlockPos centre = BlockPos.containing(stage);
            BlockPos solid =
                    serverGet(server -> BlockPos.betweenClosedStream(centre.offset(-4, 0, -4), centre.offset(4, 3, 4))
                            .filter(pos -> !server.overworld()
                                    .getBlockState(pos)
                                    .getCollisionShape(server.overworld(), pos)
                                    .isEmpty())
                            .findFirst()
                            .map(BlockPos::immutable)
                            .orElse(null));
            if (solid != null) {
                throw new AssertionError("still " + stillName + ": " + solid.toShortString()
                        + " is solid, so the pods' stage is not open ground");
            }
        }

        /** The stage for the pods, on the open ground east of the town's Continuity Office and north of the bunkhouse. */
        private Vec3 stage() {
            return p(30, 0, -8);
        }

        private void surface() {
            // From south of the town's south row, where nothing of the colony is in the way to the south, east
            // and west.
            Vec3 stand = p(0, EYE, 30);
            String[] names = {"south", "east", "west", "north"};
            Vec3[] targets = {p(0, EYE, 128), p(100, EYE, 28), p(-100, EYE, 28), p(0, EYE, -72)};
            view(0, stand, targets[0], 100);
            for (int i = 0; i < names.length; i++) {
                view(0, stand, targets[i], 20);
                still("surface-" + names[i] + "-noon");
            }
            view(0, stand, p(0, EYE + 90, 70), 20);
            still("sky-up-noon");
            serverDo(server -> command(server, "time set 12500"));
            EvidenceWorld.skyPhase(ctx, sp, EvidenceWorld.SKY_HALFWAY);
            view(0, stand, targets[0], 40);
            still("surface-south-dusk");
            view(0, stand, p(0, EYE + 70, 120), 20);
            still("sky-up-dusk");
            serverDo(server -> command(server, "time set 18000"));
            EvidenceWorld.skyPhase(ctx, sp, EvidenceWorld.SKY_DARKEST);
            view(0, stand, targets[3], 40);
            still("surface-north-night");
            view(0, stand, p(0, EYE + 90, 70), 20);
            still("sky-up-night");
            serverDo(server -> command(server, "time set noon"));
            EvidenceWorld.skyPhase(ctx, sp, EvidenceWorld.SKY_BRIGHTEST);
        }

        private void colonyTour() {
            TourShots.overviews(colony).forEach(this::shoot);

            // The orbit for the GIF.
            Vec3 middle = TourShots.middle(colony);
            for (int i = 0; i < ORBIT_FRAMES; i++) {
                double angle = 2 * Math.PI * i / ORBIT_FRAMES + Math.PI / 2;
                Vec3 eye = p(Math.cos(angle) * ORBIT_RADIUS, ORBIT_HEIGHT, Math.sin(angle) * ORBIT_RADIUS);
                view(0, eye, middle, i == 0 ? 40 : 2);
                if (i == 0) {
                    verify("colony-orbit", 0, eye, middle, TourShots.TOWN);
                }
                owner.frame(ctx);
            }

            TourShots.closeUps(colony).forEach(this::shoot);
        }

        // ------------------------------------------------------------------------------------------------ terminals

        private void terminalScreens() {
            Vec3 derelict = serverGet(
                    server -> HangarPods.derelict(server).orElseThrow().position());
            BlockPos console = serverGet(server -> HangarConsole.find(server).orElseThrow());
            Vec3 consoleEye = Vec3.atBottomCenterOf(console.west(2)).add(0, EYE, 0);
            view(0, consoleEye, derelict.add(0, 1, 0), 60);
            still("hangar-derelict-mole");
            Vec3 bay = rel(ColonyAnchor.HANGAR, 0, 0, 0);
            if (derelict.distanceTo(bay) > 6) {
                throw new AssertionError("still hangar-derelict-mole: the derelict Mole is " + derelict.distanceTo(bay)
                        + " blocks from the hangar anchor");
            }
            view(0, bay.add(5.5, 3, -2), derelict.add(0, 0.8, 0), 20);
            still("hangar-derelict-mole-from-the-door");
            view(0, bay.add(-5, 3, 5), derelict.add(0, 0.8, 0), 20);
            still("hangar-derelict-mole-from-the-back");

            // Offline: the screen where the parts go in.
            List<TerminalType> order = List.of(
                    TerminalTypes.FUEL_PUMP,
                    TerminalTypes.ORE_PROCESSOR,
                    TerminalTypes.UPGRADE_TERMINAL,
                    TerminalTypes.REPAIR_STATION);
            ColonyAnchor[] anchorOf = {
                ColonyAnchor.FUEL_PUMP,
                ColonyAnchor.ORE_PROCESSOR,
                ColonyAnchor.UPGRADE_TERMINAL,
                ColonyAnchor.REPAIR_STATION
            };
            for (int i = 0; i < order.size(); i++) {
                openTerminal(TestColony.anchor(colony, anchorOf[i]), TerminalScreen.class);
                still("screen-" + order.get(i).id().getPath().replace('_', '-') + "-offline");
                ctx.setScreen(() -> null);
            }
            openAt(console, consoleEye, TerminalScreen.class);
            still("screen-hangar-console-offline");
            ctx.setScreen(() -> null);

            // Repair the four colony terminals at once, and park a worked pod at each one so the screens
            // have something to show.
            serverDo(server -> {
                RepairState state = RepairState.get(server);
                for (TerminalType type : order) {
                    type.parts().forEach(part -> state.insert(type, part));
                }
            });
            serverDo(server -> {
                ServerPlayer player = player(server);
                player.getInventory().add(OreRegistry.stack(OreType.IRONIUM));
                player.getInventory().add(OreRegistry.stack(OreType.SILVERIUM));
            });
            Class<?>[] online = {
                FuelPumpScreen.class, OreProcessorScreen.class, UpgradeScreen.class, RepairStationScreen.class
            };
            for (int i = 0; i < order.size(); i++) {
                PodEntity pod = spawnPod(PodRegistry.POD, 0, rel(anchorOf[i], 0, -1, 3.5), 0f, false);
                serverDo(server -> {
                    pod.setFuel(34f);
                    pod.damageHull(pod.maxHull() * 0.45f);
                    pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.GOLDIUM));
                    pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.PLATINIUM));
                });
                openTerminal(TestColony.anchor(colony, anchorOf[i]), online[i]);
                still("screen-" + order.get(i).id().getPath().replace('_', '-') + "-online");
                ctx.setScreen(() -> null);
                serverDo(server -> pod.discard());
            }
            openTerminal(TestColony.anchor(colony, ColonyAnchor.CONTRACT_TERMINAL), ContractScreen.class);
            still("screen-contract-terminal");
            ctx.setScreen(() -> null);

            // The hangar console takes its four parts and repairs the founding Mole.
            view(0, consoleEye, Vec3.atCenterOf(console), 20);
            serverDo(server -> {
                ServerPlayer player = player(server);
                for (Item part : HangarParts.ALL) {
                    player.getInventory().add(new ItemStack(part));
                    TerminalParts.insert(player, console, part).ifPresent(refusal -> {
                        throw new AssertionError("inserting " + part + ": " + refusal);
                    });
                }
            });
            ctx.waitTicks(40);
            openAt(console, consoleEye, HangarScreen.class);
            still("screen-hangar-console-online");
            ctx.setScreen(() -> null);
            view(0, consoleEye, derelict.add(0, 1, 0), 40);
            still("hangar-founding-mole-repaired");
            serverDo(server -> player(server).getInventory().clearContent());
        }

        private void openTerminal(BlockPos terminal, Class<?> screen) {
            Vec3 eye = Vec3.atCenterOf(terminal).add(0, 0.9, 2.6);
            openAt(terminal, eye, screen);
        }

        private void openAt(BlockPos terminal, Vec3 eye, Class<?> screen) {
            view(0, eye, Vec3.atCenterOf(terminal), 20);
            ctx.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(terminal)));
            ClientWait.screen(ctx, screen.asSubclass(Screen.class));
            if (!TerminalViewScreen.class.isAssignableFrom(screen)) {
                throw new AssertionError(screen + " is not a terminal screen");
            }
            ctx.waitTicks(TERMINAL_TYPING_TICKS);
        }

        // ------------------------------------------------------------------------------------------------ pods

        private PodEntity spawnPod(EntityType<PodEntity> type, int layer, Vec3 at, float yaw, boolean lights) {
            return serverGet(server -> {
                ServerLevel level = TestLayers.level(server, layer);
                PodEntity pod = TestEntities.create(type, level);
                pod.setPos(at);
                pod.setYRot(yaw);
                level.addFreshEntity(pod);
                PodComponents.register(pod, charter.get());
                if (lights) {
                    PodComponents.install(pod, ComponentItems.mint(server, ComponentTrack.LIGHTS, 2, charter.get()));
                }
                return pod;
            });
        }

        /** The pod stands facing south (yaw 0). Front is its south side, back its north, side its east. */
        private void podAngles(String prefix, int layer, Vec3 base, double distance, double height, String[] angles) {
            Vec3 centre = base.add(0, height / 2, 0);
            for (String angle : angles) {
                Vec3 eye = switch (angle) {
                    case "front" -> base.add(0, 1.8, distance);
                    case "side" -> base.add(distance, 1.8, 0);
                    case "back" -> base.add(0, 1.8, -distance);
                    case "top" -> base.add(0, distance + 1, 0.5 * distance);
                    default -> throw new IllegalArgumentException(angle);
                };
                view(layer, eye, centre, 12);
                still(prefix + "-" + angle);
            }
        }

        private static final String[] FSB = {"front", "side", "back"};

        private void podsByDay() {
            Vec3 stage = stage();
            expectOpenGround("pods-by-day", stage);
            PodEntity mole = spawnPod(PodRegistry.POD, 0, stage, 0f, false);
            view(0, stage.add(0, 1.8, 6), stage.add(0, 1, 0), 60);
            podAngles("mole-unlit-day", 0, stage, 5.5, Chassis.MOLE.height(), FSB);
            podAngles("mole-unlit-day", 0, stage, 5.5, Chassis.MOLE.height(), new String[] {"top"});
            serverDo(server -> mole.discard());
            PodEntity prospector = spawnPod(PodRegistry.PROSPECTOR, 0, stage, 0f, false);
            podAngles("prospector-unlit-day", 0, stage, 7.5, 2.9, FSB);
            podAngles("prospector-unlit-day", 0, stage, 7.5, 2.9, new String[] {"top"});
            serverDo(server -> prospector.discard());

            // Wrecks: the hull gone to zero, the pod dark.
            PodEntity wreckedMole = spawnPod(PodRegistry.POD, 0, stage.add(-3, 0, 0), 0f, false);
            PodEntity wreckedProspector = spawnPod(PodRegistry.PROSPECTOR, 0, stage.add(3.5, 0, 0), 0f, false);
            serverDo(server -> {
                wreckedMole.damageHull(wreckedMole.maxHull());
                wreckedProspector.damageHull(wreckedProspector.maxHull());
            });
            view(0, stage.add(0, 2.4, 8), stage.add(0, 1, 0), 40);
            still("wrecks-mole-and-prospector-day");
            view(0, stage.add(-3, 1.8, 5.5), stage.add(-3, 1, 0), 20);
            still("wreck-mole-front-day");
            view(0, stage.add(3.5, 2, 7), stage.add(3.5, 1.4, 0), 20);
            still("wreck-prospector-front-day");
            serverDo(server -> {
                wreckedMole.discard();
                wreckedProspector.discard();
            });
        }

        /** Every block of the mod in a row on the pad, seven to a still, fronts to the camera. */
        private void blockGallery() {
            List<Block> blocks = BuiltInRegistries.BLOCK.keySet().stream()
                    .filter(id -> id.getNamespace().equals(DeepCharter.MOD_ID))
                    .sorted()
                    .map(BuiltInRegistries.BLOCK::getValue)
                    .toList();
            BlockPos row = colony.center().offset(-8, 1, 28);
            serverDo(server -> {
                ServerLevel level = server.overworld();
                for (int i = 0; i < blocks.size(); i++) {
                    BlockState state = blocks.get(i).defaultBlockState();
                    if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                        state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH);
                    }
                    level.setBlock(row.offset(i, 0, 0), state, 3);
                }
            });
            for (int page = 0; page * 7 < blocks.size(); page++) {
                int mid = Math.min(blocks.size() - 1, page * 7 + 3);
                Vec3 target = Vec3.atCenterOf(row.offset(mid, 0, 0));
                view(0, target.add(0, 1.2, 6), target, page == 0 ? 60 : 12);
                still("block-gallery-" + (page + 1));
            }
        }

        // ------------------------------------------------------------------------------------------------ dark room

        private void darkRoom() {
            BlockPos floor = colony.center().offset(ROOM_OFFSET, 0, ROOM_OFFSET).atY(ROOM_Y);
            serverDo(server -> {
                ServerLevel level = server.overworld();
                loadChunks(level, floor.getX(), floor.getZ(), 2);
                for (int dx = -ROOM_RADIUS - 1; dx <= ROOM_RADIUS + 1; dx++) {
                    for (int dz = -ROOM_RADIUS - 1; dz <= ROOM_RADIUS + 1; dz++) {
                        for (int dy = 0; dy <= ROOM_HEIGHT + 1; dy++) {
                            boolean shell = dy == 0
                                    || dy == ROOM_HEIGHT + 1
                                    || Math.abs(dx) == ROOM_RADIUS + 1
                                    || Math.abs(dz) == ROOM_RADIUS + 1;
                            // room-carver: a room in the overworld closed by its own stone shell, not layer rock
                            level.setBlock(
                                    floor.offset(dx, dy, dz),
                                    (shell ? Blocks.STONE : Blocks.AIR).defaultBlockState(),
                                    3);
                        }
                    }
                }
                LightSync.holdSendUntilLit(
                        level,
                        floor.offset(-ROOM_RADIUS - 1, 0, -ROOM_RADIUS - 1),
                        floor.offset(ROOM_RADIUS + 1, ROOM_HEIGHT + 1, ROOM_RADIUS + 1));
            });
            LitRoom dark = new LitRoom(
                    Level.OVERWORLD,
                    new Box(
                            floor.offset(-ROOM_RADIUS, 1, -ROOM_RADIUS),
                            floor.offset(ROOM_RADIUS, ROOM_HEIGHT, ROOM_RADIUS)));
            litRooms.add(dark);
            Vec3 base = Vec3.atBottomCenterOf(floor.above());
            view(0, base.add(0, 1.8, 6), base.add(0, 1, 0), 120);

            // Unlit first: a pod with no lights part in a room with no light at all.
            PodEntity mole = spawnPod(PodRegistry.POD, 0, base, 0f, false);
            view(0, base.add(0, 1.8, 5.5), base.add(0, 1, 0), 40);
            still("mole-unlit-dark-front");
            serverDo(server -> mole.discard());
            PodEntity prospector = spawnPod(PodRegistry.PROSPECTOR, 0, base, 0f, false);
            view(0, base.add(0, 1.8, 7.5), base.add(0, 1.4, 0), 40);
            still("prospector-unlit-dark-front");
            serverDo(server -> prospector.discard());

            // Lit: the lights part is a light source round the pod.
            PodEntity litMole = spawnPod(PodRegistry.POD, 0, base, 0f, true);
            awaitLight(litMole);
            ctx.waitTicks(30);
            podAngles("mole-lit-dark", 0, base, 5.5, Chassis.MOLE.height(), FSB);
            serverDo(server -> litMole.discard());
            PodEntity litProspector = spawnPod(PodRegistry.PROSPECTOR, 0, base, 0f, true);
            awaitLight(litProspector);
            ctx.waitTicks(30);
            podAngles("prospector-lit-dark", 0, base, 7.5, 2.9, FSB);
            serverDo(server -> litProspector.discard());

            // The lampless figure, still, in the dark room: once with night vision so it can be seen, once as
            // the player
            // would see it.
            LamplessFigure figure = serverGet(server -> {
                ServerLevel level = server.overworld();
                LamplessFigure f = TestEntities.create(CreatureRegistry.LAMPLESS_FIGURE, level);
                f.setPos(base);
                f.setNoAi(true);
                // A new entity gets a random yaw: face south, as the pods do, or the figure stands turned a little
                // differently in each run.
                f.setYRot(0f);
                f.setYBodyRot(0f);
                f.setYHeadRot(0f);
                tourMobs.add(f.getUUID());
                level.addFreshEntity(f);
                return f;
            });
            view(0, base.add(0, 1.8, 5), base.add(0, 1.2, 0), 40);
            still("lampless-figure-dark-no-night-vision");
            nightVision();
            ctx.waitTicks(10);
            for (String angle : FSB) {
                Vec3 eye = switch (angle) {
                    case "front" -> base.add(0, 1.8, 4.5);
                    case "side" -> base.add(4.5, 1.8, 0);
                    default -> base.add(0, 1.8, -4.5);
                };
                view(0, eye, base.add(0, 1.2, 0), 12);
                still("lampless-figure-" + angle);
            }
            view(0, base.add(0, 1.8, 2.2), base.add(0, 1.6, 0), 12);
            still("lampless-figure-close");
            serverDo(server -> figure.discard());

            // A figure beside a lit pod. It stands at rest beside the pod and stays whole: a light does not fade
            // it (#485).
            PodEntity lamp = spawnPod(PodRegistry.POD, 0, base.add(3, 0, 0), 0f, true);
            awaitLight(lamp);
            LamplessFigure walker = serverGet(server -> {
                ServerLevel level = server.overworld();
                LamplessFigure f = TestEntities.create(CreatureRegistry.LAMPLESS_FIGURE, level);
                f.setPos(base.add(-1, 0, 0));
                f.setNoAi(true);
                f.setYRot(-90f);
                f.setYBodyRot(-90f);
                f.setYHeadRot(-90f);
                tourMobs.add(f.getUUID());
                level.addFreshEntity(f);
                return f;
            });
            view(0, base.add(-1, 2.2, 7), base.add(-1, 1.2, 0), 30);
            still("lampless-figure-by-a-lit-pod");
            serverDo(server -> {
                walker.discard();
                lamp.discard();
                player(server).removeEffect(MobEffects.NIGHT_VISION);
            });
            litRooms.remove(dark);
        }

        // ------------------------------------------------------------------------------------------------ HUDs

        /** The pod's cargo screen, opened as a player does: sneak and use the pod. */
        private void cargoScreen() {
            Vec3 stage = stage();
            view(0, stage.add(0, EYE, -3), stage.add(2, 1, -3), 20);
            PodEntity pod = spawnPod(PodRegistry.POD, 0, stage.add(2, 0, -3), 0f, false);
            serverDo(server -> {
                for (OreType type : OreType.values()) {
                    pod.cargo().tryAdd(pod, OreRegistry.stack(type));
                }
                ServerPlayer player = player(server);
                player.setShiftKeyDown(true);
                UseEntity.on(player, player.level(), InteractionHand.MAIN_HAND, pod);
                player.setShiftKeyDown(false);
            });
            ClientWait.screen(ctx, OreCargoScreen.class);
            ctx.waitTicks(10);
            still("screen-pod-cargo");
            ctx.setScreen(() -> null);
            serverDo(server -> pod.discard());
        }

        private void hudsOnTheSurface() {
            cargoScreen();
            Vec3 stage = stage();
            view(0, stage.add(0, EYE, 0), stage.add(0, EYE, 20), 60);
            ScannerHudTest.mountFirstPlayer(sp.getServer(), 0);
            ClientWait.until(
                    ctx,
                    "the player riding a pod",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity);
            fillPodForHud();
            hud(true);
            ctx.waitTicks(20);
            still("hud-pod-status-and-altimeter-surface");
            ctx.runOnClient(client -> client.options.setCameraType(CameraType.THIRD_PERSON_BACK));
            ctx.waitTicks(20);
            still("hud-pod-in-third-person-surface");
            ctx.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
            leavePod();

            ScannerHudTest.rideWithGoldAhead(ctx, sp.getServer(), 1);
            fillPodForHud();
            ctx.waitTicks(20);
            still("hud-scanner-tier-1-surface");
            leavePod();
            hud(false);
        }

        /** Puts the player out of the pod they ride, if they ride one, and removes it. */
        private void leavePod() {
            serverDo(server -> {
                ServerPlayer player = player(server);
                if (player.getVehicle() instanceof PodEntity pod) {
                    player.stopRiding();
                    pod.discard();
                }
            });
            ClientWait.until(
                    ctx,
                    "the player out of the pod",
                    client -> ClientSession.player(client).getVehicle() == null);
        }

        private void fillPodForHud() {
            serverDo(server -> {
                PodEntity pod = TestEntities.vehicle(player(server), PodEntity.class);
                pod.setFuel(62f);
                pod.damageHull(pod.maxHull() * 0.3f);
                pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.GOLDIUM));
                pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.IRONIUM));
            });
        }

        private static void setHidden(Minecraft client, boolean hidden) {
            if (client.gui.hud.isHidden() != hidden) {
                client.gui.hud
                        .toggle(); // hud-toggle: the tour shows and hides the HUD between stills; run() restores the
                // starting state through ClientChecks.withHudHidden
            }
        }

        private void hud(boolean shown) {
            ctx.runOnClient(client -> setHidden(client, !shown));
        }

        // ------------------------------------------------------------------------------------------------ layer 1

        private void layerOne() {
            serverDo(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                loadChunks(one, LAYER_X, LAYER_Z, 3);
            });
            Vec3 cell = serverGet(server -> openCell(TestLayers.level(server, 1), 1));
            terrainViews(1, cell);

            // The structures of layer 1: the three shafts, one in each zone.
            for (StructureKind kind : StructureKind.inLayer(1)) {
                StructureSite site = siteOf(kind, null);
                String name = kind.name().toLowerCase(Locale.ROOT).replace('_', '-');
                int niche = site.height() / 2;
                view(1, at(site, 0, site.height() - 1.2, 0), at(site, 0, 0, 0), 120);
                still("structure-" + name + "-looking-down");
                shoot(
                        "structure-" + name + "-note-niche",
                        1,
                        at(site, -0.6, niche + 1.0, 0),
                        at(site, 2, niche + 0.5, 0.5),
                        20,
                        new Subject("the Note in the niche", state -> state.getBlock() instanceof NoteBlock, 1.5));
                view(1, at(site, 0.6, niche - 3, 0), at(site, 0, niche + 4, 0), 20);
                still("structure-" + name + "-looking-up");
            }

            // The breach crust: a room cut onto the floor of the layer, then the crust broken through.
            int x = LAYER_X + 200;
            int z = LAYER_Z + 200;
            serverDo(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                loadChunks(one, x, z, 2);
                BlockPos min = TestLayers.pos(1, x - 3, 3, z - 3);
                BlockPos max = TestLayers.pos(1, x + 3, 8, z + 3);
                RoomCarver.carve(one, min, max, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                RoomCarver.carvePocketBelow(one, 1, x - 2, x + 2, z - 2, z + 2);
            });
            litRooms.add(new LitRoom(
                    layerDimension(1),
                    new Box(TestLayers.pos(1, x - 3, 3, z - 3), TestLayers.pos(1, x + 3, 8, z + 3))));
            nightVision();
            view(1, TestLayers.vec(1, x + 0.5, 6.5, z + 0.5), TestLayers.vec(1, x + 0.5, 1.5, z + 1.5), 120);
            still("layer-1-breach-crust-floor");
            view(1, TestLayers.vec(1, x - 2.5, 4.5, z - 2.5), TestLayers.vec(1, x + 1.5, 3, z + 1.5), 20);
            still("layer-1-breach-crust-floor-low");
            serverDo(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int y = TestLayers.band(one, 1).minY();
                                y <= TestLayers.band(one, 1).minY() + 3;
                                y++) {
                            BreachService.breakCrust(one, new BlockPos(x + dx, y, z + dz));
                        }
                    }
                }
            });
            view(1, TestLayers.vec(1, x + 0.5, 6.5, z + 0.5), TestLayers.vec(1, x + 0.5, 1.5, z + 1.5), 40);
            still("layer-1-breach-crust-broken");
        }

        // ------------------------------------------------------------------------------------------------ breach,
        // layer 2

        private void breachToLayerTwo() {
            int x = LAYER_X + 200;
            int z = LAYER_Z + 200;
            serverDo(server -> {
                ServerLevel two = TestLayers.level(server, 2);
                loadChunks(two, x, z, 3);
            });
            hud(true);
            ctx.runOnClient(
                    client -> ClientSession.player(client).getInventory().clearContent());
            view(1, TestLayers.vec(1, x + 0.5, 4.0, z + 0.5), TestLayers.vec(1, x + 0.5, 0.5, z + 1.5), 30);
            clearTransmissions();
            still("hud-altimeter-layer-1-floor");
            // The crossing is in place (ADR 0061): the HUD shakes and nothing fades, so the transmissions of the
            // crossing are what is shot.
            serverDo(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                player(server)
                        .teleportTo(one, x + 0.5, TestLayers.band(one, 1).minY() - 1, z + 0.5, Set.of(), 0, 60, true);
            });
            ClientWait.until(
                    ctx,
                    "layer 2 with no screen",
                    client -> TestLayers.clientIsIn(client, 2) && client.gui.screen() == null);

            // Then the two transmissions of the crossing, each shot once it has typed out.
            Set<Identifier> shot = new HashSet<>();
            int tail = 0;
            Identifier last = null;
            for (int tick = 0; tick < 1500 && !(shot.size() >= 2 && tail >= 12); tick++) {
                Identifier current = ctx.computeOnClient(client ->
                        TransmissionOverlay.transmission().map(Transmission::id).orElse(null));
                boolean typed = ctx.computeOnClient(client -> TransmissionOverlay.typed());
                if (typed && current != null && shot.add(current)) {
                    owner.screenshot(ctx, "hud-transmission-" + current.getPath());
                }
                tail = typed && current != null && current.equals(last) ? tail + 1 : 0;
                last = current;
                ctx.waitTick();
            }
            ClientWait.until(ctx, "the client in layer 2", client -> TestLayers.clientIsIn(client, 2));
            clearTransmissions();
        }

        private void layerTwo() {
            serverDo(server -> {
                ServerLevel two = TestLayers.level(server, 2);
                loadChunks(two, LAYER_X, LAYER_Z, 3);
            });
            hud(false);
            Vec3 cell = serverGet(server -> openCell(TestLayers.level(server, 2), 2));
            terrainViews(2, cell);

            // A pod in the dark with a scanner: the HUD at depth.
            // A sealed hall, so that no lava or gas of the cave reaches the pod.
            Vec3 hall = serverGet(server -> {
                ServerLevel two = TestLayers.level(server, 2);
                int x = LAYER_X + 120;
                int z = LAYER_Z + 120;
                LayerChain.Band band = TestLayers.band(two, 2);
                int floor = (band.minY() + band.maxY()) / 2;
                loadChunks(two, x, z, 3);
                BlockPos min = new BlockPos(x - 6, floor, z - 6);
                BlockPos max = new BlockPos(x + 6, floor + 7, z + 6);
                RoomCarver.carve(two, min, max, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                return new Vec3(x + 0.5, floor + EYE, z + 0.5);
            });
            litRooms.add(new LitRoom(
                    layerDimension(2),
                    new Box(
                            BlockPos.containing(hall).offset(-6, -1, -6),
                            BlockPos.containing(hall).offset(6, 6, 6))));
            view(2, hall, hall.add(1, 0, 0), 60);
            hud(true);
            serverDo(server -> player(server).removeEffect(MobEffects.NIGHT_VISION));
            ScannerHudTest.mountFirstPlayer(sp.getServer(), 4);
            ClientWait.until(
                    ctx,
                    "the player riding a pod",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity);
            fillPodForHud();
            ctx.waitTicks(80);
            still("hud-scanner-tier-4-layer-2");
            leavePod();
            hud(false);
            nightVision();

            // The structures of layer 2.
            for (StructureKind kind : StructureKind.inLayer(2)) {
                if (kind == StructureKind.WRECK) {
                    continue;
                }
                StructureSite site = siteOf(kind, null);
                switch (kind) {
                    case GALLERY -> {
                        view(2, at(site, -8, 2, 0), at(site, 12, 1.5, 0), 120);
                        still("structure-gallery-toward-the-rubble");
                        // The board is on the near end wall and the rubble at the far end, so stand toward the
                        // board's end,
                        // in the clear.
                        shoot(
                                "structure-gallery-quota-board",
                                2,
                                at(site, -4, 1.8, 0),
                                at(site, -12.5, 1.8, 0),
                                20,
                                new Subject("the quota board", state -> state.is(Blocks.CONCRETE.black()), 1.5));
                        shoot(
                                "structure-gallery-note-n08",
                                2,
                                at(site, -9.5, 2.2, 0.8),
                                at(site, -12, 0.8, 0),
                                20,
                                new Subject("Note N08", state -> state.getBlock() instanceof NoteBlock, 1.5));
                    }
                    case PUNCH_CLOCK -> {
                        view(2, at(site, -7, 3, -7), at(site, 2, 1, 2), 120);
                        still("structure-punch-clock-overview");
                        shoot(
                                "structure-punch-clock-shelves",
                                2,
                                at(site, 2, 1.8, -1),
                                at(site, 7.5, 1.5, 0),
                                20,
                                new Subject("the shelves", state -> state.is(Blocks.BOOKSHELF), 1.5));
                        // Down the middle of the hall, clear of the stone pillars, at the lit sea lantern above
                        // the clock.
                        shoot(
                                "structure-punch-clock-lit-clock",
                                2,
                                at(site, -1, 2.2, 0),
                                at(site, -7, 2.5, 0),
                                20,
                                new Subject("the lit sea lantern", state -> state.is(Blocks.SEA_LANTERN), 1.5));
                    }
                    case RAILS -> {
                        view(2, at(site, -30, 2, 0), at(site, 30, 1.5, 0), 120);
                        still("structure-rails-long-drift");
                        // Head on at a timber set: its two posts and the cap across.
                        shoot(
                                "structure-rails-timbering",
                                2,
                                at(site, -6, 1.7, 0),
                                at(site, 0, 1.7, 0),
                                20,
                                new Subject("a timber post", state -> state.is(Blocks.OAK_LOG), 2.5));
                    }
                    case KELLS_SHAFT -> {
                        // From the drift's lip, down the shaft.
                        view(2, at(site, -7, site.height() - 1.0, -0.5), at(site, 0, site.height() - 6.0, -0.5), 60);
                        still("structure-kells-shaft-from-the-lip");
                    }
                    default -> throw new AssertionError(kind + " has no views in the tour");
                }
            }

            // The wreck sites: Prospector's wreck, with its lamp and Note N10, and an empty bay.
            StructureSite prospector = serverGet(
                    server -> LayerStructures.prospector(TestLayers.level(server, StructureKind.WRECK.layer()))
                            .orElseThrow());
            loadSite(prospector);
            Vec3 pod = at(prospector, 0, 1.2, 0);
            view(2, at(prospector, 5, 2, 0), pod, 120);
            still("structure-wreck-prospector-0002");
            view(2, at(prospector, 0, 2, 5), pod, 20);
            still("structure-wreck-prospector-0002-side");
            view(2, at(prospector, -5, 2, 0), pod, 20);
            still("structure-wreck-prospector-0002-back");
            // The burning lamp is the lantern at (3, 2): close, from the side away from the pod, so the pod is not
            // in front
            // of it.
            shoot(
                    "structure-wreck-the-lamp",
                    2,
                    at(prospector, 5.5, 1.4, 3.8),
                    at(prospector, 3, 0.6, 2),
                    20,
                    new Subject("the burning lantern", state -> state.is(Blocks.LANTERN), 1.2));
            shoot(
                    "structure-wreck-note-n10-on-the-table",
                    2,
                    at(prospector, 1.5, 1.6, 6),
                    at(prospector, 0, 1.2, 4),
                    20,
                    new Subject("Note N10", state -> state.getBlock() instanceof NoteBlock, 1.5));
            view(2, at(prospector, 4, 6, 4), at(prospector, 0, 0, 0), 20);
            still("structure-wreck-from-above");
            serverDo(server -> player(server).removeEffect(MobEffects.NIGHT_VISION));
            view(2, at(prospector, 5, 2, 0), pod, 40);
            still("structure-wreck-prospector-0002-no-night-vision");
            nightVision();
            BlockPos far = serverGet(server ->
                            Colony.anchor(server, ColonyAnchor.CONDUIT).orElseThrow())
                    .offset(384, 0, 384);
            StructureSite bay = siteOf(StructureKind.WRECK, far);
            view(2, at(bay, 5, 2.2, 0), at(bay, 0, 0.8, 0), 120);
            still("structure-wreck-empty-bay");
        }

        /** A cell of air with room round it, close to the layer's start column, as the middle of a cave. */
        private static Vec3 openCell(ServerLevel level, int layer) {
            LayerChain.Band band = TestLayers.band(level, layer);
            BlockPos best = null;
            int bestClearance = 0;
            for (int x = LAYER_X - 40; x <= LAYER_X + 40; x += 2) {
                for (int z = LAYER_Z - 40; z <= LAYER_Z + 40; z += 2) {
                    for (int y = band.minY() + 24; y <= band.maxY() - 40; y += 2) {
                        BlockPos pos = new BlockPos(x, y, z);
                        int clearance = clearance(level, pos);
                        if (clearance > bestClearance) {
                            best = pos;
                            bestClearance = clearance;
                        }
                    }
                }
            }
            if (best == null) {
                // No cave in reach: cut a hall into the rock, sealed first, so there is a place to stand.
                int mid = (band.minY() + band.maxY()) / 2;
                BlockPos min = new BlockPos(LAYER_X - 6, mid, LAYER_Z - 6);
                BlockPos max = new BlockPos(LAYER_X + 6, mid + 6, LAYER_Z + 6);
                RoomCarver.carve(level, min, max, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                best = new BlockPos(LAYER_X, mid + 3, LAYER_Z);
            }
            return Vec3.atCenterOf(best).add(0, EYE - 0.5, 0);
        }

        private static int clearance(ServerLevel level, BlockPos pos) {
            if (!level.getBlockState(pos).isAir()) {
                return 0;
            }
            int least = 8;
            for (Direction direction : Direction.values()) {
                int free = 0;
                while (free < least
                        && level.getBlockState(pos.relative(direction, free + 1))
                                .isAir()) {
                    free++;
                }
                least = Math.min(least, free);
            }
            return least;
        }

        /** The cave: four ways round, up, down, and a view with no night vision, as it is played. */
        private void terrainViews(int layer, Vec3 cell) {
            serverDo(server -> player(server).removeEffect(MobEffects.NIGHT_VISION));
            view(layer, cell, cell.add(0, 0, 10), 120);
            still("layer-" + layer + "-cave-as-played-no-light");
            nightVision();
            String[] names = {"south", "west", "north", "east"};
            Vec3[] offsets = {new Vec3(0, 0, 10), new Vec3(-10, 0, 0), new Vec3(0, 0, -10), new Vec3(10, 0, 0)};
            for (int i = 0; i < names.length; i++) {
                view(layer, cell, cell.add(offsets[i]), 20);
                still("layer-" + layer + "-cave-" + names[i]);
            }
            view(layer, cell, cell.add(2, 10, 2), 20);
            still("layer-" + layer + "-cave-up");
            view(layer, cell, cell.add(2, -10, 2), 20);
            still("layer-" + layer + "-cave-down");
        }

        // ------------------------------------------------------------------------------------------------ layer 3

        /**
         * A cavern of each zone of layer 3, the Fungal Hollows (#972): from a floor in the zone, across the longest open run, as it is
         * played and with night vision. In {@link Stops#LAYER_THREE} each still is a frame too, since nothing else frames that run, and each
         * zone has an air still as well.
         */
        private void layerThree() {
            hud(false);
            serverDo(server -> loadChunks(TestLayers.level(server, 3), LAYER_X, LAYER_Z, HOLLOWS_CHUNKS));
            for (int zone = 0; zone < Zones.COUNT; zone++) {
                int index = zone;
                HollowsView spot = serverGet(server -> hollowsView(TestLayers.level(server, 3), index));
                String prefix = "layer-3-" + spot.zone().replace('_', '-');
                serverDo(server -> player(server).removeEffect(MobEffects.NIGHT_VISION));
                view(3, spot.eye(), spot.target(), 120);
                hollowsStill(prefix + "-as-played-no-light");
                if (stops == Stops.LAYER_THREE) {
                    airStill(prefix + "-air");
                }
                nightVision();
                view(3, spot.eye(), spot.target(), 20);
                hollowsStill(prefix);
            }
        }

        /**
         * The view as played once the zone's ambient particles have had {@value #HOLLOWS_AIR_TICKS} ticks to fill it: the Spore Fields'
         * haze (#972). Settle clears the particles before every still, so the other stills never show them. Only the layer 3 stops shoot
         * it, so the whole tour's stills stay as they were.
         */
        private void airStill(String stillName) {
            settle(stillName);
            // tick-wait: the ambient particles appear a few a tick round the camera; nothing reports that the view
            // is full
            ctx.waitTicks(HOLLOWS_AIR_TICKS);
            owner.screenshot(ctx, stillName);
            owner.frame(ctx);
        }

        private void hollowsStill(String stillName) {
            still(stillName);
            if (stops == Stops.LAYER_THREE) {
                owner.frame(ctx);
            }
        }

        /**
         * The view of zone {@code zone} of layer 3: from the floor cell near the layer's start column with the longest run of open cells
         * along an axis at eye height, looking along it and a little down. Throws, naming the zone, when the zone has no floor in reach.
         */
        private static HollowsView hollowsView(ServerLevel level, int zone) {
            LayerChain.Band band = TestLayers.band(level, 3);
            Zones.Span span = Zones.span(band.minY(), band.height(), zone);
            String name = Zones.of(3, band.minY(), band.height(), span.low())
                    .orElseThrow()
                    .id()
                    .getPath();
            BlockPos best = null;
            Direction bestDirection = Direction.SOUTH;
            int bestRun = 0;
            int reach = HOLLOWS_CHUNKS * 16 - HOLLOWS_RUN;
            for (int x = LAYER_X - reach; x <= LAYER_X + reach; x += 4) {
                for (int z = LAYER_Z - reach; z <= LAYER_Z + reach; z += 4) {
                    // The floor rows leave room for the eye and the head over it inside the zone.
                    for (int y = span.low() + 1; y <= span.high() - 3; y++) {
                        BlockPos feet = new BlockPos(x, y, z);
                        if (!passable(level, feet)
                                || !passable(level, feet.above())
                                || !level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)) {
                            continue;
                        }
                        for (Direction direction : Direction.Plane.HORIZONTAL) {
                            int run = 0;
                            while (run < HOLLOWS_RUN
                                    && passable(level, feet.above().relative(direction, run + 1))) {
                                run++;
                            }
                            if (run > bestRun) {
                                best = feet;
                                bestDirection = direction;
                                bestRun = run;
                            }
                        }
                    }
                }
            }
            if (best == null) {
                throw new AssertionError("layer 3 has no floor in zone " + name + " within " + reach + " blocks of "
                        + LAYER_X + ", " + LAYER_Z);
            }
            Vec3 eye = Vec3.atBottomCenterOf(best).add(0, EYE, 0);
            Vec3 ahead = Vec3.atLowerCornerOf(bestDirection.getUnitVec3i()).scale(HOLLOWS_LOOK);
            return new HollowsView(name, eye, eye.add(ahead).add(0, -HOLLOWS_LOOK * HOLLOWS_DROP, 0));
        }

        /** Nothing to collide with: air, a glowcap, a stalk's cap the camera passes through. */
        private static boolean passable(ServerLevel level, BlockPos pos) {
            return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
        }

        // ------------------------------------------------------------------------------------------------ structures

        private StructureSite siteOf(StructureKind kind, @Nullable BlockPos near) {
            StructureSite site = serverGet(server -> {
                ServerLevel level = TestLayers.level(server, kind.layer());
                LayerChain.Band levelBand = TestLayers.band(level, kind.layer());
                BlockPos conduit = Colony.anchor(server, ColonyAnchor.CONDUIT).orElseThrow();
                return LayerStructures.nearest(
                        level.getSeed(),
                        kind,
                        levelBand.minY(),
                        levelBand.height(),
                        near == null ? conduit : near,
                        conduit);
            });
            loadSite(site);
            return site;
        }

        private void loadSite(StructureSite site) {
            serverDo(server -> {
                ServerLevel level = TestLayers.level(server, site.kind().layer());
                BoundingBox box = site.bounds();
                for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
                    for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                        level.getChunk(cx, cz, ChunkStatus.FULL);
                    }
                }
            });
        }

        // ------------------------------------------------------------------------------------------------
        // transmissions

        private void remainingTransmissions() {
            hud(true);
            for (String id : List.of("t02", "surface_arrival")) {
                Identifier transmission = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, id);
                settle("hud-transmission-" + id);
                serverDo(server -> Transmissions.fire(server, charter.get(), transmission));
                boolean typed = false;
                for (int tick = 0; tick < 1200 && !typed; tick++) {
                    typed = ctx.computeOnClient(client -> TransmissionOverlay.typed());
                    ctx.waitTick();
                }
                ctx.waitTicks(10);
                owner.screenshot(ctx, "hud-transmission-" + id);
                clearTransmissions();
            }
        }

        private void clearTransmissions() {
            for (int tick = 0;
                    tick < 1500
                            && ctx.computeOnClient(
                                    client -> TransmissionOverlay.transmission().isPresent());
                    tick += 4) {
                ctx.waitTicks(4);
            }
        }

        // ------------------------------------------------------------------------------------------------ helpers

        /** Puts the camera at {@code eye} in {@code layer}, looking at {@code target}, then waits {@code wait} ticks for chunks and light. */
        private void view(int layer, Vec3 eye, Vec3 target, int wait) {
            Vec3 d = target.subtract(eye);
            float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            float pitch = (float) Math.toDegrees(Math.atan2(-d.y, Math.hypot(d.x, d.z)));
            serverDo(server -> {
                ServerPlayer player = player(server);
                player.setNoGravity(true);
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                player.teleportTo(
                        TestLayers.level(server, layer), eye.x, eye.y - EYE, eye.z, Set.of(), yaw, pitch, true);
            });
            // Arrived exactly, looking exactly: a camera still a block short, or a turn short, makes a
            // different picture.
            ClientWait.until(
                    ctx,
                    "the camera at the eye in layer " + layer + ", facing yaw " + yaw + " and pitch " + pitch,
                    client -> TestLayers.clientIsIn(client, layer)
                            && ClientSession.player(client).distanceToSqr(eye.x, eye.y - EYE, eye.z) < 0.0001
                            && Math.abs(Mth.wrapDegrees(
                                            ClientSession.player(client).getYRot() - yaw))
                                    < 0.01f
                            && Math.abs(ClientSession.player(client).getXRot() - pitch) < 0.01f,
                    client -> "the player at " + ClientSession.player(client).position() + " in "
                            + ClientLight.clientLevel(client).dimension().identifier() + " facing yaw "
                            + ClientSession.player(client).getYRot()
                            + " and pitch " + ClientSession.player(client).getXRot());
            ctx.waitTicks(wait);
        }

        /**
         * Waits, on a wall-clock limit, until the lights part of {@code pod} holds its light block in the pod's column. The pod puts it
         * there on a tick of its own, and takes the one of a pod that stood in the same place before, so it is not there at once.
         */
        private void awaitLight(PodEntity pod) {
            long deadline = System.nanoTime() + SETTLE_LIMIT_NANOS;
            while (!serverGet(server -> {
                ServerLevel level = TestLayers.level(server, 0);
                return BlockPos.betweenClosedStream(
                                BlockPos.containing(pod.getX(), pod.getBoundingBox().minY, pod.getZ()),
                                BlockPos.containing(pod.getX(), pod.getBoundingBox().maxY, pod.getZ()))
                        .anyMatch(pos -> level.getBlockState(pos).is(Blocks.LIGHT));
            })) {
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("the lights part of " + pod + " never placed its light");
                }
                ctx.waitTicks(SETTLE_POLL_TICKS);
            }
        }

        /** A named still, with no toast, chat line or transmission over it. */
        private void still(String stillName) {
            ctx.runOnClient(client -> {
                client.gui.toastManager().clear();
                client.gui.hud.getChat().clearMessages(false);
                // The vanilla "Press Left Shift to dismount" hint fades by its own timer, so it is never in a still.
                client.gui.hud.setOverlayMessage(Component.empty(), false);
            });
            clearTransmissions();
            settle(stillName);
            owner.screenshot(ctx, stillName);
        }

        private void nightVision() {
            serverDo(server -> player(server)
                    .addEffect(new MobEffectInstance(
                            MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false)));
        }

        /** Loads the chunks within {@code radius} chunks of the one holding block column ({@code x}, {@code z}). */
        private static void loadChunks(ServerLevel level, int x, int z, int radius) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    level.getChunk((x >> 4) + dx, (z >> 4) + dz);
                }
            }
        }

        private static ServerPlayer player(MinecraftServer server) {
            return server.getPlayerList().getPlayers().getFirst();
        }

        private static void command(MinecraftServer server, String command) {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
        }

        private <T extends @Nullable Object> T serverGet(Function<MinecraftServer, T> action) {
            return sp.getServer().computeOnServer(action::apply);
        }

        private ResourceKey<Level> layerDimension(int layer) {
            return serverGet(server -> TestLayers.dimension(server, layer));
        }

        private void serverDo(Consumer<MinecraftServer> action) {
            sp.getServer().runOnServer(action::accept);
        }
    }
}
