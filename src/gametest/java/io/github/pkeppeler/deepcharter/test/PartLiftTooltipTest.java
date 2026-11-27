package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.ore.OreEncumbrance;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLoad;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.PartLabel;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTuning;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Server GameTests for #1541: an engine part says what it lifts, in the units of the cockpit's LOAD gauge, and the figure is the climb
 * limit {@link PodLoad} reads for a pod fitted with that part.
 */
public class PartLiftTooltipTest {
    private static final String LIFT_KEY = "item.deepcharter.part.lift";

    /** The text the stack's lift line shows, or null when it has none; fails on more than one. */
    private static @Nullable String shownLift(GameTestHelper helper, ItemStack stack) {
        List<TranslatableContents> lifts =
                stack.getTooltipLines(Item.TooltipContext.of(helper.getLevel()), null, TooltipFlag.NORMAL).stream()
                        .map(Component::getContents)
                        .filter(TranslatableContents.class::isInstance)
                        .map(TranslatableContents.class::cast)
                        .filter(contents -> contents.getKey().equals(LIFT_KEY))
                        .toList();
        expect(helper, lifts.size() <= 1, "%s should have at most one lift line, has %s", stack, lifts.size());
        return lifts.isEmpty() ? null : (String) lifts.getFirst().getArgs()[0];
    }

    /** Each tier a Prospector takes: the part's tooltip shows what the climb limit of a pod with that part is. */
    @GameTest
    public void eachEngineTierShowsTheClimbLimitOfAPodFittedWithIt(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.PROSPECTOR, new Vec3(0.5, 1, 0.5));
        CharterId owner = CharterId.random();
        PodComponents.register(pod, owner);
        int previous = OreEncumbrance.tenths(PodLoad.of(pod).climbLimit());
        for (int tier = 1; tier <= Chassis.PROSPECTOR.tierCap(); tier++) {
            ItemStack part = ComponentItems.mint(helper.getLevel().getServer(), ComponentTrack.ENGINE, tier, owner);
            PodComponents.install(pod, part);
            int tenths = OreEncumbrance.tenths(PodLoad.of(pod).climbLimit());
            String expected = OreEncumbrance.shown(tenths);
            String shown = shownLift(helper, part);
            expect(
                    helper,
                    expected.equals(shown),
                    "a tier %s engine shows Lifts %s, its pod climbs to %s",
                    tier,
                    shown,
                    expected);
            expect(
                    helper,
                    tenths > previous,
                    "tier %s lifts %s, no more than the tier below, %s",
                    tier,
                    shown,
                    previous);
            previous = tenths;
        }
        pod.discard();
        helper.succeed();
    }

    /** The tiers no chassis takes yet cannot be fitted, so they are held to the formula the pod's climb limit is. */
    @GameTest
    public void theTiersNoPodTakesShowWhatThrustPaysAgainstGravity(GameTestHelper helper) {
        PodTuning.Movement movement = PodTuning.DEFAULT.movement();
        double share = 1.0 - (double) movement.gravity() / movement.thrustAcceleration();
        for (int tier = Chassis.PROSPECTOR.tierCap() + 1; tier <= ComponentTrack.ENGINE.maxTier(); tier++) {
            ItemStack part = ComponentItems.stackOf(
                    ComponentTrack.ENGINE, new PartLabel(tier, CharterId.random(), "ENGINE-" + tier));
            float power = PodStats.base().enginePower() * UpgradeTuning.DEFAULT.ratio(ComponentTrack.ENGINE, tier);
            String expected = OreEncumbrance.shown(OreEncumbrance.tenths((float) (power * share)));
            String shown = shownLift(helper, part);
            expect(
                    helper,
                    expected.equals(shown),
                    "a tier %s engine shows Lifts %s, thrust against gravity gives %s",
                    tier,
                    shown,
                    expected);
        }
        helper.succeed();
    }

    @GameTest
    public void onlyALabelledEnginePartHasALiftLine(GameTestHelper helper) {
        for (ComponentTrack track : ComponentTrack.values()) {
            if (track == ComponentTrack.ENGINE) {
                continue;
            }
            ItemStack part = ComponentItems.stackOf(track, new PartLabel(1, CharterId.random(), "X-0001"));
            expect(helper, shownLift(helper, part) == null, "a %s part shows a lift line", track);
        }
        ItemStack unlabelled = new ItemStack(ComponentItems.item(ComponentTrack.ENGINE));
        expect(helper, shownLift(helper, unlabelled) == null, "an engine part with no label shows a lift line");
        helper.succeed();
    }
}
