package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;

import io.github.pkeppeler.deepcharter.attachment.Versioned;
import io.github.pkeppeler.deepcharter.repair.Consumable;
import io.github.pkeppeler.deepcharter.repair.RepairRegistry;
import io.github.pkeppeler.deepcharter.suit.SuitRegistry;
import io.github.pkeppeler.deepcharter.suit.SuitState;
import io.github.pkeppeler.deepcharter.suit.SuitState.Mode;
import io.github.pkeppeler.deepcharter.suit.SuitTicker;
import io.github.pkeppeler.deepcharter.suit.SuitTracks;
import io.github.pkeppeler.deepcharter.suit.SuitTuning;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.SuitScenes;
import io.github.pkeppeler.deepcharter.test.support.SuitScenes.Pocket;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/**
 * Server GameTests for the air canister (#1194): used on foot, it adds a minute to the suit's reserve and never past the suit's own
 * capacity; used on a full suit, or on one whose log cannot be read, it is refused and kept. A use goes through the item's own right
 * click, so the cooldown and the spent item are the real ones.
 */
public class AirCanisterTest {
    private static final String SAMPLE = "deepcharter:campaign_sample";
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 600;
    private static final float MINUTE = SuitTuning.DEFAULT.canisterSeconds();
    private static final int STACK = 3;

    /** A survivor on foot holding {@link #STACK} canisters, with a suit of {@code reserve} and {@code tracks}. */
    private static ServerPlayer holder(GameTestHelper helper, String name, SuitTracks tracks, float reserve) {
        ServerPlayer player = MockPlayers.join(helper, name).player();
        player.setGameMode(GameType.SURVIVAL);
        SuitScenes.setTracks(player, tracks);
        SuitScenes.setReserve(player, reserve, true);
        player.getInventory()
                .setItem(
                        player.getInventory().getSelectedSlot(),
                        new ItemStack(RepairRegistry.item(Consumable.AIR_CANISTER), STACK));
        return player;
    }

    private static InteractionResult use(ServerPlayer player) {
        return player.gameMode.useItem(
                player, player.level(), player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
    }

    private static int held(ServerPlayer player) {
        return player.getInventory().countItem(RepairRegistry.item(Consumable.AIR_CANISTER));
    }

    @GameTest
    public void aCanisterAddsAMinute(GameTestHelper helper) {
        ServerPlayer player = holder(helper, "canister-adds", SuitTracks.STOCK, 100f);
        InteractionResult result = use(player);
        expect(
                helper,
                result instanceof InteractionResult.Success,
                "a canister on a part-empty suit works, got %s",
                result);
        expect(
                helper,
                SuitScenes.state(player).reserve() == 100f + MINUTE,
                "a canister adds %s s to 100 s, the suit holds %s",
                MINUTE,
                SuitScenes.state(player).reserve());
        expect(helper, held(player) == STACK - 1, "a used canister is spent, %s are held", held(player));
        helper.succeed();
    }

    @GameTest
    public void aCanisterNeverFillsPastCapacity(GameTestHelper helper) {
        ServerPlayer player = holder(helper, "canister-cap", SuitTracks.STOCK, 150f);
        InteractionResult result = use(player);
        expect(
                helper,
                result instanceof InteractionResult.Success
                        && SuitScenes.state(player).reserve() == 180f,
                "a canister on 150 of 180 s tops up to 180 and no further, got %s with %s",
                result,
                SuitScenes.state(player).reserve());
        expect(helper, held(player) == STACK - 1, "the part that did not fit is wasted: the canister is spent");
        helper.succeed();
    }

    @GameTest
    public void aFullSuitRefusesAndKeepsTheCanister(GameTestHelper helper) {
        ServerPlayer player = holder(helper, "canister-full", SuitTracks.STOCK, 180f);
        InteractionResult result = use(player);
        expect(
                helper,
                result == InteractionResult.FAIL && SuitScenes.state(player).reserve() == 180f && held(player) == STACK,
                "a canister on a full suit is refused and kept, got %s, %s s, %s held",
                result,
                SuitScenes.state(player).reserve(),
                held(player));
        expect(
                helper,
                !player.getCooldowns().isOnCooldown(player.getItemInHand(InteractionHand.MAIN_HAND)),
                "a refused canister starts no cooldown");
        helper.succeed();
    }

    @GameTest
    public void aTierThreeSuitTakesItsOwnCapacity(GameTestHelper helper) {
        ServerPlayer player = holder(helper, "canister-t3", new SuitTracks(0, 0, 3), 300f);
        use(player);
        expect(
                helper,
                SuitScenes.state(player).reserve() == 360f,
                "an air tier 3 suit goes from 300 to 360 s, it holds %s",
                SuitScenes.state(player).reserve());
        SuitScenes.setReserve(player, 330f, true);
        player.getCooldowns().removeCooldown(player.getCooldowns().getCooldownGroup(player.getMainHandItem()));
        use(player);
        expect(
                helper,
                SuitScenes.state(player).reserve() == 360f,
                "an air tier 3 suit is capped at 360 s, it holds %s",
                SuitScenes.state(player).reserve());
        helper.succeed();
    }

    /** The cooldown is the stock items': a second canister in the same breath is not used. */
    @GameTest
    public void theCooldownApplies(GameTestHelper helper) {
        ServerPlayer player = holder(helper, "canister-cooldown", SuitTracks.STOCK, 0f);
        use(player);
        expect(
                helper,
                player.getCooldowns().isOnCooldown(player.getItemInHand(InteractionHand.MAIN_HAND)),
                "a used canister starts the item cooldown");
        use(player);
        expect(
                helper,
                SuitScenes.state(player).reserve() == MINUTE && held(player) == STACK - 1,
                "a second canister inside the cooldown is not used, the suit holds %s with %s held",
                SuitScenes.state(player).reserve(),
                held(player));
        helper.succeed();
    }

    @GameTest
    public void aSuitOrTracksLogThatCannotBeReadKeepsTheCanister(GameTestHelper helper) {
        ServerPlayer player = holder(helper, "canister-unreadable", SuitTracks.STOCK, 100f);
        UnreadableChecks.makeUnreadable(player, SuitRegistry.SUIT);
        InteractionResult suitResult = use(player);
        expect(
                helper,
                suitResult == InteractionResult.FAIL
                        && held(player) == STACK
                        && player.getAttached(SuitRegistry.SUIT) instanceof Versioned.Unreadable<SuitState>,
                "an unreadable suit log refuses the canister and is left as it was, got %s with %s held",
                suitResult,
                held(player));
        SuitScenes.setReserve(player, 100f, true);
        UnreadableChecks.makeUnreadable(player, SuitRegistry.TRACKS);
        InteractionResult tracksResult = use(player);
        expect(
                helper,
                tracksResult == InteractionResult.FAIL
                        && held(player) == STACK
                        && SuitScenes.state(player).reserve() == 100f
                        && player.getAttached(SuitRegistry.TRACKS) instanceof Versioned.Unreadable<SuitTracks>,
                "unreadable tracks refuse the canister and are left as they were, got %s with %s held",
                tracksResult,
                held(player));
        helper.succeed();
    }

    /** A spent, breached suit takes a canister like any other, and the next second leaves BREACHED. */
    @GameTest(environment = CampaignEnvironments.C, dimension = SAMPLE, maxTicks = MAX_TICKS)
    public void aBreachedSuitTakesOneAndTheNextSecondLeavesTheBreach(GameTestHelper helper) {
        Pocket pocket = SuitScenes.unbuilt(helper.getLevel(), 7810);
        MockPlayer mock = MockPlayers.join(helper, "canister-breached");
        ServerPlayer player = mock.player();
        SuitScenes.whenReady(helper, pocket, () -> {
            SuitScenes.onFootIn(mock, pocket, () -> {
                SuitScenes.setTracks(player, SuitTracks.STOCK);
                SuitScenes.setReserve(player, 0f, true);
                player.getInventory()
                        .setItem(
                                player.getInventory().getSelectedSlot(),
                                new ItemStack(RepairRegistry.item(Consumable.AIR_CANISTER), STACK));
                SuitTicker.second(player);
                expect(
                        helper,
                        SuitScenes.state(player).mode() == Mode.BREACHED,
                        "a spent suit at the unbuilt depth is breached, it is %s",
                        SuitScenes.state(player).mode());
                InteractionResult result = use(player);
                expect(
                        helper,
                        result instanceof InteractionResult.Success
                                && SuitScenes.state(player).reserve() == MINUTE,
                        "a breached suit takes a canister anywhere, got %s with %s",
                        result,
                        SuitScenes.state(player).reserve());
                SuitTicker.second(player);
                expect(
                        helper,
                        SuitScenes.state(player).mode() == Mode.EXPOSED
                                && SuitScenes.state(player).reserve() > 0f,
                        "the next second leaves the breach, the suit is %s with %s s",
                        SuitScenes.state(player).mode(),
                        SuitScenes.state(player).reserve());
            });
            helper.succeed();
        });
    }
}
