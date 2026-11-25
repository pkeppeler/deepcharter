package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.fuel.FuelPumpScreen;
import io.github.pkeppeler.deepcharter.client.terminal.RepairScreen;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalScreens;
import io.github.pkeppeler.deepcharter.client.terminal.TerminalViewScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtScreen;
import io.github.pkeppeler.deepcharter.client.ui.CrtTextField;
import io.github.pkeppeler.deepcharter.client.ui.Typewriter;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalActionPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.terminal.TerminalRefusal;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.TerminalView;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.SlotClicks;
import io.github.pkeppeler.deepcharter.test.support.TerminalTestTypes;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Client GameTest for #59 and #1265: an offline terminal opens its repair screen, with a ghost in each part slot; a
 * part dragged into its slot, then one shift-clicked, go in through the server, and the online screen opens when the
 * last is in; a locked terminal's slots refuse its parts with the reason on screen; and the server refuses an open
 * request from too far.
 */
public class TerminalFrameworkClientTest implements FabricClientGameTest {
    private static final int FAR_BLOCKS = 20;
    private static final int REFUSAL_TICKS = 20;

    /** The three terminals the test places, as absolute positions. */
    private record Scene(BlockPos pump, BlockPos processor, BlockPos farPump, BlockPos open) {}

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            Scene scene = singleplayer.getServer().computeOnServer(TerminalFrameworkClientTest::setUp);

            farRequestIsRefused(context, scene);
            repairScreenOpensWithAGhostPerPart(context, scene);
            lockedTerminalRefusesItsParts(context, singleplayer, scene);
            partsGoInByDragAndShiftClick(context, singleplayer, scene);
            onlineScreenOpensOnceRepaired(context, scene);
            customScreenUpdatesInPlace(context, scene);
        }
    }

    /** The player founds a charter, carries every part, and has a pump and a processor beside them and a second pump far away. */
    private static Scene setUp(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        require(
                Charters.found(server, player.getUUID(), "Terminal Test Charter")
                        .isEmpty(),
                "founding should succeed");
        BlockPos here = player.blockPosition();
        Scene scene = new Scene(
                here.relative(Direction.EAST, 2),
                here.relative(Direction.SOUTH, 2),
                here.relative(Direction.EAST, FAR_BLOCKS),
                here.relative(Direction.WEST, 2));
        server.overworld()
                .setBlock(scene.pump(), TerminalTypes.FUEL_PUMP.block().defaultBlockState(), 3);
        server.overworld()
                .setBlock(scene.processor(), TerminalTypes.ORE_PROCESSOR.block().defaultBlockState(), 3);
        server.overworld().setBlock(scene.open(), TerminalTestTypes.OPEN.block().defaultBlockState(), 3);
        server.overworld()
                .setBlock(scene.farPump(), TerminalTypes.FUEL_PUMP.block().defaultBlockState(), 3);
        TerminalTypes.all()
                .forEach(type ->
                        type.parts().forEach(part -> player.getInventory().add(new ItemStack(part))));
        return scene;
    }

    private static void request(ClientGameTestContext context, BlockPos pos) {
        context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(pos)));
    }

    private static RepairScreen awaitRepairScreen(ClientGameTestContext context) {
        ClientWait.screen(context, RepairScreen.class);
        RepairScreen screen = context.computeOnClient(client -> ClientSession.screen(client, RepairScreen.class));
        ClientWait.until(
                context,
                "the repair screen finished typing",
                client -> screen.typewriter().done(),
                client -> "typewriter text '" + screen.typewriter().text() + "'");
        return screen;
    }

    private static void farRequestIsRefused(ClientGameTestContext context, Scene scene) {
        request(context, scene.farPump());
        context.waitTicks(REFUSAL_TICKS);
        require(
                context.computeOnClient(client -> client.gui.screen() == null),
                "an open request from " + FAR_BLOCKS + " blocks away must not open a screen");
    }

    private static void repairScreenOpensWithAGhostPerPart(ClientGameTestContext context, Scene scene) {
        request(context, scene.pump());
        RepairScreen screen = awaitRepairScreen(context);
        require(
                screen.typewriter().text().contains("OFFLINE")
                        && screen.typewriter().text().contains("INSERT EVERY PART"),
                "the pump has no prerequisite, so its text asks for every part, got '"
                        + screen.typewriter().text() + "'");
        List<Item> ghosts = context.computeOnClient(client -> screen.getMenu().ruleSlots().stream()
                .map(slot -> slot.rule().ghost().orElseThrow().icon().getItem())
                .toList());
        List<Item> parts = TerminalTypes.FUEL_PUMP.parts();
        require(ghosts.equals(parts), "one slot per part, each showing its part's ghost: " + ghosts + " vs " + parts);
        require(
                context.computeOnClient(
                        client -> screen.getMenu().ruleSlots().stream().noneMatch(slot -> slot.hasItem())),
                "every part slot starts empty");
        context.waitTicks(5);
        context.setScreen(() -> null);
    }

    private static void lockedTerminalRefusesItsParts(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, Scene scene) {
        request(context, scene.processor());
        RepairScreen screen = awaitRepairScreen(context);
        require(
                screen.typewriter().text().contains("OFFLINE")
                        && screen.typewriter().text().contains("FIRST"),
                "the processor is offline and locked while the pump is not repaired, the locked text names what to repair first, got '"
                        + screen.typewriter().text() + "'");
        Item coil = TerminalTypes.ORE_PROCESSOR.parts().getFirst();
        SlotClicks.shiftClick(context, screen, inventorySlot(context, screen, coil));
        ClientWait.until(
                context,
                "the refusal on screen",
                client -> screen.getMenu().refusal().isPresent(),
                client -> "refusal " + screen.getMenu().refusal());
        require(
                screen.getMenu().refusal().orElseThrow().equals(TerminalRefusal.PREREQUISITE_UNREPAIRED.message()),
                "the screen says to repair the earlier terminal first, got "
                        + screen.getMenu().refusal());
        require(
                !singleplayer
                        .getServer()
                        .computeOnServer(server -> RepairState.get(server).inserted(TerminalTypes.ORE_PROCESSOR))
                        .contains(coil),
                "the server put nothing in");
        context.setScreen(() -> null);
    }

    private static void partsGoInByDragAndShiftClick(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, Scene scene) {
        request(context, scene.pump());
        RepairScreen screen = awaitRepairScreen(context);
        Item motor = TerminalTypes.FUEL_PUMP.parts().get(0);
        Item valve = TerminalTypes.FUEL_PUMP.parts().get(1);

        SlotClicks.click(context, screen, inventorySlot(context, screen, motor));
        SlotClicks.click(context, screen, screen.getMenu().ruleSlots().get(0));
        ClientWait.until(
                context,
                "the motor in its slot, on the server",
                client -> singleplayer
                        .getServer()
                        .computeOnServer(server -> RepairState.get(server).inserted(TerminalTypes.FUEL_PUMP))
                        .contains(motor),
                client -> "slot " + screen.getMenu().ruleSlots().get(0).getItem());
        require(
                context.computeOnClient(client -> client.gui.screen() == screen
                        && screen.getMenu().ruleSlots().get(0).getItem().is(motor)
                        && screen.getMenu().getCarried().isEmpty()),
                "one part of two leaves the repair screen open, the motor shown in its slot");

        SlotClicks.shiftClick(context, screen, inventorySlot(context, screen, valve));
        // The repaired pump opens its own screen (#69), not the generic online one.
        ClientWait.until(context, "the fuel pump screen open", client -> client.gui.screen() instanceof FuelPumpScreen);
        boolean repaired = singleplayer
                .getServer()
                .computeOnServer(server -> RepairState.get(server).repaired(TerminalTypes.FUEL_PUMP));
        require(repaired, "the server has the pump repaired");
        int carried = singleplayer
                .getServer()
                .computeOnServer(server -> server.getPlayerList()
                        .getPlayers()
                        .getFirst()
                        .getInventory()
                        .countItem(motor));
        require(carried == 0, "the part left the inventory, " + carried + " remain");
    }

    /** The slot of the player's inventory in {@code screen} that holds {@code item}. */
    private static Slot inventorySlot(ClientGameTestContext context, RepairScreen screen, Item item) {
        return context.computeOnClient(client -> screen.getMenu().slots.stream()
                .filter(slot ->
                        slot.container instanceof Inventory && slot.getItem().is(item))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the player carries no " + item)));
    }

    private static void onlineScreenOpensOnceRepaired(ClientGameTestContext context, Scene scene) {
        context.setScreen(() -> null);
        request(context, scene.farPump());
        context.waitTicks(REFUSAL_TICKS);
        require(context.computeOnClient(client -> client.gui.screen() == null), "the repair does not widen the range");

        request(context, scene.pump());
        ClientWait.screen(context, FuelPumpScreen.class);
        FuelPumpScreen screen = context.computeOnClient(client -> ClientSession.screen(client, FuelPumpScreen.class));
        ClientWait.until(
                context,
                "the fuel pump screen finished typing",
                client -> screen.typewriter().done(),
                client -> "typewriter text '" + screen.typewriter().text() + "'");
        require(
                screen.typewriter().text().contains("ONLINE"),
                "the online text says so, got '" + screen.typewriter().text() + "'");
        context.waitTicks(5);
        context.setScreen(() -> null);

        request(context, scene.processor());
        RepairScreen processor = awaitRepairScreen(context);
        require(
                processor.typewriter().text().contains("INSERT EVERY PART"),
                "with the pump repaired the processor's parts can go in, got '"
                        + processor.typewriter().text() + "'");
        context.setScreen(() -> null);
    }

    /** A feature's own online screen keeps its text field when the server answers an action: it is updated, not replaced. */
    private static void customScreenUpdatesInPlace(ClientGameTestContext context, Scene scene) {
        TerminalScreens.register(TerminalTestTypes.OPEN, InPlaceScreen::new);
        request(context, scene.open());
        ClientWait.screen(context, InPlaceScreen.class);
        InPlaceScreen screen = context.computeOnClient(client -> ClientSession.screen(client, InPlaceScreen.class));
        require(
                screen.view().repaired() && screen.view().parts().isEmpty(),
                "a terminal with no repair opens online, with no parts");
        context.runOnClient(client -> screen.field().setValue("RIGGS"));
        context.runOnClient(client -> ClientPlayNetworking.send(
                new TerminalActionPayload(scene.open(), TerminalTestTypes.PING, new CompoundTag())));
        ClientWait.until(
                context,
                "one in-place update",
                client -> screen.updates() == 1,
                client -> "updates " + screen.updates());
        require(
                context.computeOnClient(client -> client.gui.screen() == screen),
                "the action's answer must not replace the screen");
        require(
                screen.field().getValue().equals("RIGGS"),
                "the text field keeps its text, got '" + screen.field().getValue() + "'");
        context.setScreen(() -> null);
    }

    /** A screen with a text field, standing in for a feature's own online screen. */
    private static final class InPlaceScreen extends CrtScreen implements TerminalViewScreen {
        private TerminalView view;
        private @Nullable CrtTextField field;
        private int updates;
        private final Typewriter typewriter = typewriter(Component.literal("IN PLACE"), (index, letter) -> {});

        private InPlaceScreen(TerminalView view) {
            super(Component.literal("IN PLACE"));
            this.view = view;
        }

        @Override
        public Typewriter typewriter() {
            return typewriter;
        }

        @Override
        protected void layout() {
            String typed = field == null ? "" : field.getValue();
            field = addRenderableWidget(new CrtTextField(font, 30, 30, width - 60, 14, Component.literal("NAME")));
            field.setValue(typed);
        }

        @Override
        public boolean accepts(TerminalView other) {
            return view.pos().equals(other.pos());
        }

        @Override
        public void update(TerminalView newer) {
            view = newer;
            updates++;
            rebuildWidgets();
        }

        TerminalView view() {
            return view;
        }

        CrtTextField field() {
            return Objects.requireNonNull(field, "<built by layout() in init()>");
        }

        int updates() {
            return updates;
        }
    }
}
