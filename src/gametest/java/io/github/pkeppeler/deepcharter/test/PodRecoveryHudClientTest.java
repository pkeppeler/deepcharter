package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.client.pod.CockpitHud;
import io.github.pkeppeler.deepcharter.client.pod.PodStatusHud;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook;
import io.github.pkeppeler.deepcharter.client.theme.HudLook;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.repair.RecoveryTuning;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.PodReadout;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Client GameTest for what the pilot of a stranded pod is told (#963). A pod that runs dry shows STRANDED and, under it, the Company
 * winch crew's countdown with its fee, read from the pod's synced clock. Fuelling the pod takes both lines away.
 */
public class PodRecoveryHudClientTest implements FabricClientGameTest {
    private static final int FEET_Y = TestLayers.worldY(1, 10);
    private static final String STRANDED = "hud.deepcharter.pod.stranded";
    private static final String RECOVERY = "hud.deepcharter.pod.recovery";

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                RoomCarver.carve(one, -3, 3, FEET_Y - 1, FEET_Y + 6, -3, 3, Blocks.AIR);
            });
            ClientWait.lightSettled(context, singleplayer.getServer(), 1, 0, 0, 3);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(one, 0, FEET_Y, 0, Set.of(), 0f, 0f, true);
                PodEntity pod = TestEntities.create(PodRegistry.POD, one);
                pod.setPos(0, FEET_Y, 0);
                pod.setNoGravity(true);
                one.addFreshEntity(pod);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
                // The last sliver of fuel: the pod burns it and strands itself.
                pod.setFuel(0.01f);
            });
            ClientWait.until(
                    context,
                    "the stranded pod's readout names the winch crew",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity pod
                            && pod.stranded()
                            && keys(client, pod).contains(RECOVERY));
            List<Object> shown = context.computeOnClient(client -> Arrays.asList(argsOf(
                    PodReadout.texts(client, TestEntities.vehicle(ClientSession.player(client), PodEntity.class)))));
            int seconds = (Integer) shown.get(0);
            if (seconds < 1 || seconds > RecoveryTuning.DEFAULT.delayTicks() / 20) {
                throw new AssertionError("the countdown reads whole seconds of the minute, shows " + seconds);
            }
            if (!Long.valueOf(RecoveryTuning.DEFAULT.feeAt(1)).equals(shown.get(1))) {
                throw new AssertionError(
                        "the line names the fee of " + RecoveryTuning.DEFAULT.feeAt(1) + ", shows " + shown.get(1));
            }

            expectEveryLineFitsWhereItIsShown(context);

            singleplayer.getServer().runOnServer(server -> {
                PodEntity pod =
                        TestEntities.vehicle(server.getPlayerList().getPlayers().getFirst(), PodEntity.class);
                pod.setFuel(50f);
                pod.setStranded(false);
            });
            ClientWait.until(
                    context,
                    "a pod with fuel again shows neither line",
                    client -> ClientSession.player(client).getVehicle() instanceof PodEntity pod
                            && !pod.stranded()
                            && !keys(client, pod).contains(STRANDED)
                            && !keys(client, pod).contains(RECOVERY));
        }
    }

    /**
     * The widest numbers each line can carry (a minute, a $9,999 fee, a $9,999,999 account), measured with the client's font against the narrowest GUI that draws the
     * cockpit ({@code minGuiWidth}). Two bounds, by where a line is shown:
     * <ul>
     *   <li>The pilot's messages ({@code recovery_called}, {@code recovery_done}, {@code recovery_short}, {@code teleport_blocked}) are overlay lines: centred on the screen,
     *       never wrapped, so they must fit the GUI's width less a margin each side.
     *   <li>The HUD line is a row of the cockpit's plate, which sizes itself to its widest row and is anchored to the GUI: the plate holding that row alone must lie inside
     *       the GUI at the narrowest width, which also leaves it clear of its border.
     * </ul>
     */
    private static void expectEveryLineFitsWhereItIsShown(ClientGameTestContext context) {
        List<String> problems = context.computeOnClient(client -> {
            CockpitLook look = CockpitLook.current();
            int guiWidth = look.minGuiWidth();
            int clip = guiWidth - 2 * HudLook.current().podStatusMargin();
            List<String> found = new ArrayList<>();
            for (Component message : List.of(
                    Component.translatable("message.deepcharter.repair.recovery_called", 60, 9_999L),
                    Component.translatable("message.deepcharter.repair.recovery_done", 9_999_999L),
                    Component.translatable("message.deepcharter.repair.recovery_short", 9_999_999L, 9_999_999L),
                    Component.translatable("message.deepcharter.repair.teleport_blocked"))) {
                int width = client.font.width(message);
                if (width > clip) {
                    found.add("\"" + message.getString() + "\" is " + width + " px wide, the overlay has " + clip);
                }
            }
            PodStatusHud.Line hud = new PodStatusHud.Line(
                    Component.translatable("hud.deepcharter.pod.recovery", 60, 9_999L),
                    HudLook.current().podStatusColor());
            CockpitHud.Box plate = CockpitHud.plateBox(
                    client.font, look.ribbon(), look, List.of(CockpitHud.Row.of(hud)), guiWidth, 240);
            if (plate.left() < 0 || plate.left() + plate.width() > guiWidth) {
                found.add("the plate holding \"" + hud.text().getString() + "\" spans " + plate.left() + " to "
                        + (plate.left() + plate.width()) + " of a " + guiWidth + " px GUI");
            }
            return found;
        });
        if (!problems.isEmpty()) {
            throw new AssertionError("Recovery lines that do not fit the narrowest GUI: " + problems);
        }
    }

    private static List<String> keys(Minecraft client, PodEntity pod) {
        return PodReadout.texts(client, pod).stream()
                .filter(text -> text.getContents() instanceof TranslatableContents)
                .map(text -> ((TranslatableContents) text.getContents()).getKey())
                .toList();
    }

    /** The arguments of the recovery line of {@code texts}. */
    private static Object[] argsOf(List<Component> texts) {
        return texts.stream()
                .map(Component::getContents)
                .filter(contents -> contents instanceof TranslatableContents)
                .map(contents -> (TranslatableContents) contents)
                .filter(contents -> contents.getKey().equals(RECOVERY))
                .findFirst()
                .orElseThrow()
                .getArgs();
    }
}
