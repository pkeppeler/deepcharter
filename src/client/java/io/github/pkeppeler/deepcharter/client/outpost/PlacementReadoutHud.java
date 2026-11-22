package io.github.pkeppeler.deepcharter.client.outpost;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.layer.ReadoutFade;
import io.github.pkeppeler.deepcharter.client.theme.Colors;
import io.github.pkeppeler.deepcharter.client.theme.ReadoutLook;
import io.github.pkeppeler.deepcharter.outpost.Placements;
import io.github.pkeppeler.deepcharter.outpost.Placements.Readout;
import io.github.pkeppeler.deepcharter.outpost.Placements.Verdict;
import java.util.Optional;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
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
 * would hold where it would be placed, HOLDS HERE or WILL NOT HOLD UNATTENDED, and for the last why. What it says is
 * {@link Placements}'; it sits two lines under the block readout of the aimed block ({@code BlockReadoutHud}), in that readout's colours and
 * scale ({@code theme/readout.json}), and fades in and out as the block readout does ({@link ReadoutFade}). Nothing shows where the depth asks nothing (the surface, and for now layers 1 and 2), and none shows while the HUD is hidden (F1), a screen is open or the player spectates.
 */
public final class PlacementReadoutHud {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "placement_readout");
    private static final ReadoutFade<Readout> FADE = new ReadoutFade<>();
    /** Vanilla text with an alpha of 3 or less is drawn opaque, so a fade ends one step above it. */
    private static final int MIN_ALPHA = 4;
    /** The block readout takes the first two lines under the crosshair (a name and a hint). */
    private static final int LINES_BELOW = 2;

    private PlacementReadoutHud() {}

    static void init() {
        HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, ID, PlacementReadoutHud::extract);
        ClientTickEvents.END_CLIENT_TICK.register(PlacementReadoutHud::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> FADE.reset());
    }

    /** What the block in hand would read at the aimed spot: empty with no block in hand, no aimed block, no world, in a screen, with the HUD hidden or where the depth asks nothing. */
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
                return Placements.readout(client.level, at, item.getBlock().defaultBlockState());
            }
        }
        return Optional.empty();
    }

    /** The readout on screen, whatever its opacity: what a client test reads instead of pixels. */
    public static Optional<Readout> shown() {
        return FADE.shown();
    }

    private static void tick(Minecraft client) {
        if (client.player == null || client.level == null) {
            FADE.reset();
            return;
        }
        FADE.tick(looked(client), ReadoutLook.current());
    }

    private static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        Optional<Readout> shown = shown();
        if (shown.isEmpty() || client.gui.screen() != null || client.gui.hud.isHidden()) {
            return;
        }
        int alpha = Math.round(FADE.alpha(deltaTracker.getGameTimeDeltaPartialTick(false)) * 255);
        if (alpha < MIN_ALPHA) {
            return;
        }
        ReadoutLook look = ReadoutLook.current();
        Font font = client.font;
        int line = font.lineHeight + look.lineGap();
        graphics.pose().pushMatrix();
        graphics.pose().translate(graphics.guiWidth() / 2f, graphics.guiHeight() / 2f + look.offsetY());
        graphics.pose().scale((float) look.scale(), (float) look.scale());
        int verdictColor = shown.get().verdict() == Verdict.WILL_NOT_HOLD ? look.hintColor() : look.nameColor();
        centred(
                graphics,
                font,
                shown.get().verdict().label(),
                LINES_BELOW * line,
                Colors.withAlpha(verdictColor, alpha));
        shown.get()
                .reason()
                .ifPresent(reason -> centred(
                        graphics, font, reason, (LINES_BELOW + 1) * line, Colors.withAlpha(look.hintColor(), alpha)));
        graphics.pose().popMatrix();
    }

    private static void centred(GuiGraphicsExtractor graphics, Font font, Component text, int y, int color) {
        graphics.text(font, text, -font.width(text) / 2, y, color, true);
    }
}
