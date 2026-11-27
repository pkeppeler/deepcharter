package io.github.pkeppeler.deepcharter.test;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.pod.CockpitHud;
import io.github.pkeppeler.deepcharter.client.pod.SeatedHotbar;
import io.github.pkeppeler.deepcharter.client.ui.HudKeepClear;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodFootprint;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.repair.RepairTuning;
import io.github.pkeppeler.deepcharter.test.support.ChargeUse;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidencePins;
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Client GameTest for the hotbar under the cockpit (#1457): a seated pilot sees vanilla's hotbar and its selection frame (read from pixels: the frame moves to the slot a
 * key picks, and a slot nothing touched stays as it was), picks a slot with a number key and with the mouse wheel through the window's own input handlers, and uses dynamite
 * from the picked slot with the use key, which blasts the rock round the pod and spends the stick. The hotbar and the held item's name are listed for popups to keep clear
 * of while seated and not on foot, and the name is drawn inside the box listed for it. That the hotbar covers no part of the cockpit is {@code CockpitHudClientTest}'s
 * Ribbon-fit sweep, over every window, GUI scale and plate case.
 */
public class SeatedHotbarClientTest implements FabricClientGameTest {
    private static final int FEET_Y = TestLayers.worldY(1, 150);
    private static final int ROOM_RADIUS = 8;
    private static final int ROOM_HEIGHT = 12;
    private static final int GUI_SCALE = 2;
    /** Where the test puts the dynamite: the fourth slot, which the 4 key picks. */
    private static final int DYNAMITE_SLOT = 3;
    /** A slot nothing in the test picks or fills: its pixels are the control. */
    private static final int UNTOUCHED_SLOT = 7;
    /** A slot's selection frame, 24 x 23 GUI pixels, one pixel left of and over the slot's 20 pixel cell. */
    private static final int FRAME_WIDTH = 24;

    private static final int FRAME_HEIGHT = 23;
    private static final int CELL = 20;
    /** How much of a frame's box must differ when the frame moves onto it, and how much may differ in a slot it never touched (the world behind the slots' glass). */
    private static final double MOVED = 0.10;

    private static final double STILL = 0.03;
    /** How much of the held item name's listed box its text and shadow must change when vanilla draws it there. */
    private static final double NAME_DRAWN = 0.10;
    /**
     * The strip over the name's box, in GUI pixels, and how much of it may change: a name drawn higher than its box shows there (a box listed 3 pixels too low, past
     * its 2 pixel backdrop, changes 3 % of it; 0 measured with the box where it is), and nothing else in it moves between the two shots of one moment.
     */
    private static final int ABOVE_NAME = 4;

    private static final double ABOVE_STILL = 0.01;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40); // tick-wait: the new world's first ticks, as the cockpit test waits
            context.runOnClient(client -> client.options.guiScale().set(GUI_SCALE));
            EvidencePins.lightFlickerOff = true;
            EvidencePins.handsHidden = true;
            try {
                singleplayer.getServer().runOnServer(server -> {
                    ServerLevel one = TestLayers.level(server, 1);
                    ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                    player.setGameMode(GameType.SURVIVAL);
                    player.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 8));
                    player.getInventory()
                            .setItem(DYNAMITE_SLOT, new ItemStack(RepairRegistry.item(Consumable.DYNAMITE)));
                    player.getInventory().setSelectedSlot(0);
                    RoomCarver.carve(
                            one,
                            -ROOM_RADIUS,
                            ROOM_RADIUS,
                            FEET_Y - 1,
                            FEET_Y + ROOM_HEIGHT,
                            -ROOM_RADIUS,
                            ROOM_RADIUS,
                            Blocks.AIR);
                });
                ClientWait.lightSettled(context, singleplayer.getServer(), 1, 0, 0, ROOM_RADIUS);
                singleplayer.getServer().runOnServer(server -> {
                    ServerLevel one = TestLayers.level(server, 1);
                    ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                    player.teleportTo(one, 0.5, FEET_Y, 0.5, Set.of(), 0f, 0f, true);
                    PodEntity pod = TestEntities.create(PodRegistry.POD, one);
                    pod.setPos(0.5, FEET_Y, 0.5);
                    one.addFreshEntity(pod);
                    ScannerPods.fit(server, player, pod, 1);
                    if (!player.startRiding(pod)) {
                        throw new AssertionError("the player could not mount the pod");
                    }
                });
                ClientWait.until(
                        context,
                        "the pilot seated under the cockpit",
                        client -> client.player != null
                                && client.player.getVehicle() instanceof PodEntity
                                && CockpitHud.seated(client).isPresent());
                expectTheHotbarIsListedOnlyWhileSeated(context, singleplayer);
                expectTheHotbarDrawsAndANumberKeyPicksASlot(context, singleplayer);
                expectTheMouseWheelPicksASlot(context);
                expectDynamiteFromThePickedSlotBlasts(context, singleplayer);
            } finally {
                EvidencePins.reset();
            }
        }
    }

    /** The keep-clear list has the hotbar and the held item's name while seated, where vanilla draws them, and neither on foot. */
    private static void expectTheHotbarIsListedOnlyWhileSeated(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        List<String> seated = context.computeOnClient(client -> names(client));
        if (!seated.contains("hotbar") || !seated.contains("held item name")) {
            throw new AssertionError("Seated, the hotbar and the held item's name are kept clear of, got " + seated);
        }
        HudKeepClear.Rect bar = context.computeOnClient(client -> SeatedHotbar.bar(
                client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight()));
        if (!bar.equals(new HudKeepClear.Rect("hotbar", 427 / 2 - 92, 240 - 23, 184, 23))) {
            throw new AssertionError(
                    "The hotbar is vanilla's 182 x 22 at the bottom centre, with the frame's pixel round it: " + bar);
        }
        singleplayer
                .getServer()
                .runOnServer(
                        server -> server.getPlayerList().getPlayers().getFirst().stopRiding());
        ClientWait.until(
                context,
                "the pilot on foot",
                client -> ClientSession.player(client).getVehicle() == null);
        List<String> onFoot = context.computeOnClient(client -> names(client));
        if (onFoot.contains("hotbar") || onFoot.contains("held item name")) {
            throw new AssertionError("On foot the vanilla HUD is not on the keep-clear list, got " + onFoot);
        }
        board(context, singleplayer);
    }

    private static List<String> names(Minecraft client) {
        return listed(client).stream().map(HudKeepClear.Rect::what).toList();
    }

    /** The keep-clear list on the client's current GUI. */
    private static List<HudKeepClear.Rect> listed(Minecraft client) {
        return HudKeepClear.rects(
                client,
                client.getWindow().getGuiScaledWidth(),
                client.getWindow().getGuiScaledHeight());
    }

    /**
     * The 4 key, pressed through the keyboard handler, picks the fourth slot on the client and the server, and the frame moves there on the screen: the fourth slot's box
     * changes and a slot nothing touched does not.
     */
    private void expectTheHotbarDrawsAndANumberKeyPicksASlot(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        BufferedImage before = shot(context, "seated-hotbar-slot-1");
        RealInput.key(context, InputConstants.KEY_4, 0);
        awaitSlot(context, singleplayer, DYNAMITE_SLOT);
        BufferedImage after = shot(context, "seated-hotbar-slot-4");
        double moved = differing(before, after, slotBox(DYNAMITE_SLOT));
        double still = differing(before, after, slotBox(UNTOUCHED_SLOT));
        if (moved < MOVED || still > STILL) {
            throw new AssertionError("Seated, the hotbar is drawn and its frame follows the pick: " + moved
                    + " of the picked slot's box changed (at least " + MOVED + "), " + still
                    + " of an untouched slot's (at most " + STILL + ")");
        }
        expectTheNameIsDrawnWhereItIsListed(context, after);
    }

    /**
     * The name the pick brought up is drawn inside the box the keep-clear list gives for it: against a shot of the same moment with the name held off
     * ({@link EvidencePins#itemNameHidden}), the box changes and the strip just over it does not.
     */
    private static void expectTheNameIsDrawnWhereItIsListed(ClientGameTestContext context, BufferedImage named) {
        HudKeepClear.Rect name = context.computeOnClient(client -> listed(client).stream()
                .filter(rect -> rect.what().equals("held item name"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the held item's name is not on the keep-clear list")));
        EvidencePins.itemNameHidden = true;
        BufferedImage nameless;
        try {
            nameless = shot(context, "seated-hotbar-slot-4-nameless");
        } finally {
            EvidencePins.itemNameHidden = false;
        }
        double inside = differing(named, nameless, name);
        double above = differing(
                named,
                nameless,
                new HudKeepClear.Rect("over the name", name.x(), name.y() - ABOVE_NAME, name.width(), ABOVE_NAME));
        if (inside < NAME_DRAWN || above > ABOVE_STILL) {
            throw new AssertionError("The held item's name is drawn in its listed box " + name + ": " + inside
                    + " of the box changed with the name (at least " + NAME_DRAWN + "), " + above
                    + " of the strip over it (at most " + ABOVE_STILL + ")");
        }
    }

    /** A notch of the wheel down picks the next slot, a notch up the one before, as on foot. */
    private static void expectTheMouseWheelPicksASlot(ClientGameTestContext context) {
        RealInput.scroll(context, -1);
        ClientWait.until(
                context,
                "the wheel to pick the slot after the dynamite",
                client -> ClientSession.player(client).getInventory().getSelectedSlot() == DYNAMITE_SLOT + 1,
                client -> "slot " + ClientSession.player(client).getInventory().getSelectedSlot());
        RealInput.scroll(context, 1);
        ClientWait.until(
                context,
                "the wheel to pick the dynamite's slot again",
                client -> ClientSession.player(client).getInventory().getSelectedSlot() == DYNAMITE_SLOT,
                client -> "slot " + ClientSession.player(client).getInventory().getSelectedSlot());
    }

    /** Server ticks the pod must stand on one spot, on the ground, before the blast's cube is laid out round it. */
    private static final int AT_REST_TICKS = 10;

    /**
     * Waits until the pod has stood on one spot, on the ground, for {@link #AT_REST_TICKS} ticks. The blast's cube is centred on the pod's footprint when
     * the stick is used, and the test lays out the rock to match when it packs: a pod that was still settling (it is put one row over the carved
     * floor) or being nudged between the two moved the cube off the rock the test expected it to spare (#1569).
     */
    private static void awaitPodAtRest(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        Vec3[] last = {null};
        int[] still = {0};
        ClientWait.until(
                context,
                "the pod at rest on the ground",
                () -> singleplayer.getServer().computeOnServer(server -> {
                    ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                    if (!(player.getVehicle() instanceof PodEntity pod)) {
                        throw new AssertionError("the pilot left the pod before the dynamite");
                    }
                    still[0] = pod.onGround() && pod.position().equals(last[0]) ? still[0] + 1 : 0;
                    last[0] = pod.position();
                    return still[0] >= AT_REST_TICKS;
                }),
                () -> "the pod at " + last[0] + ", " + still[0] + " ticks still");
    }

    /**
     * With the dynamite picked, the use key blasts the natural rock in its cube (packed round the pod for the test, as a blocked shaft would be) and spends
     * the stick. The cube sits on the row the pod's feet are in, so the rock above it, over the cab, stays, and so does a rock just past its reach (#1478).
     */
    private static void expectDynamiteFromThePickedSlotBlasts(
            ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        int radius = RepairTuning.DEFAULT.dynamiteRadius();
        awaitPodAtRest(context, singleplayer);
        List<List<BlockPos>> packed = singleplayer.getServer().computeOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            if (!(player.getVehicle() instanceof PodEntity pod)) {
                throw new AssertionError("the pilot left the pod before the dynamite");
            }
            List<BlockPos> cells = ChargeUse.packRound(player, pod, radius);
            // The blast's cube: centred on the feet row and the footprint's middle cell.
            BlockPos middle = PodFootprint.of(pod).feetMiddle();
            List<BlockPos> inside = cells.stream()
                    .filter(cell -> Math.abs(cell.getX() - middle.getX()) <= radius
                            && Math.abs(cell.getY() - middle.getY()) <= radius
                            && Math.abs(cell.getZ() - middle.getZ()) <= radius)
                    .toList();
            List<BlockPos> outside =
                    cells.stream().filter(cell -> !inside.contains(cell)).toList();
            // One past the cube on the +x side, on the row its middle is in.
            BlockPos reach = middle.offset(radius + 1, 0, 0);
            player.level().setBlock(reach, Blocks.STONE.defaultBlockState(), 3);
            return List.of(inside, outside, List.of(reach));
        });
        List<BlockPos> inside = packed.get(0);
        List<BlockPos> outside = packed.get(1);
        BlockPos reach = packed.get(2).getFirst();
        if (inside.isEmpty() || outside.isEmpty()) {
            throw new AssertionError("the packed rock should lie both in the blast's cube (" + inside.size()
                    + " cells) and above it, over the cab (" + outside.size() + " cells)");
        }
        context.getInput().pressKey(options -> options.keyUse);
        ClientWait.until(
                context,
                "the dynamite to blast the rock in its cube and be spent",
                () -> singleplayer.getServer().computeOnServer(server -> {
                    ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                    ServerLevel level = player.level();
                    return player.getInventory().getItem(DYNAMITE_SLOT).isEmpty()
                            && inside.stream()
                                    .allMatch(cell -> level.getBlockState(cell).isAir());
                }),
                () -> singleplayer.getServer().computeOnServer(server -> {
                    ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                    ServerLevel level = player.level();
                    return player.getInventory().getItem(DYNAMITE_SLOT) + " in the slot, "
                            + inside.stream()
                                    .filter(cell -> !level.getBlockState(cell).isAir())
                                    .count()
                            + " of " + inside.size() + " cells of the cube still rock";
                }));
        String problem = singleplayer.getServer().computeOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            ServerLevel level = player.level();
            if (player.getVehicle() == null) {
                return "the pilot was thrown from the pod";
            }
            if (!level.getBlockState(reach).is(Blocks.STONE)) {
                return "the rock one past the dynamite's reach went";
            }
            long gone = outside.stream()
                    .filter(cell -> !level.getBlockState(cell).is(Blocks.STONE))
                    .count();
            return gone == 0 ? "" : gone + " of " + outside.size() + " cells outside the cube went";
        });
        if (!problem.isEmpty()) {
            throw new AssertionError("Only the dynamite's cube clears, with the pilot still seated: " + problem);
        }
    }

    private static void board(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            // The test's pod, by where the pilot stepped off it.
            PodEntity pod = TestLayers.level(server, 1)
                    .getEntitiesOfClass(PodEntity.class, new AABB(BlockPos.containing(player.position())).inflate(4))
                    .stream()
                    .findFirst()
                    .orElse(null);
            if (pod == null || !player.startRiding(pod)) {
                throw new AssertionError("the pilot could not board the pod again");
            }
        });
        ClientWait.until(
                context,
                "the pilot seated again",
                client -> ClientSession.player(client).getVehicle() instanceof PodEntity
                        && CockpitHud.seated(client).isPresent());
    }

    private static void awaitSlot(ClientGameTestContext context, TestSingleplayerContext singleplayer, int slot) {
        ClientWait.until(
                context,
                "slot " + (slot + 1) + " picked on the client and the server",
                () -> clientSlot(context) == slot && serverSlot(singleplayer) == slot,
                () -> "client slot " + clientSlot(context) + ", server slot " + serverSlot(singleplayer));
    }

    private static int clientSlot(ClientGameTestContext context) {
        return context.computeOnClient(
                client -> ClientSession.player(client).getInventory().getSelectedSlot());
    }

    private static int serverSlot(TestSingleplayerContext singleplayer) {
        return singleplayer
                .getServer()
                .computeOnServer(server -> server.getPlayerList()
                        .getPlayers()
                        .getFirst()
                        .getInventory()
                        .getSelectedSlot());
    }

    private static BufferedImage shot(ClientGameTestContext context, String name) {
        context.runOnClient(client -> {
            ClientSession.player(client).setYRot(0f);
            ClientSession.player(client).setXRot(0f);
            client.gui.toastManager().clear();
            // The dismount hint fades on its own clock, in the rows over the held item's name.
            client.gui.hud.setOverlayMessage(Component.empty(), false);
        });
        ClientWait.until(
                context, "every section in view rendered", client -> client.levelRenderer.hasRenderedAllSections());
        context.waitTicks(5); // tick-wait: the pod and the view settle for a few frames
        Path file = context.takeScreenshot(name);
        try {
            return ImageIO.read(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Slot {@code slot}'s frame box on the 427 x 240 GUI of GUI scale 2. */
    private static HudKeepClear.Rect slotBox(int slot) {
        HudKeepClear.Rect bar = SeatedHotbar.bar(427, 240);
        return new HudKeepClear.Rect("slot " + (slot + 1), bar.x() + slot * CELL, bar.y(), FRAME_WIDTH, FRAME_HEIGHT);
    }

    /** The share of the pixels of {@code box}, in GUI pixels at GUI scale 2, that differ by more than a few levels between two shots. */
    private static double differing(BufferedImage a, BufferedImage b, HudKeepClear.Rect box) {
        int differing = 0;
        int total = 0;
        for (int y = box.y() * GUI_SCALE; y < box.bottom() * GUI_SCALE; y++) {
            for (int x = box.x() * GUI_SCALE; x < box.right() * GUI_SCALE; x++) {
                total++;
                int pa = a.getRGB(x, y);
                int pb = b.getRGB(x, y);
                int delta = Math.abs((pa >> 16 & 0xFF) - (pb >> 16 & 0xFF))
                        + Math.abs((pa >> 8 & 0xFF) - (pb >> 8 & 0xFF))
                        + Math.abs((pa & 0xFF) - (pb & 0xFF));
                if (delta > 12) {
                    differing++;
                }
            }
        }
        return (double) differing / total;
    }
}
