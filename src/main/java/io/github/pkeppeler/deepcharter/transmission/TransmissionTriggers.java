package io.github.pkeppeler.deepcharter.transmission;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.hangar.Hangar;
import io.github.pkeppeler.deepcharter.hangar.HangarTerminal;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.Zones;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/**
 * What fires each transmission of the script ({@code docs/lore/transmissions.md}, which owns the triggers). A breach crossing and
 * standing in a zone are watched here, from the data file's triggers. The event triggers of the data file are the events below, each
 * mapped here to its transmission:
 *
 * <ul>
 *   <li>a charter founded: T01, then the repairs the world has fired ({@link Transmissions#replayTo});
 *   <li>a colony terminal repaired: T02 for the fuel pump, T03 for the ore processor, T04 for the upgrade terminal, T05 for any
 *       other;
 *   <li>a Mole assembled at the hangar: T06, which the hangar also fires when it hands the founding Mole to the charter
 * that repaired the
 *       hangar console;
 *   <li>the Prospector restored: T17, fired by the hangar from its tuning.
 * </ul>
 *
 * A player on no charter fires nothing.
 */
public final class TransmissionTriggers {
    private static final Identifier FOUNDED = id("t01");
    private static final Map<TerminalType, Identifier> REPAIRS = Map.of(
            TerminalTypes.FUEL_PUMP, id("t02"),
            TerminalTypes.ORE_PROCESSOR, id("t03"),
            TerminalTypes.UPGRADE_TERMINAL, id("t04"));
    private static final Identifier FURTHER_REPAIR = id("t05");

    private TransmissionTriggers() {}

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, path);
    }

    /** A new charter is welcomed first, then sent the repairs the world fired before it existed, in order. */
    public static void onFounded(MinecraftServer server, Charter charter) {
        TransmissionData data = TransmissionData.get(server);
        Transmissions.fire(server, data, charter.id(), FOUNDED);
        Transmissions.replayTo(server, data, charter.id());
    }

    /**
     * A terminal repaired by {@code charter}: the transmission of that terminal. The hangar console's is the hangar's to fire, once the
     * founding Mole it repairs is the charter's.
     */
    public static void onRepaired(MinecraftServer server, TerminalType type, Charter charter) {
        if (type != HangarTerminal.TYPE) {
            Transmissions.fire(server, charter.id(), REPAIRS.getOrDefault(type, FURTHER_REPAIR));
        }
    }

    /** A terminal action that took effect: assembling a Mole is how a charter after the first gets its Mole, and T06. */
    public static void onActed(MinecraftServer server, TerminalType type, ServerPlayer player, Identifier action) {
        if (type == HangarTerminal.TYPE && action.equals(HangarTerminal.ASSEMBLE_MOLE)) {
            Charters.readableCharterOf(server, player.getUUID())
                    .ifPresent(charter -> Transmissions.fire(server, charter.id(), Hangar.MOLE_TRANSMISSION));
        }
    }

    /** A descent fires the transmissions of the breach into the layer, and the climb out into the surface fires those of the surface. Another ascent fires nothing. */
    public static void onCrossed(Entity entity, ServerLevel from, ServerLevel to, int fromLayer, int toLayer) {
        if (!(entity instanceof ServerPlayer player) || (toLayer < fromLayer && toLayer != LayerChain.SURFACE)) {
            return;
        }
        MinecraftServer server = from.getServer();
        Charters.readableCharterOf(server, player.getUUID())
                .ifPresent(charter -> TransmissionCatalog.forBreach(toLayer)
                        .forEach(transmission -> Transmissions.fire(server, charter.id(), transmission.id())));
    }

    /** Fires the transmissions of the zone each charter member stands in, and of its lower half when they are in that. */
    public static void pollZones(MinecraftServer server) {
        if (!Charters.isReadable(server)) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Charters.readableCharterOf(server, player.getUUID()).ifPresent(charter -> {
                // The poll is also where a bonus that a full account refused is tried again.
                Transmissions.payPending(server, TransmissionData.get(server), charter.id());
                int y = player.getBlockY();
                LayerChain.layerAt(player.level(), y)
                        .ifPresent(band -> Zones.of(band.layer(), band.minY(), band.height(), y)
                                .ifPresent(zone -> {
                                    Zones.Span span = Zones.span(band.minY(), band.height(), zone.index());
                                    boolean lowerHalf = y < span.low() + span.size() / 2;
                                    TransmissionCatalog.forZone(zone.layer(), zone.index(), lowerHalf)
                                            .forEach(transmission ->
                                                    Transmissions.fire(server, charter.id(), transmission.id()));
                                }));
            });
        }
    }
}
