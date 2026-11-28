package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.handbook.SpecSheet;
import io.github.pkeppeler.deepcharter.handbook.SpecSheets;
import io.github.pkeppeler.deepcharter.market.OrePriceTooltip;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.outpost.OutpostRates;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Server GameTests for #1669: an ore's tooltip says what the ore processor pays for it, at the colony and at an
 * outpost's sell terminal, read from the {@link OutpostRates} the processor pays from, and the colony figure is the one
 * the handbook's sheet shows. {@code OreProcessorTest} sells each ore and checks the account moves by the tooltip's
 * figure.
 */
public class OrePriceTooltipTest {
    private static final String KEY = "deepcharter.market.tooltip.price";

    /** What each ore's tooltip says, colony then outpost (80 percent, rounded down), as a player reads it. */
    private static final Map<OreType, List<String>> SHOWN = Map.of(
            OreType.IRONIUM, List.of("30", "24"),
            OreType.BRONZIUM, List.of("60", "48"),
            OreType.SILVERIUM, List.of("100", "80"),
            OreType.GOLDIUM, List.of("250", "200"),
            OreType.PLATINIUM, List.of("750", "600"),
            OreType.EINSTEINIUM, List.of("2,000", "1,600"),
            OreType.CICATRIUM, List.of("1,500", "1,200"));

    /** The figures {@code stack}'s price line shows; fails when it has none or it is not the price line. */
    static List<String> shownPrices(GameTestHelper helper, ItemStack stack) {
        Component line =
                OrePriceTooltip.line(stack).orElseThrow(() -> failure(helper, "%s should have a price line", stack));
        if (!(line.getContents() instanceof TranslatableContents contents)
                || !contents.getKey().equals(KEY)) {
            throw failure(helper, "%s's price line is %s, not %s", stack, line, KEY);
        }
        return Arrays.stream(contents.getArgs()).map(String::valueOf).toList();
    }

    /** What the colony figure of {@code shown} is worth in dollars: "2,000" is 2000. */
    static long dollars(String shown) {
        return Long.parseLong(shown.replace(",", ""));
    }

    @GameTest
    public void everyOreTooltipShowsItsColonyAndOutpostPrice(GameTestHelper helper) {
        for (OreType ore : OreType.values()) {
            List<String> shown = shownPrices(helper, OreRegistry.stack(ore));
            expect(helper, shown.equals(SHOWN.get(ore)), "%s shows %s, expected %s", ore, shown, SHOWN.get(ore));
        }
        helper.succeed();
    }

    @GameTest
    public void theTooltipReadsTheRatesTheProcessorPaysFrom(GameTestHelper helper) {
        for (OreType ore : OreType.values()) {
            List<String> shown = shownPrices(helper, OreRegistry.stack(ore));
            long colony = OutpostRates.orePrice(
                    helper.getLevel(), helper.absolutePos(BlockPos.ZERO).getY(), ore);
            expect(
                    helper,
                    dollars(shown.get(0)) == colony && colony == OutpostRates.colonyOrePrice(ore),
                    "%s's tooltip says $%s at the colony, the processor here pays $%s",
                    ore,
                    shown.get(0),
                    colony);
            expect(
                    helper,
                    dollars(shown.get(1)) == OutpostRates.outpostOrePrice(ore),
                    "%s's tooltip says $%s at an outpost, a sell terminal at depth pays $%s",
                    ore,
                    shown.get(1),
                    OutpostRates.outpostOrePrice(ore));
        }
        helper.succeed();
    }

    @GameTest
    public void theColonyPriceIsTheWorthOnTheOresSheet(GameTestHelper helper) {
        for (OreType ore : OreType.values()) {
            String colony = shownPrices(helper, OreRegistry.stack(ore)).get(0);
            SpecSheet sheet = SpecSheets.of(OreRegistry.item(ore));
            List<String> worth = sheet.ratings().stream()
                    .filter(rating -> rating.label().getContents() instanceof TranslatableContents label
                            && label.getKey().equals("deepcharter.spec.rating.worth"))
                    .map(rating -> String.valueOf(
                            ((TranslatableContents) rating.value().getContents()).getArgs()[0]))
                    .toList();
            expect(
                    helper,
                    worth.equals(List.of(colony)),
                    "%s's sheet says worth %s, its tooltip $%s",
                    ore,
                    worth,
                    colony);
        }
        helper.succeed();
    }

    @GameTest
    public void onlyOreHasAPriceLine(GameTestHelper helper) {
        for (ItemStack stack : List.of(new ItemStack(Items.COAL), new ItemStack(Items.IRON_INGOT), ItemStack.EMPTY)) {
            expect(helper, OrePriceTooltip.line(stack).isEmpty(), "%s should have no price line", stack);
        }
        helper.succeed();
    }
}
