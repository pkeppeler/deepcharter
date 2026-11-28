package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.pod.PodScreen;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLining;
import io.github.pkeppeler.deepcharter.pod.PodLiningTuning;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import io.github.pkeppeler.deepcharter.test.support.SlotClicks;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.PartUnits;
import java.util.UUID;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Client GameTest for #1270: the pod screen, driven with the real keyboard and cursor. The pilot presses the pod screen
 * key in the seat; the screen shows the hold's ore and mass; a shift-clicked drill unit is fitted and shows its tier; a
 * hull unit dropped on the scanner slot is refused on screen; dirt dropped on a hold slot lands in it and shift-clicks
 * back; shift-clicked coal fills the fuel slot and the gauge rises as it feeds the tank; the readouts show the brick
 * rack (#1649); torches move into and out of the offhand slot by click, shift-click and the swap-hands key (#1670); and
 * Escape closes the screen.
 */
public class PodScreenClientTest implements FabricClientGameTest {
    /** A goldium (10) and an einsteinium (20) in the hold, in tenths. */
    private static final int GOLDIUM_AND_EINSTEINIUM_TENTHS = 300;

    /** Bricks in the pod's rack, which the screen shows against the rack's size (#1649). */
    private static final int RACKED = 5;
    /** Torches the test moves through the offhand (#1670). */
    private static final int TORCHES = 4;

    private static final Item DRILL_UNIT = PartUnits.unit(ComponentTrack.DRILL);
    private static final Item HULL_UNIT = PartUnits.unit(ComponentTrack.HULL);

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            UUID pod = singleplayer.getServer().computeOnServer(PodScreenClientTest::setUp);
            int[] at = singleplayer.getServer().computeOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                return new int[] {player.getBlockX(), player.getBlockZ()};
            });
            ClientWait.chunksAround(context, at[0], at[1], 4);
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                PodEntity entity = (PodEntity) player.level().getEntity(pod);
                require(entity != null && player.startRiding(entity, true, false), "the pilot should board");
            });
            ClientWait.until(
                    context,
                    "the pilot in the pod on the client",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity);

            context.getInput().pressKey(options -> options.keyInventory);
            ClientWait.screen(context, PodScreen.class);
            PodScreen screen = context.computeOnClient(client -> ClientSession.screen(client, PodScreen.class));
            ClientWait.until(
                    context,
                    "the hold's two ore and their mass",
                    client -> screen.getMenu().holdSlot(0).getItem().is(OreRegistry.item(OreType.GOLDIUM))
                            && screen.getMenu().holdSlot(1).getItem().is(OreRegistry.item(OreType.EINSTEINIUM))
                            && screen.getMenu().massTenths() == GOLDIUM_AND_EINSTEINIUM_TENTHS,
                    client -> "hold " + screen.getMenu().holdSlot(0).getItem() + ", "
                            + screen.getMenu().holdSlot(1).getItem() + ", mass "
                            + screen.getMenu().massTenths());

            fitsAUnit(context, singleplayer, screen);
            refusesAWrongUnit(context, singleplayer, screen);
            movesDirtThroughTheHold(context, singleplayer, screen, pod);
            feedsCoal(context, screen);
            showsTheRack(context, screen);
            movesThroughTheOffhand(context, singleplayer, screen);
            context.takeScreenshot("pod-screen-client");

            RealInput.key(context, InputConstants.KEY_ESCAPE, 0);
            ClientWait.until(
                    context,
                    "the screen closed and the pack menu back",
                    client -> client.gui.screen() == null
                            && ClientSession.player(client).containerMenu
                                    == ClientSession.player(client).inventoryMenu);
            singleplayer
                    .getServer()
                    .runOnServer(server ->
                            server.getPlayerList().getPlayers().getFirst().stopRiding());
        }
    }

    /**
     * The player founds a charter, a pod of theirs with two ore and an empty tank stands beside them, and they carry
     * what the test moves.
     */
    private static UUID setUp(MinecraftServer server) {
        ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
        require(Charters.found(server, player.getUUID(), "Pod Screen Charter").isEmpty(), "founding should succeed");
        CharterId charter = Charters.charterOfOrThrow(server, player.getUUID())
                .orElseThrow()
                .id();
        PodEntity pod = TestEntities.create(PodRegistry.POD, player.level());
        pod.setPos(player.position().add(2, 0, 0));
        player.level().addFreshEntity(pod);
        PodComponents.register(pod, charter);
        pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.GOLDIUM));
        pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.EINSTEINIUM));
        pod.setFuel(0f);
        player.getInventory().add(new ItemStack(DRILL_UNIT));
        player.getInventory().add(new ItemStack(HULL_UNIT));
        player.getInventory().add(new ItemStack(Items.DIRT, 20));
        player.getInventory().add(new ItemStack(Items.COAL, 16));
        player.getInventory().add(new ItemStack(Items.TORCH, TORCHES));
        PodLining.modify(pod, state -> state.fused(0, RACKED));
        return pod.getUUID();
    }

    private static PodEntity pod(MinecraftServer server) {
        return TestEntities.vehicle(server.getPlayerList().getPlayers().getFirst(), PodEntity.class);
    }

    private static void fitsAUnit(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, PodScreen screen) {
        SlotClicks.shiftClick(context, screen, SlotClicks.inventorySlot(context, screen, DRILL_UNIT));
        ClientWait.until(
                context,
                "the drill fitted on the server and shown in its slot",
                () -> singleplayer
                                .getServer()
                                .computeOnServer(server -> PodComponents.partOf(pod(server), ComponentTrack.DRILL)
                                        .isPresent())
                        && context.computeOnClient(client -> ComponentItems.labelOf(screen.getMenu()
                                        .partSlot(ComponentTrack.DRILL)
                                        .getItem())
                                .isPresent()),
                () -> context.computeOnClient(client -> "drill slot "
                        + screen.getMenu().partSlot(ComponentTrack.DRILL).getItem()));
    }

    private static void refusesAWrongUnit(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, PodScreen screen) {
        SlotClicks.click(context, screen, SlotClicks.inventorySlot(context, screen, HULL_UNIT));
        SlotClicks.click(context, screen, screen.getMenu().partSlot(ComponentTrack.SCANNER));
        ClientWait.until(
                context,
                "the refusal on screen",
                client -> screen.getMenu().refusal().isPresent(),
                client -> "refusal " + screen.getMenu().refusal());
        require(
                singleplayer
                        .getServer()
                        .computeOnServer(server -> PodComponents.partOf(pod(server), ComponentTrack.SCANNER)
                                .isEmpty()),
                "the server fitted nothing on the scanner track");
        SlotClicks.click(context, screen, SlotClicks.inventorySlot(context, screen, Items.AIR));
    }

    private static void movesDirtThroughTheHold(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, PodScreen screen, UUID pod) {
        SlotClicks.click(context, screen, SlotClicks.inventorySlot(context, screen, Items.DIRT));
        SlotClicks.click(context, screen, screen.getMenu().holdSlot(4));
        ClientWait.until(
                context,
                "the dirt in hold slot 4 on the server",
                () -> singleplayer
                        .getServer()
                        .computeOnServer(
                                server -> pod(server).cargo().stackAt(4).getCount() == 20),
                () -> "slot 4 "
                        + singleplayer
                                .getServer()
                                .computeOnServer(server -> pod(server).cargo().stackAt(4)));
        SlotClicks.shiftClick(context, screen, screen.getMenu().holdSlot(4));
        ClientWait.until(
                context,
                "the dirt back in the pack",
                () -> singleplayer
                                .getServer()
                                .computeOnServer(
                                        server -> pod(server).cargo().stackAt(4).isEmpty())
                        && context.computeOnClient(client ->
                                ClientSession.player(client).getInventory().countItem(Items.DIRT) == 20),
                () -> "pod " + pod + " slot 4 "
                        + singleplayer
                                .getServer()
                                .computeOnServer(server -> pod(server).cargo().stackAt(4)));
    }

    private static void feedsCoal(ClientGameTestContext context, PodScreen screen) {
        SlotClicks.shiftClick(context, screen, SlotClicks.inventorySlot(context, screen, Items.COAL));
        ClientWait.until(
                context,
                "coal in the fuel slot and the gauge rising",
                client -> screen.getMenu().fuelSlot().getItem().is(Items.COAL)
                        && screen.getMenu().fuelTenths() > 0,
                client -> "fuel slot " + screen.getMenu().fuelSlot().getItem() + ", gauge "
                        + screen.getMenu().fuelTenths() + "/" + screen.getMenu().tankTenths());
    }

    /** #1649: the readouts name the brick rack and its size, so a fused brick has a place on screen. */
    private static void showsTheRack(ClientGameTestContext context, PodScreen screen) {
        String rack = "SLAG RACK " + RACKED + "/" + PodLiningTuning.DEFAULT.brickCapacity();
        ClientWait.until(
                context,
                "the rack line '" + rack + "' and the hopper line",
                client -> screen.readouts().contains(rack)
                        && screen.readouts().contains("NO HOPPER"),
                client -> "readouts " + screen.readouts());
    }

    /**
     * #1670: the torches go into the offhand by a click, come back to the pack by a shift-click, and go in again by the
     * swap-hands key over their pack slot, all with the real cursor and keyboard.
     */
    private static void movesThroughTheOffhand(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, PodScreen screen) {
        Slot offhand = context.computeOnClient(client -> screen.getMenu().offhandSlot());
        SlotClicks.click(context, screen, SlotClicks.inventorySlot(context, screen, Items.TORCH));
        SlotClicks.click(context, screen, offhand);
        awaitOffhand(context, singleplayer, TORCHES, "a click puts the torches in the offhand");
        SlotClicks.shiftClick(context, screen, offhand);
        awaitOffhand(context, singleplayer, 0, "a shift-click sends them back to the pack");
        SlotClicks.hover(context, screen, SlotClicks.inventorySlot(context, screen, Items.TORCH));
        context.getInput().pressKey(options -> options.keySwapOffhand);
        awaitOffhand(context, singleplayer, TORCHES, "the swap-hands key over their slot moves them to the offhand");
    }

    /** Waits for the player's offhand to hold {@code torches} torches on the server and in the screen's slot. */
    private static void awaitOffhand(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, int torches, String what) {
        ClientWait.until(
                context,
                what,
                () -> singleplayer.getServer().computeOnServer(server -> torchesIn(server.getPlayerList()
                                        .getPlayers()
                                        .getFirst()
                                        .getOffhandItem()))
                                == torches
                        && context.computeOnClient(client -> torchesIn(ClientSession.player(client)
                                        .getOffhandItem()))
                                == torches,
                () -> "offhand on the server "
                        + singleplayer.getServer().computeOnServer(server -> server.getPlayerList()
                                .getPlayers()
                                .getFirst()
                                .getOffhandItem()));
    }

    private static int torchesIn(ItemStack stack) {
        return stack.is(Items.TORCH) ? stack.getCount() : 0;
    }
}
