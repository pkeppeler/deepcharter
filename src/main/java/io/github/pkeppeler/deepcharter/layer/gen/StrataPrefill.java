package io.github.pkeppeler.deepcharter.layer.gen;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import java.util.Arrays;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.material.rule.ConditionRule;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.SequenceRule;
import org.jspecify.annotations.Nullable;

/**
 * What the campaign's noise fill writes in layer 1's rows instead of its default block (#1472): the strata's rock for a
 * column with unmoved contacts, at each block's own position so a gradient's dither is guessed as it will be laid (#1551). The material rule writes a block over every solid block of layer 1 (the strata, and the
 * breach crust under them), so the fill's choice never reaches the world; a good guess only makes most of those writes
 * a block over the same block, which {@code SurfaceWrites} skips. A row the guess gets wrong (near a moved contact) is
 * written as before. Why and the target: ADR 0108.
 */
public final class StrataPrefill {
    private static final ResourceKey<NoiseGeneratorSettings> CAMPAIGN = ResourceKey.create(
            Registries.NOISE_SETTINGS, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "campaign"));
    private static final CampaignBands.Band BAND = CampaignBands.LAYER_1;

    /** A generator's settings and their prefill, null when they have none. */
    private record Known(
            Holder<NoiseGeneratorSettings> settings,
            @Nullable StrataPrefill prefill) {}

    /** The most settings kept: a server has a few generators, so more means old registries; the table starts again. */
    private static final int MOST_SETTINGS = 16;

    /**
     * Every settings holder asked about, shared by the worker threads and replaced whole when one is added (as
     * {@code SurfaceWrites} keeps its pairs): each chunk's fill asks, and finding the strata walks the material rule.
     */
    private static volatile Known[] known = new Known[0];

    private final StrataRule strata;
    /** The guess for each row of the band, bottom first; null for a row the guess depends on the position in. */
    private final @Nullable BlockState[] rows;

    private StrataPrefill(StrataRule strata) {
        this.strata = strata;
        rows = new BlockState[BAND.height()];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = strata.uniformAt(BAND.minY() + i);
        }
    }

    /** The prefill of a generator's settings: the campaign's, when its material rule holds strata; null otherwise. */
    public static @Nullable StrataPrefill of(Holder<NoiseGeneratorSettings> settings) {
        for (Known entry : known) {
            if (entry.settings == settings) {
                return entry.prefill;
            }
        }
        StrataPrefill prefill = build(settings);
        remember(new Known(settings, prefill));
        return prefill;
    }

    private static synchronized void remember(Known entry) {
        Known[] was = known;
        Known[] now = was.length < MOST_SETTINGS ? Arrays.copyOf(was, was.length + 1) : new Known[1];
        now[now.length - 1] = entry;
        known = now;
    }

    private static @Nullable StrataPrefill build(Holder<NoiseGeneratorSettings> settings) {
        if (!settings.is(CAMPAIGN)) {
            return null;
        }
        StrataRule strata = find(settings.value().materialRule().value());
        return strata == null ? null : new StrataPrefill(strata);
    }

    private static @Nullable StrataRule find(MaterialRule rule) {
        return switch (rule) {
            case StrataRule strata -> startsIn(strata) ? strata : null;
            case MaterialRule.HolderHolder holder -> find(holder.holder().value());
            case ConditionRule condition -> find(condition.thenRun());
            case SequenceRule sequence -> {
                for (MaterialRule each : sequence.sequence()) {
                    StrataRule found = find(each);
                    if (found != null) {
                        yield found;
                    }
                }
                yield null;
            }
            default -> null;
        };
    }

    /** Whether {@code strata} is layer 1's: the campaign has one rule for each layer that has strata, and each starts in its own band. */
    private static boolean startsIn(StrataRule strata) {
        int top = strata.contacts().getFirst().y();
        return top >= BAND.minY() && top <= BAND.maxY();
    }

    /** The block for a solid block at ({@code x}, {@code y}, {@code z}), or null outside the band, where the fill keeps its default. */
    public @Nullable BlockState at(int x, int y, int z) {
        int row = y - BAND.minY();
        if (row < 0 || row >= rows.length) {
            return null;
        }
        BlockState uniform = rows[row];
        return uniform != null ? uniform : strata.rockAt(x, y, z, 0);
    }
}
