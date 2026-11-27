package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.layer.CampaignBands.Band;
import io.github.pkeppeler.deepcharter.ore.OreBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The colour of layer 1's strata rock at a depth (#1551). The hardpan, the packed shale and the black shale are drawn
 * in one neutral grey, and the client multiplies it by this tint: the same function colours the world (a block colour
 * provider) and the scanner. The tint drifts continuously from the hardpan's colour over the first zone contact's long
 * gradient to the packed shale's, and on to the black shale's over the second's, whichever of the rocks a block is, so
 * the dither of the rocks inside a gradient ({@code StrataRule}) never shows as a speckle: only the colour moves, a
 * few shades every block of depth.
 *
 * <p>The drift is a function of height alone, at the contacts' rows before the contour noise and the surface depth move
 * them, so it is a nominal colour: a column whose contact is moved by a few rows is a few rows off it, which the eye
 * cannot tell on a drift this long. A rock outside its own range of rows (one a player placed) has its home colour.
 * Ore takes the colour of its host rock, and so does the item of a tinted rock ({@code HOME_TINT} in
 * {@code tools/layer_concepts/make.py} holds the same numbers, and a test holds the two equal).
 */
public final class RockTint {
    /** The tint of a block that has none: white, which multiplies a texture by one. */
    public static final int NEUTRAL = 0xFFFFFF;

    static final int HARDPAN = 0xC7EA68;
    static final int PACKED_SHALE = 0xD2805A;
    static final int BLACK_SHALE = 0x9FAFD4;

    private static final Band LAYER = CampaignBands.LAYER_1;
    private static final int HALF = LayerBlocks.CLAIM_GRADIENT_LENGTH / 2;
    /** The rows of the hardpan's gradient's centre and of the packed shale's, as the strata rule has them. */
    private static final int FIRST_CONTACT =
            Zones.span(LAYER.minY(), LAYER.height(), 0).low() - LayerBlocks.CLAIM_CONTACT_UNDER_EDGE;

    private static final int SECOND_CONTACT =
            Zones.span(LAYER.minY(), LAYER.height(), 1).low() - LayerBlocks.CLAIM_CONTACT_UNDER_EDGE;

    private RockTint() {}

    /** Whether {@code rock} is drawn in the neutral grey and takes a tint. */
    public static boolean tinted(Block rock) {
        return rock == LayerBlocks.HARDPAN || rock == LayerBlocks.PACKED_SHALE || rock == LayerBlocks.BLACK_SHALE;
    }

    /** The colour of {@code rock} where no depth tells: its own, and {@link #NEUTRAL} for a rock that is not tinted. */
    public static int home(Block rock) {
        if (rock == LayerBlocks.HARDPAN) {
            return HARDPAN;
        }
        if (rock == LayerBlocks.PACKED_SHALE) {
            return PACKED_SHALE;
        }
        return rock == LayerBlocks.BLACK_SHALE ? BLACK_SHALE : NEUTRAL;
    }

    /**
     * The tint of {@code state} at height {@code y}: for a tinted rock, or an ore in one, the drift's colour at {@code
     * y} where the rock lies in its own range of rows and its home colour elsewhere; {@link #NEUTRAL} for any other
     * block.
     */
    public static int of(BlockState state, int y) {
        Block rock = rockOf(state);
        if (!tinted(rock)) {
            return NEUTRAL;
        }
        return inRange(rock, y) ? drift(y) : home(rock);
    }

    /** The home colour of {@code state}'s rock, for a place that has no height (the block as an item, a menu): see {@link #home}. */
    public static int homeOf(BlockState state) {
        return home(rockOf(state));
    }

    /** The rock of a block: an ore's host, and any other block itself. */
    private static Block rockOf(BlockState state) {
        return state.getBlock() instanceof OreBlock
                ? state.getValue(OreBlock.HOST).rock()
                : state.getBlock();
    }

    /** Whether height {@code y} is one where the strata rule can lay {@code rock}: layer 1's rows, and the rock's side of its contacts. */
    private static boolean inRange(Block rock, int y) {
        if (y < LAYER.minY() || y > LAYER.maxY()) {
            return false;
        }
        if (rock == LayerBlocks.HARDPAN) {
            return y >= FIRST_CONTACT - HALF;
        }
        if (rock == LayerBlocks.PACKED_SHALE) {
            return y >= SECOND_CONTACT - HALF && y <= FIRST_CONTACT + HALF;
        }
        return y <= SECOND_CONTACT + HALF;
    }

    /**
     * The colour of layer 1's rock at height {@code y}: the hardpan's above its gradient, mixed linearly into the
     * packed shale's across the first gradient, the packed shale's between the two, mixed into the black shale's
     * across the second, and the black shale's below.
     */
    public static int drift(int y) {
        int length = LayerBlocks.CLAIM_GRADIENT_LENGTH;
        if (y >= FIRST_CONTACT + HALF) {
            return HARDPAN;
        }
        if (y > FIRST_CONTACT - HALF) {
            return mix(HARDPAN, PACKED_SHALE, FIRST_CONTACT + HALF - y, length);
        }
        if (y >= SECOND_CONTACT + HALF) {
            return PACKED_SHALE;
        }
        if (y > SECOND_CONTACT - HALF) {
            return mix(PACKED_SHALE, BLACK_SHALE, SECOND_CONTACT + HALF - y, length);
        }
        return BLACK_SHALE;
    }

    /** {@code from} moved {@code part} of {@code whole} of the way to {@code to}, each channel rounded. */
    static int mix(int from, int to, int part, int whole) {
        int result = 0;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int a = (from >> shift) & 0xFF;
            int b = (to >> shift) & 0xFF;
            result |= ((a * (whole - part) + b * part + whole / 2) / whole) << shift;
        }
        return result;
    }
}
