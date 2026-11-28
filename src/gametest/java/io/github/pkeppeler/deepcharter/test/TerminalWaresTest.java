package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.handbook.SpecSheets;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.repair.RepairStation;
import io.github.pkeppeler.deepcharter.suit.SuitShop;
import io.github.pkeppeler.deepcharter.suit.SuitTrack;
import io.github.pkeppeler.deepcharter.terminal.TerminalActions;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.TerminalWares;
import io.github.pkeppeler.deepcharter.terminal.TerminalWares.Ware;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Every item a terminal sells says what it does before the player buys it (#1645). The test walks every action each
 * Deep Charter terminal registers: it is a ware with a line in the lang file, or it is named below as no sale, with
 * why.
 */
public class TerminalWaresTest {
    /** The longest a line may be, so that it wraps to the tip's rows. */
    private static final int MAX_LINE = 90;

    /**
     * The actions that sell nothing, as {@code <terminal>/<action>} with why. A new action not named here must be a
     * ware.
     */
    private static final Map<String, String> NOT_A_SALE = Map.ofEntries(
            Map.entry("contract_terminal/charter_found", "charter paperwork, no price"),
            Map.entry("contract_terminal/charter_apply", "charter paperwork, no price"),
            Map.entry("contract_terminal/charter_revive", "charter paperwork, no price"),
            Map.entry("contract_terminal/charter_approve", "the Director answers an application, no price"),
            Map.entry("contract_terminal/charter_deny", "the Director answers an application, no price"),
            Map.entry("contract_terminal/charter_leave", "leaving or withdrawing, no price"),
            Map.entry("contract_terminal/charter_share_outposts", "a Director's setting, no price"),
            Map.entry("contract_terminal/charter_keep_outposts", "a Director's setting, no price"),
            Map.entry(
                    "hangar_console/scrap_wreck",
                    "takes the player's own wreck apart into its parts and hold, for no money either way"),
            Map.entry("ore_processor/sell_cargo", "the terminal buys the player's ore"),
            Map.entry("ore_processor/sell_inventory", "the terminal buys the player's ore"),
            Map.entry("ore_processor/fuse_spoil", "turns spoil the player brings into ore, nothing is bought"),
            Map.entry("ore_processor/press_crates", "presses what the player brings into crates, nothing is bought"),
            Map.entry("ore_processor/withdraw_ore", "hands back ore the player already owns"),
            Map.entry("ore_processor/work_order_deliver", "pays the player for ore delivered"));

    /**
     * Actions that sell something and have no line yet, as {@code <terminal>/<action>} with the issue that gives
     * them one.
     */
    private static final Map<String, String> PENDING = Map.of("upgrade_terminal/upgrade_buy", "#1693");

    @GameTest
    public void everyActionIsAWareWithALineOrNamedAsNoSale(GameTestHelper helper) {
        Set<String> seen = new HashSet<>();
        int terminals = 0;
        for (TerminalType type : TerminalTypes.all()) {
            if (!type.id().getNamespace().equals("deepcharter")) {
                continue;
            }
            terminals++;
            List<Ware> wares = TerminalWares.of(type);
            Set<Identifier> actions = TerminalActions.registered(type);
            for (Identifier action : actions) {
                String key = type.id().getPath() + "/" + action.getPath();
                seen.add(key);
                boolean sold = wares.stream().anyMatch(ware -> ware.action().equals(action));
                if (NOT_A_SALE.containsKey(key) || PENDING.containsKey(key)) {
                    expect(
                            helper,
                            !sold,
                            "%s is named as no sale or pending but is a ware: drop it from the list",
                            key);
                } else {
                    expect(
                            helper,
                            sold,
                            "%s sells nothing in TerminalWares: declare it with the line that says what it does, or name it in NOT_A_SALE with why",
                            key);
                }
            }
            for (Ware ware : wares) {
                expect(
                        helper,
                        actions.contains(ware.action()),
                        "%s's ware %s names an action it lacks",
                        type.id(),
                        ware.id());
                String line = Language.getInstance().getOrDefault(ware.aboutKey());
                expect(
                        helper,
                        Language.getInstance().has(ware.aboutKey()) && !line.isBlank(),
                        "%s sells %s with no line: %s is not in the lang file",
                        type.id(),
                        ware.id(),
                        ware.aboutKey());
                expect(
                        helper,
                        line.length() <= MAX_LINE,
                        "%s's line for %s is %s long, over %s: %s",
                        type.id(),
                        ware.id(),
                        line.length(),
                        MAX_LINE,
                        line);
            }
        }
        expect(helper, terminals > 0, "the test walked no terminal");
        Set<String> listed = new HashSet<>(NOT_A_SALE.keySet());
        listed.addAll(PENDING.keySet());
        listed.removeAll(seen);
        expect(helper, listed.isEmpty(), "NOT_A_SALE or PENDING names actions no terminal registers: %s", listed);
        helper.succeed();
    }

    @GameTest
    public void everyShopItemAndSuitTrackIsAWareWithItsSpecSheetLine(GameTestHelper helper) {
        List<Ware> repair = TerminalWares.of(TerminalTypes.REPAIR_STATION);
        for (Consumable consumable : Consumable.values()) {
            String ware = RepairStation.ware(consumable);
            expect(
                    helper,
                    repair.stream().anyMatch(known -> known.id().equals(ware)),
                    "the repair station sells %s with no line",
                    ware);
            Component sheet = SpecSheets.of(RepairRegistry.item(consumable)).blurb();
            expect(
                    helper,
                    TerminalWares.about(TerminalTypes.REPAIR_STATION, ware)
                            .getString()
                            .equals(sheet.getString()),
                    "%s's line is its spec sheet line",
                    ware);
        }
        for (SuitTrack track : SuitTrack.values()) {
            String ware = SuitShop.ware(track);
            expect(
                    helper,
                    repair.stream().anyMatch(known -> known.id().equals(ware)),
                    "the repair station sells %s with no line",
                    ware);
        }
        helper.succeed();
    }
}
