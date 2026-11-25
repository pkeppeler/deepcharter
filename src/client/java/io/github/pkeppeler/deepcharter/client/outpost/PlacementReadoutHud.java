package io.github.pkeppeler.deepcharter.client.outpost;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.client.layer.ReadoutFade;
import io.github.pkeppeler.deepcharter.client.theme.Colors;
import io.github.pkeppeler.deepcharter.client.theme.ReadoutLook;
import io.github.pkeppeler.deepcharter.client.ui.HudKeepClear;
import io.github.pkeppeler.deepcharter.outpost.Placements;
import io.github.pkeppeler.deepcharter.outpost.Placements.Readout;
import io.github.pkeppeler.deepcharter.outpost.Placements.Verdict;
import java.util.List;
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
 * The placement readout (#989, ADR 0072): while the player holds a block and aims at a block, it says above the hotbar
 * whether that block would hold where it would be placed, HOLDS HERE or WILL NOT HOLD UNATTENDED, and for the last why.
 * What it says is {@link Placements}'; it stands above the block readout of the aimed block ({@code BlockReadoutHud}),
 * over the room of its two lines, in that readout's colours and scale ({@code theme/readout.json}), and fades in and
 * out as the block readout does ({@link ReadoutFade}). Nothing shows where the depth asks nothing (the surface, and for
 * now layers 1 and 2), and none shows while the HUD is hidden (F1), a screen is open or the player spectates.
 */
public final class PlacementReadoutHud {
    private static final Identifier ID = Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "placement_readout");
    private static final ReadoutFade<Readout> FADE = new ReadoutFade<>();
    /** Vanilla text with an alpha of 3 or less is drawn opaque, so a fade ends one step above it. */
    private static final int MIN_ALPHA = 4;

    private PlacementReadoutHud() {}

    static void init() {
        HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, ID, PlacementReadoutHud::extract);
        ClientTickEvents.END_CLIENT_TICK.register(PlacementReadoutHud::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> FADE.reset());
        HudKeepClear.register(PlacementReadoutHud::keepClear);
    }

    /**
     * The box of the lines on a GUI {@code guiWidth} x {@code guiHeight}, as the scale puts them: as wide as the wider
     * line, the verdict and the reason under it, growing upward from {@link
     * ReadoutLook#placementTop}. What is drawn and what is kept clear are this one box.
     */
    private static HudKeepClear.Rect box(Font font, Readout readout, int guiWidth, int guiHeight) {
        ReadoutLook look = ReadoutLook.current();
        int line = font.lineHeight + look.lineGap();
        int widest = widest(font, readout);
        int height = font.lineHeight + (readout.reason().isPresent() ? line : 0);
        int width = (int) Math.ceil(widest * (float) look.scale());
        int boxHeight = (int) Math.ceil(height * (float) look.scale());
        return new HudKeepClear.Rect(
                "placement readout",
                (guiWidth - width) / 2,
                look.placementTop(guiHeight, font.lineHeight, boxHeight),
                width,
                boxHeight);
    }

    /** The width, before the scale, of the wider of the two lines. */
    private static int widest(Font font, Readout readout) {
        int widest = font.width(readout.verdict().label());
        return readout.reason()
                .map(reason -> Math.max(widest, font.width(reason)))
                .orElse(widest);
    }

    private static void keepClear(Minecraft client, int guiWidth, int guiHeight, List<HudKeepClear.Rect> into) {
        if (client.gui.screen() == null && !client.gui.hud.isHidden()) {
            shown().ifPresent(readout -> into.add(box(client.font, readout, guiWidth, guiHeight)));
        }
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

    public static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
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
        HudKeepClear.Rect box = box(font, shown.get(), graphics.guiWidth(), graphics.guiHeight());
        graphics.pose().pushMatrix();
        graphics.pose().translate(box.x(), box.y());
        graphics.pose().scale((float) look.scale(), (float) look.scale());
        int widest = widest(font, shown.get());
        int verdictColor = shown.get().verdict() == Verdict.WILL_NOT_HOLD ? look.hintColor() : look.nameColor();
        centred(graphics, font, widest, shown.get().verdict().label(), 0, Colors.withAlpha(verdictColor, alpha));
        shown.get()
                .reason()
                .ifPresent(reason -> centred(
                        graphics,
                        font,
                        widest,
                        reason,
                        font.lineHeight + look.lineGap(),
                        Colors.withAlpha(look.hintColor(), alpha)));
        graphics.pose().popMatrix();
    }

    /** {@code text} centred in a line {@code widest} wide, from the box's integer origin, so what is drawn is inside the box that is kept clear. */
    private static void centred(
            GuiGraphicsExtractor graphics, Font font, int widest, Component text, int y, int color) {
        graphics.pose().pushMatrix();
        graphics.pose().translate((widest - font.width(text)) / 2f, y);
        graphics.text(font, text, 0, 0, color, true);
        graphics.pose().popMatrix();
    }
}
