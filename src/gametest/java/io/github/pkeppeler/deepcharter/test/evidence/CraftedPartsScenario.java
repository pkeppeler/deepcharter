package io.github.pkeppeler.deepcharter.test.evidence;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.market.OreProcessorScreen;
import io.github.pkeppeler.deepcharter.client.transmission.TransmissionOverlay;
import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
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
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.EvidenceWorld;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestColony;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
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
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "crafted-parts" (#536): in a sealed room of layer 1 rock, a player lays the fuel tank unit's
 * recipe and then the drill unit's out in a crafting table (iron, copper and redstone, from the shipped recipe files)
 * and takes each unit. Each is fitted to the Mole with the client's own right click, and the pod holds a tier 1 fuel
 * tank and a tier 1 drill, which the terminal no longer sells. Then a hazard unit: the pod goes to the colony's ore
 * processor with Bronzium in its cargo, WITHDRAW ORE gives one to the pack (the account is not paid), the pod and the
 * player return to the room, and the lights unit made of it is fitted too.
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
    /**
     * The colony's ore processor is used from the pad south of it, with the pod parked to the west of the player
     * (the fuel pump's side).
     */
    private static final Vec3i PLAYER_FROM_PROCESSOR = new Vec3i(0, 0, 4);

    private static final Vec3i POD_FROM_PROCESSOR = new Vec3i(-3, 0, 3);

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
            ClientWait.until(context, "the client in layer 1", client -> TestLayers.clientIsIn(client, 1));
            context.waitTicks(40); // tick-wait: the chunks round the arrival come in before the room is cut
            RoomCarver.carveAroundFirstPlayer(server);
            Room room = server.computeOnServer(CraftedPartsScenario::furnish);
            BlockPos table = room.table();
            int podId = room.podId();
            ClientWait.until(
                    context,
                    "the crafting table in the client's world",
                    client ->
                            ClientLight.clientLevel(client).getBlockState(table).is(Blocks.CRAFTING_TABLE));
            context.waitTicks(20); // tick-wait: the lamps' light reaches the client's chunk
            waitOutTheTransmission(context);
            hold(context);
            PodEntity pod = clientEntity(context, podId, PodEntity.class);
            screenshot(context, "bare-mole");

            for (ComponentTrack track : new ComponentTrack[] {ComponentTrack.FUEL_TANK, ComponentTrack.DRILL}) {
                Item unit = BuiltInRegistries.ITEM.getValue(
                        Identifier.fromNamespaceAndPath("deepcharter", track.id() + "_unit"));
                layOutTheRecipe(context, server, table, track, unit);
                takeTheUnit(context, server, track, unit);
                hold(context);
                rightClick(context, pod);
                ClientWait.until(
                        context,
                        "the " + track.id() + " fitted to the pod",
                        () -> server.computeOnServer(
                                minecraft -> PodComponents.effectiveTier(podOnServer(minecraft, podId), track) == 1),
                        () -> "no " + track.id() + " on the pod");
                hold(context);
                screenshot(context, track.id() + "-unit-used-on-the-mole");
            }
            withdrawTheOreAndCraftTheLights(context, server, table, podId);
        }
    }

    /**
     * A hazard unit's ore: a pod parked at the colony's ore processor holds Bronzium from the dig, WITHDRAW ORE
     * moves one into the pack (the account is not paid), and back in the room the pilot crafts the lights unit
     * from it and fits it to the Mole.
     */
    private void withdrawTheOreAndCraftTheLights(
            ClientGameTestContext context, TestServerContext server, BlockPos table, int podId) {
        Trip trip = server.computeOnServer(minecraft -> goToTheProcessor(minecraft, podId));
        BlockPos processor = trip.processor();
        ClientWait.until(
                context,
                "the player and the pod at the colony's ore processor",
                client -> ClientSession.player(client).position().distanceToSqr(trip.playerAtProcessor()) < 1
                        && podAt(client, podId, trip.podAtProcessor())
                        && ClientLight.clientLevel(client)
                                .getBlockState(processor)
                                .is(TerminalTypes.ORE_PROCESSOR.block()));
        context.getInput().lookAt(processor);
        hold(context);
        context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(processor)));
        ClientWait.screen(context, OreProcessorScreen.class);
        OreProcessorScreen screen =
                context.computeOnClient(client -> ClientSession.screen(client, OreProcessorScreen.class));
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
        server.runOnServer(minecraft -> goBack(minecraft, trip));
        ClientWait.until(
                context,
                "the player and the pod back in the room",
                client -> ClientSession.player(client).position().distanceToSqr(trip.playerHome()) < 1
                        && podAt(client, podId, trip.podHome()));

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
        hold(context);
        rightClick(context, clientEntity(context, podId, PodEntity.class));
        ClientWait.until(
                context,
                "the lights fitted to the pod",
                () -> server.computeOnServer(minecraft ->
                        PodComponents.effectiveTier(podOnServer(minecraft, podId), ComponentTrack.LIGHTS) == 1),
                () -> "no lights on the pod");
        hold(context);
        screenshot(context, "lights-unit-used-on-the-mole");
    }

    /** Where the processor beat takes the player and the pod, and where each stood in the room. */
    private record Trip(
            int podId,
            BlockPos processor,
            Vec3 playerAtProcessor,
            Vec3 podAtProcessor,
            Vec3 playerHome,
            Vec3 podHome) {}

    /** What {@link #furnish} stood in the room: the crafting table, and the network id of the pod. */
    private record Room(BlockPos table, int podId) {}

    /** Whether the client's copy of the pod is within a block of {@code at}; looked up again on each call, as a teleport may replace it. */
    private static boolean podAt(Minecraft client, int podId, Vec3 at) {
        return ClientLight.clientLevel(client).getEntity(podId) instanceof PodEntity pod
                && pod.position().distanceToSqr(at) < 1;
    }

    /**
     * Repairs the pump and the colony's ore processor, puts three Bronzium in the pod's cargo, where a dig leaves
     * them, and parks the pod at the processor with the player in reach of it. Not a processor in the room: at
     * depth one serves only in its charter's outpost hall (OutpostAccess), and the room is none.
     */
    private static Trip goToTheProcessor(MinecraftServer server, int podId) {
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        PodEntity pod = podOnServer(server, podId);
        if (player.level() != server.overworld()) {
            throw new AssertionError(
                    "the room is not in the colony's level: " + player.level().dimension());
        }
        RepairState state = RepairState.get(server);
        for (TerminalType type : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR)) {
            type.parts().forEach(part -> state.insert(type, part));
        }
        for (int i = 0; i < ORE_IN_CARGO; i++) {
            pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.BRONZIUM));
        }
        ColonySite.Placed colony =
                Colony.placed(server).orElseThrow(() -> new AssertionError("the colony was not built"));
        BlockPos processor = TestColony.anchor(colony, ColonyAnchor.ORE_PROCESSOR);
        Trip trip = new Trip(
                podId,
                processor,
                Vec3.atBottomCenterOf(processor.offset(PLAYER_FROM_PROCESSOR)),
                Vec3.atBottomCenterOf(processor.offset(POD_FROM_PROCESSOR)),
                player.position(),
                pod.position());
        pod.setPos(trip.podAtProcessor());
        Vec3 look = Vec3.atCenterOf(processor).subtract(trip.playerAtProcessor().add(0, player.getEyeHeight(), 0));
        player.teleportTo(
                server.overworld(),
                trip.playerAtProcessor().x,
                trip.playerAtProcessor().y,
                trip.playerAtProcessor().z,
                Set.of(),
                (float) Math.toDegrees(Math.atan2(-look.x, look.z)),
                (float) Math.toDegrees(Math.atan2(-look.y, Math.hypot(look.x, look.z))),
                true);
        return trip;
    }

    /** Takes the player and the pod back to where they stood in the room. */
    private static void goBack(MinecraftServer server, Trip trip) {
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        podOnServer(server, trip.podId()).setPos(trip.podHome());
        player.teleportTo(
                server.overworld(),
                trip.playerHome().x,
                trip.playerHome().y,
                trip.playerHome().z,
                Set.of(),
                0f,
                0f,
                true);
    }

    /** Lights the sealed room, stands a crafting table and a registered pod in it, and puts the ingredients in the pack. */
    private static Room furnish(MinecraftServer server) {
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
        PodEntity pod = TestEntities.create(PodRegistry.POD, level);
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
        return new Room(table, pod.getId());
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
                client -> ClientSession.player(client)
                        .containerMenu
                        .getSlot(0)
                        .getItem()
                        .is(unit));
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
        context.runOnClient(
                client -> ClientSession.player(client).getInventory().setSelectedSlot(hotbarSlotOf(client, unit)));
        ClientWait.until(
                context,
                "the unit in the client's hand",
                client -> ClientSession.player(client).getMainHandItem().is(unit));
        hold(context);
        screenshot(context, track.id() + "-unit-in-hand");
    }

    private static int hotbarSlotOf(Minecraft client, Item unit) {
        Inventory inventory = ClientSession.player(client).getInventory();
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (inventory.getItem(slot).is(unit)) {
                return slot;
            }
        }
        return -1;
    }

    /** The room's pod on the server, by its network id. */
    private static PodEntity podOnServer(MinecraftServer server, int podId) {
        return TestEntities.require(
                server.getPlayerList().getPlayers().getFirst().level(), podId, PodEntity.class);
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
