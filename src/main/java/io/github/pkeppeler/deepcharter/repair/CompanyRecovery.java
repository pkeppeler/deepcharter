package io.github.pkeppeler.deepcharter.repair;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerChain.Band;
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
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The Company's recovery crew (#963, ADR 0073, SPEC section 7: a stranded pod waits for "rescue or a reserve tank"). A pod that is stranded with a
 * pilot aboard starts a clock; when {@link RecoveryTuning#delayTicks()} have passed with the pod still dry, the crew winches it, and
 * everyone in it, home to the colony. It is the one way out that needs no fuel, no item and no money, so a pilot who runs dry at the bottom
 * of a shaft is never stuck, and it costs something every time:
 *
 * <ul>
 *   <li>the wait, in the dark (a fuel item, a reserve tank or a pump in reach wave the crew off: the clock stops when the pod is not stranded);
 *   <li>the fee, {@link RecoveryTuning#feePerLayer()} dollars a layer deep, from the owner charter's account up to what it holds. The account
 *       never goes negative (ADR 0007), so there is no debt: what the account cannot pay is taken from the hull at the repair station's price a
 *       point, never below {@link RecoveryTuning#minHull()};
 *   <li>the cargo, which stays where the pod was as spilled ore (nothing mined teleports, {@link Teleports}).
 * </ul>
 *
 * <p>The crew leaves {@link RecoveryTuning#splashLitres()} litres in the tank, so the pilot can drive to the pump. The fee goes to the pod's
 * owner charter, or to the pilot's if the pod is unowned. A pod nobody pilots, a wreck and a towed pod wait for nobody.
 *
 * <p>An {@code Unreadable} clock never switches the net off: the tick reads it as no wait, writes a fresh one over it and carries on, so the pilot
 * is still hauled a delay later (ADR 0073).
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
        // A wreck does not tick, so its clock would stay set: a restored pod stranded again would be hauled with
        // no warning.
        PodEvents.HULL_DEPLETED.register(pod -> {
            if (Versioned.readable(pod, STATE).map(State::waiting).orElse(false)) {
                set(pod, State.NONE);
            }
        });
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

    /** The fee for hauling {@code pod} from where it is: {@link RecoveryTuning#feePerLayer()} a layer, the surface counting as layer 1. Reads the pod's place only, so the client can ask. */
    public static long feeFor(PodEntity pod) {
        int layer = LayerChain.layerAt(pod.level(), pod.getBlockY())
                .map(Band::layer)
                .orElse(1);
        return RecoveryTuning.DEFAULT.feeAt(layer);
    }

    private static void afterTick(PodEntity pod) {
        State state = Versioned.readable(pod, STATE).orElse(null);
        if (state == null) {
            // Unreadable (a newer build saved it): the safety net stays on. Read it as no wait and write a fresh clock
            // over it.
            pod.setAttached(STATE, Versioned.of(State.NONE));
            state = State.NONE;
        }
        // Nobody to wait for: no pilot, a wreck, a towed pod, or a pod that has fuel again.
        if (!(pod.getControllingPassenger() instanceof ServerPlayer pilot)
                || !pod.stranded()
                || pod.hull() <= 0f
                || PodTowing.isTowed(pod)) {
            if (state.waiting()) {
                set(pod, State.NONE);
            }
            return;
        }
        long now = pod.level().getGameTime();
        if (!state.waiting()) {
            set(pod, new State(now));
            pilot.sendOverlayMessage(Component.translatable(
                    "message.deepcharter.repair.recovery_called",
                    RecoveryTuning.DEFAULT.delayTicks() / TICKS_PER_SECOND,
                    feeFor(pod)));
        } else if (now - state.since() >= RecoveryTuning.DEFAULT.delayTicks()) {
            haul(pod, pilot);
        }
    }

    private static void set(PodEntity pod, State state) {
        Versioned.modifyOrThrow(pod, STATE, old -> state);
    }

    /**
     * Hauls the pod home. The fee is settled before the pod moves, spent first and refunded if the crew cannot land: the money and the
     * hull share of the fee are worked out from the account, then the account is charged, then the pod goes. When it cannot, everything is
     * put back and the pilot waits another delay.
     */
    private static void haul(PodEntity pod, ServerPlayer pilot) {
        MinecraftServer server = ((ServerLevel) pod.level()).getServer();
        RecoveryTuning tuning = RecoveryTuning.DEFAULT;
        long fee = feeFor(pod);
        Optional<Charter> payer =
                PodComponents.ownerCharter(pod).or(() -> Charters.readableCharterOf(server, pilot.getUUID()));
        long paid = payer.map(charter -> Math.min(charter.account(), fee)).orElse(0L);
        if (paid > 0) {
            Optional<CharterRefusal> overdraft =
                    Charters.spend(server, payer.orElseThrow().id(), paid);
            if (overdraft.isPresent()) {
                DeepCharter.LOGGER.warn(
                        "the recovery fee of ${} was refused by charter {}: {}",
                        paid,
                        payer.orElseThrow().name(),
                        overdraft.get());
                paid = 0;
            }
        }
        float hullBefore = pod.hull();
        float fuelBefore = pod.fuel();
        // The hull pays the unpaid dollars at the repair station's price, but never takes the pod below minHull (nor
        // raises it).
        float hullLoss = (float) (fee - paid) / RepairTuning.DEFAULT.repairCostPerHp();
        float hullAfter = Math.min(hullBefore, Math.max(tuning.minHull(), hullBefore - hullLoss));
        // The pod's own state travels with it, so it is set first and put back if the crew cannot haul it.
        float full = PodTuning.DEFAULT.shell().fullFuel();
        pod.setFuel(tuning.splashLitres() / PodStats.of(pod).tankLitres() * full);
        pod.setStranded(false);
        pod.setHull(hullAfter);
        Optional<Component> refusal = sendHome(pod, server, tuning);
        if (refusal.isPresent()) {
            pod.setFuel(fuelBefore);
            pod.setStranded(true);
            pod.setHull(hullBefore);
            if (paid > 0) {
                refund(server, payer.orElseThrow(), paid);
            }
            set(pod, new State(pod.level().getGameTime()));
            pilot.sendOverlayMessage(refusal.get());
            return;
        }
        // The crossing may have made a new pod: its own next tick clears the clock, as it is no longer stranded.
        pilot.sendOverlayMessage(
                paid >= fee
                        ? Component.translatable("message.deepcharter.repair.recovery_done", paid)
                        : Component.translatable("message.deepcharter.repair.recovery_short", paid, fee));
    }

    /** Sends the pod to the colony, or to the world spawn when the colony's column stays blocked. Empty when it went. */
    private static Optional<Component> sendHome(PodEntity pod, MinecraftServer server, RecoveryTuning tuning) {
        GlobalPos colony = Teleports.destination(server);
        Optional<Component> refusal = Teleports.send(pod, tuning.scatter(), colony, tuning.spill());
        GlobalPos spawn = Teleports.worldSpawn(server);
        return refusal.isPresent() && !colony.equals(spawn)
                ? Teleports.send(pod, tuning.scatter(), spawn, tuning.spill())
                : refusal;
    }

    private static void refund(MinecraftServer server, Charter charter, long amount) {
        Charters.deposit(server, charter.id(), amount)
                .ifPresent(refusal -> DeepCharter.LOGGER.error(
                        "the recovery fee of ${} could not be refunded to charter {}: {}",
                        amount,
                        charter.name(),
                        refusal));
    }
}
