package io.github.pkeppeler.deepcharter.ore;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;

/**
 * The icon and name of the weight a player carries. {@link OreEncumbrance} applies and removes it in step with the slowdown, and
 * the amplifier says how many units of ore are carried. It has no attribute modifier and does nothing on a tick: the slowdown is
 * the {@code ore_load} modifier alone.
 */
final class OverburdenedEffect extends MobEffect {
    private static final int COLOUR = 0x7a6254;

    OverburdenedEffect() {
        super(MobEffectCategory.HARMFUL, COLOUR);
    }
}
