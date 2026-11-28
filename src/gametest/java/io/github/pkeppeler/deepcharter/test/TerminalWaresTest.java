package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.charter.terminal.ContractTerminal;
import io.github.pkeppeler.deepcharter.hangar.HangarTerminal;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.suit.SuitTrack;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.TerminalWares;
import java.util.Map;
import java.util.Objects;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.locale.Language;

/**
 * Every item a terminal sells says what it does before the player buys it (#1645): each ware a terminal declares in
 * {@link TerminalWares} has a line in the lang file, and each terminal either declares its wares or is named below
 * as selling nothing.
 */
public class TerminalWaresTest {
    /** The longest a line may be, so that it wraps to the tip's rows. */
    private static final int MAX_LINE = 90;

    /** Terminals that sell nothing, with why: a terminal not named here and with no wares fails the test. */
    private static final Map<TerminalType, String> SELLS_NOTHING = Map.of(
            TerminalTypes.ORE_PROCESSOR, "it buys the player's ore and spends nothing of theirs on its own items",
            ContractTerminal.TYPE, "it founds and joins charters and sells nothing");

    /**
     * The upgrade terminal sells pod parts, and #1440 (PR #1494) writes each track's line and the wares with it.
     * This entry goes with that PR: the test fails once the terminal declares wares, so the entry cannot outlive the
     * fix.
     */
    private static final Map<TerminalType, String> PENDING = Map.of(TerminalTypes.UPGRADE_TERMINAL, "#1440");

    @GameTest
    public void everyTerminalSaysWhatItSells(GameTestHelper helper) {
        int terminals = 0;
        for (TerminalType type : TerminalTypes.all()) {
            if (!type.id().getNamespace().equals("deepcharter")) {
                continue;
            }
            terminals++;
            Map<String, String> wares = TerminalWares.of(type);
            if (SELLS_NOTHING.containsKey(type)) {
                expect(helper, wares.isEmpty(), "%s is listed as selling nothing but declares %s", type.id(), wares);
            } else if (PENDING.containsKey(type)) {
                expect(
                        helper,
                        wares.isEmpty(),
                        "%s now declares wares, so drop it from PENDING (%s)",
                        type.id(),
                        PENDING.get(type));
            } else {
                expect(
                        helper,
                        !wares.isEmpty(),
                        "%s sells nothing in TerminalWares: declare its wares, or name it in SELLS_NOTHING with why",
                        type.id());
            }
            wares.forEach((ware, key) -> {
                expect(
                        helper,
                        Language.getInstance().has(key),
                        "%s sells %s with no line: %s is not in the lang file",
                        type.id(),
                        ware,
                        key);
                String line = Language.getInstance().getOrDefault(key);
                expect(helper, !line.isBlank(), "%s's line for %s is empty", type.id(), ware);
                expect(
                        helper,
                        line.length() <= MAX_LINE,
                        "%s's line for %s is %s long, over %s: %s",
                        type.id(),
                        ware,
                        line.length(),
                        MAX_LINE,
                        line);
            });
        }
        expect(helper, terminals >= 6, "the test walked the colony's terminals, only %s", terminals);
        helper.succeed();
    }

    @GameTest
    public void everyShopItemAndTrackIsDeclared(GameTestHelper helper) {
        Map<String, String> repair = TerminalWares.of(TerminalTypes.REPAIR_STATION);
        for (Consumable consumable : Consumable.values()) {
            String ware = "item:" + consumable.itemId().getPath();
            expect(helper, repair.containsKey(ware), "the repair station sells %s with no line", ware);
            expect(
                    helper,
                    Objects.equals(
                            repair.get(ware),
                            "deepcharter.spec.item." + consumable.itemId().getPath()),
                    "%s's line is its spec sheet line, got %s",
                    ware,
                    repair.get(ware));
        }
        for (SuitTrack track : SuitTrack.values()) {
            expect(
                    helper,
                    repair.containsKey("suit:" + track.id()),
                    "the repair station sells suit %s with no line",
                    track);
        }
        for (String ware : new String[] {"repair", "repair_total", "service_engine", "scrape_flue"}) {
            expect(helper, repair.containsKey(ware), "the repair station sells %s with no line", ware);
        }
        Map<String, String> pump = TerminalWares.of(TerminalTypes.FUEL_PUMP);
        expect(
                helper,
                pump.containsKey("buy_fuel") && pump.containsKey("fill_fuel"),
                "the pump says what a litre and a fill do: %s",
                pump);
        Map<String, String> hangar = TerminalWares.of(HangarTerminal.TYPE);
        expect(
                helper,
                hangar.containsKey("buy_mole") && hangar.containsKey("restore_wreck"),
                "the hangar says what it sells: %s",
                hangar);
        helper.succeed();
    }
}
