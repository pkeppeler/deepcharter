package io.github.pkeppeler.deepcharter.continuity;

import io.github.pkeppeler.deepcharter.suit.SuitRegistry;
import io.github.pkeppeler.deepcharter.wreck.WreckRegistry;
import java.util.Locale;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;

/**
 * What a Continuity Event is filed under: a short, cold cause for the chat line, one per kind of damage, with {@link #UNSPECIFIED} for
 * the rest. The order of {@link #of} matters: a wreck's kill is a hull loss before anything else, and a burn from a mob's fireball is a
 * burn, not an attack.
 */
public enum ContinuityCause {
    HULL_LOSS,
    SUIT_BREACH,
    BURIED,
    CRUSHED,
    BURNED,
    DROWNED,
    BLAST,
    FROZEN,
    FELL,
    STARVED,
    LOST,
    ATTACKED,
    UNSPECIFIED;

    /** The lang key of the cause's wording. */
    public String key() {
        return "deepcharter.continuity.cause." + name().toLowerCase(Locale.ROOT);
    }

    /** The cause of a death by {@code source}. */
    public static ContinuityCause of(DamageSource source) {
        if (source.is(WreckRegistry.HULL_LOSS)) {
            return HULL_LOSS;
        }
        if (source.is(SuitRegistry.EXPOSURE)) {
            return SUIT_BREACH;
        }
        if (source.is(DamageTypes.IN_WALL)) {
            return BURIED;
        }
        if (source.is(DamageTypes.FALLING_BLOCK)
                || source.is(DamageTypes.FALLING_ANVIL)
                || source.is(DamageTypes.FALLING_STALACTITE)
                || source.is(DamageTypes.STALAGMITE)
                || source.is(DamageTypes.CRAMMING)
                || source.is(DamageTypes.FLY_INTO_WALL)) {
            return CRUSHED;
        }
        if (source.is(DamageTypeTags.IS_FIRE)) {
            return BURNED;
        }
        if (source.is(DamageTypeTags.IS_DROWNING)) {
            return DROWNED;
        }
        if (source.is(DamageTypeTags.IS_EXPLOSION)) {
            return BLAST;
        }
        if (source.is(DamageTypeTags.IS_FREEZING)) {
            return FROZEN;
        }
        if (source.is(DamageTypeTags.IS_FALL)) {
            return FELL;
        }
        if (source.is(DamageTypes.STARVE)) {
            return STARVED;
        }
        if (source.is(DamageTypes.FELL_OUT_OF_WORLD) || source.is(DamageTypes.OUTSIDE_BORDER)) {
            return LOST;
        }
        if (source.getEntity() instanceof LivingEntity) {
            return ATTACKED;
        }
        return UNSPECIFIED;
    }
}
