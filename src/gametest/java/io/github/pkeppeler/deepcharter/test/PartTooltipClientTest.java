package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.PartLabel;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/** Client GameTest for #573: the tooltip of a part stack shows its name, then its tier and serial, or says it is no real part. */
public class PartTooltipClientTest implements FabricClientGameTest {
    private static final int LABELLED_SLOT = 0;
    private static final int UNLABELLED_SLOT = 1;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(context, "the player and level", client -> client.player != null && client.level != null);
            singleplayer.getServer().runOnServer(server -> {
                Inventory inventory =
                        server.getPlayerList().getPlayers().getFirst().getInventory();
                inventory.setItem(
                        LABELLED_SLOT,
                        ComponentItems.stackOf(ComponentTrack.HULL, new PartLabel(2, CharterId.random(), "HULL-0007")));
                inventory.setItem(UNLABELLED_SLOT, new ItemStack(ComponentItems.item(ComponentTrack.HULL)));
            });
            ClientWait.until(
                    context,
                    "both parts in the client's inventory",
                    client -> !stackAt(client, LABELLED_SLOT).isEmpty()
                            && !stackAt(client, UNLABELLED_SLOT).isEmpty());
            ItemStack labelled = context.computeOnClient(client -> stackAt(client, LABELLED_SLOT));
            ItemStack unlabelled = context.computeOnClient(client -> stackAt(client, UNLABELLED_SLOT));
            String name = unlabelled.getHoverName().getString();

            require(
                    lines(context, labelled).equals(List.of(name, "Tier 2", "Serial HULL-0007")),
                    "A labelled part should list its name, tier and serial, listed " + lines(context, labelled));
            require(
                    lines(context, unlabelled).equals(List.of(name, "No tier, charter or serial: not a real part")),
                    "A part without a label should say it is not a real part, listed " + lines(context, unlabelled));
        }
    }

    private static ItemStack stackAt(Minecraft client, int slot) {
        return client.player.getInventory().getItem(slot);
    }

    private static List<String> lines(ClientGameTestContext context, ItemStack stack) {
        return context.computeOnClient(client ->
                stack.getTooltipLines(Item.TooltipContext.of(client.level), client.player, TooltipFlag.NORMAL).stream()
                        .map(Component::getString)
                        .toList());
    }
}
