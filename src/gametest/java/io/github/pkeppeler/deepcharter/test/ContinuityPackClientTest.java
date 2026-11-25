package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.ContinuityClientTest.click;
import static io.github.pkeppeler.deepcharter.test.ContinuityClientTest.labels;
import static io.github.pkeppeler.deepcharter.test.ContinuityClientTest.shown;
import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.client.continuity.ContinuityClient;
import io.github.pkeppeler.deepcharter.client.continuity.ContinuityScreen;
import io.github.pkeppeler.deepcharter.client.continuity.Silence;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientCursor;
import io.github.pkeppeler.deepcharter.test.support.ClientPacks;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.locale.Language;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * Client GameTest for #1371: the Continuity Event always lets the dead person go. A dead person teleported to another dimension (a
 * command block's {@code /tp @a}) keeps the one sequence they died in, and RETURN TO WORK brings them back. A death after RETURN TO
 * WORK, before the client saw the person alive, is a new sequence. A resource pack whose laugh envelope is broken ({@link #BROKEN})
 * and whose skull line is blank neither crashes the client nor holds the person on the screen: the skull still draws, and RETURN TO
 * WORK comes as soon as the steady fallback laugh ends, well inside {@code ContinuityScreen}'s cap, with Esc showing DISCONNECT after
 * it.
 */
public class ContinuityPackClientTest implements FabricClientGameTest {
    private static final String PACK = "continuity-broken";
    private static final Identifier ENVELOPE =
            Identifier.fromNamespaceAndPath("deepcharter", "continuity/laugh_envelope.json");
    private static final String SKULL_KEY = "deepcharter.continuity.skull";
    private static final String RETURN = "RETURN TO WORK";
    private static final String DISCONNECT = "DISCONNECT";
    /**
     * The latest the button may come for a broken envelope, from the death: the cut, the skip and the steady fallback laugh (3.68 s) take
     * under 5 s, with room for a slow machine. An envelope read as it is would take the cap, 40 s, or never come.
     */
    private static final double FALLBACK_BUTTON_SECONDS = 10.0;

    /**
     * The broken envelopes, each a pack's {@code continuity/laugh_envelope.json}: a length that is not a number, endless, negative,
     * absurdly long or missing, no values a second, a value that is not a number, and not JSON.
     */
    private static final List<String> BROKEN = List.of(
            "{\"framesPerSecond\": 25, \"seconds\": NaN, \"values\": [0.5, 0.5]}",
            "{\"framesPerSecond\": 25, \"seconds\": Infinity, \"values\": [0.5, 0.5]}",
            "{\"framesPerSecond\": 25, \"seconds\": -1, \"values\": [0.5, 0.5]}",
            "{\"framesPerSecond\": 25, \"seconds\": 1e9, \"values\": [0.5, 0.5]}",
            "{\"framesPerSecond\": 25, \"values\": [0.5, 0.5]}",
            // Lengths a valid envelope may have, so the button would come late if these were read as they are.
            "{\"framesPerSecond\": 0, \"seconds\": 20, \"values\": [0.5, 0.5]}",
            "{\"framesPerSecond\": 25, \"seconds\": 20, \"values\": [NaN]}",
            "{ this is not json");

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        Path pack = context.computeOnClient(
                client -> client.getResourcePackDirectory().resolve(PACK));
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(
                    context, "the player in the world", client -> client.player != null && client.level != null);
            singleplayer
                    .getServer()
                    .runOnServer(server ->
                            server.getPlayerList().getPlayers().getFirst().setGameMode(GameType.SURVIVAL));
            teleportedWhileDead(context, singleplayer);
            diedAgainAfterReturn(context, singleplayer);
            try {
                for (String broken : BROKEN) {
                    writePack(pack, broken);
                    // Selected already after the first case, it is reloaded all the same, which reads the new files.
                    ClientPacks.enable(context, PACK);
                    context.runOnClient(client -> require(
                            broken.equals(envelope(client))
                                    && Language.getInstance()
                                            .getOrDefault(SKULL_KEY)
                                            .isEmpty(),
                            "the pack with " + broken + " is loaded"));
                    aDeathEndsInTheButton(context, singleplayer, broken);
                }
            } finally {
                ClientPacks.disable(context, PACK);
                deleteTree(pack);
            }
        }
    }

    /**
     * A dead person teleported to the Nether gets a new player on the client, still dead: the same sequence carries on over the loading
     * screen, the silence holds, and RETURN TO WORK brings them back alive.
     */
    private static void teleportedWhileDead(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        kill(context, singleplayer);
        ClientWait.screen(context, ContinuityScreen.class);
        Screen sequence = context.computeOnClient(ContinuityClientTest::shown);
        LocalPlayer died = context.computeOnClient(client -> Objects.requireNonNull(client.player, "<in the world>"));
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            ServerLevel nether = Objects.requireNonNull(server.getLevel(Level.NETHER), "no nether");
            require(player.isDeadOrDying(), "the player is dead before the teleport");
            player.teleport(new TeleportTransition(
                    nether, player.position(), Vec3.ZERO, 0f, 0f, TeleportTransition.DO_NOTHING));
        });
        ClientWait.until(
                context,
                "the dead player in the Nether, the sequence on screen again",
                client -> client.player != null
                        && client.player != died
                        && client.level != null
                        && client.level.dimension() == Level.NETHER
                        && client.gui.screen() instanceof ContinuityScreen);
        context.runOnClient(client -> {
            require(client.gui.screen() == sequence, "the teleport carries the same sequence on, not a new one");
            require(ContinuityClient.inSequence(), "the sequence is still under way");
            require(Silence.refuses(click()), "the silence holds over the teleport");
        });
        returnToWork(context);
    }

    /**
     * Once RETURN TO WORK is pressed, a dead player the client is given next has died again: a respawn and a death handled in one batch,
     * as a deadly spawn or a repeating {@code /kill} makes them while the client stalls. That death is a sequence of its own, never the
     * old one with its button spent, which would leave the person only DISCONNECT. The second death is staged on the client, a new player
     * handed to {@link ContinuityClient#screenFor} dead, because the server cannot be made to batch the two packets.
     */
    private static void diedAgainAfterReturn(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        kill(context, singleplayer);
        buttonShown(context, "RETURN TO WORK shown");
        context.runOnClient(client -> {
            Screen sequence = shown(client);
            ClientChecks.button(sequence, RETURN).onPress(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
            LocalPlayer died = Objects.requireNonNull(client.player, "<in the world>");
            LocalPlayer again = Objects.requireNonNull(client.gameMode, "<in the world>")
                    .createPlayer(
                            Objects.requireNonNull(client.level, "<in the world>"),
                            died.getStats(),
                            died.getRecipeBook(),
                            died.itemActivation());
            again.setHealth(0f);
            ContinuityScreen next = ContinuityClient.screenFor(again);
            require(next != sequence, "a death after RETURN TO WORK is a sequence of its own, not the spent one");
            // Shown as the death's screen would be: the respawn the button asked for closes it.
            client.gui.setScreen(next);
        });
        backAtWork(context);
    }

    /** The person dies with the broken pack on: the skull draws, the button comes in time, Esc shows DISCONNECT. */
    private static void aDeathEndsInTheButton(
            ClientGameTestContext context, TestSingleplayerContext singleplayer, String broken) {
        kill(context, singleplayer);
        long before = context.computeOnClient(client -> Util.getMillis());
        buttonShown(context, "RETURN TO WORK with " + broken);
        double seconds = (context.computeOnClient(client -> Util.getMillis()) - before) / 1000.0;
        require(
                seconds <= FALLBACK_BUTTON_SECONDS,
                "a broken envelope " + broken + " is read as the steady fallback, so the button comes within "
                        + FALLBACK_BUTTON_SECONDS + " s; it came at " + seconds);
        RealInput.key(context, InputConstants.KEY_ESCAPE, 0);
        context.runOnClient(client -> require(
                labels(client).equals(List.of(RETURN, DISCONNECT)),
                "Esc shows DISCONNECT, the screen shows " + labels(client)));
        returnToWork(context);
    }

    /** Clicks RETURN TO WORK once it shows, and waits for the person to be back at work. */
    private static void returnToWork(ClientGameTestContext context) {
        buttonShown(context, "RETURN TO WORK shown");
        Button button = context.computeOnClient(client -> ClientChecks.button(shown(client), RETURN));
        ClientCursor.moveTo(context, button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0);
        RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
        backAtWork(context);
    }

    /** Waits for the sequence's buttons: RETURN TO WORK shows once the laugh ends. */
    private static void buttonShown(ClientGameTestContext context, String what) {
        ClientWait.until(
                context,
                what,
                client -> client.gui.screen() instanceof ContinuityScreen screen
                        && !ClientChecks.visibleWidgets(screen).isEmpty());
    }

    private static void backAtWork(ClientGameTestContext context) {
        ClientWait.until(
                context,
                "back at work: the screen closed and the player alive",
                client -> client.gui.screen() == null && client.player != null && client.player.isAlive());
    }

    /**
     * The player dies, on the server, as {@code /kill} kills. The server does not let a player be hurt until their client says it has
     * loaded the world, which can be a while after the join, so the blow is struck again until it lands.
     */
    private static void kill(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        ClientWait.until(
                context,
                "the player dead",
                () -> singleplayer.getServer().computeOnServer(server -> {
                    ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                    if (!player.isDeadOrDying()) {
                        player.hurtServer(
                                player.level(), player.level().damageSources().genericKill(), 1000f);
                    }
                    return player.isDeadOrDying();
                }),
                () -> "a player who could not be hurt");
    }

    /** The laugh envelope the game reads now, as text; empty when there is none. */
    private static String envelope(Minecraft client) {
        return client.getResourceManager()
                .getResource(ENVELOPE)
                .map(resource -> {
                    try (Reader reader = resource.openAsReader()) {
                        StringWriter text = new StringWriter();
                        reader.transferTo(text);
                        return text.toString();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .orElse("");
    }

    private static void writePack(Path pack, String envelope) {
        try {
            deleteTree(pack);
            Files.createDirectories(pack.resolve("assets/deepcharter/continuity"));
            Files.createDirectories(pack.resolve("assets/deepcharter/lang"));
            Files.writeString(
                    pack.resolve("pack.mcmeta"),
                    "{\"pack\": {\"description\": \"Deep Charter broken Continuity Event (throwaway)\", \"min_format\": 97, \"max_format\": 97}}\n");
            Files.writeString(pack.resolve("assets/deepcharter/continuity/laugh_envelope.json"), envelope);
            Files.writeString(pack.resolve("assets/deepcharter/lang/en_us.json"), "{\"" + SKULL_KEY + "\": \"\"}\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
