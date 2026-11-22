package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.CampaignBands.Band;
import io.github.pkeppeler.deepcharter.layer.StructureKind;
import io.github.pkeppeler.deepcharter.layer.StructureSite;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.pod.TetherTuning;
import io.github.pkeppeler.deepcharter.pod.TowTuning;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel.Run;
import io.github.pkeppeler.deepcharter.test.support.EarlyRunModel.Zone;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the tether costs and how far it reaches, set against the game it is played in (#986). The cost is read from the run model
 * ({@link EarlyRunModel}, an expected value, so modelled) and the line against the hollows the layers' structures really carve
 * (their bounds from the generator, so measured). How long the winch takes over the whole line is measured in {@code TetherTest},
 * on a running server, because it needs a person and a pod.
 */
public class TetherPacingTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(TetherPacingTest.class);

    @GameTest
    public void aTetherCostsWithinTheLayerOneToolBand(GameTestHelper helper) {
        Run layerOne = EarlyRunModel.run(Zone.load("topsoil_claims"), PodStats.base(), 0);
        long price = Consumable.TETHER.price();
        double runs = price / layerOne.net();
        LOGGER.info(
                "[tether] ${} is {} of a stock layer 1 run's ${} net (modelled), a blasting charge is ${} and nanobots ${}",
                price,
                runs,
                Math.round(layerOne.net()),
                Consumable.BLASTING_CHARGE.price(),
                Consumable.HULL_NANOBOTS.price());
        expect(
                helper,
                runs >= 0.25 && runs <= 1.0,
                "a tether should cost a quarter to one stock run, it costs %.2f runs",
                runs);
        expect(
                helper,
                price > Consumable.BLASTING_CHARGE.price() && price < Consumable.HULL_NANOBOTS.price(),
                "a reusable line costs more than one charge and less than the hull nanobots, it costs $%s",
                price);
        helper.succeed();
    }

    @GameTest
    public void theLineReachesAcrossAHollowOfTheMedianSizeAndNotTheLongest(GameTestHelper helper) {
        List<Integer> spans = new ArrayList<>();
        StringBuilder log = new StringBuilder();
        for (int layer = 1; layer <= 2; layer++) {
            Band band = CampaignBands.layer(layer);
            BlockPos conduit = Colony.anchor(helper.getLevel().getServer(), ColonyAnchor.CONDUIT)
                    .orElseThrow(() -> failure(helper, "the colony was not built"));
            for (StructureKind kind : StructureKind.inLayer(layer)) {
                StructureSite site =
                        StructureSite.in(helper.getLevel().getSeed(), kind, band.minY(), band.height(), 0, 0, conduit);
                BoundingBox bounds = site.bounds();
                // The hollow lies inside the walls, one block in from each side.
                int span = Math.max(bounds.getXSpan(), bounds.getZSpan()) - 2;
                spans.add(span);
                log.append(' ').append(kind).append('=').append(span);
            }
        }
        spans.sort(Integer::compare);
        int median = spans.get(spans.size() / 2);
        int longest = spans.getLast();
        double length = TetherTuning.DEFAULT.length();
        LOGGER.info(
                "[tether] the line is {} blocks; the hollows of layers 1 and 2 (measured, longest axis):{}",
                length,
                log);
        expect(
                helper,
                length >= median,
                "the line should cross a hollow of the median size, %s, it is %s",
                median,
                length);
        expect(helper, length < longest, "the line should not cross the longest hollow, %s, it is %s", longest, length);
        expect(
                helper,
                length >= 3 * TowTuning.DEFAULT.lineLength(),
                "a person's line is a long way past the pod's tow line of %s, it is %s",
                TowTuning.DEFAULT.lineLength(),
                length);
        helper.succeed();
    }
}
