package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.handbook.SpecSheet;
import io.github.pkeppeler.deepcharter.handbook.SpecSheets;
import io.github.pkeppeler.deepcharter.pod.PartAxis;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.scanner.ScannerTuning;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.TrackOffer;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeStat;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeTuning;
import io.github.pkeppeler.deepcharter.upgrade.UpgradeView;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Server GameTests for #1440: what the upgrade terminal says about each track. Every track has a description, every
 * stat a track moves has a phrase and a unit, the next tier's gain is the pod's own stat change, the crafted tier 1 is
 * never offered, a track that waits for its crafted tier says so, and the fitted tier is named.
 */
public class UpgradeOfferTest {
    /** A Mole: parts above tier 2 work as tier 2. */
    private static final int MOLE_CAP = 2;

    @GameTest
    public void everyTrackHasADescription(GameTestHelper helper) {
        for (ComponentTrack track : ComponentTrack.values()) {
            String key = "screen.deepcharter.upgrade.about." + track.id();
            if (!Language.getInstance().has(key)) {
                throw failure(helper, "the %s track has no description %s", track.id(), key);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void everyStatATrackMovesHasAPhraseAndAUnit(GameTestHelper helper) {
        for (ComponentTrack track : ComponentTrack.values()) {
            if (track.maxTier() > 1 && UpgradeStat.of(track).isEmpty()) {
                throw failure(helper, "the terminal sells tiers of %s, but no stat shows their gain", track.id());
            }
        }
        for (UpgradeStat stat : UpgradeStat.values()) {
            for (String key : List.of(stat.phraseKey(), stat.unitKey())) {
                if (!Language.getInstance().has(key)) {
                    throw failure(helper, "the stat %s has no line %s", stat, key);
                }
            }
        }
        helper.succeed();
    }

    /**
     * For each track that moves a pod stat: the gain the terminal shows for tier 2 over tier 1 is what the pod's stats
     * do when the part goes in.
     */
    @GameTest
    public void theNextTierGainIsThePodsOwnStatChange(GameTestHelper helper) {
        PodEntity pod = helper.spawn(PodRegistry.POD, 2, 1, 2);
        CharterId owner = CharterId.random();
        PodComponents.register(pod, owner);
        for (ComponentTrack track : ComponentTrack.values()) {
            List<PartAxis> axes = PodComponents.axes(track);
            if (axes.isEmpty()) {
                continue;
            }
            PodComponents.install(pod, ComponentItems.mint(helper.getLevel().getServer(), track, 1, owner));
            PodStats first = PodStats.of(pod);
            PodComponents.install(pod, ComponentItems.mint(helper.getLevel().getServer(), track, 2, owner));
            PodStats second = PodStats.of(pod);
            List<String> expected =
                    axes.stream().map(axis -> expectedGain(axis, first, second)).toList();
            String line = TrackOffer.of(pod(track, 1, 1), track)
                    .nextLine()
                    .orElseThrow()
                    .getString();
            String wanted = "TIER 2  " + String.join("  ", expected);
            if (!line.equals(wanted)) {
                throw failure(helper, "the %s track's next tier should read '%s', got '%s'", track.id(), wanted, line);
            }
        }
        // The issue's own example, from the stats.
        String tank = TrackOffer.of(pod(ComponentTrack.FUEL_TANK, 1, 1), ComponentTrack.FUEL_TANK)
                .nextLine()
                .orElseThrow()
                .getString();
        if (!tank.matches("TIER 2  TANK \\d+ -> \\d+ L")) {
            throw failure(helper, "the tank's gain should read in litres, got '%s'", tank);
        }
        // The scanner reads in blocks of depth, as the scan does (ScannerTuning.area), not in the tuning's multiplier.
        String scanner = TrackOffer.of(pod(ComponentTrack.SCANNER, 1, 1), ComponentTrack.SCANNER)
                .nextLine()
                .orElseThrow()
                .getString();
        String depth = "TIER 2  SCAN DEPTH " + ScannerTuning.DEFAULT.area(1).down() + " -> "
                + ScannerTuning.DEFAULT.area(2).down() + " BLOCKS";
        if (!scanner.equals(depth)) {
            throw failure(helper, "the scanner's gain should read '%s', got '%s'", depth, scanner);
        }
        pod.discard();
        helper.succeed();
    }

    /**
     * "LIFT +7%" for a stat shown as a rise, "TANK 15 -> 25 L" for one shown before and after: the words are the lang
     * file's.
     */
    private static String expectedGain(PartAxis axis, PodStats before, PodStats after) {
        float from = axis.read(before);
        float to = axis.read(after);
        return switch (axis) {
            case DRILL_TIME -> "DRILL SPEED +" + Math.round((from / to - 1f) * 100f) + "%";
            case ENGINE_POWER -> "LIFT +" + Math.round((to / from - 1f) * 100f) + "%";
            case HORIZONTAL_SPEED -> "SPEED +" + Math.round((to / from - 1f) * 100f) + "%";
            case MAX_HULL -> "HULL " + Math.round(from) + " -> " + Math.round(to);
            case TANK_LITRES -> "TANK " + Math.round(from) + " -> " + Math.round(to) + " L";
            case CARGO_SLOTS -> "CARGO " + Math.round(from) + " -> " + Math.round(to) + " SLOTS";
        };
    }

    /** One model of per-tier figures: the spec sheet rates every track and tier with the figure the terminal shows. */
    @GameTest
    public void theSpecSheetAndTheTerminalGiveTheSameFigure(GameTestHelper helper) {
        for (ComponentTrack track : ComponentTrack.values()) {
            List<SpecSheet.Rating> rows =
                    SpecSheets.of(ComponentItems.item(track)).ratings();
            List<UpgradeStat> stats = UpgradeStat.of(track);
            boolean stock = UpgradeTuning.DEFAULT.hasStock(track);
            for (int tier = stock ? 0 : 1; tier <= track.maxTier(); tier++) {
                List<String> sheet = argsOf(rows.get(stock ? tier : tier - 1).value());
                List<String> terminal =
                        stats.isEmpty() ? List.of() : List.of(stats.getFirst().figure(tier));
                if (!sheet.equals(terminal)) {
                    throw failure(
                            helper,
                            "%s at tier %d: the spec sheet rates %s, the terminal %s",
                            track.id(),
                            tier,
                            sheet,
                            terminal);
                }
            }
        }
        helper.succeed();
    }

    private static List<String> argsOf(Component component) {
        if (!(component.getContents() instanceof TranslatableContents translatable)) {
            throw new AssertionError("not a lang key: " + component);
        }
        return Arrays.stream(translatable.getArgs())
                .map(arg -> arg instanceof Component inner ? inner.getString() : String.valueOf(arg))
                .toList();
    }

    @GameTest
    public void theCraftedTierIsNeverOffered(GameTestHelper helper) {
        for (ComponentTrack track : ComponentTrack.values()) {
            for (int installed = 0; installed <= track.maxTier(); installed++) {
                for (int effective : List.of(0, Math.min(installed, MOLE_CAP))) {
                    TrackOffer offer = TrackOffer.of(pod(track, installed, effective), track);
                    if (offer.forSale().contains(1) || offer.next().equals(Optional.of(1))) {
                        throw failure(
                                helper,
                                "%s with tier %d fitted offers the crafted tier 1: %s",
                                track.id(),
                                installed,
                                offer.forSale());
                    }
                }
            }
        }
        // A stock drill is sold tier 2 and up (ADR 0053): only the scanner waits for its crafted tier.
        List<Integer> drill = TrackOffer.of(pod(ComponentTrack.DRILL, 0, 0), ComponentTrack.DRILL)
                .forSale();
        if (!drill.equals(List.of(2, 3, 4, 5, 6))) {
            throw failure(helper, "a stock drill should be offered tiers 2 to 6, got %s", drill);
        }
        helper.succeed();
    }

    @GameTest
    public void anUnfittedFirstTierLocksTheNextPurchase(GameTestHelper helper) {
        TrackOffer scanner = TrackOffer.of(pod(ComponentTrack.SCANNER, 0, 0), ComponentTrack.SCANNER);
        expectLocked(helper, scanner, TrackOffer.Lock.FIT_FIRST, "FIT A CRAFTED SCANNER UNIT FIRST");
        if (!scanner.next().equals(Optional.of(2))) {
            throw failure(helper, "the scanner's next purchase is tier 2, locked; got %s", scanner.next());
        }
        TrackOffer hopper = TrackOffer.of(pod(ComponentTrack.SPOIL_HOPPER, 0, 0), ComponentTrack.SPOIL_HOPPER);
        expectLocked(helper, hopper, TrackOffer.Lock.CRAFT_ONLY, "CRAFT A SPOIL HOPPER UNIT AND FIT IT");
        // Another charter's scanner is void, so it unlocks nothing.
        expectLocked(
                helper,
                TrackOffer.of(pod(ComponentTrack.SCANNER, 1, 0), ComponentTrack.SCANNER),
                TrackOffer.Lock.FIT_FIRST,
                "FIT A CRAFTED SCANNER UNIT FIRST");

        TrackOffer fitted = TrackOffer.of(pod(ComponentTrack.SCANNER, 1, 1), ComponentTrack.SCANNER);
        if (fitted.lock() != TrackOffer.Lock.NONE
                || fitted.lockLine().isPresent()
                || !fitted.forSale().equals(List.of(2, 3, 4))) {
            throw failure(
                    helper,
                    "a fitted scanner unlocks tiers 2 to 4, got %s, %s, %s",
                    fitted.lock(),
                    fitted.lockLine(),
                    fitted.forSale());
        }
        helper.succeed();
    }

    private static void expectLocked(GameTestHelper helper, TrackOffer offer, TrackOffer.Lock lock, String line) {
        String shown = offer.lockLine().map(Component::getString).orElse("<none>");
        if (offer.lock() != lock || !shown.equals(line) || !offer.forSale().isEmpty()) {
            throw failure(
                    helper,
                    "%s: expected %s with '%s' and nothing for sale, got %s with '%s' and %s",
                    offer.track().id(),
                    lock,
                    line,
                    offer.lock(),
                    shown,
                    offer.forSale());
        }
    }

    @GameTest
    public void theFittedTierIsShown(GameTestHelper helper) {
        expectFitted(helper, ComponentTrack.HULL, 2, 2, "FITTED  TIER 2");
        expectFitted(helper, ComponentTrack.HULL, 4, 2, "FITTED  TIER 4  (WORKS AS TIER 2)");
        expectFitted(helper, ComponentTrack.DRILL, 0, 0, "FITTED  STOCK PART");
        expectFitted(helper, ComponentTrack.LINER, 0, 0, "FITTED  NOTHING");
        expectFitted(helper, ComponentTrack.SCANNER, 1, 0, "FITTED  TIER 1  (VOID)");
        String top = TrackOffer.of(pod(ComponentTrack.BRACE, 1, 1), ComponentTrack.BRACE)
                .nextLine()
                .map(Component::getString)
                .orElse("<none>");
        if (!top.equals("TOP TIER FITTED")) {
            throw failure(helper, "a fitted brace has nothing left to buy, got '%s'", top);
        }
        String capped = TrackOffer.of(pod(ComponentTrack.HULL, 2, 2), ComponentTrack.HULL)
                .nextLine()
                .map(Component::getString)
                .orElse("<none>");
        if (!capped.equals("TIER 3 WORKS AS TIER 2 IN THIS POD")) {
            throw failure(helper, "a Mole at its cap gains nothing from tier 3, got '%s'", capped);
        }
        helper.succeed();
    }

    private static void expectFitted(
            GameTestHelper helper, ComponentTrack track, int installed, int effective, String line) {
        String shown = TrackOffer.of(pod(track, installed, effective), track)
                .fittedLine()
                .getString();
        if (!shown.equals(line)) {
            throw failure(
                    helper,
                    "%s at tier %d (works as %d) should read '%s', got '%s'",
                    track.id().toUpperCase(Locale.ROOT),
                    installed,
                    effective,
                    line,
                    shown);
        }
    }

    /**
     * A Mole's view with {@code track} at {@code installed}, working at {@code effective}, and nothing on the other
     * tracks.
     */
    private static UpgradeView.Pod pod(ComponentTrack track, int installed, int effective) {
        List<UpgradeView.Slot> slots = Arrays.stream(ComponentTrack.values())
                .map(each -> each == track
                        ? new UpgradeView.Slot(each, installed, effective)
                        : new UpgradeView.Slot(each, 0, 0))
                .toList();
        return new UpgradeView.Pod("MOLE-0001", MOLE_CAP, slots);
    }
}
