package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.client.upgrade.UpgradeScreen;
import io.github.pkeppeler.deepcharter.terminal.TerminalOpenPayload;
import io.github.pkeppeler.deepcharter.test.UpgradeTerminalClientTest;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.UpgradeShown;
import io.github.pkeppeler.deepcharter.test.support.UseEntity;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTerminal;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * Evidence scenario "upgrade-tracks" (#1440): the upgrade terminal with some tracks fitted and some not. The Mole has a crafted drill
 * unit fitted (tier 1) and a bought tier 2 hull; its engine is stock, and it has no scanner and no spoil hopper. The pilot opens the
 * terminal and picks each of those tracks in turn; one still of each ({@code track-<id>}). It clicks only the track buttons, whose
 * labels did not change, so the same scenario records the screen before and after the change.
 */
public class UpgradeTracksScenario extends EvidenceScenario {
    private static final List<ComponentTrack> SHOWN = List.of(
            ComponentTrack.DRILL,
            ComponentTrack.HULL,
            ComponentTrack.ENGINE,
            ComponentTrack.SCANNER,
            ComponentTrack.SPOIL_HOPPER);
    private static final int SETTLE_TICKS = 10;

    @Override
    protected String name() {
        return "upgrade-tracks";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        ClientChecks.withHudHidden(context, () -> runHidden(context));
    }

    private void runHidden(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            UpgradeTerminalClientTest.Scene scene =
                    singleplayer.getServer().computeOnServer(UpgradeTerminalClientTest::setUp);
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                Entity pod = TestEntities.require(server.overworld(), scene.pod());
                player.setItemInHand(
                        InteractionHand.MAIN_HAND,
                        new ItemStack(BuiltInRegistries.ITEM.getValue(
                                Identifier.fromNamespaceAndPath("deepcharter", "drill_unit"))));
                if (!UseEntity.on(player, pod.level(), InteractionHand.MAIN_HAND, pod)
                        .consumesAction()) {
                    throw new AssertionError("the drill unit should fit the Mole");
                }
                UpgradeTerminal.buy(server, player, scene.terminal(), ComponentTrack.HULL, 2)
                        .ifPresent(refusal -> {
                            throw new AssertionError("buying the tier 2 hull: " + refusal.getString());
                        });
            });
            context.runOnClient(client -> ClientPlayNetworking.send(new TerminalOpenPayload(scene.terminal())));
            ClientWait.screen(context, UpgradeScreen.class);
            UpgradeScreen screen = context.computeOnClient(client -> ClientSession.screen(client, UpgradeScreen.class));
            ClientWait.until(
                    context,
                    "the upgrade screen finished typing with the pod",
                    client -> screen.typewriter().done()
                            && screen.upgrade().flatMap(view -> view.pod()).isPresent(),
                    client -> "typewriter text '" + screen.typewriter().text() + "'");
            for (ComponentTrack track : SHOWN) {
                context.runOnClient(client -> UpgradeShown.reveal(screen, track));
                String label = context.computeOnClient(client -> trackLabel(screen, track));
                context.clickScreenButton(label);
                context.waitTicks(SETTLE_TICKS); // tick-wait: the screen rebuilds its widgets on the click itself
                frame(context);
                screenshot(context, "track-" + track.id().replace('_', '-'));
            }
            context.setScreen(() -> null);
        }
    }

    /** The label of the track's button, with the tier the pod has on it. Client thread. */
    private static String trackLabel(UpgradeScreen screen, ComponentTrack track) {
        int installed = screen.upgrade()
                .flatMap(view -> view.pod())
                .orElseThrow()
                .slots()
                .get(track.ordinal())
                .installed();
        return Component.translatable(
                        "screen.deepcharter.upgrade.track",
                        Component.translatable("deepcharter.upgrade.track." + track.id())
                                .getString(),
                        installed)
                .getString();
    }
}
