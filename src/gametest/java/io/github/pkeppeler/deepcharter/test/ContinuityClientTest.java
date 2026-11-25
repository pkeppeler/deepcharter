package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.ClientChecks.require;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.client.continuity.ContinuityClient;
import io.github.pkeppeler.deepcharter.client.continuity.ContinuityScreen;
import io.github.pkeppeler.deepcharter.client.continuity.Silence;
import io.github.pkeppeler.deepcharter.continuity.ContinuityRecord;
import io.github.pkeppeler.deepcharter.continuity.ContinuityRegistry;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import io.github.pkeppeler.deepcharter.test.support.ClientChecks;
import io.github.pkeppeler.deepcharter.test.support.ClientCursor;
import io.github.pkeppeler.deepcharter.test.support.ClientWait;
import io.github.pkeppeler.deepcharter.test.support.RealInput;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEventListener;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Util;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/**
 * Client GameTest for #1337's Continuity Event, through a real death (a burn, on the integrated server): vanilla's death screen never
 * opens; the screen goes black and silent at once, and no sound of the world gets through until the person is back; the record skips
 * three times on the last sound, each pass lower, each with the needle, then the laugh; nothing shows or answers before RETURN TO WORK,
 * which comes when the laugh ends; a container closed or a screen opened and shut over it while the person is dead neither brings back
 * vanilla's death screen nor cuts the laugh; Esc then shows DISCONNECT; a click on RETURN TO WORK brings the person back alive, and the
 * world's sounds with them; a second death is No. 2 and plays again; leaving the world mid-sequence lifts the silence. And what the crew
 * see of a death: another player's body still, unflashed and then see-through.
 */
public class ContinuityClientTest implements FabricClientGameTest {
    private static final Identifier ANVIL = SoundEvents.ANVIL_LAND.location();
    private static final Identifier NEEDLE = DeepSound.CONTINUITY_NEEDLE.event().location();
    private static final Identifier LAUGH = DeepSound.CONTINUITY_LAUGH.event().location();
    private static final String RETURN = "RETURN TO WORK";
    private static final String DISCONNECT = "DISCONNECT";
    /** The laugh's length ({@code continuity/laugh_envelope.json}) and the cut before it: the button cannot come sooner. */
    private static final double SOONEST_BUTTON_SECONDS = 0.35 + 3.68;

    /** The made-up crewmate's entity id: never added to the level, so it needs one of its own; the level's ids count up from 1. */
    private static final int CREWMATE_ID = -1337;

    /** A vanilla death screen ever made: set by a listener that cannot be removed, so it only ever records a fact. */
    private static final AtomicBoolean SAW_DEATH_SCREEN = new AtomicBoolean();
    /** When the Continuity Event's screen last opened, on the client's clock. */
    private static final AtomicLong OPENED = new AtomicLong();

    /** Every sound the engine starts, with its pitch and when. */
    private record Played(SoundInstance sound, Identifier id, float pitch, long millis) {}

    private static final class Heard implements SoundEventListener {
        private final List<Played> played = new CopyOnWriteArrayList<>();

        @Override
        public void onPlaySound(SoundInstance sound, WeighedSoundEvents events, float range) {
            played.add(new Played(sound, sound.getIdentifier(), sound.getPitch(), Util.getMillis()));
        }

        List<Played> since(long millis) {
            return played.stream().filter(p -> p.millis() >= millis).toList();
        }

        /** Whether {@code id} was started at or after {@code millis}. */
        boolean heard(Identifier id, long millis) {
            return since(millis).stream().anyMatch(p -> p.id().equals(id));
        }
    }

    @Override
    public void runTest(ClientGameTestContext context) {
        ClientTestLog.start(this);
        ScreenEvents.BEFORE_INIT.register((client, screen, width, height) -> {
            if (screen instanceof DeathScreen) {
                SAW_DEATH_SCREEN.set(true);
            }
            if (screen instanceof ContinuityScreen) {
                OPENED.compareAndSet(0, Util.getMillis());
            }
        });
        Heard heard = new Heard();
        context.runOnClient(client -> client.getSoundManager().addListener(heard));
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientWait.until(
                    context, "the player in the world", client -> client.player != null && client.level != null);
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setGameMode(GameType.SURVIVAL);
            });
            context.runOnClient(ContinuityClientTest::checkTheStillBody);
            long beforeAnvil = context.computeOnClient(client -> Util.getMillis());
            anvil(singleplayer);
            ClientWait.until(context, "the anvil heard", client -> heard.heard(ANVIL, beforeAnvil));

            OPENED.set(0);
            burn(singleplayer);
            ClientWait.screen(context, ContinuityScreen.class);
            long opened = OPENED.get();
            require(opened != 0, "the screen's opening was seen");
            require(!SAW_DEATH_SCREEN.get(), "vanilla's death screen must never open");

            // Nothing to see or press, and Esc does nothing, before the button.
            context.runOnClient(client -> require(
                    ClientChecks.visibleWidgets(shown(client)).isEmpty(), "nothing shows before the laugh ends"));
            RealInput.key(context, InputConstants.KEY_ESCAPE, 0);
            context.runOnClient(client -> {
                require(client.gui.screen() instanceof ContinuityScreen, "Esc does not leave the sequence");
                require(ClientChecks.visibleWidgets(shown(client)).isEmpty(), "Esc shows nothing before the button");
            });
            // The world's sounds are cut off: an anvil now is never started.
            anvil(singleplayer);

            // Once the laugh plays, nothing that clears or covers the screen while the person is dead ends the
            // sequence:
            // vanilla would build its death screen again for a container the server closes and for a screen shut over
            // it.
            ClientWait.until(context, "the laugh started", client -> heard.heard(LAUGH, opened));
            SoundInstance laugh = heard.since(opened).stream()
                    .filter(p -> p.id().equals(LAUGH))
                    .findFirst()
                    .orElseThrow()
                    .sound();
            Screen sequence = context.computeOnClient(ContinuityClientTest::shown);
            context.runOnClient(client -> {
                Objects.requireNonNull(client.player, "<in the world>").clientSideCloseContainer();
                require(client.gui.screen() == sequence, "a closed container leaves the same sequence on screen");
                require(client.getSoundManager().isActive(laugh), "a closed container does not cut the laugh");
                client.gui.setScreen(new ChatScreen("", false));
            });
            ClientWait.until(
                    context, "the chat open over the sequence", client -> client.gui.screen() instanceof ChatScreen);
            context.runOnClient(client -> {
                require(client.getSoundManager().isActive(laugh), "a screen over the sequence does not cut the laugh");
                client.gui.setScreen(null);
                require(client.gui.screen() == sequence, "shutting the screen over it shows the same sequence again");
                require(client.getSoundManager().isActive(laugh), "and the laugh goes on");
            });
            anvil(singleplayer);

            ClientWait.until(
                    context,
                    "RETURN TO WORK shown",
                    client -> client.gui.screen() instanceof ContinuityScreen screen
                            && !ClientChecks.visibleWidgets(screen).isEmpty());
            long shown = context.computeOnClient(client -> Util.getMillis());
            double seconds = (shown - opened) / 1000.0;
            require(
                    seconds >= SOONEST_BUTTON_SECONDS - 0.05,
                    "the button comes when the laugh ends, at least " + SOONEST_BUTTON_SECONDS + " s, it came at "
                            + seconds);
            checkTheSequence(heard.since(opened));

            context.runOnClient(client -> {
                List<String> labels = labels(client);
                require(labels.equals(List.of(RETURN)), "only RETURN TO WORK shows, the screen shows " + labels);
            });
            RealInput.key(context, InputConstants.KEY_ESCAPE, 0);
            context.runOnClient(client -> {
                require(client.gui.screen() instanceof ContinuityScreen, "the first Esc stays on the screen");
                List<String> labels = labels(client);
                require(labels.equals(List.of(RETURN, DISCONNECT)), "Esc shows DISCONNECT, the screen shows " + labels);
            });

            Button button = context.computeOnClient(client -> ClientChecks.button(shown(client), RETURN));
            ClientCursor.moveTo(
                    context, button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0);
            RealInput.click(context, InputConstants.MOUSE_BUTTON_LEFT);
            ClientWait.until(
                    context,
                    "back at work: the screen closed and the player alive",
                    client -> client.gui.screen() == null && client.player != null && client.player.isAlive());

            long back = context.computeOnClient(client -> Util.getMillis());
            anvil(singleplayer);
            ClientWait.until(context, "the world's sounds back", client -> heard.heard(ANVIL, back));

            // A second death in the same session is a sequence of its own, No. 2, laugh and all.
            long second = context.computeOnClient(client -> Util.getMillis());
            burn(singleplayer);
            ClientWait.until(
                    context,
                    "a second sequence",
                    client -> client.gui.screen() instanceof ContinuityScreen screen && screen != sequence);
            ClientWait.until(context, "the second laugh", client -> heard.heard(LAUGH, second));
            int events = singleplayer
                    .getServer()
                    .computeOnServer(server -> Versioned.readable(
                                    server.getPlayerList().getPlayers().getFirst(), ContinuityRegistry.RECORD)
                            .map(ContinuityRecord::events)
                            .orElse(-1));
            require(events == 2, "the second death is No. 2, the record reads " + events);
            require(!SAW_DEATH_SCREEN.get(), "vanilla's death screen never opened");
            context.runOnClient(client -> require(Silence.refuses(click()), "the second sequence holds the silence"));
        } finally {
            context.runOnClient(client -> client.getSoundManager().removeListener(heard));
        }
        // Leaving the world mid-sequence ends it: the silence lifts and nothing is left in sequence.
        context.runOnClient(client -> {
            require(!Silence.refuses(click()), "leaving the world lifts the silence");
            require(!ContinuityClient.inSequence(), "leaving the world ends the sequence");
        });
    }

    /**
     * From the death to the button: three passes of one sound of the world, each lower than the last and each with the needle, then the
     * laugh, and nothing else.
     */
    private static void checkTheSequence(List<Played> sequence) {
        List<Played> needles =
                sequence.stream().filter(p -> p.id().equals(NEEDLE)).toList();
        List<Played> laughs =
                sequence.stream().filter(p -> p.id().equals(LAUGH)).toList();
        List<Played> passes = sequence.stream()
                .filter(p -> !p.id().equals(NEEDLE) && !p.id().equals(LAUGH))
                .toList();
        require(needles.size() == 3, "three needle clicks, heard " + sequence);
        require(laughs.size() == 1, "one laugh, heard " + sequence);
        require(passes.size() == 3, "three passes of the last sound and nothing else of the world, heard " + sequence);
        require(
                passes.stream().map(Played::id).distinct().count() == 1,
                "the three passes are one sound, heard " + passes);
        require(
                passes.get(0).pitch() > passes.get(1).pitch()
                        && passes.get(1).pitch() > passes.get(2).pitch(),
                "each pass is lower than the one before, heard " + passes);
        require(
                passes.get(2).millis() <= laughs.getFirst().millis(),
                "the laugh comes after the last pass, heard " + sequence);
    }

    /**
     * What the crew see of a death ({@code AvatarRendererMixin}): another player's render state while they die. Alive, they are drawn as
     * vanilla draws them. In the first half of the dying time the body stands still and solid, with no fall and no red flash, still in
     * its armour. In the second half it is see-through, and nothing it wears or holds, and no name, is drawn over it.
     */
    private static void checkTheStillBody(Minecraft client) {
        ClientLevel level = Objects.requireNonNull(client.level, "<in the world>");
        LocalPlayer me = Objects.requireNonNull(client.player, "<in the world>");
        RemotePlayer crewmate = new RemotePlayer(level, new GameProfile(UUID.randomUUID(), "Crewmate"));
        crewmate.setId(CREWMATE_ID);
        crewmate.setPos(me.getX() + 2, me.getY(), me.getZ());
        crewmate.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        crewmate.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
        crewmate.setCustomName(Component.literal("Crewmate"));
        crewmate.setCustomNameVisible(true);

        AvatarRenderState alive = state(client, crewmate);
        require(!alive.isInvisible && !alive.chestEquipment.isEmpty(), "a living crewmate is drawn whole");
        require(alive.nameTag != null, "a living crewmate's name is drawn");

        crewmate.hurtTime = 5;
        crewmate.deathTime = 5;
        AvatarRenderState solid = state(client, crewmate);
        require(solid.deathTime == 0f, "a dying body does not fall, its death time reads " + solid.deathTime);
        require(!solid.hasRedOverlay, "a dying body does not flash red");
        require(!solid.isInvisible, "a body in the first half of its dying is solid");
        require(!solid.chestEquipment.isEmpty(), "and still in its armour");

        crewmate.deathTime = 15;
        AvatarRenderState fading = state(client, crewmate);
        require(fading.deathTime == 0f && !fading.hasRedOverlay, "the fading body neither falls nor flashes");
        require(fading.isInvisible && !fading.isInvisibleToPlayer, "the second half is drawn see-through");
        require(
                fading.chestEquipment.isEmpty() && fading.rightHandItemState.isEmpty() && fading.nameTag == null,
                "nothing it wears or holds, and no name, is drawn over the fading body");
    }

    private static AvatarRenderState state(Minecraft client, RemotePlayer player) {
        return (AvatarRenderState) client.getEntityRenderDispatcher().extractEntity(player, 0f);
    }

    /** A sound of the game's own, not the sequence's: one the silence refuses while it holds. */
    private static SoundInstance click() {
        return SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f);
    }

    /** The player dies in lava, on the server. */
    private static void burn(TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.hurtServer(player.level(), player.level().damageSources().lava(), 1000f);
        });
    }

    /** The labels of the widgets the open screen shows. */
    private static List<String> labels(Minecraft client) {
        return ClientChecks.visibleWidgets(shown(client)).stream()
                .map(widget -> widget.getMessage().getString())
                .toList();
    }

    /** An anvil lands at the player, loud. */
    private static void anvil(TestSingleplayerContext singleplayer) {
        singleplayer.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
            player.level().playSound(null, player.blockPosition(), SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 1f, 1f);
        });
    }

    /** The screen open now, which the step before waited for. */
    private static Screen shown(Minecraft client) {
        return Objects.requireNonNull(client.gui.screen(), "<a screen is open>");
    }
}
