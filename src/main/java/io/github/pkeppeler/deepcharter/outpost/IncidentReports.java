package io.github.pkeppeler.deepcharter.outpost;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerChain.Band;
import io.github.pkeppeler.deepcharter.outpost.IncidentReportPayload.Kind;
import io.github.pkeppeler.deepcharter.outpost.IncidentReportPayload.Line;
import io.github.pkeppeler.deepcharter.outpost.OutpostData.Outpost;
import io.github.pkeppeler.deepcharter.outpost.OutpostVisits.Ledger;
import io.github.pkeppeler.deepcharter.outpost.OutpostVisits.Report;
import io.github.pkeppeler.deepcharter.pod.PodFuelItems;
import io.github.pkeppeler.deepcharter.terminal.TerminalEvents;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * The incident report: the first member of an outpost's charter to open one of its terminals after visits met it is sent what was lost,
 * and the ledger's unread report is cleared, so the rest of the charter is not sent it again. A guest of the outpost, a player on
 * another charter, and a client that cannot show it get nothing, and the report waits.
 */
final class IncidentReports {
    private IncidentReports() {}

    static void init() {
        IncidentReportPayload.register();
        TerminalEvents.OPENED.register((server, type, player, pos) -> onOpened(server, player, pos));
    }

    private static void onOpened(MinecraftServer server, ServerPlayer player, BlockPos pos) {
        OutpostData outposts = OutpostData.get(server);
        OutpostVisits visits = OutpostVisits.get(server);
        if (!outposts.isReadable() || !visits.isReadable()) {
            return;
        }
        Optional<Charter> charter = Charters.readableCharterOf(server, player.getUUID());
        Optional<Band> band = LayerChain.layerAt(player.level(), pos.getY());
        if (charter.isEmpty() || band.isEmpty()) {
            return;
        }
        Optional<Outpost> outpost = outposts.containing(band.get().layer(), pos)
                .filter(found -> found.charter().equals(charter.get().id()));
        Optional<Ledger> ledger = outpost.flatMap(found -> visits.find(found.charter(), found.layer()))
                .filter(held -> !held.unread().isEmpty());
        if (ledger.isEmpty() || !ServerPlayNetworking.canSend(player, IncidentReportPayload.TYPE)) {
            return;
        }
        ServerPlayNetworking.send(
                player, payload(outpost.get().layer(), ledger.get().unread()));
        visits.put(ledger.get().withUnread(Report.NONE));
    }

    /** The report as the client is sent it: the biggest lines, fuel then ore then losses, each by item id. */
    private static IncidentReportPayload payload(int layer, Report report) {
        List<Line> lines = new ArrayList<>();
        sorted(report.taken())
                .forEach(entry -> lines.add(new Line(entry.getKey(), entry.getValue(), kindOfTaken(entry.getKey()))));
        sorted(report.broken()).forEach(entry -> lines.add(new Line(entry.getKey(), entry.getValue(), Kind.BROKEN)));
        // The biggest losses make the cut; the lines then read fuel, ore, losses.
        lines.sort(Comparator.comparingInt(Line::count).reversed());
        List<Line> shown = new ArrayList<>(lines.subList(0, Math.min(lines.size(), IncidentReportPayload.MAX_LINES)));
        shown.sort(Comparator.comparing(Line::kind));
        return new IncidentReportPayload(layer, report.visits(), shown, report.heaps(), report.sortedCauses());
    }

    private static List<Map.Entry<Identifier, Integer>> sorted(Map<Identifier, Integer> counts) {
        return counts.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
    }

    /** Fuel when the item refuels a pod, else ore: the ledger keeps one map of what was taken. */
    private static Kind kindOfTaken(Identifier item) {
        boolean fuel = BuiltInRegistries.ITEM
                .getOptional(item)
                .map(found -> new ItemStack(found).is(PodFuelItems.TAG))
                .orElse(false);
        return fuel ? Kind.FUEL : Kind.ORE;
    }
}
