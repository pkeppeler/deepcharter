package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreTuning;
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

    private static final int GUI_WIDTH = 427;
    private static final int INVENTORY_WIDTH = 176;
    private static final int EFFECT_SPACING = 7;
    private static final int EFFECT_TEXT_OFFSET = 32;
    /** Below this much room the inventory draws the compact, icon-only effect list. */
    private static final int EFFECT_PANEL_MIN = 120 - EFFECT_SPACING;

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
                            .equals("Ore/Fuel"),
                    "the status should be called Ore/Fuel, which names both causes (#1280) and fits the inventory list");
            require(
                    context.computeOnClient(client -> Hud.getMobEffectSprite(OreRegistry.OVERBURDENED))
                            .equals(Identifier.fromNamespaceAndPath("deepcharter", "mob_effect/overburdened")),
                    "the HUD and the inventory should draw the status from its own sprite");
            require(
                    context.computeOnClient(client ->
                            client.getResourceManager().getResource(ICON).isPresent()),
                    "the icon texture " + ICON + " should ship in the mod");

            // The inventory clips an effect's name to the panel's width less the icon column and a margin
            // (EffectsInInventory): at the default
            // 427 x 240 GUI the panel starts 2 px right of the inventory, which is 176 wide and centred.
            int panel = GUI_WIDTH - ((GUI_WIDTH - INVENTORY_WIDTH) / 2 + INVENTORY_WIDTH + 2) - EFFECT_SPACING;
            int clip = panel - EFFECT_TEXT_OFFSET - EFFECT_SPACING;
            int capLevel = (int) Math.round(OreTuning.DEFAULT.maxSlowdown()
                    / (OreTuning.DEFAULT.slowdownPerMass() * OreTuning.DEFAULT.massScale()));
            int widest = context.computeOnClient(client -> client.font.width(Component.empty()
                    .append(OreRegistry.OVERBURDENED.value().getDisplayName())
                    .append(" ")
                    .append(Component.translatable("enchantment.level." + capLevel))));
            require(
                    panel >= EFFECT_PANEL_MIN && widest <= clip,
                    "the status at its cap level (" + capLevel + ") is " + widest
                            + " px wide and the inventory clips an effect name at " + clip
                            + " px, so the level would be cut off");

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
                            "Mass 20",
                            "Heavy: it slows you while you carry it.",
                            "Won't stack. Haul it in your pod's cargo bay.")),
                    "an ore tooltip should give its mass, say it is heavy and where to haul it, listed " + lines);
        }
    }
}
