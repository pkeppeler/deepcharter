package io.github.pkeppeler.deepcharter.test.evidence;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.client.pod.PodScreen;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientCursor;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.ContainerScreenFields;
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import io.github.pkeppeler.deepcharter.test.support.SlotClicks;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.UseEntity;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.PartUnits;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Evidence scenario "pod-screen" for #1270: a charter sneaks and uses their Mole, which has a bought tier 2 hull, two ore
 * in the hold and an empty tank. The screen shows the fitted hull with its tier, ghosts on the empty tracks, the fuel
 * gauge and the hold. A cargo bay unit dragged onto its slot is fitted and the hold grows at once; a hull unit dragged onto
 * the fitted hull is refused with the reason; coal shift-clicked into the fuel slot feeds the tank as the gauge rises;
 * and dirt dragged into the hold lands where it is dropped. Stills of each step.
 */
public class PodScreenScenario extends EvidenceScenario {
    private static final int TICKS_PER_FRAME = 3;
    private static final int TYPING_FRAMES = 60;
    private static final int HOLD_FRAMES = 8;
    /** Frames a dragged item takes to cross from one slot to another. */
    private static final int DRAG_FRAMES = 10;
    /** Frames the gauge is watched while the slot feeds the tank. */
    private static final int FEED_FRAMES = 12;
    /** Where the cursor waits: off the window, so no slot shows a tooltip. */
    private static final double OFF_SCREEN = -10_000;

    private static final Item CARGO_UNIT = PartUnits.unit(ComponentTrack.CARGO_BAY);
    private static final Item HULL_UNIT = PartUnits.unit(ComponentTrack.HULL);

    @Override
    protected String name() {
        return "pod-screen";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                if (Charters.found(server, player.getUUID(), "Riggs and Sons").isPresent()) {
                    throw new AssertionError("founding should succeed");
                }
                CharterId charter = Charters.charterOfOrThrow(server, player.getUUID())
                        .orElseThrow()
                        .id();
                PodEntity pod = TestEntities.create(PodRegistry.POD, player.level());
                pod.setPos(player.position().add(2, 0, 0));
                player.level().addFreshEntity(pod);
                PodComponents.register(pod, charter);
                PodComponents.installAndDropReplaced(pod, ComponentItems.mint(server, ComponentTrack.HULL, 2, charter));
                pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.IRONIUM));
                pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.GOLDIUM));
                pod.setFuel(0f);
                player.getInventory().add(new ItemStack(CARGO_UNIT));
                player.getInventory().add(new ItemStack(HULL_UNIT));
                player.getInventory().add(new ItemStack(Items.COAL, 16));
                player.getInventory().add(new ItemStack(Items.DIRT, 32));
                player.setShiftKeyDown(true);
                UseEntity.on(player, player.level(), InteractionHand.MAIN_HAND, pod);
                player.setShiftKeyDown(false);
            });
            ClientWait.screen(context, PodScreen.class);
            PodScreen screen = context.computeOnClient(client -> ClientSession.screen(client, PodScreen.class));
            context.getInput().setCursorPos(OFF_SCREEN, OFF_SCREEN); // no slot hovered, so no tooltip
            for (int i = 0; i < TYPING_FRAMES && !screen.typewriter().done(); i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            hold(context);
            screenshot(context, "screen-open");

            drag(
                    context,
                    screen,
                    SlotClicks.inventorySlot(context, screen, CARGO_UNIT),
                    screen.getMenu().partSlot(ComponentTrack.CARGO_BAY));
            ClientWait.until(
                    context,
                    "the bigger hold on screen",
                    client -> screen.getMenu().capacity() > 7);
            context.getInput().setCursorPos(OFF_SCREEN, OFF_SCREEN);
            hold(context);
            screenshot(context, "cargo-bay-fitted");

            drag(
                    context,
                    screen,
                    SlotClicks.inventorySlot(context, screen, HULL_UNIT),
                    screen.getMenu().partSlot(ComponentTrack.HULL));
            ClientWait.until(
                    context,
                    "the refusal on screen",
                    client -> screen.getMenu().refusal().isPresent());
            hold(context);
            screenshot(context, "hull-unit-refused");
            SlotClicks.click(context, screen, SlotClicks.inventorySlot(context, screen, Items.AIR));

            Slot coal = SlotClicks.inventorySlot(context, screen, Items.COAL);
            SlotClicks.hover(context, screen, coal);
            hold(context);
            SlotClicks.shiftClick(context, screen, coal);
            context.getInput().setCursorPos(OFF_SCREEN, OFF_SCREEN);
            for (int i = 0; i < FEED_FRAMES; i++) {
                context.waitTicks(TICKS_PER_FRAME);
                frame(context);
            }
            screenshot(context, "fuel-fed");

            drag(
                    context,
                    screen,
                    SlotClicks.inventorySlot(context, screen, Items.DIRT),
                    screen.getMenu().holdSlot(5));
            context.getInput().setCursorPos(OFF_SCREEN, OFF_SCREEN);
            hold(context);
            screenshot(context, "dirt-stowed");
            context.setScreen(() -> null);
        }
    }

    /** Picks up what {@code from} holds, carries it across in frames, and drops it on {@code to}. */
    private void drag(ClientGameTestContext context, PodScreen screen, Slot from, Slot to) {
        SlotClicks.click(context, screen, from);
        int left = ContainerScreenFields.intField(screen, "leftPos");
        int top = ContainerScreenFields.intField(screen, "topPos");
        for (int i = 1; i <= DRAG_FRAMES; i++) {
            double share = (double) i / DRAG_FRAMES;
            ClientCursor.moveTo(
                    context, left + from.x + 8 + (to.x - from.x) * share, top + from.y + 8 + (to.y - from.y) * share);
            frame(context);
        }
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
    }

    private void hold(ClientGameTestContext context) {
        for (int i = 0; i < HOLD_FRAMES; i++) {
            context.waitTicks(TICKS_PER_FRAME);
            frame(context);
        }
    }
}
