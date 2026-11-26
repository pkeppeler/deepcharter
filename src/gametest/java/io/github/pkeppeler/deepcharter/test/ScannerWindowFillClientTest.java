package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import io.github.pkeppeler.deepcharter.client.scanner.ScannerHud;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook.Decal;
import io.github.pkeppeler.deepcharter.client.theme.ScannerLook;
import io.github.pkeppeler.deepcharter.pod.Chassis;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.scanner.ScanArea;
import io.github.pkeppeler.deepcharter.scanner.ScannerTuning;
import io.github.pkeppeler.deepcharter.test.ScannerHudTest.HudShot;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.ScannerPods;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import java.util.Set;
import java.util.TreeSet;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * Client GameTest for #1452: at every scanner tier, the cockpit's scanner window is full. The map's whole cells cover the window, or
 * what is left of it is the instrument's out-of-range raster, never the flat air colour of an empty cell. Run at GUI scales 2 and 4,
 * for the window sizes the cockpit supports.
 */
public class ScannerWindowFillClientTest implements FabricClientGameTest {
    private static final ScannerTuning TUNING = ScannerTuning.DEFAULT;
    private static final int RGB = 0xFFFFFF;
    /** The best scanner tier a shipped pod works: tier 4 parts exist, but no chassis takes one yet, so its area is checked by geometry alone. */
    private static final int LIVE_TIERS = Chassis.PROSPECTOR.tierCap();

    private static final int TOP_TIER = ComponentTrack.SCANNER.maxTier();
    /** The bezel's rounded corners cover this many pixels of the window rectangle's corners (BEZEL_RADIUS in tools/cockpit_ribbon.py, plus its gasket). */
    private static final int CORNER = 4;

    /** The GUI scales the cockpit is checked at, with a window that gives each the same 427 x 240 GUI. */
    private static final int[][] WINDOW_AT_SCALE = {{854, 480, 2}, {1708, 960, 4}};

    private static String hex(Set<Integer> colours) {
        return colours.stream().map("%06X"::formatted).sorted().toList().toString();
    }

    /**
     * Seats the first player in a Prospector with a scanner of {@code tier} and waits for the client to ride it and the HUD to rescan. A Mole's
     * parts cap at tier 2, so a tier above that needs the Prospector.
     */
    public static void ride(ClientGameTestContext context, TestServerContext server, int tier) {
        int fitted = server.computeOnServer(minecraftServer -> {
            ServerPlayer player = minecraftServer.getPlayerList().getPlayers().getFirst();
            PodEntity pod = TestEntities.create(PodRegistry.PROSPECTOR, player.level());
            pod.setPos(player.position());
            player.level().addFreshEntity(pod);
            ScannerPods.fit(minecraftServer, player, pod, tier);
            if (!player.startRiding(pod)) {
                throw new AssertionError("the player could not mount the new pod");
            }
            return PodComponents.effectiveTier(pod, ComponentTrack.SCANNER);
        });
        require(fitted == tier, "the pod should work its scanner at tier %d, not %d".formatted(tier, fitted));
        ClientWait.until(
                context,
                "the client riding a pod",
                client -> client.player != null && client.player.getVehicle() instanceof PodEntity);
        context.waitTicks(2 * TUNING.rescanTicks() + 2);
    }

    /** How a drawn window falls short of full, or null when it is full. */
    static @Nullable String gaps(HudShot shot) {
        Decal window = shot.window();
        ScanArea area = shot.area();
        if (window == null) {
            return "the client is not in the cockpit, so there is no scanner window";
        }
        int cell = ScannerHud.windowCellSize(window, area);
        int mapLeft = ScannerHud.windowCellLeft(shot.guiWidth(), window, area, -area.halfWidth());
        int mapTop = ScannerHud.windowCellTop(shot.guiHeight(), window, area, area.up());
        int windowLeft = window.left(shot.guiWidth());
        int windowTop = window.top(shot.guiHeight());
        Set<Integer> margin = new TreeSet<>();
        Set<Integer> covered = new TreeSet<>();
        for (int y = 0; y < window.height(); y++) {
            for (int x = 0; x < window.width(); x++) {
                boolean cornerX = x < CORNER || x >= window.width() - CORNER;
                boolean cornerY = y < CORNER || y >= window.height() - CORNER;
                int guiX = windowLeft + x;
                int guiY = windowTop + y;
                boolean onMap = guiX >= mapLeft
                        && guiX < mapLeft + area.columns() * cell
                        && guiY >= mapTop
                        && guiY < mapTop + area.rows() * cell;
                if (!(cornerX && cornerY)) {
                    int rgb = shot.image()
                                    .getRGB(
                                            guiX * shot.scale() + shot.scale() / 2,
                                            guiY * shot.scale() + shot.scale() / 2)
                            & RGB;
                    (onMap ? covered : margin).add(rgb);
                }
            }
        }
        ScannerLook look = ScannerLook.current();
        Set<Integer> raster = Set.of(look.outOfRangeColor() & RGB, look.outOfRangeLineColor() & RGB);
        if (margin.isEmpty()) {
            // The map alone fills the window, so no pixel of it may be the raster showing through.
            Set<Integer> showing = new TreeSet<>(covered);
            showing.retainAll(raster);
            return showing.isEmpty()
                    ? null
                    : "the map should cover the window, but raster colours %s show in it".formatted(hex(showing));
        }
        if (!raster.containsAll(margin)) {
            return "the margin holds colours %s, outside the out-of-range raster %s"
                    .formatted(hex(margin), hex(raster));
        }
        if (margin.size() < raster.size()) {
            return "the margin shows %s, not the whole raster %s".formatted(hex(margin), hex(raster));
        }
        return null;
    }

    /** The area of the top tier is wider and taller than the window, so its whole cells reach every edge of it. */
    private static void requireCovered(ClientGameTestContext context, int scale) {
        ScanArea area = TUNING.area(TOP_TIER);
        int[] gaps = context.computeOnClient(client -> {
            int guiWidth = client.getWindow().getGuiScaledWidth();
            int guiHeight = client.getWindow().getGuiScaledHeight();
            Decal window = CockpitLook.current().ribbon().scanner();
            int cell = ScannerHud.windowCellSize(window, area);
            int left = ScannerHud.windowCellLeft(guiWidth, window, area, -area.halfWidth());
            int top = ScannerHud.windowCellTop(guiHeight, window, area, area.up());
            return new int[] {
                left - window.left(guiWidth),
                top - window.top(guiHeight),
                window.left(guiWidth) + window.width() - (left + area.columns() * cell),
                window.top(guiHeight) + window.height() - (top + area.rows() * cell)
            };
        });
        String[] sides = {"left", "top", "right", "bottom"};
        for (int side = 0; side < sides.length; side++) {
            require(
                    gaps[side] <= 0,
                    "tier %d at GUI %d: the map leaves a gap of %d px at the %s of the window"
                            .formatted(TOP_TIER, scale, gaps[side], sides[side]));
        }
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            TestServerContext server = singleplayer.getServer();
            server.runCommand("time set midnight");
            context.waitTicks(20);
            try {
                for (int[] size : WINDOW_AT_SCALE) {
                    context.getInput().resizeWindow(size[0], size[1]);
                    context.runOnClient(client -> client.options.guiScale().set(size[2]));
                    context.waitTicks(10);
                    for (int tier = 1; tier <= LIVE_TIERS; tier++) {
                        ride(context, server, tier);
                        HudShot shot = HudShot.take(
                                context, "scanner-window-fill-%d-gui%d".formatted(tier, size[2]), TUNING.area(tier));
                        String gap = gaps(shot);
                        require(gap == null, "tier %d at GUI %d: %s".formatted(tier, size[2], gap));
                        ScannerHudTest.leavePod(context, server);
                    }
                    requireCovered(context, size[2]);
                }
            } finally {
                context.getInput().resizeWindow(WINDOW_AT_SCALE[0][0], WINDOW_AT_SCALE[0][1]);
                context.runOnClient(client -> client.options.guiScale().set(WINDOW_AT_SCALE[0][2]));
            }
        }
    }
}
