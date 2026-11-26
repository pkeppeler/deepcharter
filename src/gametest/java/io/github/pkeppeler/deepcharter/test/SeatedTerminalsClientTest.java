package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.fuel.FuelPumpScreen;
import io.github.pkeppeler.deepcharter.client.market.OreProcessorScreen;
import io.github.pkeppeler.deepcharter.client.pod.PodChase;
import io.github.pkeppeler.deepcharter.handbook.HandbookItems;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalBlock;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Client GameTest for #1447: seated in a parked Mole with the chase camera on, a new player's use key (the right
 * mouse button, through the window's own handler), with the handbook they are given in hand, opens the terminal they
 * look toward, which stands past on-foot reach. The use first lands on the pilot's own pod, as it did before #1447,
 * when it opened nothing. Escape closes the screen and the pilot stays seated. With fuel in the off hand the use
 * refuels the pod instead, as before.
 */
public class SeatedTerminalsClientTest implements FabricClientGameTest {
    /** Blocks from the pod's middle to each terminal's middle, east and west: in reach of its box, not the eyes. */
    private static final int TERMINAL_OFFSET = 6;
    /** Blocks of open stone pad round the pod. */
    private static final int PAD = 8;

    private static final int HEADROOM = 4;
    /** A hotbar slot a new player has nothing in. */
    private static final int EMPTY_SLOT = 8;

    /** Dollars the charter starts with. */
    public static final long START_BALANCE = 20;

    /** Marks the test's pod, which burns no fuel while the test waits. */
    private static final String SEATED = "seated-terminals-pod";

    static {
        PodEvents.IS_POWERED.register(pod -> !pod.entityTags().contains(SEATED));
    }

    /** The scene: the pump east of the pod, the processor west of it. */
    public record Scene(BlockPos pump, BlockPos processor, CharterId charter) {}

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        PodChase.setAutomatic(true);
        CameraType original = context.computeOnClient(client -> client.options.getCameraType());
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            Scene scene = singleplayer.getServer().computeOnServer(SeatedTerminalsClientTest::setUp);
            board(context, singleplayer, scene);
            require(
                    context.computeOnClient(client -> client.options.getCameraType()) == CameraType.THIRD_PERSON_BACK,
                    "the pilot seat should put the chase camera on");
            require(
                    context.computeOnClient(SeatedTerminalsClientTest::picksOwnPod),
                    "a seated pilot's use lands on their own pod: that is why a click never reached a terminal");

            String hands = context.computeOnClient(
                    client -> ClientSession.player(client).getMainHandItem() + " / "
                            + ClientSession.player(client).getOffhandItem());
            require(
                    context.computeOnClient(client -> HandbookItems.isHandbook(
                                    ClientSession.player(client).getMainHandItem())
                            && ClientSession.player(client).getOffhandItem().isEmpty()),
                    "the pilot holds the handbook they were given and nothing in the off hand, holds " + hands);
            useFacing(context, scene.pump(), FuelPumpScreen.class);
            RealInput.key(context, InputConstants.KEY_ESCAPE, 0);
            ClientWait.until(context, "the pump screen closed", client -> client.gui.screen() == null);
            context.waitTicks(2);
            require(
                    context.computeOnClient(
                            client -> ClientSession.player(client).getVehicle() instanceof PodEntity),
                    "closing the screen should leave the pilot seated on the client");
            require(
                    singleplayer
                            .getServer()
                            .computeOnServer(server -> realPlayer(server).getVehicle() instanceof PodEntity),
                    "closing the screen should leave the pilot seated on the server");

            useFacing(context, scene.processor(), OreProcessorScreen.class);
            context.setScreen(() -> null);
            ClientWait.until(context, "the processor screen closed", client -> client.gui.screen() == null);

            // With the main hand empty, fuel in the off hand keeps its use on the pod: the use refuels it and opens
            // nothing. (With the handbook in the main hand, the handbook's own use comes first, as before #1447.)
            context.runOnClient(
                    client -> ClientSession.player(client).getInventory().setSelectedSlot(EMPTY_SLOT));
            float before = singleplayer
                    .getServer()
                    .computeOnServer(server -> pod(server).fuel());
            singleplayer
                    .getServer()
                    .runOnServer(server ->
                            realPlayer(server).setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.COAL)));
            ClientWait.until(
                    context,
                    "the coal in the client's off hand",
                    client -> ClientSession.player(client).getOffhandItem().is(Items.COAL));
            RealInput.click(context, InputConstants.MOUSE_BUTTON_RIGHT);
            ClientWait.until(
                    context,
                    "the pod refuelled from the off hand",
                    client -> TestEntities.vehicle(ClientSession.player(client), PodEntity.class)
                                    .fuel()
                            > before,
                    client -> "client fuel "
                            + TestEntities.vehicle(ClientSession.player(client), PodEntity.class)
                                    .fuel());
            require(
                    context.computeOnClient(client -> client.gui.screen() == null),
                    "a use with fuel in the off hand should open no terminal");
        } finally {
            context.runOnClient(client -> client.options.setCameraType(original));
        }
    }

    /**
     * A repaired pump and ore processor on a stone pad, {@link #TERMINAL_OFFSET} blocks east and west of a Mole of the
     * player's new charter with {@link #START_BALANCE} dollars; the Mole has a fifth of a tank and three ores of cargo.
     * Public for the evidence scenario.
     */
    public static Scene setUp(MinecraftServer server) {
        ServerPlayer player = realPlayer(server);
        ServerLevel level = server.overworld();
        require(Charters.found(server, player.getUUID(), "Seated Charter").isEmpty(), "founding the charter");
        CharterId charter = Charters.charterOfOrThrow(server, player.getUUID())
                .orElseThrow()
                .id();
        require(Charters.deposit(server, charter, START_BALANCE).isEmpty(), "the deposit");
        RepairState repairs = RepairState.get(server);
        for (TerminalType type : List.of(TerminalTypes.FUEL_PUMP, TerminalTypes.ORE_PROCESSOR)) {
            type.parts().forEach(part -> repairs.insert(type, part));
        }
        BlockPos base = player.blockPosition();
        for (int x = -PAD; x <= PAD; x++) {
            for (int z = -PAD; z <= PAD; z++) {
                level.setBlock(base.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 3);
                for (int y = 0; y < HEADROOM; y++) {
                    level.setBlock(base.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        BlockPos pump = base.relative(Direction.EAST, TERMINAL_OFFSET);
        BlockPos processor = base.relative(Direction.WEST, TERMINAL_OFFSET);
        TerminalBlock.place(level, pump, TerminalTypes.FUEL_PUMP.block().defaultBlockState(), 3);
        TerminalBlock.place(
                level, processor, TerminalTypes.ORE_PROCESSOR.block().defaultBlockState(), 3);
        PodEntity pod = TestEntities.create(PodRegistry.POD, level);
        pod.setPos(Vec3.atBottomCenterOf(base));
        pod.addTag(SEATED);
        level.addFreshEntity(pod);
        PodComponents.register(pod, charter);
        pod.setFuel(20f);
        for (OreType ore : List.of(OreType.IRONIUM, OreType.BRONZIUM, OreType.GOLDIUM)) {
            pod.cargo().tryAdd(pod, OreRegistry.stack(ore));
        }
        return new Scene(pump, processor, charter);
    }

    /** Seats the player in the scene's pod, holding the handbook they were given, and waits until the client has them seated. */
    public static void board(ClientGameTestContext context, TestSingleplayerContext singleplayer, Scene scene) {
        BlockPos middle = scene.pump().relative(Direction.WEST, TERMINAL_OFFSET);
        ClientWait.chunksAround(context, middle.getX(), middle.getZ(), PAD + 1);
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = realPlayer(server);
            require(player.startRiding(pod(server), true, false), "the player should board the pod");
        });
        ClientWait.until(
                context,
                "the client seated in the pod",
                client -> ClientSession.player(client).getVehicle() instanceof PodEntity);
        // A new player's first slot holds the starting rations, which eat; the handbook is beside them.
        ClientWait.until(
                context,
                "the handbook in the client's hotbar",
                client -> HandbookScreenClientTest.handbookSlot(
                                ClientSession.player(client).getInventory())
                        >= 0);
        context.runOnClient(client -> ClientSession.player(client)
                .getInventory()
                .setSelectedSlot(HandbookScreenClientTest.handbookSlot(
                        ClientSession.player(client).getInventory())));
        context.waitTicks(5);
    }

    /**
     * Turns the pilot to face the terminal at {@code pos}, presses the use button through the window's mouse handler
     * and waits for {@code screen}. Public for the evidence scenario.
     */
    public static void useFacing(ClientGameTestContext context, BlockPos pos, Class<? extends Screen> screen) {
        context.runOnClient(
                client -> ClientSession.player(client).lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos)));
        // The client sends its look with its next movement packet.
        context.waitTicks(2);
        RealInput.click(context, InputConstants.MOUSE_BUTTON_RIGHT);
        ClientWait.screen(context, screen);
    }

    private static boolean picksOwnPod(Minecraft client) {
        return client.hitResult instanceof EntityHitResult hit
                && hit.getEntity() == ClientSession.player(client).getVehicle();
    }

    private static PodEntity pod(MinecraftServer server) {
        return server.overworld()
                .getEntities(PodRegistry.POD, entity -> entity.entityTags().contains(SEATED))
                .getFirst();
    }

    private static ServerPlayer realPlayer(MinecraftServer server) {
        return server.getPlayerList().getPlayers().getFirst();
    }
}
