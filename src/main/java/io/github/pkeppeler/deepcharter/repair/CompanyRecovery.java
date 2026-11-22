package io.github.pkeppeler.deepcharter.repair;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodEvents;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.pod.PodTowing;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.netty.buffer.ByteBuf;
import java.util.Optional;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The Company's recovery crew (#963, SPEC section 7: a stranded pod waits for "rescue or a reserve tank"). A pod that is stranded with a
 * pilot aboard starts a clock; when {@link RecoveryTuning#delayTicks()} have passed with the pod still dry, the crew winches it, and
 * everyone in it, home to the colony. It is the one way out that needs no fuel, no item and no money, so a pilot who runs dry at the bottom
 * of a shaft is never stuck, and it costs something every time:
 *
 * <ul>
 *   <li>the wait, in the dark (a fuel item, a reserve tank or a pump in reach wave the crew off: the clock stops when the pod is not stranded);
 *   <li>the fee, {@link RecoveryTuning#fee()} dollars from the owner charter's account, up to what it holds. The account never goes negative
 *       (ADR 0007), so there is no debt: what the account cannot pay is taken from the hull instead, the unpaid share of
 *       {@link RecoveryTuning#scrapeHull()};
 *   <li>the cargo, which stays where the pod was as spilled ore (nothing mined teleports, {@link Teleports}).
 * </ul>
 *
 * <p>The crew leaves {@link RecoveryTuning#splashLitres()} litres in the tank, so the pilot can drive to the pump. The fee goes to the pod's
 * owner charter, or to the pilot's if the pod is unowned. A pod nobody pilots, a wreck and a towed pod wait for nobody.
 *
 * <p>The clock is one versioned attachment holding the game time the wait began, so it is set twice a stranding (not every tick), the client
 * reads the time left from it for the HUD ({@link #secondsLeft}), and it survives a reload and a crossing.
 */
public final class CompanyRecovery {
    public static final int VERSION = 1;

    /** The game time the pod's wait began, or {@link #NONE} when it is not waiting. */
    public record State(long since) {
        public static final State NONE = new State(Long.MIN_VALUE);
        public static final MapCodec<State> BODY = RecordCodecBuilder.mapCodec(
                instance -> instance.group(Codec.LONG.fieldOf("since").forGetter(State::since))
                        .apply(instance, State::new));
        public static final StreamCodec<ByteBuf, State> STREAM = ByteBufCodecs.LONG.map(State::new, State::since);

        public boolean waiting() {
            return since != Long.MIN_VALUE;
        }
    }

    public static final AttachmentType<Versioned<State>> STATE = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "company_recovery"),
            builder -> builder.persistent(Versioned.codec(VERSION, State.BODY))
                    .initializer(() -> Versioned.of(State.NONE))
                    .syncWith(Versioned.streamCodec(VERSION, State.STREAM), AttachmentSyncPredicate.all()));

    private static final int TICKS_PER_SECOND = 20;

    private CompanyRecovery() {}

    static void init() {
        PodEvents.AFTER_TICK.register(CompanyRecovery::afterTick);
    }

    /**
     * Whole seconds until the crew hauls the pod, for the HUD: empty when the pod is not waiting for them. Reads synced state only, so
     * the client can ask. Rounds up, so the readout never shows 0 while the pod is still there.
     */
    public static Optional<Integer> secondsLeft(PodEntity pod) {
        return Versioned.readable(pod, STATE).filter(State::waiting).map(state -> {
            long left = state.since()
                    + RecoveryTuning.DEFAULT.delayTicks()
                    - pod.level().getGameTime();
            return (int) Math.max(0L, (left + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND);
        });
    }

    private static void afterTick(PodEntity pod) {
        Optional<State> read = Versioned.readable(pod, STATE);
        if (read.isEmpty()) {
            return;
        }
        // Nobody to wait for: no pilot, a wreck, a towed pod, or a pod that has fuel again.
        if (!(pod.getControllingPassenger() instanceof ServerPlayer pilot)
                || !pod.stranded()
                || pod.hull() <= 0f
                || PodTowing.isTowed(pod)) {
            if (read.get().waiting()) {
                set(pod, State.NONE);
            }
            return;
        }
        long now = pod.level().getGameTime();
        if (!read.get().waiting()) {
            set(pod, new State(now));
            RecoveryTuning tuning = RecoveryTuning.DEFAULT;
            pilot.sendOverlayMessage(Component.translatable(
                    "message.deepcharter.repair.recovery_called",
                    tuning.delayTicks() / TICKS_PER_SECOND,
                    tuning.fee()));
        } else if (now - read.get().since() >= RecoveryTuning.DEFAULT.delayTicks()) {
            haul(pod, pilot);
        }
    }

    private static void set(PodEntity pod, State state) {
        Versioned.modifyOrThrow(pod, STATE, old -> state);
    }

    /** Hauls the pod home, or, when the colony cannot take it, waits another delay and tells the pilot why. */
    private static void haul(PodEntity pod, ServerPlayer pilot) {
        MinecraftServer server = ((ServerLevel) pod.level()).getServer();
        RecoveryTuning tuning = RecoveryTuning.DEFAULT;
        Optional<Charter> payer =
                PodComponents.ownerCharter(pod).or(() -> Charters.readableCharterOf(server, pilot.getUUID()));
        long paid =
                payer.map(charter -> Math.min(charter.account(), tuning.fee())).orElse(0L);
        float unpaidShare = 1f - (float) paid / tuning.fee();
        float fuelBefore = pod.fuel();
        float hullBefore = pod.hull();
        // The pod's own state travels with it, so it is set first and put back if the crew cannot haul it.
        float full = PodTuning.DEFAULT.shell().fullFuel();
        pod.setFuel(tuning.splashLitres() / PodStats.of(pod).tankLitres() * full);
        pod.setStranded(false);
        pod.setHull(Math.min(hullBefore, Math.max(1f, hullBefore - tuning.scrapeHull() * unpaidShare)));
        Optional<Component> refusal = Teleports.send(pod, 0.0);
        if (refusal.isPresent()) {
            pod.setFuel(fuelBefore);
            pod.setStranded(true);
            pod.setHull(hullBefore);
            set(pod, new State(pod.level().getGameTime()));
            pilot.sendOverlayMessage(refusal.get());
            return;
        }
        // The crossing may have made a new pod: the clock is cleared by its own next tick, as it is no longer stranded.
        payer.filter(charter -> paid > 0)
                .ifPresent(charter -> Charters.spend(server, charter.id(), paid)
                        .ifPresent(overdraft -> DeepCharter.LOGGER.warn(
                                "the recovery fee of ${} was refused by charter {}: {}",
                                paid,
                                charter.name(),
                                refusal)));
        pilot.sendOverlayMessage(
                paid >= tuning.fee()
                        ? Component.translatable("message.deepcharter.repair.recovery_done", paid)
                        : Component.translatable("message.deepcharter.repair.recovery_short", paid, tuning.fee()));
    }
}
