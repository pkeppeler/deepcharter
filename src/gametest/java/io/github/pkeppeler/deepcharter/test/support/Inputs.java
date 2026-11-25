package io.github.pkeppeler.deepcharter.test.support;

import net.minecraft.world.entity.player.Input;

/** The pilot inputs GameTests and evidence scenarios share: a key or a pair of keys held. */
public final class Inputs {
    private Inputs() {}

    public static final Input FORWARD = new Input(true, false, false, false, false, false, false);
    public static final Input BACK = new Input(false, true, false, false, false, false, false);
    /** A (steer left). */
    public static final Input LEFT = new Input(false, false, true, false, false, false, false);
    /** D (steer right). */
    public static final Input RIGHT = new Input(false, false, false, true, false, false, false);

    public static final Input FORWARD_LEFT = new Input(true, false, true, false, false, false, false);
    public static final Input FORWARD_RIGHT = new Input(true, false, false, true, false, false, false);
    public static final Input BACK_LEFT = new Input(false, true, true, false, false, false, false);
    public static final Input FORWARD_BACK = new Input(true, true, false, false, false, false, false);
    public static final Input JUMP = new Input(false, false, false, false, true, false, false);
    public static final Input FORWARD_JUMP = new Input(true, false, false, false, true, false, false);
    public static final Input SNEAK = new Input(false, false, false, false, false, true, false);
    public static final Input SPRINT = new Input(false, false, false, false, false, false, true);
}
