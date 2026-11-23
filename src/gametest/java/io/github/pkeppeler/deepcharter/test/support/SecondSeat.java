package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import java.lang.reflect.Field;

/** Gives a Mole a second seat, for a test of a passenger: the Mole has one seat and no other chassis exists yet. */
public final class SecondSeat {
    private SecondSeat() {}

    public static void add(PodEntity pod) {
        try {
            Field chassis = PodEntity.class.getDeclaredField("chassis");
            chassis.setAccessible(true);
            chassis.set(
                    pod, new Chassis("mole", 2, Chassis.MOLE.tierCap(), Chassis.MOLE.width(), Chassis.MOLE.height()));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("could not add a seat", failure);
        }
    }
}
