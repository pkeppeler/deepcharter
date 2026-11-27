package io.github.pkeppeler.deepcharter.layer.gen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.jspecify.annotations.Nullable;

/**
 * A layer's strata as one material rule (#1277): rocks stacked one over the next, each contact at its own height and in
 * a transition style of its own (#1551), moved in every column by {@code amplitude} times a 2D noise (rounded to a row,
 * at most {@link #MAX_SHIFT} either way) and raised by the column's surface depth times
 * {@code surface_depth_multiplier}. Under the last contact is {@code bottom}. A contact's style says how the rock above
 * it gives way to the rock under it ({@link Style}).
 *
 * <p>It is one rule rather than a tree of vanilla conditions because the material rule runs for every block of the
 * layer and each condition it tests costs about the same: a contact made of a dozen noise bins tested per block doubled
 * layer 1's cost per row. This rule works out its contacts once a column and then compares rows. A gradient adds one
 * integer hash of the block's position to a block in its rows and samples no noise: the column's shift is the only
 * noise read, once a column, as before.
 *
 * @param contacts                the contacts, top first
 * @param bottom                  the rock under the last contact
 * @param noise                   the 2D noise that moves the contacts, read once a column
 * @param amplitude               rows a noise value of 1 moves a contact
 * @param surfaceDepthMultiplier  rows each step of the column's surface depth raises a contact
 */
public record StrataRule(
        List<Contact> contacts,
        BlockState bottom,
        ResourceKey<NormalNoise> noise,
        int amplitude,
        int surfaceDepthMultiplier)
        implements MaterialRule {
    /** Rows of the upper rock on a sharp contact under its thin bed, and rows of the bed. */
    private static final int ON_CONTACT = 2;

    private static final int BED_ROWS = 2;
    /** The most rows the noise moves a contact either way. */
    private static final int MAX_SHIFT = 6;
    /** Rows of one interbedded cycle: the upper rock, then a band of the lower rock that grows from cycle to cycle. */
    private static final int BED_PERIOD = 6;

    /**
     * How the rock above a contact gives way to the rock under it. The sharp contact is the strata's first style (a
     * thin bed of the lower rock above the line); the others take the rows named by {@code length}, centred on the
     * contact.
     */
    public enum Style implements StringRepresentable {
        /** A wavy line with a thin bed of the lower rock in the upper rock over it. */
        SHARP(0, 0),
        /**
         * The chance of the lower rock rises evenly from 0 to 1 over 12 to 20 rows, by the dither of a position hash.
         */
        SHORT_GRADIENT(12, 20),
        /** The same over 48 to 128 rows: the layer's colour drifts with it ({@code RockTint}) so no block shows it. */
        LONG_GRADIENT(48, 128),
        /** Bands of the lower rock in the upper rock, thicker the deeper they lie, over 12 to 64 rows. */
        INTERBEDDED(12, 64);

        public static final Codec<Style> CODEC = StringRepresentable.fromEnum(Style::values);

        private final int minLength;
        private final int maxLength;

        Style(int minLength, int maxLength) {
            this.minLength = minLength;
            this.maxLength = maxLength;
        }

        /** The fewest rows a transition of this style takes, 0 for the sharp contact, which takes none. */
        public int minLength() {
            return minLength;
        }

        /** The most rows a transition of this style takes. */
        public int maxLength() {
            return maxLength;
        }

        /** Whether the rock is dithered, block by block, between the two rocks. */
        public boolean dithered() {
            return this == SHORT_GRADIENT || this == LONG_GRADIENT;
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * One contact: the upper rock starts at {@code y} (before the shifts), and {@code style} and {@code length} say how
     * it gives way to the rock under it, which is the next contact's upper rock or the rule's {@code bottom}. A
     * gradient or interbedding is centred on {@code y} and spans {@code length} rows; a sharp contact has no length.
     */
    public record Contact(int y, BlockState upper, Style style, int length) {
        static final Codec<Contact> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        Codec.INT.fieldOf("y").forGetter(Contact::y),
                        BlockState.CODEC.fieldOf("upper").forGetter(Contact::upper),
                        Style.CODEC.optionalFieldOf("style", Style.SHARP).forGetter(Contact::style),
                        Codec.INT.optionalFieldOf("length", 0).forGetter(Contact::length))
                .apply(instance, Contact::new));

        public Contact {
            if (length < style.minLength() || length > style.maxLength()) {
                throw new IllegalArgumentException("a " + style.getSerializedName() + " contact spans "
                        + style.minLength() + " to " + style.maxLength() + " rows, not " + length);
            }
        }

        /**
         * The row the transition starts under (the upper rock holds it and every row over it) in a column moved by
         * {@code shift}.
         */
        private int top(int shift) {
            return y + shift + length / 2;
        }

        /** The lowest row the transition reaches, before any shift. */
        private int reachDown() {
            return y - length / 2;
        }

        /** The highest row the transition reaches, before any shift. */
        private int reachUp() {
            return y + length / 2;
        }
    }

    public static final MapCodec<StrataRule> CODEC = RecordCodecBuilder.<StrataRule>mapCodec(instance -> instance.group(
                            Contact.CODEC
                                    .listOf(1, Integer.MAX_VALUE)
                                    .fieldOf("contacts")
                                    .forGetter(StrataRule::contacts),
                            BlockState.CODEC.fieldOf("bottom").forGetter(StrataRule::bottom),
                            ResourceKey.codec(Registries.NOISE).fieldOf("noise").forGetter(StrataRule::noise),
                            Codec.intRange(0, MAX_SHIFT * 4)
                                    .fieldOf("amplitude")
                                    .forGetter(StrataRule::amplitude),
                            Codec.intRange(0, 4)
                                    .fieldOf("surface_depth_multiplier")
                                    .forGetter(StrataRule::surfaceDepthMultiplier))
                    .apply(instance, StrataRule::new))
            .validate(rule -> problem(rule.contacts) == null
                    ? DataResult.success(rule)
                    : DataResult.error(() -> "a strata rule's contacts " + problem(rule.contacts)));

    /**
     * What is wrong with the order of {@code contacts}, or null: they run top first and their transitions do not
     * overlap.
     */
    private static @Nullable String problem(List<Contact> contacts) {
        for (int i = 1; i < contacts.size(); i++) {
            Contact above = contacts.get(i - 1);
            Contact below = contacts.get(i);
            if (below.y() >= above.y()) {
                return "must run top first, got " + contacts;
            }
            if (below.reachUp() > above.reachDown()) {
                return "must not overlap, got " + contacts;
            }
        }
        return null;
    }

    public StrataRule {
        contacts = List.copyOf(contacts);
    }

    public static void register() {
        Registry.register(
                BuiltInRegistries.MATERIAL_RULE_TYPE,
                Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "strata"),
                CODEC);
    }

    @Override
    public MapCodec<StrataRule> codec() {
        return CODEC;
    }

    @Override
    public RuleEvaluator compile(MaterialRuleContext context) {
        return evaluator(context.getNoiseSampler(noise, false), context::surfaceDepth);
    }

    /**
     * The rule in one chunk: it reads {@code sampler} once for a column and {@code surfaceDepth} for the column's
     * first block asked about, and every row of the column is then a comparison and, in a gradient, one hash.
     */
    public RuleEvaluator evaluator(DoubleSupplier sampler, IntSupplier surfaceDepth) {
        return new Evaluator(sampler, surfaceDepth);
    }

    private BlockState lowerOf(int index) {
        return index + 1 < contacts.size() ? contacts.get(index + 1).upper() : bottom;
    }

    /**
     * The rock at ({@code x}, {@code y}, {@code z}) in a column whose contacts are moved by {@code shift} rows: the
     * rock of the first contact whose transition has not ended at {@code y}, and {@link #bottom} under every
     * contact. Only a gradient looks at {@code x} and {@code z}: its dither is a hash of the position, so the same
     * block is the same rock whichever chunk asks.
     */
    public BlockState rockAt(int x, int y, int z, int shift) {
        for (int i = 0; i < contacts.size(); i++) {
            Contact contact = contacts.get(i);
            switch (contact.style()) {
                case SHARP -> {
                    int above = y - (contact.y() + shift);
                    if (above >= 0) {
                        return above >= ON_CONTACT && above < ON_CONTACT + BED_ROWS ? lowerOf(i) : contact.upper();
                    }
                }
                case SHORT_GRADIENT, LONG_GRADIENT -> {
                    int depth = contact.top(shift) - y;
                    if (depth <= 0) {
                        return contact.upper();
                    }
                    if (depth < contact.length()) {
                        return jitter(x, y, z) % contact.length() < depth ? lowerOf(i) : contact.upper();
                    }
                }
                case INTERBEDDED -> {
                    int depth = contact.top(shift) - y;
                    if (depth <= 0) {
                        return contact.upper();
                    }
                    if (depth < contact.length()) {
                        return lowerBand(depth, contact.length()) ? lowerOf(i) : contact.upper();
                    }
                }
            }
        }
        return bottom;
    }

    /**
     * Whether row {@code depth} below the top of an interbedded transition of {@code length} rows is in a band of the
     * lower rock.
     */
    private static boolean lowerBand(int depth, int length) {
        int cycle = (depth - 1) / BED_PERIOD;
        int cycles = Math.ceilDiv(length, BED_PERIOD);
        int lowerRows = (cycle + 1) * BED_PERIOD / (cycles + 1);
        return (depth - 1) % BED_PERIOD >= BED_PERIOD - lowerRows;
    }

    /**
     * The rock of row {@code y} for every column whose contacts are not moved, when it is the same in all of them; null
     * in a gradient's rows, where it depends on the position. The prefill keeps one block for each such row.
     */
    public @Nullable BlockState uniformAt(int y) {
        for (Contact contact : contacts) {
            if (contact.style().dithered()) {
                int depth = contact.top(0) - y;
                if (depth > 0 && depth < contact.length()) {
                    return null;
                }
            }
        }
        return rockAt(0, y, 0, 0);
    }

    /**
     * Every rock row {@code y} can hold in a column whose contacts are moved by up to {@code reach} rows either way: a
     * row inside a gradient or interbedding holds both of its rocks, and a sharp contact's bed rows hold the lower
     * rock.
     */
    // test-seam: the tests of the generated wall ask which rocks a row can hold, and nothing else does
    public Set<Block> possibleAt(int y, int reach) {
        Set<Block> found = new HashSet<>();
        for (int shift = -reach; shift <= reach; shift++) {
            addPossible(y, shift, found);
        }
        return found;
    }

    private void addPossible(int y, int shift, Set<Block> found) {
        for (int i = 0; i < contacts.size(); i++) {
            Contact contact = contacts.get(i);
            if (contact.style() == Style.SHARP) {
                int above = y - (contact.y() + shift);
                if (above >= 0) {
                    boolean bed = above >= ON_CONTACT && above < ON_CONTACT + BED_ROWS;
                    found.add((bed ? lowerOf(i) : contact.upper()).getBlock());
                    return;
                }
            } else {
                int depth = contact.top(shift) - y;
                if (depth <= 0) {
                    found.add(contact.upper().getBlock());
                    return;
                }
                if (depth < contact.length()) {
                    found.add(contact.upper().getBlock());
                    found.add(lowerOf(i).getBlock());
                    return;
                }
            }
        }
        found.add(bottom.getBlock());
    }

    /**
     * A hash of a block position, 0 or more: the dither of a gradient, so a row's lower rock is spread evenly and in no
     * bands.
     */
    static int jitter(int x, int y, int z) {
        int h = x * 0x1B873593 ^ y * 0xCC9E2D51 ^ z * 0x85EBCA6B;
        h ^= h >>> 16;
        h *= 0x7FEB352D;
        h ^= h >>> 15;
        h *= 0x846CA68B;
        h ^= h >>> 16;
        return h >>> 1;
    }

    /** The shift of a column's contacts: the noise at the column, in rows, clamped, and the surface depth's rise. */
    private int shiftOf(double noiseValue, int surfaceDepth) {
        int moved = (int) Math.round(amplitude * noiseValue);
        return Math.clamp(moved, -MAX_SHIFT, MAX_SHIFT) + surfaceDepth * surfaceDepthMultiplier;
    }

    /** The rule in one chunk: the shift of the column last asked about. */
    private final class Evaluator implements RuleEvaluator {
        private final DoubleSupplier sampler;
        private final IntSupplier surfaceDepth;
        private int shift;
        private int lastX = Integer.MIN_VALUE;
        private int lastZ = Integer.MIN_VALUE;

        Evaluator(DoubleSupplier sampler, IntSupplier surfaceDepth) {
            this.sampler = sampler;
            this.surfaceDepth = surfaceDepth;
        }

        @Override
        public BlockState tryApply(int x, int y, int z) {
            if (x != lastX || z != lastZ) {
                lastX = x;
                lastZ = z;
                shift = shiftOf(sampler.getAsDouble(), surfaceDepth.getAsInt());
            }
            return rockAt(x, y, z, shift);
        }
    }
}
