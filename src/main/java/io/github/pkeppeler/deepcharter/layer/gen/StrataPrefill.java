package io.github.pkeppeler.deepcharter.layer.gen;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
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
 * column with unmoved contacts. The material rule writes a block over every solid block of layer 1 (the strata, and the
 * breach crust under them), so the fill's choice never reaches the world; a good guess only makes most of those writes
 * a block over the same block, which {@code SurfaceWrites} skips. A row the guess gets wrong (near a moved contact) is
 * written as before. Why and the target: ADR 0090's amendment.
 */
public final class StrataPrefill {
    private static final ResourceKey<NoiseGeneratorSettings> CAMPAIGN = ResourceKey.create(
            Registries.NOISE_SETTINGS, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "campaign"));
    private static final CampaignBands.Band BAND = CampaignBands.LAYER_1;

    /** The guess for each row of the band, bottom first. */
    private final BlockState[] rows;

    private StrataPrefill(StrataRule strata) {
        rows = new BlockState[BAND.height()];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = strata.rockAt(BAND.minY() + i, 0);
        }
    }

    /** The prefill of a generator's settings: the campaign's, when its material rule holds strata; null for any other. */
    public static @Nullable StrataPrefill of(Holder<NoiseGeneratorSettings> settings) {
        if (!settings.is(CAMPAIGN)) {
            return null;
        }
        StrataRule strata = find(settings.value().materialRule().value());
        return strata == null ? null : new StrataPrefill(strata);
    }

    private static @Nullable StrataRule find(MaterialRule rule) {
        return switch (rule) {
            case StrataRule strata -> strata;
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

    /** The block for a solid row at {@code y}, or null outside the band, where the fill keeps its default. */
    public @Nullable BlockState at(int y) {
        int row = y - BAND.minY();
        return row >= 0 && row < rows.length ? rows[row] : null;
    }
}
