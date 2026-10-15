package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import java.util.Objects;
import net.minecraft.core.BlockPos;

/** Reads of a placed colony that a test makes. */
public final class TestColony {
    private TestColony() {}

    /** Where {@code anchor} is: a placed colony holds every anchor, so a missing one is a broken colony. */
    public static BlockPos anchor(ColonySite.Placed colony, ColonyAnchor anchor) {
        return Objects.requireNonNull(colony.anchors().get(anchor), () -> "the colony has no " + anchor + " anchor");
    }
}
