package io.github.pkeppeler.deepcharter.test;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.layer.BreachEvents;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.sound.HymnMoments;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;

/**
 * Server GameTests for #249: the Lamp Hymn plays to one player at the moments it is for (founding a charter at the colony, joining one,
 * going down a breach, coming up into the colony), in the version for how deep that is, and at no other.
 */
public class HymnMomentsTest {
    private static final int DRAIN_TICKS = 3;

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path);
    }

    @GameTest
    public void everyDepthHasItsOwnHymnSlowerAndMoreWornThanTheLast(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        int layers = LayerChain.count(server.registryAccess());
        List<DeepSound> expected = List.of(DeepSound.HYMN_COLONY, DeepSound.HYMN_LAYER_1, DeepSound.HYMN_LAYER_2);
        expect(
                helper,
                layers + 1 == expected.size(),
                "the chain has %s layers; the hymn has %s versions",
                layers,
                expected.size());
        for (int layer = LayerChain.SURFACE; layer <= layers; layer++) {
            expect(
                    helper,
                    HymnMoments.hymnFor(layer) == expected.get(layer),
                    "layer %s plays %s",
                    layer,
                    HymnMoments.hymnFor(layer));
        }
        expect(
                helper,
                HymnMoments.hymnFor(layers + 5) == DeepSound.HYMN_LAYER_2,
                "a layer with no version of its own plays the deepest");
        helper.succeed();
    }

    @GameTest
    public void foundingACharterPlaysTheColonyHymnToTheFounderAlone(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer founder = MockPlayers.join(helper, "Founder");
        MockPlayer bystander = MockPlayers.join(helper, "Bystander");
        expect(
                helper,
                Charters.found(server, founder.player().getUUID(), "Hymn Test Charter")
                        .isEmpty(),
                "founding should succeed");
        helper.runAfterDelay(DRAIN_TICKS, () -> {
            expect(
                    helper,
                    founder.soundsHeard().equals(List.of(id("hymn.colony"))),
                    "the founder heard %s",
                    founder.soundsHeard());
            expect(helper, bystander.soundsHeard().isEmpty(), "a bystander heard %s", bystander.soundsHeard());
            helper.succeed();
        });
    }

    @GameTest
    public void goingDownABreachPlaysTheHymnOfTheLayerReached(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer pilot = MockPlayers.join(helper, "Pilot");
        ServerLevel surface = server.overworld();
        ServerLevel layer1 = server.getLevel(LayerChain.dimension(1));
        ServerLevel layer2 = server.getLevel(LayerChain.dimension(2));
        BreachEvents.CROSSED.invoker().onCrossed(pilot.player(), surface, layer1, LayerChain.SURFACE, 1);
        BreachEvents.CROSSED.invoker().onCrossed(pilot.player(), layer1, layer2, 1, 2);
        helper.runAfterDelay(DRAIN_TICKS, () -> {
            expect(
                    helper,
                    pilot.soundsHeard().equals(List.of(id("hymn.layer_1"), id("hymn.layer_2"))),
                    "the pilot heard %s",
                    pilot.soundsHeard());
            helper.succeed();
        });
    }

    @GameTest
    public void comingUpIntoTheColonyPlaysTheColonyHymnButClimbingBetweenLayersIsSilent(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer pilot = MockPlayers.join(helper, "Climber");
        ServerLevel surface = server.overworld();
        ServerLevel layer1 = server.getLevel(LayerChain.dimension(1));
        ServerLevel layer2 = server.getLevel(LayerChain.dimension(2));
        BreachEvents.CROSSED.invoker().onCrossed(pilot.player(), layer2, layer1, 2, 1);
        BreachEvents.CROSSED.invoker().onCrossed(pilot.player(), layer1, surface, 1, LayerChain.SURFACE);
        helper.runAfterDelay(DRAIN_TICKS, () -> {
            expect(
                    helper,
                    pilot.soundsHeard().equals(List.of(id("hymn.colony"))),
                    "the climber heard %s",
                    pilot.soundsHeard());
            helper.succeed();
        });
    }

    @GameTest
    public void aCrossingThatIsNotAPlayerIsSilent(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer watcher = MockPlayers.join(helper, "Watcher");
        Entity pod = PodRegistry.POD.create(server.overworld(), EntitySpawnReason.COMMAND);
        BreachEvents.CROSSED
                .invoker()
                .onCrossed(pod, server.overworld(), server.getLevel(LayerChain.dimension(1)), LayerChain.SURFACE, 1);
        helper.runAfterDelay(DRAIN_TICKS, () -> {
            expect(helper, watcher.soundsHeard().isEmpty(), "the watcher heard %s", watcher.soundsHeard());
            helper.succeed();
        });
    }

    @FormatMethod
    private static void expect(GameTestHelper helper, boolean condition, @FormatString String format, Object... args) {
        if (!condition) {
            throw helper.assertionException(Component.literal(String.format(format, args)));
        }
    }
}
