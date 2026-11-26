package io.github.pkeppeler.deepcharter.continuity;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.creature.LamplessFigure;
import io.github.pkeppeler.deepcharter.layer.Altimeter;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.sound.DeepSound;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;

/**
 * A person's death as the Company files it (#1337): counted once, announced in one chat line, and shown to the dying client as the
 * skull. Called from {@code continuity/mixin/ServerPlayerDieMixin} (ADR 0100), on the server thread, inside {@code ServerPlayer.die}.
 * Nothing here throws on an unreadable record: the death goes ahead and the number reads as unknown.
 */
public final class ContinuityEvents {
    /** The lowest and highest employee numbers: four digits. */
    static final int FIRST_EMPLOYEE = 1000;

    static final int LAST_EMPLOYEE = 9999;

    private ContinuityEvents() {}

    /**
     * The first thing a death does, before vanilla builds its message: counts the event, tells the dying client its number, and puts the
     * person's lamp out for everyone else, with the body's ash.
     */
    public static void died(ServerPlayer player) {
        Optional<ContinuityRecord> after =
                Versioned.readable(player, ContinuityRegistry.RECORD).map(ContinuityRecord::next);
        after.ifPresent(record -> player.setAttached(ContinuityRegistry.RECORD, Versioned.of(record)));
        int number = after.map(ContinuityRecord::events).orElse(0);
        if (ServerPlayNetworking.canSend(player, ContinuityEventPayload.TYPE)) {
            ServerPlayNetworking.send(player, new ContinuityEventPayload(number, employeeNumber(player.getUUID())));
        }
        ServerLevel level = player.level();
        // The person themselves hears nothing of this: their screen has gone black and silent.
        level.playSound(
                player,
                player.getX(),
                player.getEyeY(),
                player.getZ(),
                DeepSound.CONTINUITY_LAMP_OUT.event(),
                SoundSource.PLAYERS,
                1f,
                1f);
        LamplessFigure.dissolve(level, player);
    }

    /**
     * The chat line that replaces vanilla's death message, for every cause: {@code ▌CONTINUITY EVENT — <name> (No. N) — <cause>,
     * <depth>}. Read after {@link #died}, so N is this event's number. The depth is given only in the campaign's own bands, the surface
     * and the layers, as {@code DeepestPoint} counts them: anywhere else (the Nether, the End, under the last layer) the altimeter's
     * reading means nothing, and the line ends at the cause.
     */
    public static Component message(ServerPlayer player, DamageSource source) {
        Component number = Versioned.readable(player, ContinuityRegistry.RECORD)
                .map(record -> (Component) Component.literal(Integer.toString(record.events())))
                .orElseGet(() -> Component.translatable("deepcharter.continuity.unknown_number"));
        Component cause = Component.translatable(ContinuityCause.of(source).key());
        ServerLevel level = player.level();
        int y = player.getBlockY();
        if (LayerChain.bandAt(level, y).isEmpty()) {
            return Component.translatable(
                    "deepcharter.continuity.chat_no_depth", player.getDisplayName(), number, cause);
        }
        return Component.translatable(
                "deepcharter.continuity.chat", player.getDisplayName(), number, cause, Altimeter.reading(level, y));
    }

    /**
     * A person's employee number, four digits, the same in every world and every session: the mod has no number of its own for a person,
     * so it is made from their UUID. Permanent: a test pins it.
     */
    public static int employeeNumber(UUID person) {
        long mixed = person.getMostSignificantBits() * 31 + person.getLeastSignificantBits();
        return FIRST_EMPLOYEE + Math.floorMod(Long.hashCode(mixed), LAST_EMPLOYEE - FIRST_EMPLOYEE + 1);
    }
}
