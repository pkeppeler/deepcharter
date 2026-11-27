package io.github.pkeppeler.deepcharter.creature;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.handbook.ReadMarks;
import io.github.pkeppeler.deepcharter.ore.OreEncumbrance;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.LightLayer;
import org.jspecify.annotations.Nullable;

/**
 * Which people on foot a Retained holds (ADR 0046, amended for #916), and who draws one. A grip lives in memory only, as a figure does:
 * a figure is not saved, so a grip never outlasts it, and nothing here is persisted that could fail to read. The grip weighs on the
 * person through {@link OreEncumbrance#EXTRA_MASS}, so it is slowed exactly as by ore; the figure drains the hunger.
 */
public final class FootGrips {
    /** The read mark that records the one-time line that states the rule, at a person's first Retained. */
    static final Identifier FIRST_EXPOSURE_MARK =
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "hint/first_cling");

    /** The figure that holds each person, by UUID: a respawn is a new player entity. Server thread only. */
    private static final Map<UUID, LamplessFigure> HELD = new HashMap<>();

    private FootGrips() {}

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> HELD.clear());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> HELD.clear());
        // A person who quits is let go of: the figure notices the removed player and lets go, and the entry would only
        // go stale.
        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> HELD.remove(handler.getPlayer().getUUID()));
        OreEncumbrance.EXTRA_MASS.register(player ->
                holder(player) == null ? 0f : CreatureTuning.DEFAULT.foot().loadMass());
    }

    /**
     * Whether a person draws a figure: not on Peaceful, on foot, alive, not a creative or spectator player, not held
     * already, standing where the block light is under {@link CreatureTuning#litBlockLight}, with no light in either
     * hand ({@link HandLights#held}), and not within the reach of a burning flare ({@link DrawSources}: a flare
     * protects at once, though it frees no one already held). The suit lamp, when it exists, replaces the hand check.
     */
    public static boolean draws(ServerPlayer person) {
        return person.level().getDifficulty() != Difficulty.PEACEFUL
                && !person.isPassenger()
                && person.isAlive()
                && !person.isCreative()
                && !person.isSpectator()
                && holder(person) == null
                && person.level().getBrightness(LightLayer.BLOCK, person.blockPosition())
                        < CreatureTuning.DEFAULT.litBlockLight()
                && !HandLights.held(person)
                && !DrawSources.litByFlare(person.level(), person.position());
    }

    /** The live figure that holds {@code person}, or null. A figure that was removed or died without letting go is forgotten here. */
    public static @Nullable LamplessFigure holder(ServerPlayer person) {
        LamplessFigure figure = HELD.get(person.getUUID());
        if (figure != null && (figure.isRemoved() || !figure.isAlive())) {
            HELD.remove(person.getUUID());
            return null;
        }
        return figure;
    }

    static void take(ServerPlayer person, LamplessFigure figure) {
        HELD.put(person.getUUID(), figure);
    }

    static void release(ServerPlayer person, LamplessFigure figure) {
        HELD.remove(person.getUUID(), figure);
    }

    /**
     * States the rule once per person, at the first figure that comes for them. A person whose marks cannot be read is not told (as with
     * the first ore): the line of each approach still names the counter.
     */
    static void explainFirstExposure(ServerPlayer person) {
        if (!ReadMarks.isReadable(person) || ReadMarks.isRead(person, FIRST_EXPOSURE_MARK)) {
            return;
        }
        ReadMarks.mark(person, FIRST_EXPOSURE_MARK);
        person.sendSystemMessage(Component.translatable("creature.deepcharter.foot_first"));
    }
}
