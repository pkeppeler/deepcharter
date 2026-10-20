package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.client.pod.CockpitHud;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.EvidenceWorld;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

/**
 * Evidence scenario "cockpit-ribbon" for #247: the Ribbon, the cockpit HUD the user picked, from the seat of a Mole with a scanner, in sealed stone rooms (never the void). At GUI
 * scale 2 it shoots calm, low fuel, hull burning (lava under the pod), stranded and burning together, a game message on the action bar, and deep (the last layer's floor); calm
 * at GUI scale 1 and at GUI scale 4 (on a 1920 x 1080 window, the least window that makes a GUI scale of 4); and once on foot beside the pod, where the vanilla HUD is back. A
 * short drive down a long room is the GIF. Stills are named {@code ribbon-<state>}.
 */
public class CockpitRibbonScenario extends EvidenceScenario {
    private static final int X = 5000;
    private static final int Z = 5000;
    /** Near the top of layer 1, which is 192 tall, and near the floor of the last layer: the two ends of the depth gauge. */
    private static final int UPPER_FLOOR_BELOW_TOP = 42;

    private static final int DEEP_FLOOR_ABOVE_MIN = 20;
    private static final int ROOM_RADIUS = 7;
    private static final int ROOM_HEIGHT = 8;
    /** The drive room is long, east to west, so the pod has road for the GIF. */
    private static final int DRIVE_LENGTH = 30;

    private static final int LAVA_RADIUS = 2;
    private static final int GUI_SCALE = 2;
    private static final int SETTLE_TICKS = 25;
    private static final int COOL_TICKS = 70;
    private static final int TRANSMISSION_TICKS = 60;
    private static final int DRIVE_FRAMES = 40;
    private static final int TICKS_PER_FRAME = 3;
    private static final double ON_FOOT_BACK = 4.5;
    private static final float EYE_PITCH = 4f;
    private static final float FACING_WEST = 90f;
    private static final float CALM_FUEL = 78f;
    private static final float LOW_FUEL = 12f;
    private static final float DEEP_FUEL = 55f;
    private static final int BIG_WINDOW_WIDTH = 1920;
    private static final int BIG_WINDOW_HEIGHT = 1080;
    private static final int WINDOW_SETTLE_TICKS = 20;

    @Override
    protected String name() {
        return "cockpit-ribbon";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            EvidenceWorld.pin(context, singleplayer);
            context.runOnClient(client -> {
                client.options.guiScale().set(GUI_SCALE);
                client.options.setCameraType(CameraType.FIRST_PERSON);
            });
            EvidencePins.lightFlickerOff = true;
            EvidencePins.handsHidden = true;
            try {
                Room upper = singleplayer.getServer().computeOnServer(server -> {
                    var type = server.registryAccess()
                            .lookupOrThrow(Registries.DIMENSION_TYPE)
                            .getValueOrThrow(LayerChain.type(1));
                    return new Room(LayerChain.dimension(1), type.minY() + type.height() - UPPER_FLOOR_BELOW_TOP);
                });
                Room deep = singleplayer.getServer().computeOnServer(server -> {
                    int last = LayerChain.count(server.registryAccess());
                    int minY = server.registryAccess()
                            .lookupOrThrow(Registries.DIMENSION_TYPE)
                            .getValueOrThrow(LayerChain.type(last))
                            .minY();
                    return new Room(LayerChain.dimension(last), minY + DEEP_FLOOR_ABOVE_MIN);
                });
                singleplayer.getServer().runOnServer(server -> {
                    buildRoom(server, upper, ROOM_RADIUS, ROOM_RADIUS);
                    buildRoom(server, deep, ROOM_RADIUS, ROOM_RADIUS);
                });
                PodEntity upperPod = enter(context, singleplayer, upper);
                upperStates(context, singleplayer, upperPod);
                guiScales(context, singleplayer, upperPod);
                leave(context, singleplayer, upperPod);
                drive(context, singleplayer, upper);
                PodEntity deepPod = enter(context, singleplayer, deep);
                deepStates(context, singleplayer, deepPod, deep);
            } finally {
                EvidencePins.reset();
            }
        }
    }

    /** A sealed stone room in a layer: where it is and the Y of its floor. */
    private record Room(ResourceKey<Level> dimension, int floorY) {}

    /** Calm, low fuel, hull burning with lava under the pod, stranded and burning together, and a message on the action bar. */
    private void upperStates(ClientGameTestContext context, TestSingleplayerContext singleplayer, PodEntity pod) {
        singleplayer.getServer().runOnServer(server -> {
            pod.setHull(pod.maxHull());
            pod.setFuel(CALM_FUEL);
        });
        shoot(context, "ribbon-calm");
        singleplayer.getServer().runOnServer(server -> pod.setFuel(LOW_FUEL));
        shoot(context, "ribbon-low-fuel");
        singleplayer.getServer().runOnServer(server -> {
            pod.setFuel(CALM_FUEL);
            lava(pod, true);
        });
        ClientWait.until(
                context,
                "the pod's hull burning on the client",
                client -> ClientSession.player(client).getVehicle() instanceof PodEntity shown && shown.hullBurning());
        shoot(context, "ribbon-burning");
        singleplayer.getServer().runOnServer(server -> {
            pod.setFuel(0f);
            pod.setStranded(true);
        });
        ClientWait.until(
                context,
                "the pod stranded on the client",
                client -> ClientSession.player(client).getVehicle() instanceof PodEntity shown && shown.stranded());
        shoot(context, "ribbon-burning-stranded");
        singleplayer.getServer().runOnServer(server -> {
            lava(pod, false);
            pod.setStranded(false);
            pod.setFuel(CALM_FUEL);
        });
        ClientWait.until(
                context,
                "the pod's hull cool on the client",
                client -> ClientSession.player(client).getVehicle() instanceof PodEntity shown
                        && !shown.hullBurning()
                        && !shown.stranded());
        context.waitTicks(COOL_TICKS); // tick-wait: the heat gauge eases back to 0 over client ticks
        context.runOnClient(client ->
                client.gui.hud.setOverlayMessage(Component.translatable("creature.deepcharter.bitten"), false));
        shoot(context, "ribbon-message");
    }

    /** Calm at GUI scale 1, then at GUI scale 4 on a window big enough to make one; the window and the scale are put back. */
    private void guiScales(ClientGameTestContext context, TestSingleplayerContext singleplayer, PodEntity pod) {
        singleplayer.getServer().runOnServer(server -> pod.setFuel(CALM_FUEL));
        context.runOnClient(client -> client.getWindow().setGuiScale(1));
        shoot(context, "ribbon-calm-gui1");
        context.runOnClient(client -> client.getWindow().setWindowed(BIG_WINDOW_WIDTH, BIG_WINDOW_HEIGHT));
        context.waitTicks(WINDOW_SETTLE_TICKS); // tick-wait: the window resizes over a few frames
        try {
            context.runOnClient(
                    client -> client.getWindow().setGuiScale(client.getWindow().calculateScale(4, false)));
            shoot(context, "ribbon-calm-gui4", false);
        } finally {
            context.runOnClient(client -> client.getWindow().setWindowed(854, 480));
            context.waitTicks(WINDOW_SETTLE_TICKS); // tick-wait: the window resizes back
            context.runOnClient(client -> client.getWindow().setGuiScale(GUI_SCALE));
        }
    }

    /** A short drive west down a long room: the GIF. */
    private void drive(ClientGameTestContext context, TestSingleplayerContext singleplayer, Room room) {
        singleplayer.getServer().runOnServer(server -> buildRoom(server, room, ROOM_RADIUS, DRIVE_LENGTH));
        PodEntity pod = enter(context, singleplayer, room, DRIVE_LENGTH - 2, FACING_WEST);
        singleplayer.getServer().runOnServer(server -> pod.setFuel(CALM_FUEL));
        context.runOnClient(client -> {
            ClientSession.player(client).setXRot(EYE_PITCH);
            client.gui.toastManager().clear();
            TransmissionOverlay.reset();
        });
        context.getInput().holdKey(options -> options.keyUp);
        try {
            for (int i = 0; i < DRIVE_FRAMES; i++) {
                frame(context);
                context.waitTicks(TICKS_PER_FRAME);
            }
        } finally {
            context.getInput().releaseKey(options -> options.keyUp);
        }
        screenshot(context, "ribbon-drive");
        leave(context, singleplayer, pod);
    }

    /** The deepest floor, then the pilot on foot beside the pod. */
    private void deepStates(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, PodEntity pod, Room room) {
        singleplayer.getServer().runOnServer(server -> {
            pod.setHull(pod.maxHull());
            pod.setFuel(DEEP_FUEL);
        });
        shoot(context, "ribbon-deep");
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.stopRiding();
            player.teleportTo(
                    ClientLight.serverLevel(server, room.dimension()),
                    X + 0.5,
                    room.floorY(),
                    Z + 0.5 - ON_FOOT_BACK,
                    Set.of(),
                    0f,
                    0f,
                    true);
        });
        ClientWait.until(
                context,
                "the pilot on foot",
                client -> ClientSession.player(client).getVehicle() == null);
        ClientWait.until(
                context,
                "the cockpit gone on foot",
                client -> CockpitHud.seated(client).isEmpty());
        shoot(context, "ribbon-on-foot");
    }

    private PodEntity enter(ClientGameTestContext context, TestSingleplayerContext singleplayer, Room room) {
        return enter(context, singleplayer, room, 0, 0f);
    }

    /** Takes the first player into the room, {@code east} blocks east of its middle and facing {@code yaw}, boards a new pod with a tier 1 scanner, and waits until the client sits in it. */
    private PodEntity enter(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, Room room, int east, float yaw) {
        PodEntity[] made = {null};
        singleplayer.getServer().runOnServer(server -> {
            ServerLevel level = ClientLight.serverLevel(server, room.dimension());
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.setGameMode(GameType.SURVIVAL);
            player.teleportTo(level, X + east + 0.5, room.floorY(), Z + 0.5, Set.of(), yaw, 0f, true);
            PodEntity pod = TestEntities.create(PodRegistry.POD, level);
            pod.setPos(X + east + 0.5, room.floorY(), Z + 0.5);
            pod.setYRot(yaw);
            level.addFreshEntity(pod);
            ScannerPods.fit(server, player, pod, 1);
            if (!player.startRiding(pod)) {
                throw new AssertionError("the pilot could not board the pod");
            }
            made[0] = pod;
        });
        ClientWait.until(
                context,
                "the pilot seated in the pod on the client",
                client -> client.player != null
                        && client.player.getVehicle() instanceof PodEntity
                        && ClientLight.clientLevel(client).dimension().equals(room.dimension()));
        context.waitTicks(
                TRANSMISSION_TICKS); // tick-wait: a charter's first transmission arrives a moment after the first
        // mount, and every shot clears it
        return made[0];
    }

    private void leave(ClientGameTestContext context, TestSingleplayerContext singleplayer, PodEntity pod) {
        singleplayer.getServer().runOnServer(server -> {
            server.getPlayerList().getPlayers().getFirst().stopRiding();
            pod.discard();
        });
        ClientWait.until(
                context,
                "the pilot out of the pod",
                client -> ClientSession.player(client).getVehicle() == null);
    }

    /** Lava, or air again, in a square under the pod, so the hull burns while it sits there. */
    private static void lava(PodEntity pod, boolean on) {
        ServerLevel level = (ServerLevel) pod.level();
        BlockPos feet = pod.blockPosition();
        RoomCarver.carve(
                level,
                feet.offset(-LAVA_RADIUS, 0, -LAVA_RADIUS),
                feet.offset(LAVA_RADIUS, 0, LAVA_RADIUS),
                (on ? Blocks.LAVA : Blocks.AIR).defaultBlockState());
    }

    /** A sealed room: a shell of stone, air inside, and glowstone so the pod and the walls are lit. {@code length} is how far it reaches east and west of its middle. */
    private static void buildRoom(MinecraftServer server, Room room, int radius, int length) {
        ServerLevel level = ClientLight.serverLevel(server, room.dimension());
        int floor = room.floorY();
        RoomCarver.carve(
                level,
                new BlockPos(X - length - 1, floor - 1, Z - radius - 1),
                new BlockPos(X + length + 1, floor + ROOM_HEIGHT + 1, Z + radius + 1),
                Blocks.STONE.defaultBlockState());
        RoomCarver.carve(
                level,
                new BlockPos(X - length, floor, Z - radius),
                new BlockPos(X + length, floor + ROOM_HEIGHT, Z + radius),
                Blocks.AIR.defaultBlockState());
        for (int x = -length; x <= length; x += ROOM_RADIUS) {
            for (int z : new int[] {-radius, radius}) {
                level.setBlock(new BlockPos(X + x, floor + 4, Z + z), Blocks.GLOWSTONE.defaultBlockState(), 3);
            }
        }
    }

    private void shoot(ClientGameTestContext context, String still) {
        shoot(context, still, true);
    }

    /** Takes a still, and a frame of the GIF too when {@code asFrame} (both must be the default window's size). */
    private void shoot(ClientGameTestContext context, String still, boolean asFrame) {
        context.runOnClient(client -> {
            ClientSession.player(client).setYRot(ClientSession.player(client).getYRot());
            ClientSession.player(client).setXRot(EYE_PITCH);
            ClientSession.player(client).xRotO = EYE_PITCH;
            client.gui.toastManager().clear();
            TransmissionOverlay.reset();
        });
        ClientWait.until(
                context, "every section in view rendered", client -> client.levelRenderer.hasRenderedAllSections());
        context.waitTicks(
                SETTLE_TICKS); // tick-wait: the scanner rescans, the gauges ease and the pod settles over client ticks
        if (asFrame) {
            screenshot(context, still);
            frame(context);
        } else {
            // A still of another window size: the scenario's own screenshot refuses it, so it goes to the client's
            // screenshot folder, build/run/clientGameTest/screenshots.
            context.takeScreenshot(still);
        }
    }
}
