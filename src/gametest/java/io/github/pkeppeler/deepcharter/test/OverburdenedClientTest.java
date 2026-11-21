package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * Client GameTest for #853: carried ore reaches the client as an Overburdened status with its own icon and name, and an ore stack's
 * tooltip says it is heavy, in the language file's words.
 */
public class OverburdenedClientTest implements FabricClientGameTest {
    private static final Identifier ICON =
            Identifier.fromNamespaceAndPath("deepcharter", "textures/mob_effect/overburdened.png");

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(context, "the player and level", client -> client.player != null && client.level != null);
            singleplayer
                    .getServer()
                    .runOnServer(server -> server.getPlayerList()
                            .getPlayers()
                            .getFirst()
                            .getInventory()
                            .setItem(0, OreRegistry.stack(OreType.EINSTEINIUM)));
            ClientWait.until(
                    context,
                    "the Overburdened status on the client",
                    client -> ClientSession.player(client).hasEffect(OreRegistry.OVERBURDENED));

            MobEffectInstance shown = context.computeOnClient(
                    client -> ClientSession.player(client).getEffect(OreRegistry.OVERBURDENED));
            require(
                    shown != null && shown.getAmplifier() == 3,
                    "Einsteinium should show level IV (amplifier 3), got " + shown);
            require(
                    context.computeOnClient(client -> OreRegistry.OVERBURDENED
                                    .value()
                                    .getDisplayName()
                                    .getString())
                            .equals("Ore overload"),
                    "the status should be called Ore overload, which names the cause and fits the inventory list");
            require(
                    context.computeOnClient(client -> Hud.getMobEffectSprite(OreRegistry.OVERBURDENED))
                            .equals(Identifier.fromNamespaceAndPath("deepcharter", "mob_effect/overburdened")),
                    "the HUD and the inventory should draw the status from its own sprite");
            require(
                    context.computeOnClient(client ->
                            client.getResourceManager().getResource(ICON).isPresent()),
                    "the icon texture " + ICON + " should ship in the mod");

            ItemStack ore = OreRegistry.stack(OreType.EINSTEINIUM);
            List<String> lines = context.computeOnClient(client ->
                    ore
                            .getTooltipLines(Item.TooltipContext.of(client.level), client.player, TooltipFlag.NORMAL)
                            .stream()
                            .map(Component::getString)
                            .toList());
            require(
                    lines.equals(List.of(
                            ore.getHoverName().getString(),
                            "Heavy: it slows you while you carry it.",
                            "Won't stack. Haul it in your pod's cargo bay.")),
                    "an ore tooltip should say it is heavy and where to haul it, listed " + lines);
        }
    }
}
