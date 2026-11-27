package io.github.pkeppeler.deepcharter.test.evidence;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.client.pod.CockpitHud;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.fuel.EngineSoot;
import io.github.pkeppeler.deepcharter.fuel.FuelTuning;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLining;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.repair.RepairTuning;
import io.github.pkeppeler.deepcharter.test.support.ChargeUse;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.EvidenceWorld;
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Evidence scenario "seated-hotbar" for #1457: a pilot in a Mole with a scanner, in a lit stone chamber in layer 1, with the repair station's consumables in the hotbar.
 * The still {@code seated-hotbar-calm} is the cockpit with the hotbar; the GIF is the 4 key picking the dynamite (its name shows over the hotbar, the still
 * {@code seated-hotbar-picked}), rock packed round the pod as a blocked shaft ({@code seated-hotbar-blocked}), and the use key blasting it from the seat
 * ({@code seated-hotbar-blasted}, one stick of two spent). Last, the still {@code seated-hotbar-long-plate-narrow} (not a GIF frame: a 640 x 480 window at GUI scale 2, a
 * 320 x 240 GUI, where the hotbar reaches under the plate's columns) shows a pod with many lines on its plate, which stops a row above the hotbar.
 */
public class SeatedHotbarScenario extends EvidenceScenario {
    private static final int X = 6200;
    private static final int Z = 6200;
    private static final int FLOOR_Y = LAYER_ROOM_Y;
    /** The pod faces north, at the wall the lamps are on. */
    private static final float FACING_NORTH = 180f;

    private static final float EYE_PITCH = 4f;
    private static final int GUI_SCALE = 2;
    private static final int DYNAMITE_SLOT = 3;
    private static final int TRANSMISSION_TICKS = 60;
    private static final int SETTLE_TICKS = 25;
    private static final int IDLE_FRAMES = 12;
    private static final int PICK_FRAMES = 24;
    private static final int BLAST_FRAMES = 30;
    /** The narrowest window the Ribbon fits at GUI scale 2: a 320 x 240 GUI. */
    private static final int NARROW_WINDOW_WIDTH = 640;

    private static final int NARROW_WINDOW_HEIGHT = 480;
    private static final int WINDOW_SETTLE_TICKS = 20;
    private static final int LAVA_RADIUS = 2;

    @Override
    protected String name() {
        return "seated-hotbar";
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
                board(context, singleplayer);
                shoot(context, "seated-hotbar-calm");
                tickFrames(context, IDLE_FRAMES);
                RealInput.key(context, InputConstants.KEY_4, 0);
                ClientWait.until(
                        context,
                        "the dynamite picked",
                        client -> ClientSession.player(client).getInventory().getSelectedSlot() == DYNAMITE_SLOT);
                tickFrames(context, PICK_FRAMES / 2);
                screenshot(context, "seated-hotbar-picked");
                tickFrames(context, PICK_FRAMES / 2);
                pack(singleplayer);
                shoot(context, "seated-hotbar-blocked");
                tickFrames(context, IDLE_FRAMES);
                context.getInput().pressKey(options -> options.keyUse);
                tickFrames(context, BLAST_FRAMES);
                screenshot(context, "seated-hotbar-blasted");
                longPlateOnANarrowGui(context, singleplayer);
            } finally {
                EvidencePins.reset();
            }
        }
    }

    /** The chamber, the consumables in the hotbar with the first slot picked, and the pilot in a new pod with a tier 1 scanner, facing the lamps. */
    private void board(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> {
            ServerLevel one = TestLayers.level(server, 1);
            stoneChamber(
                    one,
                    X,
                    Z,
                    FLOOR_Y,
                    new BlockPos(X - 3, FLOOR_Y + 4, Z - 5),
                    new BlockPos(X + 3, FLOOR_Y + 4, Z - 5),
                    new BlockPos(X - 5, FLOOR_Y + 4, Z),
                    new BlockPos(X + 5, FLOOR_Y + 4, Z));
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.setGameMode(GameType.SURVIVAL);
            Consumable[] shelf = {
                Consumable.RESERVE_FUEL_TANK,
                Consumable.HULL_NANOBOTS,
                Consumable.BLASTING_CHARGE,
                Consumable.DYNAMITE,
                Consumable.PLASTIC_EXPLOSIVES
            };
            for (int slot = 0; slot < shelf.length; slot++) {
                player.getInventory()
                        .setItem(
                                slot,
                                new ItemStack(
                                        RepairRegistry.item(shelf[slot]), shelf[slot] == Consumable.DYNAMITE ? 2 : 1));
            }
            player.getInventory().setSelectedSlot(0);
            player.teleportTo(one, X + 0.5, FLOOR_Y, Z + 0.5, Set.of(), FACING_NORTH, 0f, true);
            PodEntity pod = TestEntities.create(PodRegistry.POD, one);
            pod.setPos(X + 0.5, FLOOR_Y, Z + 0.5);
            pod.setYRot(FACING_NORTH);
            one.addFreshEntity(pod);
            ScannerPods.fit(server, player, pod, 1);
            if (!player.startRiding(pod)) {
                throw new AssertionError("the pilot could not board the pod");
            }
        });
        ClientWait.until(
                context,
                "the pilot seated under the cockpit in layer 1",
                client -> client.player != null
                        && client.player.getVehicle() instanceof PodEntity
                        && TestLayers.clientIsIn(client, 1)
                        && CockpitHud.seated(client).isPresent());
        context.waitTicks(
                TRANSMISSION_TICKS); // tick-wait: a charter's first transmission arrives a moment after the first
        // mount, and every shot clears it
    }

    /** Rock round the pod, every cell the dynamite reaches but the one the pilot's eyes are in: a shaft caved in on it. */
    private static void pack(TestSingleplayerContext singleplayer) {
        int radius = RepairTuning.DEFAULT.dynamiteRadius();
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            if (!(player.getVehicle() instanceof PodEntity pod)) {
                throw new AssertionError("the pilot left the pod");
            }
            ChargeUse.packRound(player, pod, radius);
        });
    }

    /**
     * A pod in a bad way, so its plate has more lines than a 320 x 240 GUI has room for above the hotbar: limp with soot, stranded,
     * burning in lava, out of lining brick, with a spoil hopper and a liner. The still is taken on a 640 x 480 window at GUI scale 2, and the window is put back.
     */
    private static void longPlateOnANarrowGui(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            if (!(player.getVehicle() instanceof PodEntity pod)) {
                throw new AssertionError("the pilot left the pod");
            }
            ScannerPods.fit(server, player, pod, ComponentTrack.SPOIL_HOPPER, 1);
            ScannerPods.fit(server, player, pod, ComponentTrack.LINER, 1);
            pod.setAttached(
                    EngineSoot.STATE,
                    Versioned.of(new EngineSoot.State(FuelTuning.DEFAULT.soot().limpLitres(), 0f, 0f)));
            pod.setAttached(PodLining.STATE, Versioned.of(new PodLining.State(3, 6, 0, false, true)));
            pod.setStranded(true);
            BlockPos feet = pod.blockPosition();
            RoomCarver.carve(
                    (ServerLevel) pod.level(),
                    feet.offset(-LAVA_RADIUS, 0, -LAVA_RADIUS),
                    feet.offset(LAVA_RADIUS, 0, LAVA_RADIUS),
                    Blocks.LAVA.defaultBlockState());
        });
        ClientWait.until(
                context,
                "the pod burning and stranded on the client",
                client -> ClientSession.player(client).getVehicle() instanceof PodEntity shown
                        && shown.hullBurning()
                        && shown.stranded());
        context.runOnClient(client -> client.getWindow().setWindowed(NARROW_WINDOW_WIDTH, NARROW_WINDOW_HEIGHT));
        context.waitTicks(WINDOW_SETTLE_TICKS); // tick-wait: the window resizes over a few frames
        try {
            context.runOnClient(client -> client.getWindow().setGuiScale(GUI_SCALE));
            settle(context);
            // A still of another window size: the scenario's own screenshot refuses it, so it goes to the client's
            // screenshot folder, build/run/clientGameTest/screenshots.
            context.takeScreenshot("seated-hotbar-long-plate-narrow");
        } finally {
            context.runOnClient(client -> client.getWindow().setWindowed(854, 480));
            context.waitTicks(WINDOW_SETTLE_TICKS); // tick-wait: the window resizes back
            context.runOnClient(client -> client.getWindow().setGuiScale(GUI_SCALE));
        }
    }

    /** A still and a frame of the GIF, once the view {@link #settle}s. */
    private void shoot(ClientGameTestContext context, String still) {
        settle(context);
        screenshot(context, still);
        frame(context);
    }

    /** The view level and the popups cleared, then a wait until the view has rendered and the gauges have eased. */
    private static void settle(ClientGameTestContext context) {
        context.runOnClient(client -> {
            ClientSession.player(client).setXRot(EYE_PITCH);
            ClientSession.player(client).xRotO = EYE_PITCH;
            client.gui.toastManager().clear();
            TransmissionOverlay.reset();
        });
        ClientWait.until(
                context, "every section in view rendered", client -> client.levelRenderer.hasRenderedAllSections());
        context.waitTicks(SETTLE_TICKS); // tick-wait: the scanner rescans, the gauges ease and the pod settles
    }
}
