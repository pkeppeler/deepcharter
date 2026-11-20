package io.github.pkeppeler.deepcharter.test.support;

import net.minecraft.world.entity.player.Input;

/** The pilot inputs GameTests and evidence scenarios share: a key or a pair of keys held. */
public final class Inputs {
    private Inputs() {}

    public static final Input FORWARD = new Input(true, false, false, false, false, false, false);
    public static final Input JUMP = new Input(false, false, false, false, true, false, false);
    public static final Input FORWARD_JUMP = new Input(true, false, false, false, true, false, false);
    public static final Input SNEAK = new Input(false, false, false, false, false, true, false);
    public static final Input SPRINT = new Input(false, false, false, false, false, false, true);
}
