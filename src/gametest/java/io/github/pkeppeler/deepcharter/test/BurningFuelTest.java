package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.fuel.EngineSoot;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.UseEntity;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/**
 * What the engine burns now (#1438): the solid fuel still in the tank, which burns first, by name and with its litres;
 * pump fuel once it has burned. A playtester's engine sooted after a pump fill because charcoal fed earlier was still
 * in the tank, and nothing said so.
 */
public class BurningFuelTest {
    private static final float TOLERANCE = 0.001f;

    private static Identifier id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item);
    }

    /** Feeds {@code items} of {@code fuel} to the pod by hand, as a player does. */
    private static void feed(PodEntity pod, MockPlayer player, Item fuel, int items) {
        for (int item = 0; item < items; item++) {
            player.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(fuel));
            UseEntity.on(player.player(), pod.level(), InteractionHand.MAIN_HAND, pod);
        }
    }

    /** Burns {@code litres} and writes it, as the pod's tick does a quantum at a time. */
    private static void burn(PodEntity pod, float litres) {
        EngineSoot.burned(pod, litres);
        EngineSoot.flush(pod);
    }

    private static void expectPumpFuel(GameTestHelper helper, PodEntity pod, String when) {
        Optional<EngineSoot.Solid> solid = EngineSoot.solidLeft(pod);
        expect(helper, solid.isEmpty(), "%s the pod should burn pump fuel, it burns %s", when, solid);
    }

    private static void expectSolid(
            GameTestHelper helper, PodEntity pod, Optional<Identifier> fuel, float litres, String when) {
        EngineSoot.Solid solid = EngineSoot.solidLeft(pod)
                .orElseThrow(
                        () -> helper.assertionException("%s the pod should burn solid fuel, it burns pump fuel", when));
        expect(helper, solid.fuel().equals(fuel), "%s the solid fuel should be %s, got %s", when, fuel, solid.fuel());
        expect(
                helper,
                Math.abs(solid.litres() - litres) <= TOLERANCE,
                "%s %s L of solid fuel should be left, got %s",
                when,
                litres,
                solid.litres());
    }

    @GameTest
    public void aPodWithOnlyPumpFuelBurnsPumpFuel(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
        try {
            pod.setFuel(60f);
            expectPumpFuel(helper, pod, "with 60% of pump fuel");
            helper.succeed();
        } finally {
            pod.discard();
        }
    }

    @GameTest
    public void charcoalFedBeforeAPumpFillBurnsFirstAndThenThePodBurnsPumpFuel(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
        MockPlayer player = MockPlayers.join(helper, "charcoal-feeder");
        try {
            player.player().setGameMode(GameType.SURVIVAL);
            pod.setFuel(0f);
            feed(pod, player, Items.CHARCOAL, 3);
            expectSolid(helper, pod, Optional.of(id(Items.CHARCOAL)), 3f, "after 3 charcoal");
            // A pump fill adds clean litres; the charcoal's account stays, and it burns first.
            pod.setFuel(100f);
            expectSolid(helper, pod, Optional.of(id(Items.CHARCOAL)), 3f, "after a pump fill");
            burn(pod, 2.5f);
            expectSolid(helper, pod, Optional.of(id(Items.CHARCOAL)), 0.5f, "with half a litre left");
            burn(pod, 0.5f);
            expectPumpFuel(helper, pod, "once the charcoal has burned");
            EngineSoot.State state = Versioned.orThrow(pod, EngineSoot.STATE);
            expect(helper, state.solid().isEmpty(), "a burned-out account names no fuel, got %s", state);
            helper.succeed();
        } finally {
            player.leave();
            pod.discard();
        }
    }

    @GameTest
    public void coalOnTopOfCharcoalIsSolidFuelOfNoOneNameAndANewFeedNamesItsOwn(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
        MockPlayer player = MockPlayers.join(helper, "coal-mixer");
        try {
            player.player().setGameMode(GameType.SURVIVAL);
            pod.setFuel(0f);
            feed(pod, player, Items.CHARCOAL, 2);
            feed(pod, player, Items.CHARCOAL, 1);
            expectSolid(helper, pod, Optional.of(id(Items.CHARCOAL)), 3f, "after charcoal twice");
            feed(pod, player, Items.COAL, 1);
            expectSolid(helper, pod, Optional.empty(), 5f, "after coal on charcoal");
            burn(pod, 5f);
            expectPumpFuel(helper, pod, "once the mix has burned");
            feed(pod, player, Items.COAL, 1);
            expectSolid(helper, pod, Optional.of(id(Items.COAL)), 2f, "after coal alone");
            helper.succeed();
        } finally {
            player.leave();
            pod.discard();
        }
    }

    @GameTest
    public void theSolidLeftIsNeverMoreThanTheTankHoldsAndAnEmptyTankBurnsNothingSolid(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
        MockPlayer player = MockPlayers.join(helper, "drained");
        try {
            player.player().setGameMode(GameType.SURVIVAL);
            pod.setFuel(0f);
            feed(pod, player, Items.CHARCOAL, 10);
            // A recovery or a reserve tank sets the tank to a few litres of other fuel: only those can be solid.
            float tank = EngineSoot.solidLeft(pod).orElseThrow().litres() / 10f;
            pod.setFuel(pod.fuel() / 10f);
            expectSolid(helper, pod, Optional.of(id(Items.CHARCOAL)), tank, "with a tenth of the tank left");
            pod.setFuel(0f);
            expectPumpFuel(helper, pod, "with an empty tank");
            helper.succeed();
        } finally {
            player.leave();
            pod.discard();
        }
    }
}
