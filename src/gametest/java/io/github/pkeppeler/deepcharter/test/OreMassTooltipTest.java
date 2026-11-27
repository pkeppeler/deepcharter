package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.ore.MassTooltip;
import io.github.pkeppeler.deepcharter.ore.OreEncumbrance;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLoad;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for #1538: the tooltip line of a heavy item names its mass in the units the pod's load is counted in, read from the
 * same {@code OreEncumbrance.massOf} the cockpit's LOAD gauge is made of, so the tooltips of what is held add up to the load.
 */
public class OreMassTooltipTest {
    private static final String HEAVY_KEY = "deepcharter.ore.tooltip.heavy";
    private static final String HAUL_KEY = "deepcharter.ore.tooltip.haul";

    /** One ironium drilled at a mass that is not its own, with a fraction. */
    private static final float DRILLED_IRONIUM = 7.4f;

    /** What each ore weighs in pod units, as the spec says: its original mass of 1, 1, 1, 2, 3, 4 and 2 times 5. */
    private static final Map<OreType, String> SHOWN = Map.of(
            OreType.IRONIUM, "5",
            OreType.BRONZIUM, "5",
            OreType.SILVERIUM, "5",
            OreType.GOLDIUM, "10",
            OreType.PLATINIUM, "15",
            OreType.EINSTEINIUM, "20",
            OreType.CICATRIUM, "10");

    private static List<Component> lines(GameTestHelper helper, ItemStack stack) {
        return stack.getTooltipLines(Item.TooltipContext.of(helper.getLevel()), null, TooltipFlag.NORMAL);
    }

    private static boolean has(List<Component> lines, String key) {
        return lines.stream()
                .anyMatch(line -> line.getContents() instanceof TranslatableContents contents
                        && contents.getKey().equals(key));
    }

    /** The text the stack's mass line shows; fails when it has none. */
    private static String shownMass(GameTestHelper helper, ItemStack stack) {
        Component line = MassTooltip.line(stack).orElse(null);
        expect(helper, line != null, "%s should have a mass line", stack);
        return (String) ((TranslatableContents) line.getContents()).getArgs()[0];
    }

    /** What a tooltip text such as "7.4" is, in tenths. */
    private static int tenthsOf(String shown) {
        return Math.round(Float.parseFloat(shown) * 10f);
    }

    @GameTest
    public void everyOreTooltipShowsItsMassAndKeepsTheHeavyAndHaulLines(GameTestHelper helper) {
        for (OreType type : OreType.values()) {
            ItemStack ore = OreRegistry.stack(type);
            String shown = shownMass(helper, ore);
            expect(
                    helper,
                    shown.equals(SHOWN.get(type)),
                    "%s shows mass %s, expected %s",
                    type,
                    shown,
                    SHOWN.get(type));
            List<Component> lines = lines(helper, ore);
            expect(helper, has(lines, HEAVY_KEY) && has(lines, HAUL_KEY), "%s lost its heavy or haul line", type);
        }
        helper.succeed();
    }

    @GameTest
    public void aDrilledOreShowsTheMassItWasDrilledAtToATenth(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(0.5, 2, 0.5));
        try {
            expect(
                    helper,
                    pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.IRONIUM), DRILLED_IRONIUM),
                    "the bay refused the drilled ironium");
            String shown = shownMass(helper, pod.cargo().stackAt(0));
            expect(helper, shown.equals("7.4"), "ironium drilled at 7.4 should show 7.4, shows %s", shown);
        } finally {
            pod.discard();
        }
        helper.succeed();
    }

    @GameTest
    public void everyCargoWeightItemShowsItsMass(GameTestHelper helper) {
        Map<Item, String> expected = Map.of(Items.COAL, "0.6", Items.CHARCOAL, "0.4");
        expected.forEach((item, text) -> {
            String shown = shownMass(helper, new ItemStack(item));
            expect(helper, shown.equals(text), "%s shows mass %s, expected %s", item, shown, text);
        });
        String five = shownMass(helper, new ItemStack(Items.COAL, 5));
        expect(helper, five.equals("3"), "five coal should show 3, shows %s", five);
        helper.succeed();
    }

    @GameTest
    public void theTooltipsOfTheBayAddUpToThePodsLoad(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(0.5, 2, 0.5));
        try {
            List<OreType> held = List.of(OreType.GOLDIUM, OreType.PLATINIUM, OreType.EINSTEINIUM, OreType.CICATRIUM);
            for (OreType type : held) {
                expect(helper, pod.cargo().tryAdd(pod, OreRegistry.stack(type)), "the bay refused %s", type);
            }
            expect(
                    helper,
                    pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.IRONIUM), DRILLED_IRONIUM),
                    "the bay refused the drilled ironium");
            int summed = 0;
            for (int slot = 0; slot < held.size() + 1; slot++) {
                summed += tenthsOf(shownMass(helper, pod.cargo().stackAt(slot)));
            }
            int load = OreEncumbrance.tenths(PodLoad.of(pod).load());
            expect(
                    helper,
                    summed == 624 && load == 624,
                    "tooltips sum to %s tenths, the load reads %s, both 624",
                    summed,
                    load);
        } finally {
            pod.discard();
        }
        helper.succeed();
    }

    @GameTest
    public void theTooltipsOfWhatAPlayerCarriesAddUpToWhatTheEncumbranceCounts(GameTestHelper helper) {
        MockPlayer holder = MockPlayers.join(helper, "mass-tooltip");
        try {
            List<ItemStack> carried = List.of(
                    OreRegistry.stack(OreType.PLATINIUM),
                    OreRegistry.stack(OreType.EINSTEINIUM),
                    new ItemStack(Items.COAL, 5),
                    new ItemStack(Items.CHARCOAL, 5));
            carried.forEach(stack -> holder.player().getInventory().add(stack.copy()));
            int summed = 0;
            for (ItemStack stack : carried) {
                summed += tenthsOf(shownMass(helper, stack));
            }
            int counted = OreEncumbrance.tenths(OreEncumbrance.carriedMass(holder.player()));
            expect(
                    helper,
                    summed == 400 && counted == 400,
                    "tooltips sum to %s tenths, carried %s, both 400",
                    summed,
                    counted);
        } finally {
            holder.leave();
        }
        helper.succeed();
    }
}
