package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.client.pod.CockpitHud;
import io.github.pkeppeler.deepcharter.client.pod.CockpitReading;
import io.github.pkeppeler.deepcharter.client.pod.PodStatusHud;
import io.github.pkeppeler.deepcharter.client.theme.CockpitLook;
import io.github.pkeppeler.deepcharter.client.theme.PanelLook;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodLoad;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.test.support.ClientSession;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * Client GameTest for the cockpit's load gauge and overloaded lamp (#1449), read from the pixels of the Ribbon at GUI scale 2: the gauge lights a segment for each tenth
 * of the climb limit the load reaches, with the limit's mark after the tenth; the bar turns the limit colour and the fifth lamp lights, with no key held, once the load
 * reaches the climb limit, whether the weight is in the bay or in the pilot's pack (which reaches the client through the synced extra mass); ore taken out of the bay,
 * as a dump takes it, puts the lamp out. The LOAD title is drawn under the gauge.
 */
public class PodLoadClientTest implements FabricClientGameTest {
    /** High in layer 1, far above the signal decay near its floor. */
    private static final int FEET_Y = TestLayers.worldY(1, 150);

    private static final int GUI_SCALE = 2;
    private static final String OVERLOADED = "hud.deepcharter.pod.overloaded";
    /** The lamp the overload lights: the last of the Ribbon's row. */
    private static final int OVERLOAD_LAMP = CockpitHud.LAMPS - 1;

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            context.waitTicks(40); // tick-wait: the new world's first ticks, as every cockpit test waits
            context.runOnClient(client -> client.options.guiScale().set(GUI_SCALE));
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                // The floor stays rock: the pod stands on it under gravity, which the climb limit is
                // worked out against.
                RoomCarver.carve(one, -4, 4, FEET_Y, FEET_Y + 8, -4, 4, Blocks.AIR);
            });
            ClientWait.lightSettled(context, singleplayer.getServer(), 1, 0, 0, 4);
            singleplayer.getServer().runOnServer(server -> {
                ServerLevel one = TestLayers.level(server, 1);
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
                player.teleportTo(one, 0.5, FEET_Y, 0.5, Set.of(), 0f, 0f, true);
                PodEntity pod = TestEntities.create(PodRegistry.POD, one);
                pod.setPos(0.5, FEET_Y, 0.5);
                one.addFreshEntity(pod);
                if (!player.startRiding(pod)) {
                    throw new AssertionError("the player could not mount the pod");
                }
            });
            ClientWait.until(context, "the Ribbon over the seated pilot", CockpitHud::showsReadout);

            // A light pod: an empty bay and pack.
            State light = load(context, singleplayer, 0, 0, false);
            expectGauge(light, 0, false, "an empty pod");
            expectTitle(context, light);
            // Near the limit: two einsteinium are 8 tenths of a stock Mole's climb limit.
            State near = load(context, singleplayer, 2, 0, false);
            expectGauge(near, 8, false, "two einsteinium in the bay");
            // Over it, with no key held: a third.
            State over = load(context, singleplayer, 3, 0, true);
            expectGauge(over, 12, true, "three einsteinium in the bay");
            // A dump takes ore out of the bay: back under, and the lamp goes out.
            State dumped = load(context, singleplayer, 2, 0, false);
            expectGauge(dumped, 8, false, "a dump of one einsteinium");
            // The pilot's pack weighs on the client too: two ironium on top of the bay reach the limit.
            State pack = load(context, singleplayer, 2, 2, true);
            expectGauge(pack, 10, true, "two einsteinium in the bay and two ironium in the pack");
            load(context, singleplayer, 0, 0, false);
        }
    }

    /** What the client reads: the cockpit's reading, the readout's keys and the theme. */
    private record Seen(CockpitReading reading, List<String> readout, CockpitLook look) {}

    /** One look at the cockpit: what the client reads, and a shot of the screen. */
    private record State(CockpitReading reading, List<String> readout, BufferedImage shot, CockpitLook look) {}

    /**
     * Puts {@code einsteinium} in the bay and {@code ironium} in the pilot's pack (and nothing else in either), waits for the client to read it, and checks the
     * readout's OVERLOADED line is there exactly when {@code overloaded}.
     */
    private static State load(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            int einsteinium,
            int ironium,
            boolean overloaded) {
        float expected = einsteinium * OreType.EINSTEINIUM.mass() + ironium * OreType.IRONIUM.mass();
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            PodEntity pod = TestEntities.vehicle(player, PodEntity.class);
            int held = pod.cargo().count(OreType.EINSTEINIUM);
            if (held > einsteinium) {
                pod.cargo().take(pod, OreType.EINSTEINIUM, held - einsteinium);
            }
            for (int i = held; i < einsteinium; i++) {
                pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.EINSTEINIUM));
            }
            player.getInventory().clearContent();
            if (ironium > 0) {
                player.getInventory().add(new ItemStack(OreRegistry.item(OreType.IRONIUM), ironium));
            }
        });
        String what = einsteinium + " einsteinium in the bay and " + ironium + " ironium in the pack";
        ClientWait.until(
                context,
                "the client's load of " + what,
                client -> ClientSession.player(client).getVehicle() instanceof PodEntity pod
                        && Math.abs(PodLoad.of(pod).load() - expected) < 1e-3f
                        && CockpitReading.of(pod, 0).overloaded() == overloaded,
                client -> ClientSession.player(client).getVehicle() instanceof PodEntity pod
                        ? "load " + PodLoad.of(pod)
                        : "not seated");
        Seen seen = context.computeOnClient(client -> {
            PodEntity pod = TestEntities.vehicle(ClientSession.player(client), PodEntity.class);
            List<String> keys = PodStatusHud.readout(ClientSession.player(client), pod).stream()
                    .map(line ->
                            line.text().getContents() instanceof TranslatableContents contents ? contents.getKey() : "")
                    .toList();
            return new Seen(CockpitReading.of(pod, 0), keys, CockpitLook.current());
        });
        if (seen.readout().contains(OVERLOADED) != overloaded) {
            throw new AssertionError("With " + what + " the readout " + (overloaded ? "says" : "does not say")
                    + " OVERLOADED, got " + seen.readout());
        }
        return new State(
                seen.reading(), seen.readout(), shot(context, "pod-load-" + einsteinium + "-" + ironium), seen.look());
    }

    private static BufferedImage shot(ClientGameTestContext context, String name) {
        context.runOnClient(client -> client.gui.toastManager().clear());
        context.waitTicks(3); // tick-wait: the frame after the synced load draws it
        Path file = context.takeScreenshot(name);
        try {
            return ImageIO.read(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int rgb(BufferedImage shot, int guiX, int guiY) {
        return shot.getRGB(guiX * GUI_SCALE, guiY * GUI_SCALE) & 0xFFFFFF;
    }

    /**
     * The gauge lights {@code lit} segments (at most all of them) in the gauge colour, or the limit colour when {@code overloaded}, and the rest are unlit; the limit's
     * mark is in the gap after segment {@code loadLimit}; the overloaded lamp is lit exactly when {@code overloaded}.
     */
    private static void expectGauge(State state, int lit, boolean overloaded, String what) {
        CockpitLook look = state.look();
        CockpitLook.Layout layout = look.ribbon();
        CockpitLook.Gauge gauge = layout.cargo();
        int guiWidth = state.shot().getWidth() / GUI_SCALE;
        int guiHeight = state.shot().getHeight() / GUI_SCALE;
        int left = gauge.area().left(guiWidth);
        int middle = gauge.area().top(guiHeight) + gauge.area().height() / 2;
        int segment = gauge.area().width() / gauge.segments();
        int on = (overloaded ? look.lowColor() : look.cargoColor()) & 0xFFFFFF;
        int off = look.offColor() & 0xFFFFFF;
        for (int i = 0; i < gauge.segments(); i++) {
            int at = rgb(state.shot(), left + i * segment, middle);
            int want = i < Math.min(lit, gauge.segments()) ? on : off;
            if (at != want) {
                throw new AssertionError(
                        "With " + what + " (load " + state.reading().load() + " of the climb limit) segment " + i
                                + " is " + Integer.toHexString(at) + ", expected " + Integer.toHexString(want));
            }
        }
        int mark = rgb(state.shot(), left + layout.loadLimit() * segment - 1, middle);
        if (mark != (look.lowColor() & 0xFFFFFF)) {
            throw new AssertionError("The climb limit's mark after segment " + layout.loadLimit() + " is "
                    + Integer.toHexString(mark) + ", expected the limit colour");
        }
        PanelLook.Decal lamps = layout.lamps();
        int size = (lamps.width() - look.lampGap() * (CockpitHud.LAMPS - 1)) / CockpitHud.LAMPS;
        int lamp = rgb(
                state.shot(),
                lamps.left(guiWidth) + OVERLOAD_LAMP * (size + look.lampGap()) + size / 2,
                lamps.top(guiHeight) + lamps.height() / 2);
        int wantLamp = (overloaded ? look.lowColor() : look.offColor()) & 0xFFFFFF;
        if (lamp != wantLamp) {
            throw new AssertionError("With " + what + " the overloaded lamp is " + Integer.toHexString(lamp)
                    + ", expected " + Integer.toHexString(wantLamp));
        }
    }

    /** The LOAD title's plate is under the gauge and its text, in the gauge's colour, is drawn on it. */
    private static void expectTitle(ClientGameTestContext context, State state) {
        CockpitHud.Box box = context.computeOnClient(client -> CockpitHud.loadTitleBox(
                client.font,
                state.look().ribbon(),
                state.look(),
                client.getWindow().getGuiScaledWidth(),
                client.getWindow().getGuiScaledHeight()));
        int color = state.look().cargoColor() & 0xFFFFFF;
        int ink = 0;
        for (int y = box.top() * GUI_SCALE; y < (box.top() + box.height()) * GUI_SCALE; y++) {
            for (int x = box.left() * GUI_SCALE; x < (box.left() + box.width()) * GUI_SCALE; x++) {
                if (near(state.shot().getRGB(x, y) & 0xFFFFFF, color)) {
                    ink++;
                }
            }
        }
        // LOAD in the game font is some 40 GUI pixels of ink, 4 screen pixels each at GUI scale 2.
        if (ink < 60) {
            throw new AssertionError("The LOAD title's plate " + box + " holds " + ink
                    + " screen pixels of the gauge colour, expected the title's ink");
        }
    }

    private static boolean near(int a, int b) {
        for (int shift = 0; shift <= 16; shift += 8) {
            if (Math.abs((a >> shift & 0xFF) - (b >> shift & 0xFF)) > 24) {
                return false;
            }
        }
        return true;
    }
}
