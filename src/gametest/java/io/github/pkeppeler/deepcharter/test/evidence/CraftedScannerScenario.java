package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.scanner.ScannerRegistry;
import io.github.pkeppeler.deepcharter.test.ScannerHudTest;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidenceWorld;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "crafted-scanner" (#483): in a sealed room of layer 1 rock, a player lays the scanner unit's recipe out in a crafting
 * table (iron, copper and redstone) and takes the unit. A Mole with no scanner shows no map. The player uses the unit on it from outside, the
 * client's own interaction, and the next ride shows the scanner's map with ore in it.
 */
public class CraftedScannerScenario extends EvidenceScenario {
    private static final int TICKS_PER_FRAME = 3;
    private static final int HOLD_FRAMES = 8;
    private static final int ROOM_LIGHTS_UP = 4;
    /** The recipe's grid (data/deepcharter/recipe/scanner_unit.json): I iron, C copper, R redstone. */
    private static final String[] GRID = {"RCR", "ICI", "I I"};
    /** The table and the pod stand this far from the player, who looks south. */
    private static final int TABLE_AHEAD = 2;

    private static final int TABLE_SIDE = -2;
    private static final int POD_AHEAD = 3;
    private static final int POD_SIDE = 2;
    private static final int ORE_AHEAD = 5;
    private static final int ORE_UP = -4;
    /** Where the cursor is parked when a screen is open: well outside the window. */
    private static final double OFF_SCREEN = -10_000;

    @Override
    protected String name() {
        return "crafted-scanner";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            ClientWait.until(
                    context, "the player in the world", client -> client.player != null && client.level != null);
            EvidenceWorld.pin(context, singleplayer);
            context.runOnClient(client -> client.options.setCameraType(CameraType.FIRST_PERSON));
            ScannerHudTest.goToLayer(server, 1);
            ClientWait.until(
                    context,
                    "the client in layer 1",
                    client -> client.level.dimension().identifier().getPath().equals("layer_1"));
            context.waitTicks(40); // tick-wait: the chunks round the arrival come in before the room is cut
            RoomCarver.carveAroundFirstPlayer(server);
            BlockPos[] spots = server.computeOnServer(CraftedScannerScenario::furnish);
            BlockPos table = spots[0];
            ClientWait.until(
                    context,
                    "the crafting table in the client's world",
                    client -> client.level.getBlockState(table).is(Blocks.CRAFTING_TABLE));
            context.waitTicks(20); // tick-wait: the lamps' light reaches the client's chunk
            waitOutTheTransmission(context);
            hold(context);

            layOutTheRecipe(context, server, table);
            takeTheUnit(context, server);

            ClientWait.until(context, "the pod in the client's world", client -> podOf(client) != null);
            PodEntity pod = context.computeOnClient(CraftedScannerScenario::podOf);
            ride(context, server);
            screenshot(context, "bare-mole-no-scanner");
            hold(context);
            leave(context, server);

            useTheUnit(context, pod);
            ClientWait.until(
                    context,
                    "the scanner fitted to the pod",
                    () -> server.computeOnServer(minecraft ->
                            PodComponents.effectiveTier(podOnServer(minecraft), ComponentTrack.SCANNER) == 1),
                    () -> "no scanner on the pod");
            hold(context);
            screenshot(context, "unit-used-on-the-mole");

            ride(context, server);
            ScannerHudTest.placeBesidePod(server, ORE_AHEAD, ORE_UP, Blocks.GOLD_ORE);
            ScannerHudTest.placeBesidePod(server, ORE_AHEAD - 1, ORE_UP - 1, Blocks.IRON_ORE);
            context.waitTicks(40); // tick-wait: the HUD rescans a few times a second and the ore reaches the client
            hold(context);
            screenshot(context, "scanner-on-the-hud");
        }
    }

    /** Cuts nothing: lights the sealed room, stands a crafting table and a registered pod in it, and puts the ingredients in the pack. */
    private static BlockPos[] furnish(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        ServerLevel level = player.level();
        player.setGameMode(GameType.SURVIVAL);
        Charters.found(server, player.getUUID(), "Demo Charter");
        BlockPos feet = player.blockPosition();
        player.teleportTo(level, feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, Set.of(), 0f, 0f, true);
        for (int[] corner : new int[][] {{-3, -3}, {3, -3}, {-3, 3}, {3, 3}}) {
            level.setBlock(
                    feet.offset(corner[0], ROOM_LIGHTS_UP, corner[1]), Blocks.SEA_LANTERN.defaultBlockState(), 3);
        }
        BlockPos table = feet.offset(TABLE_SIDE, 0, TABLE_AHEAD);
        level.setBlock(table, Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
        PodEntity pod = PodRegistry.POD.create(level, EntitySpawnReason.COMMAND);
        Vec3 at = Vec3.atBottomCenterOf(feet.offset(POD_SIDE, 0, POD_AHEAD));
        pod.setPos(at);
        level.addFreshEntity(pod);
        PodComponents.register(
                pod,
                Charters.charterOfOrThrow(server, player.getUUID())
                        .orElseThrow()
                        .id());
        Inventory inventory = player.getInventory();
        inventory.clearContent();
        for (Item item : new Item[] {Items.IRON_INGOT, Items.COPPER_INGOT, Items.REDSTONE}) {
            inventory.add(new ItemStack(item, 4));
        }
        return new BlockPos[] {table};
    }

    /** Opens the table's own menu and sets each ingredient in its place, as a player would; the screen shows the grid and the unit it makes. */
    private void layOutTheRecipe(ClientGameTestContext context, TestServerContext server, BlockPos table) {
        server.runOnServer(minecraft -> {
            ServerPlayer player = minecraft.getPlayerList().getPlayers().getFirst();
            player.openMenu(player.level().getBlockState(table).getMenuProvider(player.level(), table));
        });
        ClientWait.screen(context, CraftingScreen.class);
        context.getInput().setCursorPos(OFF_SCREEN, OFF_SCREEN); // no slot hovered, so no tooltip
        AbstractContainerMenu[] menu = new AbstractContainerMenu[1];
        for (int row = 0; row < GRID.length; row++) {
            for (int column = 0; column < GRID[row].length(); column++) {
                Item ingredient = switch (GRID[row].charAt(column)) {
                    case 'I' -> Items.IRON_INGOT;
                    case 'C' -> Items.COPPER_INGOT;
                    case 'R' -> Items.REDSTONE;
                    default -> null;
                };
                if (ingredient != null) {
                    int slot = 1 + row * 3 + column;
                    server.runOnServer(minecraft -> {
                        menu[0] = minecraft.getPlayerList().getPlayers().getFirst().containerMenu;
                        menu[0].getSlot(slot).set(new ItemStack(ingredient));
                        menu[0].broadcastChanges();
                    });
                    context.waitTicks(
                            TICKS_PER_FRAME); // tick-wait: one ingredient a frame, so the clip shows the grid fill
                    frame(context);
                }
            }
        }
        ClientWait.until(
                context,
                "the unit in the table's result slot",
                client -> client.player.containerMenu.getSlot(0).getItem().is(ScannerRegistry.SCANNER_UNIT));
        hold(context);
        screenshot(context, "recipe-in-the-crafting-table");
    }

    /** Takes the result as a shift-click does, closes the table, and selects the unit on the hotbar as the player would. */
    private void takeTheUnit(ClientGameTestContext context, TestServerContext server) {
        server.runOnServer(minecraft -> {
            ServerPlayer player = minecraft.getPlayerList().getPlayers().getFirst();
            player.containerMenu.clicked(0, 0, ContainerInput.QUICK_MOVE, player);
            player.closeContainer();
            // The shift-click put the unit in the last hotbar slot or in the pack: it goes to the first slot, which is
            // where the hand starts.
            Inventory inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                if (inventory.getItem(slot).is(ScannerRegistry.SCANNER_UNIT)) {
                    ItemStack unit = inventory.getItem(slot);
                    inventory.setItem(slot, inventory.getItem(0));
                    inventory.setItem(0, unit);
                    return;
                }
            }
            throw new AssertionError("the shift-click made no scanner unit");
        });
        ClientWait.until(
                context,
                "the unit in the client's pack",
                client -> client.gui.screen() == null && hotbarSlotOfTheUnit(client) >= 0);
        context.runOnClient(client -> client.player.getInventory().setSelectedSlot(hotbarSlotOfTheUnit(client)));
        ClientWait.until(
                context,
                "the unit in the client's hand",
                client -> client.player.getMainHandItem().is(ScannerRegistry.SCANNER_UNIT));
        hold(context);
        screenshot(context, "the-unit-in-hand");
    }

    private static int hotbarSlotOfTheUnit(Minecraft client) {
        Inventory inventory = client.player.getInventory();
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (inventory.getItem(slot).is(ScannerRegistry.SCANNER_UNIT)) {
                return slot;
            }
        }
        return -1;
    }

    /** The client's own use of the held item on the pod: what a right click on it does. */
    private void useTheUnit(ClientGameTestContext context, PodEntity pod) {
        context.getInput().lookAt(pod.blockPosition().above());
        hold(context);
        context.runOnClient(client -> {
            EntityHitResult hit = new EntityHitResult(pod, pod.position().add(0, 1, 0));
            client.gameMode.interact(client.player, pod, hit, InteractionHand.MAIN_HAND);
            client.player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        });
        context.waitTicks(TICKS_PER_FRAME); // tick-wait: the use packet reaches the server
    }

    private static PodEntity podOf(Minecraft client) {
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity instanceof PodEntity found) {
                return found;
            }
        }
        return null;
    }

    /** The room's pod on the server. */
    private static PodEntity podOnServer(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        // entity-query: the room is sealed and 9 blocks across, so its one pod is within 10 blocks of the player, who
        // is inside it
        return player.level()
                .getEntitiesOfClass(PodEntity.class, player.getBoundingBox().inflate(10))
                .getFirst();
    }

    /** Seats the player on the pod of the room. */
    private void ride(ClientGameTestContext context, TestServerContext server) {
        server.runOnServer(minecraft -> {
            ServerPlayer player = minecraft.getPlayerList().getPlayers().getFirst();
            if (!player.startRiding(podOnServer(minecraft))) {
                throw new AssertionError("the player could not mount the pod");
            }
        });
        ClientWait.until(
                context, "the client riding the pod", client -> client.player.getVehicle() instanceof PodEntity);
        context.waitTicks(20); // tick-wait: the HUD's first scan after the mount
    }

    private void leave(ClientGameTestContext context, TestServerContext server) {
        server.runOnServer(
                minecraft -> minecraft.getPlayerList().getPlayers().getFirst().stopRiding());
        ClientWait.until(context, "the player out of the pod", client -> client.player.getVehicle() == null);
    }

    /** The charter's founding transmission types over the screen; the first still waits until it has come and gone. */
    private void waitOutTheTransmission(ClientGameTestContext context) {
        ClientWait.until(context, "the founding transmission on screen", client -> TransmissionOverlay.active());
        ClientWait.until(context, "the founding transmission over", client -> !TransmissionOverlay.active());
        context.waitTicks(60); // tick-wait: the chat lines and toasts of the founding fade
    }

    private void hold(ClientGameTestContext context) {
        context.runOnClient(client -> client.gui.toastManager().clear());
        for (int i = 0; i < HOLD_FRAMES; i++) {
            context.waitTicks(TICKS_PER_FRAME); // tick-wait: a still held for a fixed number of frames
            frame(context);
        }
    }
}
