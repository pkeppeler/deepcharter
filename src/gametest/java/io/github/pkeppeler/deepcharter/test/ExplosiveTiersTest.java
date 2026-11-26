package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairTuning;
import io.github.pkeppeler.deepcharter.repair.ThrownKitTuning;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.Mth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server GameTests for #1458: the explosives are a ladder, and a retune that breaks it fails here. No world is built: each blast's size is read
 * from the tunables that the blast tests pin to the real blast ({@code RepairStationTest} the dynamite's and plastic's cube round the pod's
 * middle, {@code BlastingChargeTest} and {@code ShapedChargeTest} the charges' bore square and rows, {@code ThrownKitTest} the thrown charge's
 * column).
 *
 * <p>Two sizes, for every chassis. <b>Depth</b>, the rows a blast clears under the pod's feet (under where it lies, for a thrown charge): the
 * ladder dynamite, plastic explosives, blasting charge, shaped charge rises in it, because going down is what a pod buys explosives for.
 * <b>Cells</b>, the blocks a blast clears in solid rock: the dynamite's cube is smaller than the plastic's and the blasting charge's hole
 * smaller than the shaped charge's. The charges are not wider than the bore (a wider chamber is more air for the liner to ring, mechanics.md
 * "Blasting charges vs. a straight shaft"), so a seated charge's hole is not compared in cells with the plastic's cube. The thrown charge is
 * smaller than a seated one in both.
 */
public class ExplosiveTiersTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(ExplosiveTiersTest.class);
    private static final RepairTuning REPAIR = RepairTuning.DEFAULT;
    private static final ThrownKitTuning.Charge THROWN = ThrownKitTuning.DEFAULT.charge();

    /** A blast's size: rows under the feet, and cells in solid rock. */
    private record Size(int depth, int cells) {}

    /** A cube of {@code radius} round the pod's middle, the dynamite's and the plastic's blast: the middle is the row of the box's centre. */
    private static Size cube(Chassis chassis, int radius) {
        int middleAboveFeet = Mth.floor(chassis.height() / 2.0);
        int side = 2 * radius + 1;
        return new Size(radius - middleAboveFeet, side * side * side);
    }

    /** A seated charge: the bore square, {@code rows} deep. */
    private static Size seated(Chassis chassis, int rows) {
        return new Size(rows, chassis.slabCells() * rows);
    }

    private static Size thrown() {
        int side = 2 * THROWN.blastRadius() + 1;
        return new Size(THROWN.depth(), side * side * THROWN.depth());
    }

    @GameTest
    public void eachExplosiveTierBlastsDeeperThanTheOneBelowIt(GameTestHelper helper) {
        for (Chassis chassis : Chassis.all()) {
            Size dynamite = cube(chassis, REPAIR.dynamiteRadius());
            Size plastic = cube(chassis, REPAIR.plasticRadius());
            Size blasting = seated(chassis, REPAIR.chargeDepth());
            Size shaped = seated(chassis, REPAIR.shapedDepth());
            LOGGER.info(
                    "[explosives] {}: dynamite {}, plastic {}, blasting charge {}, shaped charge {}, thrown charge {}",
                    chassis.id(),
                    dynamite,
                    plastic,
                    blasting,
                    shaped,
                    thrown());
            expect(
                    helper,
                    dynamite.depth() < plastic.depth()
                            && plastic.depth() < blasting.depth()
                            && blasting.depth() < shaped.depth(),
                    "under a %s the rows cleared must rise dynamite %d < plastic %d < blasting charge %d < shaped charge %d",
                    chassis.id(),
                    dynamite.depth(),
                    plastic.depth(),
                    blasting.depth(),
                    shaped.depth());
            expect(
                    helper,
                    dynamite.cells() < plastic.cells() && blasting.cells() < shaped.cells(),
                    "under a %s the dearer of each pair must clear more: dynamite %d against plastic %d, blasting %d against shaped %d",
                    chassis.id(),
                    dynamite.cells(),
                    plastic.cells(),
                    blasting.cells(),
                    shaped.cells());
        }
        helper.succeed();
    }

    @GameTest
    public void aThrownChargeClearsLessThanAnySeatedCharge(GameTestHelper helper) {
        Size thrown = thrown();
        for (Chassis chassis : Chassis.all()) {
            Size seated = seated(chassis, REPAIR.chargeDepth());
            expect(
                    helper,
                    thrown.depth() < seated.depth() && thrown.cells() < seated.cells(),
                    "a thrown charge (%s) must clear less than a %s's blasting charge (%s): it is a fighting round, not the shaft tool",
                    thrown,
                    chassis.id(),
                    seated);
        }
        helper.succeed();
    }

    /** The price rises with the blast in each family: the explosives, and the charges from the thrown one to the shaped one. */
    @GameTest
    public void theBiggerBlastOfEachFamilyCostsMore(GameTestHelper helper) {
        long dynamite = Consumable.DYNAMITE.price();
        long plastic = Consumable.PLASTIC_EXPLOSIVES.price();
        long thrown = Consumable.THROWN_CHARGE.price();
        long blasting = Consumable.BLASTING_CHARGE.price();
        long shaped = Consumable.SHAPED_CHARGE.price();
        expect(helper, dynamite < plastic, "dynamite $%d must cost less than plastic $%d", dynamite, plastic);
        expect(
                helper,
                thrown < blasting && blasting < shaped,
                "the charges must cost more as they grow: thrown $%d < blasting $%d < shaped $%d",
                thrown,
                blasting,
                shaped);
        helper.succeed();
    }
}
