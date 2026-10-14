package io.github.pkeppeler.deepcharter.test.evidence;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.market.OreProcessorScreen;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.test.ScannerHudTest;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidenceWorld;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
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
 * Evidence scenario "crafted-parts" (#536): in a sealed room of layer 1 rock, a player lays the fuel tank unit's recipe and then the drill unit's
 * out in a crafting table (iron, copper and redstone, from the shipped recipe files) and takes each unit. Each is used on the Mole from outside,
 * the client's own interaction, and the pod holds a tier 1 fuel tank and a tier 1 drill, which the terminal no longer sells. Then a hazard unit: the pod, parked at the ore processor
 * with Bronzium in its cargo, gives one to the pack through WITHDRAW ORE (the account is not paid), and the lights unit made of it is fitted too.
 */
public class CraftedPartsScenario extends EvidenceScenario {
    private static final int TICKS_PER_FRAME = 3;
    private static final int HOLD_FRAMES = 8;
    private static final int ROOM_LIGHTS_UP = 4;
    /** The table and the pod stand this far from the player, who looks south. */
    private static final int TABLE_AHEAD = 2;

    private static final int TABLE_SIDE = -2;
    private static final int POD_AHEAD = 3;
    private static final int POD_SIDE = 2;
    /** Where the cursor is parked when a screen is open: well outside the window. */
    private static final double OFF_SCREEN = -10_000;
    /** What the pack holds: enough for the two recipes (12 iron, 2 copper, 1 redstone), and a little more as a player's pack would. */
    private static final int INGREDIENTS_EACH = 16;
    /** The Bronzium a dig left in the pod's cargo: the lights unit takes one. */
    private static final int ORE_IN_CARGO = 3;

    private static final int TYPING_FRAMES = 40;

    @Override
    protected String name() {
        return "crafted-parts";
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
            BlockPos table = server.computeOnServer(CraftedPartsScenario::furnish);
            ClientWait.until(
                    context,
                    "the crafting table in the client's world",
                    client -> client.level.getBlockState(table).is(Blocks.CRAFTING_TABLE));
            context.waitTicks(20); // tick-wait: the lamps' light reaches the client's chunk
            waitOutTheTransmission(context);
            hold(context);
            ClientWait.until(context, "the pod in the client's world", client -> podOf(client) != null);
            PodEntity pod = context.computeOnClient(CraftedPartsScenario::podOf);
            screenshot(context, "bare-mole");

            for (ComponentTrack track : new ComponentTrack[] {ComponentTrack.FUEL_TANK, ComponentTrack.DRILL}) {
                Item unit = BuiltInRegistries.ITEM.getValue(
                        Identifier.fromNamespaceAndPath("deepcharter", track.id() + "_unit"));
                layOutTheRecipe(context, server, table, track, unit);
                takeTheUnit(context, server, track, unit);
                context.getInput().lookAt(pod.blockPosition().above());
                hold(context);
                useTheUnit(context, pod);
                ClientWait.until(
                        context,
                        "the " + track.id() + " fitted to the pod",
                        () -> server.computeOnServer(
                                minecraft -> PodComponents.effectiveTier(podOnServer(minecraft), track) == 1),
                        () -> "no " + track.id() + " on the pod");
                hold(context);
                screenshot(context, track.id() + "-unit-used-on-the-mole");
            }
            withdrawTheOreAndCraftTheLights(context, server, table, pod);
        }
    }

    /**
     * A hazard unit's ore: a pod parked at the ore processor holds Bronzium from the dig, WITHDRAW ORE moves one into the pack (the account is not paid), the
     * pilot crafts the lights unit from it, and fits it to the Mole.
     */
    private void withdrawTheOreAndCraftTheLights(
            ClientGameTestContext context, TestServerContext server, BlockPos table, PodEntity pod) {
        BlockPos processor = server.computeOnServer(CraftedPartsScenario::setUpTheProcessor);
        ClientWait.until(
                context,
                "the ore processor in the client's world",
                client -> client.level.getBlockState(processor).is(TerminalTypes.ORE_PROCESSOR.block()));
        context.getInput().lookAt(processor);
        hold(context);
        context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(processor)));
        ClientWait.screen(context, OreProcessorScreen.class);
        OreProcessorScreen screen = context.computeOnClient(client -> (OreProcessorScreen) client.gui.screen());
        for (int i = 0; i < TYPING_FRAMES && !screen.typewriter().done(); i++) {
            context.waitTicks(TICKS_PER_FRAME); // tick-wait: one frame of the processor's typing
            frame(context);
        }
        hold(context);
        screenshot(context, "processor-with-bronzium-in-cargo");
        context.clickScreenButton("WITHDRAW ORE");
        ClientWait.until(
                context,
                "the withdraw list",
                client -> screen.withdrawing() && !screen.cargo().isEmpty());
        hold(context);
        screenshot(context, "withdraw-ore-list");
        context.clickScreenButton("TAKE ONE");
        ClientWait.until(
                context,
                "a Bronzium in the pack",
                () -> server.computeOnServer(minecraft -> minecraft
                                .getPlayerList()
                                .getPlayers()
                                .getFirst()
                                .getInventory()
                                .countItem(OreRegistry.item(OreType.BRONZIUM)))
                        == 1,
                () -> "no Bronzium in the pack");
        hold(context);
        screenshot(context, "bronzium-withdrawn");
        context.setScreen(() -> null);

        Item unit = BuiltInRegistries.ITEM.getValue(
                Identifier.fromNamespaceAndPath("deepcharter", ComponentTrack.LIGHTS.id() + "_unit"));
        layOutTheRecipe(context, server, table, ComponentTrack.LIGHTS, unit);
        takeTheUnit(context, server, ComponentTrack.LIGHTS, unit);
        // The recipe was laid out by the test, so the pack's Bronzium is spent here, as the table would have taken it.
        server.runOnServer(minecraft -> {
            Inventory pack = minecraft.getPlayerList().getPlayers().getFirst().getInventory();
            for (int slot = 0; slot < pack.getContainerSize(); slot++) {
                if (pack.getItem(slot).is(OreRegistry.item(OreType.BRONZIUM))) {
                    pack.setItem(slot, ItemStack.EMPTY);
                }
            }
        });
        context.getInput().lookAt(pod.blockPosition().above());
        hold(context);
        useTheUnit(context, pod);
        ClientWait.until(
                context,
                "the lights fitted to the pod",
                () -> server.computeOnServer(
                        minecraft -> PodComponents.effectiveTier(podOnServer(minecraft), ComponentTrack.LIGHTS) == 1),
                () -> "no lights on the pod");
        hold(context);
        screenshot(context, "lights-unit-used-on-the-mole");
    }

    /** Repairs the pump and the processor, stands the processor between the table and the pod, and puts three Bronzium in the pod's cargo, where a dig leaves them. */
    private static BlockPos setUpTheProcessor(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        ServerLevel level = player.level();
        RepairState state = RepairState.get(server);
        for (TerminalType type : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR)) {
            type.parts().forEach(part -> state.insert(type, part));
        }
        BlockPos processor = player.blockPosition().offset(0, 0, POD_AHEAD);
        level.setBlock(processor, TerminalTypes.ORE_PROCESSOR.block().defaultBlockState(), 3);
        PodEntity pod = podOnServer(server);
        for (int i = 0; i < ORE_IN_CARGO; i++) {
            pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.BRONZIUM));
        }
        return processor;
    }

    /** Lights the sealed room, stands a crafting table and a registered pod in it, and puts the ingredients in the pack. */
    private static BlockPos furnish(MinecraftServer server) {
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
        pod.setPos(Vec3.atBottomCenterOf(feet.offset(POD_SIDE, 0, POD_AHEAD)));
        level.addFreshEntity(pod);
        PodComponents.register(
                pod,
                Charters.charterOfOrThrow(server, player.getUUID())
                        .orElseThrow()
                        .id());
        Inventory inventory = player.getInventory();
        inventory.clearContent();
        for (Item item : new Item[] {Items.IRON_INGOT, Items.COPPER_INGOT, Items.REDSTONE}) {
            inventory.add(new ItemStack(item, INGREDIENTS_EACH));
        }
        return table;
    }

    /** The shipped recipe of a unit: the rows of its grid and the ingredient each letter stands for. */
    private static JsonObject recipe(ComponentTrack track) {
        String path = "/data/deepcharter/recipe/" + track.id() + "_unit.json";
        try (InputStream stream = CraftedPartsScenario.class.getResourceAsStream(path)) {
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(path, e);
        }
    }

    /** Opens the table's own menu and sets each ingredient in its place, as a player would; the screen shows the grid and the unit it makes. */
    private void layOutTheRecipe(
            ClientGameTestContext context, TestServerContext server, BlockPos table, ComponentTrack track, Item unit) {
        server.runOnServer(minecraft -> {
            ServerPlayer player = minecraft.getPlayerList().getPlayers().getFirst();
            player.openMenu(player.level().getBlockState(table).getMenuProvider(player.level(), table));
        });
        ClientWait.screen(context, CraftingScreen.class);
        context.getInput().setCursorPos(OFF_SCREEN, OFF_SCREEN); // no slot hovered, so no tooltip
        JsonObject recipe = recipe(track);
        JsonObject key = recipe.getAsJsonObject("key");
        var pattern = recipe.getAsJsonArray("pattern");
        AbstractContainerMenu[] menu = new AbstractContainerMenu[1];
        for (int row = 0; row < pattern.size(); row++) {
            String cells = pattern.get(row).getAsString();
            for (int column = 0; column < cells.length(); column++) {
                if (cells.charAt(column) == ' ') {
                    continue;
                }
                Item ingredient = BuiltInRegistries.ITEM.getValue(Identifier.parse(
                        key.get(String.valueOf(cells.charAt(column))).getAsString()));
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
        ClientWait.until(
                context,
                "the " + track.id() + " unit in the table's result slot",
                client -> client.player.containerMenu.getSlot(0).getItem().is(unit));
        hold(context);
        screenshot(context, track.id() + "-recipe-in-the-crafting-table");
    }

    /** Takes the result as a shift-click does, closes the table, and selects the unit on the hotbar as the player would. */
    private void takeTheUnit(ClientGameTestContext context, TestServerContext server, ComponentTrack track, Item unit) {
        server.runOnServer(minecraft -> {
            ServerPlayer player = minecraft.getPlayerList().getPlayers().getFirst();
            player.containerMenu.clicked(0, 0, ContainerInput.QUICK_MOVE, player);
            player.closeContainer();
            // The shift-click put the unit in the last hotbar slot or in the pack: it goes to the first slot, which is
            // where the hand starts.
            Inventory inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                if (inventory.getItem(slot).is(unit)) {
                    ItemStack made = inventory.getItem(slot);
                    inventory.setItem(slot, inventory.getItem(0));
                    inventory.setItem(0, made);
                    return;
                }
            }
            throw new AssertionError("the shift-click made no " + track.id() + " unit");
        });
        ClientWait.until(
                context,
                "the unit in the client's pack",
                client -> client.gui.screen() == null && hotbarSlotOf(client, unit) >= 0);
        context.runOnClient(client -> client.player.getInventory().setSelectedSlot(hotbarSlotOf(client, unit)));
        ClientWait.until(
                context,
                "the unit in the client's hand",
                client -> client.player.getMainHandItem().is(unit));
        hold(context);
        screenshot(context, track.id() + "-unit-in-hand");
    }

    private static int hotbarSlotOf(Minecraft client, Item unit) {
        Inventory inventory = client.player.getInventory();
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (inventory.getItem(slot).is(unit)) {
                return slot;
            }
        }
        return -1;
    }

    /** The client's own use of the held item on the pod: what a right click on it does. */
    private void useTheUnit(ClientGameTestContext context, PodEntity pod) {
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
