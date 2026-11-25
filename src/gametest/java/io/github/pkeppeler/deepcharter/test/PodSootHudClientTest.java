package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.client.pod.CockpitHud;
import io.github.pkeppeler.deepcharter.client.pod.CockpitReading;
import io.github.pkeppeler.deepcharter.client.pod.PodStatusHud;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook;
import io.github.pkeppeler.deepcharter.client.theme.HudLook;
import io.github.pkeppeler.deepcharter.client.ui.HudKeepClear;
import io.github.pkeppeler.deepcharter.fuel.EngineSoot;
import io.github.pkeppeler.deepcharter.fuel.FuelTuning;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.PodReadout;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.VanillaHudRows;
import java.util.List;
import java.util.Locale;
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
 * Client GameTest for the soot lines of the pod's readout (#1280): none for a clean engine or one inside the grace, the
 * sooted line only once it costs something (never "0%"), the limp line past the limit, and in each state the readout
 * box stays inside the GUI and clear of vanilla's rows at the two smallest GUI sizes ({@link VanillaHudRows}). The
 * line that holds a limp pod at the colony names both ways out and fits the smallest GUI's action bar.
 */
public class PodSootHudClientTest implements FabricClientGameTest {
    private static final int FEET_Y = TestLayers.worldY(1, 10);
    private static final String SOOT = "hud.deepcharter.pod.soot";
    private static final String LIMP = "hud.deepcharter.pod.soot_limp";
    private static final int[][] GUIS = {{320, 240}, {427, 240}};
    /** Pixels kept clear at each end of the action bar line. */
    private static final int ACTION_BAR_MARGIN = 4;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                RoomCarver.carve(one, -3, 3, FEET_Y - 1, FEET_Y + 6, -3, 3, Blocks.AIR);
                player.teleportTo(one, 0, FEET_Y, 0, Set.of(), 0f, 0f, true);
                PodEntity pod = TestEntities.create(PodRegistry.POD, one);
                pod.setPos(0, FEET_Y, 0);
                pod.setNoGravity(true);
                one.addFreshEntity(pod);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
            });
            FuelTuning.Soot soot = FuelTuning.DEFAULT.soot();
            // Clean, inside the grace, just past it (under 1% slower: no line), sooted, and limp.
            expectKeys(context, singleplayer, 0f, List.of());
            expectKeys(context, singleplayer, soot.graceLitres(), List.of());
            expectKeys(context, singleplayer, soot.graceLitres() + 0.01f, List.of());
            expectKeys(context, singleplayer, 7.5f, List.of(SOOT));
            expectClear(context);
            expectKeys(context, singleplayer, soot.limpLitres() - 0.01f, List.of(SOOT));
            expectClear(context);
            expectKeys(context, singleplayer, soot.limpLitres(), List.of(LIMP));
            expectClear(context);
            expectKeys(context, singleplayer, 0f, List.of());
            expectTheLaunchRefusalFits(context);
        }
    }

    /**
     * The line a pilot held at the colony reads (#1280) names both ways out, the service and the free flue scrape, and
     * fits the action bar of the smallest GUI with a margin each side.
     */
    private static void expectTheLaunchRefusalFits(ClientGameTestContext context) {
        context.runOnClient(client -> {
            String line =
                    Component.translatable("deepcharter.fuel.launch_refused").getString();
            String lower = line.toLowerCase(Locale.ROOT);
            if (!lower.contains("service") || !lower.contains("scrape") || !lower.contains("free")) {
                throw new AssertionError("the launch refusal should name the service and the free scrape: " + line);
            }
            int room = GUIS[0][0] - 2 * ACTION_BAR_MARGIN;
            if (client.font.width(line) > room) {
                throw new AssertionError("the launch refusal is " + client.font.width(line)
                        + " px, the action bar of a " + GUIS[0][0] + " px GUI has " + room + ": " + line);
            }
        });
    }

    private static void expectKeys(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, float soot, List<String> expected) {
        singleplayer.getServer().runOnServer(server -> {
            PodEntity pod =
                    TestEntities.vehicle(server.getPlayerList().getPlayers().getFirst(), PodEntity.class);
            pod.setAttached(EngineSoot.STATE, Versioned.of(new EngineSoot.State(soot, 0f, 0f)));
        });
        ClientWait.until(
                context,
                "the readout of an engine with " + soot + " L of soot: " + expected,
                client -> ClientSession.player(client).getVehicle() instanceof PodEntity pod
                        && EngineSoot.soot(pod) == soot
                        && sootKeys(client, pod).equals(expected));
    }

    private static List<String> sootKeys(Minecraft client, PodEntity pod) {
        return PodReadout.texts(client, pod).stream()
                .filter(text -> text.getContents() instanceof TranslatableContents)
                .map(text -> ((TranslatableContents) text.getContents()).getKey())
                .filter(key -> key.equals(SOOT) || key.equals(LIMP))
                .toList();
    }

    /**
     * The plain readout's box, as {@code PodStatusHud} draws it, inside the GUI and clear of vanilla's rows; and the
     * plate.
     */
    private static void expectClear(ClientGameTestContext context) {
        context.runOnClient(client -> {
            PodEntity pod = TestEntities.vehicle(ClientSession.player(client), PodEntity.class);
            HudLook look = HudLook.current();
            List<PodStatusHud.Line> readout = PodStatusHud.readout(ClientSession.player(client), pod);
            int widest = readout.stream()
                    .mapToInt(line -> client.font.width(line.text()))
                    .max()
                    .orElse(0);
            int pitch = client.font.lineHeight + look.podStatusLineGap();
            HudKeepClear.Rect box = new HudKeepClear.Rect(
                    "pod readout",
                    look.podStatusMargin(),
                    look.podStatusMargin(),
                    widest,
                    readout.size() * pitch - look.podStatusLineGap());
            for (int[] gui : GUIS) {
                if (box.right() > gui[0] || box.bottom() > gui[1]) {
                    throw new AssertionError("the readout " + box + " leaves the GUI " + gui[0] + " x " + gui[1]);
                }
                for (HudKeepClear.Rect row : VanillaHudRows.rects(gui[0], gui[1])) {
                    if (box.overlaps(row)) {
                        throw new AssertionError("the readout " + box + " with " + readout.size()
                                + " lines runs into vanilla's " + row.what() + " " + row + " on " + gui[0] + " x "
                                + gui[1]);
                    }
                }
            }
            CockpitLook cockpit = CockpitLook.current();
            CockpitHud.Box plate = CockpitHud.plateBox(
                    client.font,
                    cockpit.ribbon(),
                    cockpit,
                    CockpitHud.rows(pod, CockpitReading.of(pod, 0), cockpit, readout),
                    cockpit.minGuiWidth(),
                    240);
            if (plate.left() < 0 || plate.left() + plate.width() > cockpit.minGuiWidth()) {
                throw new AssertionError("the plate spans " + plate.left() + " to " + (plate.left() + plate.width())
                        + " of a " + cockpit.minGuiWidth() + " px GUI");
            }
        });
    }
}
