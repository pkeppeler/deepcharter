package io.github.pkeppeler.deepcharter.client.outpost;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.theme.Colors;
import io.github.pkeppeler.deepcharter.client.theme.ReadoutLook;
import io.github.pkeppeler.deepcharter.outpost.Placements;
import io.github.pkeppeler.deepcharter.outpost.Placements.Readout;
import io.github.pkeppeler.deepcharter.outpost.Placements.Verdict;
import java.util.Optional;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * The placement readout (#989, ADR 0072): while the player holds a block and aims at a block, it says under the crosshair whether that block
 * would hold where it would be placed, HOLDS HERE, SHELTERED or WILL NOT HOLD UNATTENDED, and for the last why. What it says is
 * {@link Placements}'; it sits two lines under the block readout of the aimed block ({@code BlockReadoutHud}), in that readout's colours and
 * scale ({@code theme/readout.json}), and shows at once. Nothing shows while the HUD is hidden (F1), a screen is open or the player spectates.
 */
public final class PlacementReadoutHud {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "placement_readout");
    /** The block readout takes the first two lines under the crosshair (a name and a hint). */
    private static final int LINES_BELOW = 2;

    private PlacementReadoutHud() {}

    static void init() {
        HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, ID, PlacementReadoutHud::extract);
    }

    /** What the block in hand would read at the aimed spot: empty with no block in hand, no aimed block, no world, in a screen or with the HUD hidden. */
    public static Optional<Readout> looked(Minecraft client) {
        if (client.player == null
                || client.level == null
                || client.player.isSpectator()
                || client.gui.screen() != null
                || client.gui.hud.isHidden()
                || !(client.hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) {
            return Optional.empty();
        }
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = client.player.getItemInHand(hand);
            if (stack.getItem() instanceof BlockItem item) {
                BlockPos at = new BlockPlaceContext(client.player, hand, stack, hit).getClickedPos();
                return Optional.of(Placements.SHARED.readout(
                        client.level, at, item.getBlock().defaultBlockState()));
            }
        }
        return Optional.empty();
    }

    private static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        Optional<Readout> shown = looked(client);
        if (shown.isEmpty()) {
            return;
        }
        ReadoutLook look = ReadoutLook.current();
        Font font = client.font;
        int line = font.lineHeight + look.lineGap();
        graphics.pose().pushMatrix();
        graphics.pose().translate(graphics.guiWidth() / 2f, graphics.guiHeight() / 2f + look.offsetY());
        graphics.pose().scale((float) look.scale(), (float) look.scale());
        int verdictColor = shown.get().verdict() == Verdict.WILL_NOT_HOLD ? look.hintColor() : look.nameColor();
        centred(graphics, font, shown.get().verdict().label(), LINES_BELOW * line, verdictColor);
        shown.get()
                .reason()
                .ifPresent(reason -> centred(graphics, font, reason, (LINES_BELOW + 1) * line, look.hintColor()));
        graphics.pose().popMatrix();
    }

    private static void centred(GuiGraphicsExtractor graphics, Font font, Component text, int y, int color) {
        graphics.text(font, text, -font.width(text) / 2, y, Colors.withAlpha(color, 255), true);
    }
}
