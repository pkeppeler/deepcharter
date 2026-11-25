package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.expect;
import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;
import static io.github.pkeppeler.deepcharter.test.support.Inputs.SNEAK;

import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.menu.MenuRefusalPayload;
import io.github.pkeppeler.deepcharter.menu.SlottedMenu;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.pod.PodCargo;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodMenu;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodScreenPayload;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.SettlingRooms;
import io.github.pkeppeler.deepcharter.test.support.SlotClicks;
import io.github.pkeppeler.deepcharter.test.support.TestCleanups;
import io.github.pkeppeler.deepcharter.test.support.UseEntity;
import io.github.pkeppeler.deepcharter.upgrade.ComponentItems;
import io.github.pkeppeler.deepcharter.upgrade.ComponentTrack;
import io.github.pkeppeler.deepcharter.upgrade.PartFitting;
import io.github.pkeppeler.deepcharter.upgrade.PartLabel;
import io.github.pkeppeler.deepcharter.upgrade.PartUnits;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/**
 * Server GameTests for #1270 (ADR 0102): the pod screen. Only the pod's charter opens it, by sneak and use or by the key
 * from a seat; a unit dragged or shift-clicked into its track's slot is fitted and stays, a wrong one is refused with a
 * reason; the slots show bought tiers; fuel goes into the fuel slot, at most a stack, and feeds the tank as it has room;
 * the hold moves items both ways up to the cargo bay's size; and no item is made or lost on close, with two viewers, or
 * when the pod goes while the screen is open. Walking away, leaving the seat or losing the pod closes it.
 *
 * <p>The clicks are the server menu's own {@code clicked}, which the click packet runs.
 */
public class PodScreenTest {
    private static final AtomicInteger PLAYERS = new AtomicInteger();

    /** The pod's charter's player and the pod, beside each other; both go when the test ends, however it ends. */
    private record Scene(MockPlayer mock, ServerPlayer player, PodEntity pod, CharterId charter) {
        void clean() {
            mock.leave();
            pod.discard();
        }
    }

    private static MockPlayer join(GameTestHelper helper) {
        MockPlayer mock = MockPlayers.join(helper, "Pod" + PLAYERS.incrementAndGet());
        TestCleanups.onFinish(helper, mock::leave);
        mock.player().setGameMode(GameType.SURVIVAL);
        return mock;
    }

    private static CharterId found(GameTestHelper helper, ServerPlayer player) {
        MinecraftServer server = helper.getLevel().getServer();
        expect(
                helper,
                Charters.found(
                                server,
                                player.getUUID(),
                                "Pod " + UUID.randomUUID().toString().substring(0, 8))
                        .isEmpty(),
                "founding a charter should succeed");
        return Charters.charterOfOrThrow(server, player.getUUID()).orElseThrow().id();
    }

    private static void standBy(MockPlayer mock, PodEntity pod) {
        mock.teleportTo((ServerLevel) pod.level(), pod.position().add(2, 0, 0), 0f, 0f);
    }

    private static Scene scene(GameTestHelper helper) {
        MockPlayer mock = join(helper);
        CharterId charter = found(helper, mock.player());
        PodEntity pod = helper.spawn(PodRegistry.POD, 2, 1, 2);
        TestCleanups.onFinish(helper, pod::discard);
        PodComponents.register(pod, charter);
        standBy(mock, pod);
        return new Scene(mock, mock.player(), pod, charter);
    }

    private static PodMenu open(GameTestHelper helper, ServerPlayer player, PodEntity pod) {
        Optional<Component> refusal = PodMenu.open(player, pod);
        expect(helper, refusal.isEmpty(), "the pod's crew should open its screen, was refused: %s", refusal);
        if (!(player.containerMenu instanceof PodMenu menu)) {
            throw failure(helper, "the pod screen should be open, %s is", player.containerMenu);
        }
        return menu;
    }

    private static void click(PodMenu menu, ServerPlayer player, int index, ContainerInput input) {
        menu.clicked(index, 0, input, player);
    }

    private static void shiftClickFromInventory(PodMenu menu, ServerPlayer player, Item item) {
        click(menu, player, SlotClicks.inventorySlot(menu, item).index, ContainerInput.QUICK_MOVE);
    }

    private static int held(ServerPlayer player, Item item) {
        return SettlingRooms.count(player.getInventory(), item);
    }

    /** How many of {@code item} the pod's hold has, in all its slots. */
    private static int inHold(PodEntity pod, Item item) {
        int count = 0;
        for (int i = 0; i < pod.cargo().reach(); i++) {
            ItemStack stack = pod.cargo().stackAt(i);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    // ---- opening ----

    @GameTest
    public void sneakAndUseOpensThePodScreenForItsCharterWithoutBoarding(GameTestHelper helper) {
        Scene scene = scene(helper);
        try {
            scene.mock().setInput(SNEAK);
            InteractionResult result =
                    UseEntity.on(scene.player(), scene.pod().level(), InteractionHand.MAIN_HAND, scene.pod());
            expect(
                    helper,
                    result.consumesAction() && scene.player().containerMenu instanceof PodMenu,
                    "sneak and use should open the pod screen, got %s with %s",
                    result,
                    scene.player().containerMenu);
            expect(
                    helper,
                    !scene.pod().getPassengers().contains(scene.player()),
                    "opening the screen must not board the pod");
            helper.succeed();
        } finally {
            scene.clean();
        }
    }

    @GameTest
    public void aPlayerOutsideThePodsCharterCannotOpenIt(GameTestHelper helper) {
        Scene scene = scene(helper);
        MockPlayer stranger = join(helper);
        MockPlayer drifter = join(helper);
        try {
            found(helper, stranger.player());
            standBy(stranger, scene.pod());
            standBy(drifter, scene.pod());
            stranger.setInput(SNEAK);
            UseEntity.on(stranger.player(), scene.pod().level(), InteractionHand.MAIN_HAND, scene.pod());
            expect(
                    helper,
                    stranger.player().containerMenu == stranger.player().inventoryMenu,
                    "another charter's player must not open the screen, opened %s",
                    stranger.player().containerMenu);
            Optional<Component> refusal = PodMenu.open(drifter.player(), scene.pod());
            expect(
                    helper,
                    refusal.isPresent() && drifter.player().containerMenu == drifter.player().inventoryMenu,
                    "a player on no charter must not open the screen, refusal %s, open %s",
                    refusal,
                    drifter.player().containerMenu);
            helper.succeed();
        } finally {
            stranger.leave();
            drifter.leave();
            scene.clean();
        }
    }

    @GameTest
    public void theKeyOpensTheScreenOfThePodTheSenderSitsIn(GameTestHelper helper) {
        Scene scene = scene(helper);
        expect(helper, scene.player().startRiding(scene.pod(), true, false), "the crew should board");
        scene.mock().send(new PodScreenPayload());
        helper.succeedWhen(() -> {
            expect(
                    helper,
                    scene.player().containerMenu instanceof PodMenu,
                    "the key should open the pod screen, open %s",
                    scene.player().containerMenu);
        });
    }

    // ---- parts ----

    @GameTest
    public void aUnitShiftClickedIntoItsSlotIsFittedAndStays(GameTestHelper helper) {
        Scene scene = scene(helper);
        try {
            scene.player().getInventory().add(new ItemStack(PartUnits.unit(ComponentTrack.DRILL)));
            PodMenu menu = open(helper, scene.player(), scene.pod());
            Item unit = PartUnits.unit(ComponentTrack.DRILL);
            shiftClickFromInventory(menu, scene.player(), unit);
            Optional<PartLabel> fitted = PodComponents.partOf(scene.pod(), ComponentTrack.DRILL, scene.charter());
            expect(
                    helper,
                    fitted.map(PartLabel::tier).equals(Optional.of(1)) && held(scene.player(), unit) == 0,
                    "the drill unit should be fitted at tier 1 and spent, fitted %s, %s units held",
                    fitted,
                    held(scene.player(), unit));
            menu.broadcastChanges();
            ItemStack shown = menu.partSlot(ComponentTrack.DRILL).getItem();
            expect(
                    helper,
                    ComponentItems.trackOf(shown).equals(Optional.of(ComponentTrack.DRILL)),
                    "the drill slot should show the fitted part, shows %s",
                    shown);
            click(menu, scene.player(), menu.partSlot(ComponentTrack.DRILL).index, ContainerInput.QUICK_MOVE);
            click(menu, scene.player(), menu.partSlot(ComponentTrack.DRILL).index, ContainerInput.PICKUP);
            menu.broadcastChanges();
            expect(
                    helper,
                    menu.getCarried().isEmpty()
                            && held(scene.player(), ComponentItems.item(ComponentTrack.DRILL)) == 0
                            && PodComponents.partOf(scene.pod(), ComponentTrack.DRILL)
                                    .isPresent(),
                    "a fitted part never comes back out, carried %s",
                    menu.getCarried());
            helper.succeed();
        } finally {
            scene.clean();
        }
    }

    @GameTest
    public void aUnitDraggedOntoItsSlotIsFitted(GameTestHelper helper) {
        Scene scene = scene(helper);
        try {
            scene.player().getInventory().add(new ItemStack(PartUnits.unit(ComponentTrack.CARGO_BAY)));
            PodMenu menu = open(helper, scene.player(), scene.pod());
            Item unit = PartUnits.unit(ComponentTrack.CARGO_BAY);
            click(menu, scene.player(), SlotClicks.inventorySlot(menu, unit).index, ContainerInput.PICKUP);
            click(menu, scene.player(), menu.partSlot(ComponentTrack.CARGO_BAY).index, ContainerInput.PICKUP);
            expect(
                    helper,
                    menu.getCarried().isEmpty()
                            && PodComponents.partOf(scene.pod(), ComponentTrack.CARGO_BAY, scene.charter())
                                    .isPresent(),
                    "a dragged cargo bay unit should be fitted, carried %s",
                    menu.getCarried());
            helper.succeed();
        } finally {
            scene.clean();
        }
    }

    @GameTest
    public void aWrongUnitIsRefusedWithAReasonAndKept(GameTestHelper helper) {
        Scene scene = scene(helper);
        Component already = PartFitting.Refusal.ALREADY_FITTED.message(ComponentTrack.HULL);
        scene.mock().announceChannel(MenuRefusalPayload.TYPE);
        Item drill = PartUnits.unit(ComponentTrack.DRILL);
        Item hull = PartUnits.unit(ComponentTrack.HULL);
        scene.player().getInventory().add(new ItemStack(drill));
        scene.player().getInventory().add(new ItemStack(hull));
        PodComponents.installAndDropReplaced(
                scene.pod(),
                ComponentItems.mint(helper.getLevel().getServer(), ComponentTrack.HULL, 2, scene.charter()));
        PodMenu menu = open(helper, scene.player(), scene.pod());

        click(menu, scene.player(), SlotClicks.inventorySlot(menu, drill).index, ContainerInput.PICKUP);
        click(menu, scene.player(), menu.partSlot(ComponentTrack.SCANNER).index, ContainerInput.PICKUP);
        expect(
                helper,
                menu.getCarried().is(drill)
                        && menu.refusal().isPresent()
                        && PodComponents.partOf(scene.pod(), ComponentTrack.SCANNER)
                                .isEmpty(),
                "a drill unit dropped on the scanner slot is refused and kept, carried %s, refusal %s",
                menu.getCarried(),
                menu.refusal());
        menu.setCarried(ItemStack.EMPTY);
        scene.player().getInventory().add(new ItemStack(drill));

        shiftClickFromInventory(menu, scene.player(), hull);
        expect(
                helper,
                menu.refusal().equals(Optional.of(already))
                        && held(scene.player(), hull) == 1
                        && inHold(scene.pod(), hull) == 0
                        && PodComponents.partOf(scene.pod(), ComponentTrack.HULL)
                                .map(PartLabel::tier)
                                .equals(Optional.of(2)),
                "a hull unit over a bought hull is refused with the reason and stays in the pack, not the hold:"
                        + " refusal %s, %s held, %s in the hold",
                menu.refusal(),
                held(scene.player(), hull),
                inHold(scene.pod(), hull));
        helper.succeedWhen(() -> {
            expect(
                    helper,
                    scene.mock().payloadsOf(MenuRefusalPayload.class).stream()
                            .anyMatch(payload -> payload.reason().equals(already)),
                    "the server sends its refusal to the client's menu, sent %s",
                    scene.mock().payloadsOf(MenuRefusalPayload.class));
        });
    }

    @GameTest
    public void theSlotsShowTheBoughtTiersAndTheirCondition(GameTestHelper helper) {
        Scene scene = scene(helper);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            PodComponents.installAndDropReplaced(
                    scene.pod(), ComponentItems.mint(server, ComponentTrack.ENGINE, 3, scene.charter()));
            PodComponents.installAndDropReplaced(
                    scene.pod(), ComponentItems.mint(server, ComponentTrack.LIGHTS, 2, scene.charter()));
            PodMenu menu = open(helper, scene.player(), scene.pod());
            menu.broadcastChanges();
            Optional<PartLabel> engine =
                    ComponentItems.labelOf(menu.partSlot(ComponentTrack.ENGINE).getItem());
            Optional<PartLabel> lights =
                    ComponentItems.labelOf(menu.partSlot(ComponentTrack.LIGHTS).getItem());
            expect(
                    helper,
                    engine.map(PartLabel::tier).equals(Optional.of(3))
                            && lights.map(PartLabel::tier).equals(Optional.of(2))
                            && menu.partSlot(ComponentTrack.DRILL).getItem().isEmpty(),
                    "the slots should show the bought tier 3 engine and tier 2 lights and an empty drill, show %s, %s",
                    engine,
                    lights);
            // A Mole works a part at tier 2 at most.
            expect(
                    helper,
                    menu.condition(ComponentTrack.ENGINE).equals(Optional.of(PodMenu.Condition.CAPPED))
                            && menu.condition(ComponentTrack.LIGHTS).equals(Optional.of(PodMenu.Condition.WORKING))
                            && menu.condition(ComponentTrack.DRILL).isEmpty(),
                    "the engine is held to the Mole's cap and the lights work, got %s and %s",
                    menu.condition(ComponentTrack.ENGINE),
                    menu.condition(ComponentTrack.LIGHTS));
            helper.succeed();
        } finally {
            scene.clean();
        }
    }

    // ---- fuel ----

    @GameTest
    public void fuelGoesInAtMostAStackAndFeedsTheTankAsItHasRoom(GameTestHelper helper) {
        Scene scene = scene(helper);
        PodEntity pod = scene.pod();
        pod.setFuel(0f);
        ServerPlayer player = scene.player();
        player.getInventory().add(new ItemStack(Items.COAL, 16));
        PodMenu menu = open(helper, player, pod);
        shiftClickFromInventory(menu, player, Items.COAL);
        player.getInventory().add(new ItemStack(Items.COAL, 4));
        shiftClickFromInventory(menu, player, Items.COAL);
        int slotted = menu.fuelSlot().getItem().getCount();
        expect(
                helper,
                slotted <= 16 && slotted + inHold(pod, Items.COAL) == 20 && held(player, Items.COAL) == 0,
                "the fuel slot holds at most the 16 of a stack and the rest goes to the hold: %s slotted, %s held,"
                        + " %s in the hold",
                slotted,
                held(player, Items.COAL),
                inHold(pod, Items.COAL));
        float tank = PodStats.of(pod).tankLitres();
        float full = PodTuning.DEFAULT.shell().fullFuel();
        helper.succeedWhen(() -> {
            float litres = pod.fuel() / full * tank;
            int fed = 16 - menu.fuelSlot().getItem().getCount();
            expect(
                    helper,
                    fed > 0 && Math.abs(litres - fed * 2f) < 0.01f && tank - litres < 2f,
                    "the slot feeds the tank 2 L a coal until it has no room for another: %s coal fed, %s of %s L",
                    fed,
                    litres,
                    tank);
            menu.broadcastChanges();
            expect(
                    helper,
                    menu.fuelTenths() == Math.round(litres * 10f) && menu.tankTenths() == Math.round(tank * 10f),
                    "the gauge reads the tank: %s and %s tenths for %s of %s L",
                    menu.fuelTenths(),
                    menu.tankTenths(),
                    litres,
                    tank);
        });
    }

    @GameTest
    public void anOldOversizedStackDroppedOnTheFuelSlotLeavesTheRestOnTheCursor(GameTestHelper helper) {
        Scene scene = scene(helper);
        try {
            // A coal stack saved at 64 before #1280 keeps its count; the slot still holds a stack of 16.
            PodMenu menu = open(helper, scene.player(), scene.pod());
            scene.pod().setFuel(PodTuning.DEFAULT.shell().fullFuel());
            menu.setCarried(new ItemStack(Items.COAL, 64));
            click(menu, scene.player(), menu.fuelSlot().index, ContainerInput.PICKUP);
            expect(
                    helper,
                    menu.fuelSlot().getItem().getCount() == 16
                            && menu.getCarried().getCount() == 48,
                    "the slot takes 16 and the cursor keeps 48, slot %s, cursor %s",
                    menu.fuelSlot().getItem(),
                    menu.getCarried());
            helper.succeed();
        } finally {
            scene.clean();
        }
    }

    @GameTest
    public void theFuelSlotTakesOnlyPodFuel(GameTestHelper helper) {
        Scene scene = scene(helper);
        try {
            PodMenu menu = open(helper, scene.player(), scene.pod());
            menu.setCarried(new ItemStack(Items.DIRT, 3));
            click(menu, scene.player(), menu.fuelSlot().index, ContainerInput.PICKUP);
            expect(
                    helper,
                    menu.fuelSlot().getItem().isEmpty()
                            && menu.getCarried().is(Items.DIRT)
                            && menu.refusal().isPresent(),
                    "dirt is refused by the fuel slot with a reason, slot %s, refusal %s",
                    menu.fuelSlot().getItem(),
                    menu.refusal());
            helper.succeed();
        } finally {
            scene.clean();
        }
    }

    // ---- hold ----

    @GameTest
    public void theHoldMovesItemsBothWaysLikeAChest(GameTestHelper helper) {
        Scene scene = scene(helper);
        try {
            ServerPlayer player = scene.player();
            PodEntity pod = scene.pod();
            pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.GOLDIUM));
            player.getInventory().add(new ItemStack(Items.DIRT, 32));
            player.getInventory().add(new ItemStack(Items.TORCH, 5));
            PodMenu menu = open(helper, player, pod);

            shiftClickFromInventory(menu, player, Items.DIRT);
            expect(
                    helper,
                    inHold(pod, Items.DIRT) == 32 && held(player, Items.DIRT) == 0 && pod.cargoUsed() == 2,
                    "shift-click puts the dirt in the hold beside the ore: %s in the hold, %s used",
                    inHold(pod, Items.DIRT),
                    pod.cargoUsed());
            click(menu, player, SlotClicks.inventorySlot(menu, Items.TORCH).index, ContainerInput.PICKUP);
            click(menu, player, menu.holdSlot(4).index, ContainerInput.PICKUP);
            expect(
                    helper,
                    pod.cargo().stackAt(4).is(Items.TORCH) && menu.getCarried().isEmpty(),
                    "a dragged stack lands in the slot it is dropped on, slot 4 holds %s",
                    pod.cargo().stackAt(4));

            click(menu, player, menu.holdSlot(0).index, ContainerInput.QUICK_MOVE);
            click(menu, player, menu.holdSlot(0).index, ContainerInput.PICKUP);
            menu.broadcastChanges();
            expect(
                    helper,
                    held(player, OreRegistry.item(OreType.GOLDIUM)) == 0
                            && menu.getCarried().isEmpty()
                            && pod.cargo().entries().size() == 1
                            && menu.refusal().isPresent(),
                    "ore stays in the hold until a terminal takes it, with a reason: %s held, refusal %s",
                    held(player, OreRegistry.item(OreType.GOLDIUM)),
                    menu.refusal());
            int dirtSlot = -1;
            for (int i = 0; i < PodMenu.HOLD_WINDOW; i++) {
                if (menu.holdSlot(i).getItem().is(Items.DIRT)) {
                    dirtSlot = i;
                }
            }
            click(menu, player, menu.holdSlot(dirtSlot).index, ContainerInput.QUICK_MOVE);
            expect(
                    helper,
                    held(player, Items.DIRT) == 32 && inHold(pod, Items.DIRT) == 0 && pod.cargoUsed() == 2,
                    "shift-click takes the dirt back, %s held, %s used",
                    held(player, Items.DIRT),
                    pod.cargoUsed());
            helper.succeed();
        } finally {
            scene.clean();
        }
    }

    @GameTest
    public void stowedFuelWeighsInTheHold(GameTestHelper helper) {
        Scene scene = scene(helper);
        try {
            PodMenu menu = open(helper, scene.player(), scene.pod());
            menu.setCarried(new ItemStack(Items.CHARCOAL, 16));
            click(menu, scene.player(), menu.holdSlot(0).index, ContainerInput.PICKUP);
            float expected = PodCargo.massOf(new ItemStack(Items.CHARCOAL, 16));
            expect(
                    helper,
                    expected > 0f && Math.abs(scene.pod().cargoMass() - expected) < 1e-4f,
                    "16 charcoal in the hold weigh %s, the pod's cargo mass is %s",
                    expected,
                    scene.pod().cargoMass());
            helper.succeed();
        } finally {
            scene.clean();
        }
    }

    @GameTest
    public void theHoldIsAsBigAsTheCargoBay(GameTestHelper helper) {
        Scene scene = scene(helper);
        try {
            ServerPlayer player = scene.player();
            PodEntity pod = scene.pod();
            PodMenu menu = open(helper, player, pod);
            int stock = PodStats.of(pod).cargoSlots();
            menu.broadcastChanges();
            expect(helper, menu.capacity() == stock, "the hold shows %s slots, the bay has %s", menu.capacity(), stock);
            menu.setCarried(new ItemStack(Items.DIRT));
            click(menu, player, menu.holdSlot(stock).index, ContainerInput.PICKUP);
            expect(
                    helper,
                    menu.getCarried().is(Items.DIRT) && menu.refusal().isPresent(),
                    "a slot past the bay's %s takes nothing, refusal %s",
                    stock,
                    menu.refusal());
            for (int i = 0; i < stock; i++) {
                menu.setCarried(new ItemStack(Items.STONE));
                click(menu, player, menu.holdSlot(i).index, ContainerInput.PICKUP);
            }
            menu.setCarried(ItemStack.EMPTY);
            player.getInventory().add(new ItemStack(Items.DIRT));
            shiftClickFromInventory(menu, player, Items.DIRT);
            expect(
                    helper,
                    held(player, Items.DIRT) == 1 && inHold(pod, Items.DIRT) == 0,
                    "a full hold takes nothing more, as a full chest: %s held, %s in the hold",
                    held(player, Items.DIRT),
                    inHold(pod, Items.DIRT));

            player.getInventory().add(new ItemStack(PartUnits.unit(ComponentTrack.CARGO_BAY)));
            shiftClickFromInventory(menu, player, PartUnits.unit(ComponentTrack.CARGO_BAY));
            menu.broadcastChanges();
            int bigger = PodStats.of(pod).cargoSlots();
            shiftClickFromInventory(menu, player, Items.DIRT);
            expect(
                    helper,
                    bigger > stock && menu.capacity() == bigger && inHold(pod, Items.DIRT) == 1,
                    "a cargo bay unit fitted in the screen grows the hold to %s at once, shows %s, %s dirt stowed",
                    bigger,
                    menu.capacity(),
                    inHold(pod, Items.DIRT));
            helper.succeed();
        } finally {
            scene.clean();
        }
    }

    @GameTest
    public void aVersionOneOreBayLoadsIntoTheFirstHoldSlots(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PodEntity pod = helper.spawn(PodRegistry.POD, 2, 2, 2);
        pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.GOLDIUM), 3f);
        pod.cargo().tryAdd(pod, OreRegistry.stack(OreType.IRONIUM), 4f);
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        pod.saveWithoutId(output);
        pod.discard();
        CompoundTag tag = output.buildResult();
        tag.putInt("cargo_version", 1);
        ListTag entries = tag.getListOrEmpty("cargo");
        for (Tag entry : entries) {
            ((CompoundTag) entry).remove("slot");
        }
        PodEntity loaded = (PodEntity) EntityType.create(
                        PodRegistry.POD,
                        TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag),
                        level,
                        EntitySpawnReason.LOAD)
                .orElseThrow(() -> failure(helper, "the version 1 pod did not load"));
        try {
            expect(
                    helper,
                    loaded.cargo().isReadable()
                            && loaded.cargo().stackAt(0).is(OreRegistry.item(OreType.GOLDIUM))
                            && loaded.cargo().stackAt(1).is(OreRegistry.item(OreType.IRONIUM))
                            && loaded.cargoMass() == 7f
                            && loaded.cargoUsed() == 2,
                    "the ore bay should load into hold slots 0 and 1 with its masses, got %s, %s, mass %s",
                    loaded.cargo().stackAt(0),
                    loaded.cargo().stackAt(1),
                    loaded.cargoMass());
            helper.succeed();
        } finally {
            loaded.discard();
        }
    }

    // ---- no dupes ----

    @GameTest
    public void closingWithAStackOnTheCursorGivesItBackAndChangesNothingElse(GameTestHelper helper) {
        Scene scene = scene(helper);
        try {
            ServerPlayer player = scene.player();
            player.getInventory().add(new ItemStack(Items.DIRT, 10));
            player.getInventory().add(new ItemStack(Items.COAL, 8));
            PodMenu menu = open(helper, player, scene.pod());
            scene.pod().setFuel(PodTuning.DEFAULT.shell().fullFuel());
            shiftClickFromInventory(menu, player, Items.COAL);
            click(menu, player, SlotClicks.inventorySlot(menu, Items.DIRT).index, ContainerInput.PICKUP);
            click(menu, player, menu.holdSlot(2).index, ContainerInput.PICKUP);
            click(menu, player, menu.holdSlot(2).index, ContainerInput.PICKUP);
            int carried = menu.getCarried().getCount();
            player.closeContainer();
            expect(
                    helper,
                    player.containerMenu == player.inventoryMenu
                            && held(player, Items.DIRT) == 10
                            && inHold(scene.pod(), Items.DIRT) == 0
                            && carried == 10,
                    "the carried 10 dirt return to the pack and the hold keeps none: %s held, %s in the hold",
                    held(player, Items.DIRT),
                    inHold(scene.pod(), Items.DIRT));
            PodMenu again = open(helper, player, scene.pod());
            expect(
                    helper,
                    again.fuelSlot().getItem().getCount() == 8 && held(player, Items.COAL) == 0,
                    "the fuel slot keeps its 8 coal over a close and a reopen, holds %s",
                    again.fuelSlot().getItem());
            helper.succeed();
        } finally {
            scene.clean();
        }
    }

    @GameTest
    public void twoViewersShareOneHoldAndNothingIsMadeOrLost(GameTestHelper helper) {
        Scene scene = scene(helper);
        MockPlayer second = join(helper);
        try {
            MinecraftServer server = helper.getLevel().getServer();
            expect(
                    helper,
                    Charters.apply(server, second.player().getUUID(), scene.charter())
                                    .isEmpty()
                            && Charters.approve(
                                            server,
                                            scene.player().getUUID(),
                                            second.player().getUUID())
                                    .isEmpty(),
                    "the second player should join the pod's charter");
            standBy(second, scene.pod());
            ServerPlayer pilot = scene.player();
            ServerPlayer crew = second.player();
            pilot.getInventory().add(new ItemStack(Items.DIRT, 10));
            crew.getInventory().add(new ItemStack(Items.STONE, 7));
            pilot.getInventory().add(new ItemStack(Items.COAL, 5));
            scene.pod().setFuel(PodTuning.DEFAULT.shell().fullFuel());
            PodMenu outside = open(helper, pilot, scene.pod());
            // The coal is in the fuel slot before the second screen opens, so a copy per screen would show it twice.
            shiftClickFromInventory(outside, pilot, Items.COAL);
            PodMenu inside = open(helper, crew, scene.pod());

            shiftClickFromInventory(outside, pilot, Items.DIRT);
            inside.broadcastChanges();
            click(inside, crew, inside.holdSlot(0).index, ContainerInput.PICKUP);
            click(outside, pilot, outside.holdSlot(0).index, ContainerInput.PICKUP);
            expect(
                    helper,
                    inside.getCarried().getCount() == 10 && outside.getCarried().isEmpty(),
                    "one viewer takes the dirt and the other finds the slot empty: %s and %s",
                    inside.getCarried(),
                    outside.getCarried());
            click(inside, crew, inside.holdSlot(0).index, ContainerInput.PICKUP);
            click(inside, crew, inside.fuelSlot().index, ContainerInput.QUICK_MOVE);
            click(outside, pilot, outside.fuelSlot().index, ContainerInput.QUICK_MOVE);
            pilot.closeContainer();
            crew.closeContainer();
            int dirt = held(pilot, Items.DIRT) + held(crew, Items.DIRT) + inHold(scene.pod(), Items.DIRT);
            int coal = held(pilot, Items.COAL) + held(crew, Items.COAL);
            expect(
                    helper,
                    dirt == 10 && coal == 5 && held(crew, Items.STONE) == 7,
                    "two viewers made or lost nothing: %s dirt, %s coal, %s stone",
                    dirt,
                    coal,
                    held(crew, Items.STONE));
            helper.succeed();
        } finally {
            second.leave();
            scene.clean();
        }
    }

    @GameTest
    public void aPodRemovedOrUnloadedWhileOpenGivesNothingAndTakesNothing(GameTestHelper helper) {
        List<Entity.RemovalReason> reasons =
                List.of(Entity.RemovalReason.DISCARDED, Entity.RemovalReason.UNLOADED_TO_CHUNK);
        List<String> problems = new ArrayList<>();
        for (Entity.RemovalReason reason : reasons) {
            Scene scene = scene(helper);
            try {
                ServerPlayer player = scene.player();
                PodEntity pod = scene.pod();
                pod.setFuel(PodTuning.DEFAULT.shell().fullFuel());
                player.getInventory().add(new ItemStack(Items.DIRT, 4));
                player.getInventory().add(new ItemStack(Items.COAL, 3));
                PodMenu menu = open(helper, player, pod);
                shiftClickFromInventory(menu, player, Items.DIRT);
                shiftClickFromInventory(menu, player, Items.COAL);
                player.getInventory().add(new ItemStack(Items.STONE, 2));
                pod.remove(reason);
                click(menu, player, menu.holdSlot(0).index, ContainerInput.PICKUP);
                click(menu, player, menu.fuelSlot().index, ContainerInput.QUICK_MOVE);
                ItemStack fromHold = menu.getCarried().copy();
                menu.setCarried(ItemStack.EMPTY);
                shiftClickFromInventory(menu, player, Items.STONE);
                if (!fromHold.isEmpty()
                        || held(player, Items.DIRT) != 0
                        || held(player, Items.COAL) != 0
                        || held(player, Items.STONE) != 2
                        || menu.stillValid(player)) {
                    problems.add(reason + ": carried " + fromHold + ", held " + held(player, Items.DIRT) + " dirt "
                            + held(player, Items.COAL) + " coal " + held(player, Items.STONE) + " stone, valid "
                            + menu.stillValid(player));
                }
            } finally {
                scene.clean();
            }
        }
        expect(helper, problems.isEmpty(), "a gone pod must give and take nothing: %s", problems);
        helper.succeed();
    }

    // ---- closing ----

    @GameTest
    public void walkingAwayClosesTheScreen(GameTestHelper helper) {
        Scene scene = scene(helper);
        PodMenu menu = open(helper, scene.player(), scene.pod());
        expect(helper, menu.stillValid(scene.player()), "the screen is valid beside the pod");
        scene.mock().teleportTo(helper.getLevel(), scene.pod().position().add(20, 0, 0), 0f, 0f);
        expect(helper, !menu.stillValid(scene.player()), "out of reach, the screen is no longer valid");
        helper.succeedWhen(() -> {
            expect(
                    helper,
                    scene.player().containerMenu == scene.player().inventoryMenu,
                    "the player's next tick closes it, open %s",
                    scene.player().containerMenu);
        });
    }

    @GameTest
    public void leavingTheSeatClosesAScreenOpenedFromIt(GameTestHelper helper) {
        Scene scene = scene(helper);
        expect(helper, scene.player().startRiding(scene.pod(), true, false), "the crew should board");
        PodMenu menu = open(helper, scene.player(), scene.pod());
        expect(helper, menu.stillValid(scene.player()), "the screen is valid in the seat");
        scene.player().stopRiding();
        standBy(scene.mock(), scene.pod());
        expect(helper, !menu.stillValid(scene.player()), "out of the seat, a screen opened from it is not valid");
        helper.succeedWhen(() -> {
            expect(
                    helper,
                    scene.player().containerMenu == scene.player().inventoryMenu,
                    "the player's next tick closes it, open %s",
                    scene.player().containerMenu);
        });
    }

    @GameTest
    public void aRemovedPodClosesTheScreen(GameTestHelper helper) {
        Scene scene = scene(helper);
        PodMenu menu = open(helper, scene.player(), scene.pod());
        scene.pod().discard();
        expect(helper, !menu.stillValid(scene.player()), "with the pod gone, the screen is not valid");
        helper.succeedWhen(() -> {
            expect(
                    helper,
                    scene.player().containerMenu == scene.player().inventoryMenu,
                    "the player's next tick closes it, open %s",
                    scene.player().containerMenu);
        });
    }

    @GameTest
    public void everyTrackHasALockedSlotWithAGhost(GameTestHelper helper) {
        Scene scene = scene(helper);
        try {
            PodMenu menu = open(helper, scene.player(), scene.pod());
            List<ComponentTrack> wrong = new ArrayList<>();
            for (ComponentTrack track : ComponentTrack.values()) {
                SlottedMenu.RuleSlot slot = menu.partSlot(track);
                if (!slot.rule().locks()
                        || slot.rule().ghost().isEmpty()
                        || !slot.rule().fits().test(new ItemStack(PartUnits.unit(track)))) {
                    wrong.add(track);
                }
            }
            expect(
                    helper,
                    wrong.isEmpty(),
                    "these tracks' slots do not lock, show a ghost and take the unit: %s",
                    wrong);
            helper.succeed();
        } finally {
            scene.clean();
        }
    }
}
