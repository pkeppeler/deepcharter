package io.github.pkeppeler.deepcharter.suit;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.outpost.DepthRatings;
import io.github.pkeppeler.deepcharter.outpost.OutpostData;
import io.github.pkeppeler.deepcharter.outpost.OutpostData.Outpost;
import io.github.pkeppeler.deepcharter.outpost.OutpostHabitability;
import io.github.pkeppeler.deepcharter.outpost.OutpostHabitability.Status;
import io.github.pkeppeler.deepcharter.outpost.RatingAxis;
import io.github.pkeppeler.deepcharter.outpost.Ratings;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.suit.SuitState.Mode;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.Level;

/**
 * Runs every player's suit (#993, ADR 0083). Every tick it holds the air of a person under water whose suit still has reserve, and hurts a
 * person whose suit is breached every {@link SuitTuning#breachTicks()}. Once a second (every twentieth tick, staggered by the player's id so
 * no tick carries every player's) it decides what the suit is doing ({@link #second}): where the person is, what the depth there asks of a
 * suit, and what shelter they are in; pays the reserve out or takes it back; and warns before the reserve is spent.
 *
 * <p>It never throws: a suit, or the tracks that rate it, whose saved data this build cannot read is left alone (no drain, no harm), and the player's own client shows
 * that its log is unreadable.
 */
public final class SuitTicker {
    private static final int SECOND_TICKS = 20;
    /** The warning tiers, by how much time is left at the current rate. */
    private static final int AMBER = 1;

    private static final int RED = 2;
    private static final int SPENT = 3;
    /** An amber beep every this many seconds, a red one every second. */
    private static final int AMBER_BEEP_SECONDS = 5;
    /** How many drips a second shows in a leaking hall, and how far across the hall's ceiling they fall. */
    private static final int DRIPS = 12;

    private static final double DRIP_SPREAD = 3.5;

    /** The players who respawned since the last tick, whose suits the next tick refills. Server thread only. */
    private static final Set<UUID> NEW_LIVES = new HashSet<>();

    private SuitTicker() {}

    static void init() {
        ServerTickEvents.END_SERVER_TICK.register(SuitTicker::tick);
        // The new player's attachments are copied from the old one's around this event, in no order I can rely on, so
        // the refill waits for the next tick. A respawn alive (out of the End) is no new life and refills nothing.
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (!alive) {
                NEW_LIVES.add(newPlayer.getUUID());
            }
        });
    }

    /** What the suit does this second: how, at what rate, on which axes, and in which hall it leaks. */
    private record Plan(Mode mode, float rate, int shortfall, Optional<Outpost> hall) {
        static Plan of(Mode mode) {
            return new Plan(mode, 0f, 0, Optional.empty());
        }
    }

    private static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (NEW_LIVES.remove(player.getUUID())) {
                refill(player);
            }
            if ((now + player.getId()) % SECOND_TICKS == 0) {
                second(player);
            }
            perTick(player);
        }
    }

    /**
     * One second of {@code player}'s suit: reads it, decides what it is doing, pays or refills the reserve, says what is due, and writes the
     * suit only when it changed. Does nothing for a suit whose data is unreadable.
     */
    public static void second(ServerPlayer player) {
        second(player, OutpostHabitability::requiredAt);
    }

    /**
     * One second of {@code player}'s suit, with a hall judged against the row {@code hallRow} gives for it in place of its depth's own.
     */
    // test-seam: no built layer asks quake yet, so a hall short on quake alone is staged against a row the world does
    // not have
    public static void second(ServerPlayer player, BiFunction<Level, Outpost, Optional<Ratings>> hallRow) {
        Optional<SuitState> read = Versioned.readable(player, SuitRegistry.SUIT);
        if (read.isEmpty()) {
            return;
        }
        Optional<SuitTracks> bought = Versioned.readable(player, SuitRegistry.TRACKS);
        if (bought.isEmpty()) {
            return;
        }
        SuitState before = read.get();
        SuitTracks tracks = bought.get();
        Plan plan = plan(player, tracks.ratings(), hallRow);
        SuitTuning tuning = SuitTuning.DEFAULT;
        float reserve = before.reserve();
        Mode mode = plan.mode();
        if (mode.refills()) {
            reserve += tuning.refillPerSecond();
        } else if (mode.drains()) {
            reserve -= plan.rate();
        }
        reserve = Math.min(SuitState.sane(reserve), tracks.capacity(tuning));
        if (mode.drains() && reserve <= 0f) {
            mode = Mode.BREACHED;
        }
        SuitState after = new SuitState(
                reserve,
                before.explained(),
                mode,
                mode.drains() ? plan.rate() : 0f,
                mode.drains() ? plan.shortfall() : 0);
        if (mode.drains()) {
            after = warn(player, before, after, plan);
        }
        if (mode == Mode.LEAKING) {
            drip(player, plan.hall().orElseThrow());
        }
        if (!after.equals(before)) {
            player.setAttached(SuitRegistry.SUIT, Versioned.of(after));
        }
    }

    /** What the suit would do this second for {@code player}, without doing it: the mode {@link #second} would write. */
    static Mode modeNow(ServerPlayer player, SuitTracks tracks) {
        return plan(player, tracks.ratings(), OutpostHabitability::requiredAt).mode();
    }

    /** Where the person is decides what the suit does: seated, sheltered, leaking, exposed, or none of them. */
    private static Plan plan(ServerPlayer player, Ratings suit, BiFunction<Level, Outpost, Optional<Ratings>> hallRow) {
        if (OnFoot.seated(player) && player.getVehicle() instanceof PodEntity pod) {
            return Plan.of(PodEvents.isPowered(pod) ? Mode.POD_REFILL : Mode.POD_HOLD);
        }
        if (!OnFoot.is(player)) {
            return Plan.of(Mode.INERT);
        }
        SuitTuning tuning = SuitTuning.DEFAULT;
        ServerLevel level = player.level();
        OutpostData halls = OutpostData.get(level.getServer());
        Optional<Outpost> hall =
                halls.isReadable() ? halls.enclosing(level.dimension(), player.blockPosition()) : Optional.empty();
        if (hall.isPresent()) {
            Optional<Ratings> row = hallRow.apply(level, hall.get());
            Status status = OutpostHabitability.of(level, hall.get(), row);
            if (status.habitable()) {
                return Plan.of(Mode.SHELTERED);
            }
            // Water seeps in through a shell that is short on flooding, and a flooded hall is as wet as the depth.
            boolean wet = player.isUnderWater() || status.shortOn().contains(RatingAxis.FLOODING);
            return drain(Mode.LEAKING, row.orElse(tuning.uncharted()), suit, wet, tuning.leakShare(), hall);
        }
        Ratings asked = DepthRatings.requiredAt(level, player.getBlockY()).orElse(tuning.uncharted());
        return drain(Mode.EXPOSED, asked, suit, player.isUnderWater(), 1f, Optional.empty());
    }

    private static Plan drain(
            Mode mode, Ratings asked, Ratings suit, boolean wet, float share, Optional<Outpost> hall) {
        SuitTuning tuning = SuitTuning.DEFAULT;
        if (!SuitDrain.asks(asked)) {
            return Plan.of(Mode.INERT);
        }
        float rate = share * SuitDrain.perSecond(asked, suit, wet, tuning);
        return new Plan(mode, rate, SuitDrain.shortfall(asked, suit, wet), hall);
    }

    /** The first line, the amber and the red warnings and the breach, each said when its time comes, and the beeps between them. */
    private static SuitState warn(ServerPlayer player, SuitState before, SuitState after, Plan plan) {
        SuitTuning tuning = SuitTuning.DEFAULT;
        SuitState told = after;
        if (!before.explained()) {
            player.sendOverlayMessage(after.exceededLine().withStyle(ChatFormatting.GOLD));
            told = told.told();
        }
        float left = before.reserve() / plan.rate();
        float leftNow = after.reserve() / plan.rate();
        int tier = tier(left, tuning);
        int tierNow = tier(leftNow, tuning);
        if (tierNow > tier) {
            switch (tierNow) {
                case AMBER ->
                    player.sendOverlayMessage(
                            Component.translatable("message.deepcharter.suit.low", (int) tuning.warnSeconds())
                                    .withStyle(ChatFormatting.GOLD));
                case RED ->
                    player.sendOverlayMessage(
                            Component.translatable("message.deepcharter.suit.critical", (int) tuning.urgentSeconds())
                                    .withStyle(ChatFormatting.RED));
                default ->
                    player.sendOverlayMessage(Component.translatable("message.deepcharter.suit.breached")
                            .withStyle(ChatFormatting.RED));
            }
        }
        if (tierNow == RED || (tierNow == AMBER && (int) Math.ceil(leftNow) % AMBER_BEEP_SECONDS == 0)) {
            beep(player, tierNow == RED ? 1.8f : 1.2f);
        }
        if (tierNow == SPENT && tier != SPENT) {
            beep(player, 0.6f);
        }
        return told;
    }

    private static int tier(float secondsLeft, SuitTuning tuning) {
        if (secondsLeft <= 0f) {
            return SPENT;
        }
        if (secondsLeft <= tuning.urgentSeconds()) {
            return RED;
        }
        return secondsLeft <= tuning.warnSeconds() ? AMBER : 0;
    }

    private static void beep(ServerPlayer player, float pitch) {
        // PLACEHOLDER sound until the sound pass: a note block's pling, at the player.
        player.level().playSound(null, player, SoundEvents.NOTE_BLOCK_PLING.value(), SoundSource.PLAYERS, 0.6f, pitch);
    }

    /**
     * Water drips from the ceiling of a hall that leaks; none is placed, because fluid would delete a player's blocks. Each person in the
     * hall sends their own, so two people see twice the drips: a heavier seep, not worth a clock for each hall.
     */
    private static void drip(ServerPlayer player, Outpost hall) {
        BlockPos ceiling = hall.insideTop();
        player.level()
                .sendParticles(
                        ParticleTypes.DRIPPING_WATER,
                        ceiling.getX() + 0.5,
                        ceiling.getY() + 0.5,
                        ceiling.getZ() + 0.5,
                        DRIPS,
                        DRIP_SPREAD,
                        0.0,
                        DRIP_SPREAD,
                        0.0);
    }

    /** Every tick: the air of a person in a suit with reserve, and the harm of a breached one. */
    private static void perTick(ServerPlayer player) {
        Optional<SuitState> read = Versioned.readable(player, SuitRegistry.SUIT);
        if (read.isEmpty() || Versioned.readable(player, SuitRegistry.TRACKS).isEmpty()) {
            return;
        }
        SuitState state = read.get();
        SuitTuning tuning = SuitTuning.DEFAULT;
        if ((state.mode() == Mode.EXPOSED || state.mode() == Mode.LEAKING)
                && state.reserve() > 0f
                && player.isUnderWater()) {
            player.setAirSupply(player.getMaxAirSupply());
        }
        if (state.mode() == Mode.BREACHED && OnFoot.is(player) && player.tickCount % tuning.breachTicks() == 0) {
            ServerLevel level = player.level();
            DamageSource source = new DamageSource(
                    level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(SuitRegistry.EXPOSURE));
            player.hurtServer(level, source, tuning.breachDamage());
            player.sendOverlayMessage(
                    Component.translatable("message.deepcharter.suit.breached").withStyle(ChatFormatting.RED));
        }
    }

    /**
     * A new life starts with a full suit (to its own capacity) that is doing nothing yet; what the person was told stays told.
     */
    private static void refill(ServerPlayer player) {
        Optional<SuitTracks> tracks = Versioned.readable(player, SuitRegistry.TRACKS);
        Versioned.readable(player, SuitRegistry.SUIT)
                .filter(state -> tracks.isPresent())
                .ifPresent(state -> player.setAttached(
                        SuitRegistry.SUIT,
                        Versioned.of(new SuitState(
                                tracks.get().capacity(SuitTuning.DEFAULT), state.explained(), Mode.INERT, 0f, 0))));
    }
}
