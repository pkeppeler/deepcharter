package io.github.pkeppeler.deepcharter.client.continuity;

import io.github.pkeppeler.deepcharter.client.theme.ContinuityLook;
import io.github.pkeppeler.deepcharter.client.ui.CrtButton;
import io.github.pkeppeler.deepcharter.client.ui.CrtDraw;
import io.github.pkeppeler.deepcharter.client.ui.CrtTuning;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * The Continuity Event as the person it happens to sees and hears it (#1337, ADR 0100), in place of vanilla's death screen, the same for
 * every cause:
 *
 * <ol>
 *   <li>At the death, a hard cut: the screen is black and every sound stops ({@link Silence}).
 *   <li>{@code cutSeconds} later the skull tears in from static ({@link SkullArt}), and the record skips on the person's last sound
 *       ({@link RecentSounds}): it plays {@code skipPasses} times, each pass shorter and a little lower, with a needle's click.
 *   <li>The laugh takes over (or starts at once, with no last sound to skip on), and the skull laughs with it, by the loudness the laugh
 *       was made with ({@link LaughEnvelope}).
 *   <li>When the laugh ends, RETURN TO WORK, which is vanilla's respawn. Nothing before it can be skipped. After it, Esc shows a small
 *       DISCONNECT, and a second Esc (or a click on it) leaves the world.
 * </ol>
 *
 * Time runs on the client's clock from the moment the screen opens, so the sounds keep to the beat at any frame rate. The screen closes
 * itself when the respawn swaps the player.
 */
public final class ContinuityScreen extends Screen {
    private static final int BUTTON_WIDTH = 120;
    private static final int BUTTON_HEIGHT = 20;
    private static final int SMALL_WIDTH = 72;
    private static final int SMALL_HEIGHT = 14;
    private static final int GAP = 8;

    private final LocalPlayer dead;
    private final String line;
    private final SkullArt art = new SkullArt();

    private long openedAt = -1;
    private @Nullable Plan plan;
    private int passesPlayed;
    private @Nullable SoundInstance pass;
    private @Nullable SoundInstance laugh;
    private boolean buttonShown;
    private boolean disconnectShown;
    private @Nullable CrtButton returnButton;
    private @Nullable CrtButton disconnectButton;

    /**
     * The beat of one sequence, fixed when it starts: when each pass of the skip starts, and when the laugh starts and ends, in seconds
     * from the death.
     */
    @SuppressWarnings("ArrayRecordComponent") // made once per sequence and only read; never compared
    private record Plan(
            Optional<RecentSounds.Heard> last,
            double[] passStarts,
            double skipEnd,
            double laughStart,
            double laughEnd,
            LaughEnvelope envelope,
            ContinuityLook look) {
        static Plan of(Optional<RecentSounds.Heard> last, LaughEnvelope envelope, ContinuityLook look) {
            int passes = last.isPresent() ? look.skipPasses() : 0;
            double[] starts = new double[passes];
            double at = look.cutSeconds();
            double period = look.skipPeriodSeconds();
            for (int k = 0; k < passes; k++) {
                starts[k] = at;
                at += period;
                period *= look.skipShrink();
            }
            double laughStart = passes > 0 ? at - look.skipOverlapSeconds() : look.cutSeconds();
            return new Plan(last, starts, at, laughStart, laughStart + envelope.seconds(), envelope, look);
        }

        /** How loud the sequence is at {@code now} for the skull: a pass's knock dying away, or the laugh. */
        float loudness(double now) {
            float loud = envelope.at(now - laughStart);
            for (int k = passStarts.length - 1; k >= 0; k--) {
                if (now >= passStarts[k]) {
                    double knock = look.skipLoudness() * Math.exp(-(now - passStarts[k]) / look.skipDecaySeconds());
                    return Math.max(loud, (float) knock);
                }
            }
            return loud;
        }

        /** The skull's brightness: whole while it laughs, fading over the laugh's last {@code fadeSeconds}, then gone. */
        float life(double now) {
            if (look.fadeSeconds() <= 0) {
                return now < laughEnd ? 1f : 0f;
            }
            return (float) Math.clamp((laughEnd - now) / look.fadeSeconds(), 0.0, 1.0);
        }
    }

    /**
     * @param dead     the player who died, whose respawn the button asks for; the screen closes when the client has another
     * @param number   this Continuity Event's number, or 0 when the server could not read it
     * @param employee the person's employee number
     */
    public ContinuityScreen(LocalPlayer dead, int number, int employee) {
        super(Component.translatable("deepcharter.continuity.screen"));
        this.dead = dead;
        Component numberText = number > 0
                ? Component.literal(Integer.toString(number))
                : Component.translatable("deepcharter.continuity.unknown_number");
        this.line = Component.translatable("deepcharter.continuity.skull", Integer.toString(employee), numberText)
                .getString();
    }

    @Override
    protected void init() {
        if (openedAt < 0) {
            openedAt = Util.getMillis();
            Silence.begin(minecraft);
            ContinuityLook look = ContinuityLook.current();
            plan = Plan.of(
                    RecentSounds.INSTANCE.last(
                            openedAt, Math.round(look.replaySeconds() * 1000), (float) look.loudVolume()),
                    LaughEnvelope.load(minecraft.getResourceManager()),
                    look);
        }
        int x = (width - BUTTON_WIDTH) / 2;
        int y = (height - BUTTON_HEIGHT) / 2;
        returnButton = addRenderableWidget(new CrtButton(
                x,
                y,
                BUTTON_WIDTH,
                BUTTON_HEIGHT,
                Component.translatable("deepcharter.continuity.return_to_work"),
                this::returnToWork));
        disconnectButton = addRenderableWidget(new CrtButton(
                (width - SMALL_WIDTH) / 2,
                y + BUTTON_HEIGHT + GAP,
                SMALL_WIDTH,
                SMALL_HEIGHT,
                Component.translatable("deepcharter.continuity.disconnect"),
                button -> disconnect()));
        returnButton.visible = buttonShown;
        disconnectButton.visible = disconnectShown;
    }

    private double now() {
        return (Util.getMillis() - openedAt) / 1000.0;
    }

    private Plan plan() {
        return Objects.requireNonNull(plan, "<made by init() when the screen opens>");
    }

    /** Plays what is due by now: each pass of the skip and its needle, the laugh, then shows the button. */
    private void advance() {
        Plan plan = plan();
        double now = now();
        while (passesPlayed < plan.passStarts().length && now >= plan.passStarts()[passesPlayed]) {
            stopPass();
            RecentSounds.Heard last = plan.last().orElseThrow();
            float pitch = (float) (last.pitch() * Math.pow(plan.look().skipPitch(), passesPlayed + 1));
            pass = SequenceSound.replay(last, pitch);
            play(pass);
            play(SequenceSound.atListener(DeepSound.CONTINUITY_NEEDLE.event().location(), 1f, 1f));
            passesPlayed++;
        }
        if (passesPlayed > 0 && now >= plan.skipEnd()) {
            stopPass();
        }
        if (laugh == null && now >= plan.laughStart()) {
            laugh = SequenceSound.atListener(DeepSound.CONTINUITY_LAUGH.event().location(), 1f, 1f);
            play(laugh);
        }
        if (!buttonShown && now >= plan.laughEnd()) {
            buttonShown = true;
            CrtButton button = Objects.requireNonNull(returnButton, "<built by init()>");
            button.visible = true;
            setFocused(button);
        }
    }

    private void play(SoundInstance sound) {
        minecraft.getSoundManager().play(sound);
    }

    private void stopPass() {
        if (pass != null) {
            minecraft.getSoundManager().stop(pass);
            pass = null;
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (minecraft.player != dead) {
            // The respawn gave the client a new player: the person is back at work.
            onClose();
            return;
        }
        advance();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, CrtTuning.current().backgroundColor());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        advance();
        Plan plan = plan();
        double now = now();
        double cut = plan.look().cutSeconds();
        if (now >= cut && now < plan.laughEnd()) {
            art.draw(graphics, font, width, height, now - cut, plan.loudness(now), plan.life(now), line);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        CrtDraw.scanlines(graphics, width, height);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (!buttonShown) {
            return true;
        }
        if (event.isEscape()) {
            if (!disconnectShown) {
                disconnectShown = true;
                CrtButton button = Objects.requireNonNull(disconnectButton, "<built by init()>");
                button.visible = true;
                setFocused(button);
            } else {
                disconnect();
            }
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        Silence.end();
        stopPass();
        if (laugh != null) {
            minecraft.getSoundManager().stop(laugh);
        }
        super.removed();
    }

    private void returnToWork(Button button) {
        dead.respawn();
        button.active = false;
    }

    /** Leaves the world, as vanilla's death screen does from its title-screen button. */
    private void disconnect() {
        ClientLevel level = minecraft.level;
        if (level != null) {
            level.disconnect(ClientLevel.DEFAULT_QUIT_MESSAGE);
        }
        minecraft.disconnectWithSavingScreen();
        minecraft.gui.setScreen(new TitleScreen());
    }
}
