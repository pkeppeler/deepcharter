package io.github.pkeppeler.deepcharter.layer.gen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.List;
import java.util.function.DoubleSupplier;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * A layer's strata as one material rule (#1277): rocks stacked one over the next, each contact at its own height, moved
 * in every column by {@code amplitude} times a 2D noise (rounded to a row, at most {@link #MAX_SHIFT} either way) and
 * raised by the column's surface depth times {@code surface_depth_multiplier}. A contact's two rows over it hold the
 * upper rock, the next {@value #BED_ROWS} a thin bed of the rock under it, and from there up the upper rock again, so a
 * contact reads as bedding. Under the last contact is {@code bottom}.
 *
 * <p>It is one rule rather than a tree of vanilla conditions because the material rule runs for every block of the
 * layer and each condition it tests costs about the same: a contact made of a dozen noise bins tested per block doubled
 * layer 1's cost per row. This rule works out its contacts once a column and then compares rows.
 *
 * @param contacts                the contacts, top first, each at the row its upper rock starts from before the shifts
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
    /** Rows of the upper rock on a contact under its thin bed, and rows of the bed. */
    private static final int ON_CONTACT = 2;

    private static final int BED_ROWS = 2;
    /** The most rows the noise moves a contact either way. */
    private static final int MAX_SHIFT = 6;

    /**
     * One contact: the upper rock starts at {@code y} (before the shifts), and {@code bed} is the thin bed of the lower
     * rock in it.
     */
    public record Contact(int y, BlockState upper, BlockState bed) {
        static final Codec<Contact> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        Codec.INT.fieldOf("y").forGetter(Contact::y),
                        BlockState.CODEC.fieldOf("upper").forGetter(Contact::upper),
                        BlockState.CODEC.fieldOf("bed").forGetter(Contact::bed))
                .apply(instance, Contact::new));
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
            .validate(rule -> descending(rule.contacts)
                    ? DataResult.success(rule)
                    : DataResult.error(() -> "a strata rule's contacts must run top first, got " + rule.contacts));

    private static boolean descending(List<Contact> contacts) {
        for (int i = 1; i < contacts.size(); i++) {
            if (contacts.get(i).y() >= contacts.get(i - 1).y()) {
                return false;
            }
        }
        return true;
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
        return new Evaluator(context, context.getNoiseSampler(noise, false));
    }

    /**
     * The rock at {@code y} in a column whose contacts are moved by {@code shift} rows: the upper rock of the first
     * contact at or under {@code y}, or its bed in the bed's rows, and {@link #bottom} under every contact.
     */
    public BlockState rockAt(int y, int shift) {
        for (int i = 0; i < contacts.size(); i++) {
            Contact contact = contacts.get(i);
            int above = y - (contact.y() + shift);
            if (above >= 0) {
                return above >= ON_CONTACT && above < ON_CONTACT + BED_ROWS ? contact.bed() : contact.upper();
            }
        }
        return bottom;
    }

    /** The rule in one chunk: the shift of the column last asked about. */
    private final class Evaluator implements RuleEvaluator {
        private final MaterialRuleContext context;
        private final DoubleSupplier sampler;
        private int shift;
        private int lastX = Integer.MIN_VALUE;
        private int lastZ = Integer.MIN_VALUE;

        Evaluator(MaterialRuleContext context, DoubleSupplier sampler) {
            this.context = context;
            this.sampler = sampler;
        }

        @Override
        public BlockState tryApply(int x, int y, int z) {
            if (x != lastX || z != lastZ) {
                lastX = x;
                lastZ = z;
                int moved = (int) Math.round(amplitude * sampler.getAsDouble());
                shift = Math.clamp(moved, -MAX_SHIFT, MAX_SHIFT) + context.surfaceDepth() * surfaceDepthMultiplier;
            }
            return rockAt(y, shift);
        }
    }
}
