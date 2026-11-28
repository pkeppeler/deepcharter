package io.github.pkeppeler.deepcharter.layer;

import io.github.pkeppeler.deepcharter.layer.CampaignBands.Band;
import io.github.pkeppeler.deepcharter.layer.gen.StrataRule;
import io.github.pkeppeler.deepcharter.layer.gen.StrataRule.Style;
import io.github.pkeppeler.deepcharter.ore.OreBlock;
import java.util.List;
import java.util.Set;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * The colour of a layer's rock at a depth (#1551). Each layer's rock is drawn in one neutral grey and the client
 * multiplies it by this tint: the same function colours the world (a block colour provider) and the scanner. Every
 * zone of a layer has a colour of its own, and each contact between two zones gives way from one to the next in a
 * transition style ({@link Style}), drawn in colour alone:
 *
 * <ul>
 *   <li>Layer 1's strata rocks drift from the hardpan's colour over the first zone contact's long gradient to the
 *       packed shale's, and on to the black shale's over the second's, whichever of the rocks a block is, so the dither
 *       of the rocks inside a gradient ({@code StrataRule}) never shows as a speckle: only the colour moves, a few
 *       shades every block of depth.
 *   <li>Layers 2 and 3 have one rock each, and their zone contacts are tint-only: the rock block is the same either
 *       side, and only its colour changes. Worldgen does nothing for them (no rule, no block switch, no noise), so
 *       they cost nothing to generate.
 * </ul>
 *
 * <p>The colour is a function of height alone, at the contacts' rows before any contour noise moves them, so it is a
 * nominal colour: a layer 1 column whose contact is moved by a few rows is a few rows off it, which the eye cannot tell
 * on a drift this long. A rock outside its own layer's rows (one a player placed, the green shale in layer 1's floor
 * gradient and in layer 3's top beds) has its home colour, its own zone's. Ore takes the colour of its host rock, and
 * so does the item of a tinted rock ({@code HOME_TINT} in {@code tools/layer_concepts/make.py} holds the same numbers,
 * and a test holds the two equal).
 */
public final class RockTint {
    /** The tint of a block that has none: white, which multiplies a texture by one. */
    public static final int NEUTRAL = 0xFFFFFF;

    /** Topsoil Claims' hardpan: olive. */
    static final int HARDPAN = 0xC7EA68;
    /** Stone Benches' packed shale: rust. */
    static final int PACKED_SHALE = 0xD2805A;
    /** Deep Claim's black shale: cold blue-grey. */
    static final int BLACK_SHALE = 0x9FAFD4;
    /** The Upper Levels' green shale: the grey-green layer 2 always had, and the green shale's home colour. */
    static final int UPPER_LEVELS = 0x69937C;
    /** Shift Change: the shale stained a warm khaki where the old workings' water ran iron. */
    static final int SHIFT_CHANGE = 0x9A9060;
    /** Prospector's Run: a cold slate-blue, the deep's light reaching up. */
    static final int PROSPECTORS_RUN = 0x5E8494;
    /** The Garden's basalt core: the cold blue-black layer 3 always had, and the core's home colour. */
    static final int GARDEN = 0x6E84B1;
    /** The Spore Fields: a sickly spore-teal cast. */
    static final int SPORE_FIELDS = 0x70C8A8;
    /** The Quiet: drained of colour, a grey with a cold violet edge. */
    static final int QUIET = 0x8C8CA4;

    /**
     * One zone contact of a layer's tint: its style, the rows it spans ({@code 0} for a sharp one) and its centre's
     * rows under the upper zone's lower edge.
     */
    record Contact(Style style, int length, int underEdge) {
        Contact {
            if (length < style.minLength() || length > style.maxLength()) {
                throw new IllegalArgumentException("a " + style.getSerializedName() + " tint contact spans "
                        + style.minLength() + " to " + style.maxLength() + " rows, not " + length);
            }
        }
    }

    /**
     * One layer's tint: the rocks it colours, its band, its zones' colours top first, and the contact under each zone
     * but the last. A rock's home colour is the colour of the first zone it is listed for.
     */
    record Profile(Band band, List<Block> rocks, List<Integer> colours, List<Contact> contacts) {
        Profile {
            if (colours.size() != Zones.COUNT || contacts.size() != Zones.COUNT - 1 || rocks.size() > Zones.COUNT) {
                throw new IllegalArgumentException("a tint profile has one colour a zone and one contact between two");
            }
        }

        /** The row of contact {@code index}'s centre. */
        int centre(int index) {
            return Zones.span(band.minY(), band.height(), index).low()
                    - contacts.get(index).underEdge();
        }

        /** The colour at height {@code y}: each zone's, given way to the next one's at each contact in its style. */
        int colour(int y) {
            for (int i = 0; i < contacts.size(); i++) {
                Contact contact = contacts.get(i);
                int upper = colours.get(i);
                int lower = colours.get(i + 1);
                int centre = centre(i);
                if (contact.style() == Style.SHARP) {
                    int above = y - centre;
                    if (above >= 0) {
                        return StrataRule.inBed(above) ? lower : upper;
                    }
                    continue;
                }
                int depth = centre + contact.length() / 2 - y;
                if (depth <= 0) {
                    return upper;
                }
                if (depth < contact.length()) {
                    return switch (contact.style()) {
                        case SHORT_GRADIENT, LONG_GRADIENT -> mix(upper, lower, depth, contact.length());
                        case INTERBEDDED -> StrataRule.lowerBand(depth, contact.length()) ? lower : upper;
                        case SHARP -> throw new IllegalStateException("a sharp contact has no rows");
                    };
                }
            }
            return colours.getLast();
        }
    }

    /**
     * The tint of every layer that has one, top first, layer 1 at index 0. Layer 1's contacts are its strata rule's ({@code
     * claim_strata.json}, a test holds them equal); layers 2 and 3 have one rock each and their contacts lie on their
     * zones' edges, so layer 3's interbedding falls across the rock shelf between its top two halls.
     */
    private static final List<Profile> PROFILES = List.of(
            new Profile(
                    CampaignBands.LAYER_1,
                    List.of(LayerBlocks.HARDPAN, LayerBlocks.PACKED_SHALE, LayerBlocks.BLACK_SHALE),
                    List.of(HARDPAN, PACKED_SHALE, BLACK_SHALE),
                    List.of(
                            new Contact(
                                    Style.LONG_GRADIENT,
                                    LayerBlocks.CLAIM_GRADIENT_LENGTH,
                                    LayerBlocks.CLAIM_CONTACT_UNDER_EDGE),
                            new Contact(
                                    Style.LONG_GRADIENT,
                                    LayerBlocks.CLAIM_GRADIENT_LENGTH,
                                    LayerBlocks.CLAIM_CONTACT_UNDER_EDGE))),
            new Profile(
                    CampaignBands.LAYER_2,
                    List.of(LayerBlocks.GREEN_SHALE),
                    List.of(UPPER_LEVELS, SHIFT_CHANGE, PROSPECTORS_RUN),
                    List.of(new Contact(Style.SHARP, 0, 0), new Contact(Style.SHORT_GRADIENT, 16, 0))),
            new Profile(
                    CampaignBands.LAYER_3,
                    List.of(LayerBlocks.BASALT_CORE),
                    List.of(GARDEN, SPORE_FIELDS, QUIET),
                    List.of(new Contact(Style.INTERBEDDED, 24, 0), new Contact(Style.LONG_GRADIENT, 64, 0))));

    /** Every rock some profile tints. */
    private static final Set<Block> TINTED = Set.of(
            LayerBlocks.HARDPAN,
            LayerBlocks.PACKED_SHALE,
            LayerBlocks.BLACK_SHALE,
            LayerBlocks.GREEN_SHALE,
            LayerBlocks.BASALT_CORE);

    private RockTint() {}

    /** Whether {@code rock} is drawn in the neutral grey and takes a tint. */
    public static boolean tinted(Block rock) {
        return TINTED.contains(rock);
    }

    /** The colour of {@code rock} where no depth tells: its own, and {@link #NEUTRAL} for a rock that is not tinted. */
    public static int home(Block rock) {
        Profile profile = profileOf(rock);
        return profile == null ? NEUTRAL : profile.colours().get(profile.rocks().indexOf(rock));
    }

    /**
     * The tint of {@code state} at height {@code y}: for a tinted rock, or an ore in one, its layer's colour at
     * {@code y} in that layer's rows, whichever of the layer's rocks it is (a column's shift puts a rock outside its
     * nominal rows), and its home colour elsewhere; {@link #NEUTRAL} for any other block.
     */
    public static int of(BlockState state, int y) {
        Block rock = rockOf(state);
        Profile profile = profileOf(rock);
        if (profile == null) {
            return NEUTRAL;
        }
        Band band = profile.band();
        return y >= band.minY() && y <= band.maxY() ? profile.colour(y) : home(rock);
    }

    /**
     * The home colour of {@code state}'s rock, for a place that has no height (the block as an item, a menu): see
     * {@link #home}.
     */
    public static int homeOf(BlockState state) {
        return home(rockOf(state));
    }

    /** The colour of layer {@code layer}'s rock at height {@code y} of its band, whichever of its rocks a block is. */
    // test-seam: RockTintTest walks each layer's colour by depth, its zones and its contacts' profiles
    public static int layerColour(int layer, int y) {
        return PROFILES.get(layer - 1).colour(y);
    }

    /** The rock of a block: an ore's host, and any other block itself. */
    private static Block rockOf(BlockState state) {
        return state.getBlock() instanceof OreBlock
                ? state.getValue(OreBlock.HOST).rock()
                : state.getBlock();
    }

    private static @Nullable Profile profileOf(Block rock) {
        if (!tinted(rock)) {
            return null;
        }
        for (Profile profile : PROFILES) {
            if (profile.rocks().contains(rock)) {
                return profile;
            }
        }
        return null;
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
