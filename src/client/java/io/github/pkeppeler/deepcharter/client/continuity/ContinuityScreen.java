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
 * Time runs on the client's clock from the moment the screen first opens, so the sounds keep to the beat at any frame rate. The sequence
 * outlives the screen being shown: {@link ContinuityClient} plays it on each tick, hands this same screen back whenever vanilla would
 * show its death screen again, follows the person to the new player a teleport to another dimension gives the client, and ends it
 * ({@link #end}) when the respawn brings them back alive or the client leaves the world.
 */
public final class ContinuityScreen extends Screen {
    /**
     * The latest RETURN TO WORK can come, in seconds from the screen's opening at the death, whatever the packs say: Esc and DISCONNECT
     * wait only for the button, so no data can hold a dead person on this screen for longer. The theme's bounds ({@code
     * ContinuityLook.of}) put the laugh's start at most 8 s in, and the laugh ({@link LaughEnvelope#MAX_SECONDS}) lasts at most 20 s, so
     * any valid pack's button comes by 28 s, 12 s inside the cap; it is here for whatever slips past them.
     */
    static final double LONGEST_SECONDS = 40.0;

    private LocalPlayer dead;
    private String line;
    private boolean numberKnown;
    private final SkullArt art = new SkullArt();

    private long openedAt = -1;
    private @Nullable Plan plan;
    private int passesPlayed;
    private @Nullable SoundInstance pass;
    private @Nullable SoundInstance laugh;
    private boolean buttonShown;
    private boolean disconnectShown;
    private boolean respawnAsked;
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
            double laughEnd = laughStart + envelope.seconds();
            // Written to hold for NaN too: a sum that is not a number ends at the cap.
            return new Plan(
                    last,
                    starts,
                    at,
                    laughStart,
                    laughEnd <= LONGEST_SECONDS ? laughEnd : LONGEST_SECONDS,
                    envelope,
                    look);
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
        this.line = line(number, employee);
        this.numberKnown = number > 0;
    }

    /**
     * The line the skull's letters cycle through. A pack that blanks it ({@code deepcharter.continuity.skull}) leaves the skull its
     * numbers: the letters need a line to cycle through.
     */
    private static String line(int number, int employee) {
        Component numberText = number > 0
                ? Component.literal(Integer.toString(number))
                : Component.translatable("deepcharter.continuity.unknown_number");
        String line = Component.translatable("deepcharter.continuity.skull", Integer.toString(employee), numberText)
                .getString();
        return line.isEmpty() ? employee + " · " + numberText.getString() + " · " : line;
    }

    /** Whether the skull is written in a number of the server's; false when it reads as unknown. */
    boolean numberKnown() {
        return numberKnown;
    }

    /** Writes the skull in the numbers the server sent after the sequence began: it started before they arrived. */
    void adopt(int number, int employee) {
        line = line(number, employee);
        numberKnown = number > 0;
    }

    @Override
    protected void init() {
        ContinuityLook look = ContinuityLook.current();
        if (openedAt < 0) {
            openedAt = Util.getMillis();
            plan = Plan.of(
                    RecentSounds.INSTANCE.last(
                            openedAt, Math.round(look.replaySeconds() * 1000), (float) look.loudVolume()),
                    LaughEnvelope.load(minecraft.getResourceManager()),
                    look);
        }
        int x = (width - look.buttonWidth()) / 2;
        int y = (height - look.buttonHeight()) / 2;
        returnButton = addRenderableWidget(new CrtButton(
                x,
                y,
                look.buttonWidth(),
                look.buttonHeight(),
                Component.translatable("deepcharter.continuity.return_to_work"),
                this::returnToWork));
        disconnectButton = addRenderableWidget(new CrtButton(
                (width - look.smallButtonWidth()) / 2,
                y + look.buttonHeight() + look.buttonGap(),
                look.smallButtonWidth(),
                look.smallButtonHeight(),
                Component.translatable("deepcharter.continuity.disconnect"),
                button -> disconnect()));
        returnButton.visible = buttonShown;
        returnButton.active = !respawnAsked;
        disconnectButton.visible = disconnectShown;
    }

    /** A re-show (a screen shut over the sequence) builds the buttons anew: the focus goes back where it was. */
    @Override
    protected void setInitialFocus() {
        if (disconnectShown) {
            setFocused(disconnectButton);
        } else if (buttonShown) {
            setFocused(returnButton);
        } else {
            super.setInitialFocus();
        }
    }

    private double now() {
        return (Util.getMillis() - openedAt) / 1000.0;
    }

    private Plan plan() {
        return Objects.requireNonNull(plan, "<made by init() when the screen opens>");
    }

    /**
     * Whether {@code player} carries this sequence on: the player who died, or one the client swapped them for while they are still dead,
     * whom the sequence then follows. A teleport to another dimension (a command block's {@code /tp}) gives the client a new player that
     * keeps the old one's health; a respawn's new player is alive, and the sequence ends with the old one. Once RETURN TO WORK is
     * pressed, no new player carries it on: one that is dead already (a respawn and a death handled in one batch) has died again, and
     * that is a new death with a sequence of its own.
     */
    boolean carriesOn(LocalPlayer player) {
        if (player != dead && !respawnAsked && player.isDeadOrDying()) {
            dead = player;
        }
        return player == dead;
    }

    /**
     * Plays what is due by now: the latest pass of the skip that has begun and its needle (passes a stall skipped over are not caught up,
     * so they never play on top of each other), the laugh, then shows the button. Nothing before the screen first opens.
     */
    void advance() {
        Plan plan = this.plan;
        if (plan == null) {
            return;
        }
        double now = now();
        int due = passesPlayed;
        while (due < plan.passStarts().length && now >= plan.passStarts()[due]) {
            due++;
        }
        if (due > passesPlayed && now < plan.skipEnd()) {
            stopPass();
            RecentSounds.Heard last = plan.last().orElseThrow();
            float pitch = (float) (last.pitch() * Math.pow(plan.look().skipPitch(), due));
            pass = SequenceSound.replay(last, pitch);
            play(pass);
            play(SequenceSound.atListener(DeepSound.CONTINUITY_NEEDLE.event().location()));
        }
        passesPlayed = due;
        if (passesPlayed > 0 && now >= plan.skipEnd()) {
            stopPass();
        }
        if (laugh == null && now >= plan.laughStart()) {
            laugh = SequenceSound.atListener(DeepSound.CONTINUITY_LAUGH.event().location());
            play(laugh);
        }
        if (!buttonShown && now >= plan.laughEnd()) {
            buttonShown = true;
            CrtButton button = Objects.requireNonNull(returnButton, "<built by init()>");
            button.visible = true;
            setFocused(button);
        }
    }

    /** Only the live sequence plays: a screen built anywhere else (a layout test) has no silence and no end to stop its sounds. */
    private void play(SoundInstance sound) {
        if (ContinuityClient.isLive(this)) {
            minecraft.getSoundManager().play(sound);
        }
    }

    private void stopPass() {
        if (pass != null) {
            minecraft.getSoundManager().stop(pass);
            pass = null;
        }
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

    /**
     * The sequence is over: the silence lifts and its sounds stop. Called by {@link ContinuityClient} on the respawn or on leaving the
     * world, never when another screen merely covers this one for a while.
     */
    void end() {
        Silence.end();
        stopPass();
        if (laugh != null) {
            minecraft.getSoundManager().stop(laugh);
        }
    }

    private void returnToWork(Button button) {
        dead.respawn();
        respawnAsked = true;
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
