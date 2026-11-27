package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.ore.OreEncumbrance;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLoad;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for #1538: an ore's tooltip names its mass in the units the pod's load is counted in, read from the same {@code
 * OreEncumbrance.massOf} the cockpit's LOAD gauge is made of, so the tooltips of a held set add up to the load.
 */
public class OreMassTooltipTest {
    private static final String MASS_KEY = "deepcharter.ore.tooltip.mass";
    private static final String HEAVY_KEY = "deepcharter.ore.tooltip.heavy";
    private static final String HAUL_KEY = "deepcharter.ore.tooltip.haul";

    /** One ironium drilled at a mass that is not its own and not a whole number, to show the rounding. */
    private static final float DRILLED_IRONIUM = 7.4f;

    private static List<Component> lines(GameTestHelper helper, ItemStack stack) {
        return stack.getTooltipLines(Item.TooltipContext.of(helper.getLevel()), null, TooltipFlag.NORMAL);
    }

    /** The number the stack's mass line shows; fails when it has none or more than one. */
    private static int shownMass(GameTestHelper helper, ItemStack stack) {
        List<TranslatableContents> masses = lines(helper, stack).stream()
                .map(Component::getContents)
                .filter(TranslatableContents.class::isInstance)
                .map(TranslatableContents.class::cast)
                .filter(contents -> contents.getKey().equals(MASS_KEY))
                .toList();
        expect(helper, masses.size() == 1, "%s should have one mass line, has %s", stack, masses.size());
        return (Integer) masses.getFirst().getArgs()[0];
    }

    @GameTest
    public void everyOreTooltipShowsItsMassAndKeepsTheHeavyAndHaulLines(GameTestHelper helper) {
        for (OreType type : OreType.values()) {
            ItemStack ore = OreRegistry.stack(type);
            int shown = shownMass(helper, ore);
            int expected = OreEncumbrance.shown(type.mass());
            expect(helper, shown == expected, "%s shows mass %s, its mass is %s", type, shown, expected);
            expect(
                    helper,
                    shown == OreEncumbrance.shown(OreEncumbrance.massOf(ore)),
                    "%s shows %s, the encumbrance counts %s",
                    type,
                    shown,
                    OreEncumbrance.massOf(ore));
            List<Component> lines = lines(helper, ore);
            for (String key : List.of(HEAVY_KEY, HAUL_KEY)) {
                expect(
                        helper,
                        lines.stream()
                                .anyMatch(line -> line.getContents() instanceof TranslatableContents contents
                                        && contents.getKey().equals(key)),
                        "%s lost its %s line",
                        type,
                        key);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void aDrilledOreShowsTheMassItWasDrilledAt(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(0.5, 2, 0.5));
        ItemStack plain = OreRegistry.stack(OreType.IRONIUM);
        expect(helper, pod.cargo().tryAdd(pod, plain, DRILLED_IRONIUM), "the bay refused the drilled ironium");
        int shown = shownMass(helper, pod.cargo().stackAt(0));
        pod.discard();
        expect(
                helper,
                shown == Math.round(DRILLED_IRONIUM),
                "ironium drilled at %s should show %s, shows %s",
                DRILLED_IRONIUM,
                Math.round(DRILLED_IRONIUM),
                shown);
        helper.succeed();
    }

    @GameTest
    public void theTooltipsOfAHeldSetSumToWhatThePodsLoadReads(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, new Vec3(0.5, 2, 0.5));
        List<OreType> held = List.of(OreType.GOLDIUM, OreType.PLATINIUM, OreType.EINSTEINIUM, OreType.CICATRIUM);
        for (OreType type : held) {
            expect(helper, pod.cargo().tryAdd(pod, OreRegistry.stack(type)), "the bay refused %s", type);
        }
        int summed = 0;
        for (int slot = 0; slot < held.size(); slot++) {
            summed += shownMass(helper, pod.cargo().stackAt(slot));
        }
        float load = PodLoad.of(pod).load();
        pod.discard();
        if (summed != OreEncumbrance.shown(load)) {
            throw failure(
                    helper,
                    "the tooltips of %s sum to %s, the pod's load reads %s",
                    held,
                    summed,
                    OreEncumbrance.shown(load));
        }
        helper.succeed();
    }
}
