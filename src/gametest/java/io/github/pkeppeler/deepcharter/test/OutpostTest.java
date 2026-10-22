package io.github.pkeppeler.deepcharter.test;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import io.github.pkeppeler.deepcharter.charter.CharterData;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.colony.ColonyKit;
import io.github.pkeppeler.deepcharter.fuel.FuelPump;
import io.github.pkeppeler.deepcharter.layer.BreachService;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.market.OreProcessor;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.outpost.OutpostAccess;
import io.github.pkeppeler.deepcharter.outpost.OutpostBuilder;
import io.github.pkeppeler.deepcharter.outpost.OutpostData;
import io.github.pkeppeler.deepcharter.outpost.OutpostRegistry;
import io.github.pkeppeler.deepcharter.outpost.OutpostTuning;
import io.github.pkeppeler.deepcharter.pod.PodComponents;
import io.github.pkeppeler.deepcharter.pod.PodEntity;
import io.github.pkeppeler.deepcharter.pod.PodRegistry;
import io.github.pkeppeler.deepcharter.pod.PodStats;
import io.github.pkeppeler.deepcharter.pod.PodTuning;
import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalAction;
import io.github.pkeppeler.deepcharter.terminal.TerminalParts;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import io.github.pkeppeler.deepcharter.test.support.UnreadableChecks;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Server GameTests for #320: a charter builds an outpost at the breach floor of layer 1 with a kit. The hall has the colony's own fuel
 * pump and ore processor, so a pod parked in it fills its tank and its ore sells, at the outpost's prices (dearer fuel, a smaller share of the
 * ore's value), and the crust of the breach is left in the middle of the floor for the next drop.
 */
public class OutpostTest {
    private static final int Z = 7200;
    private static final int MAX_TICKS = FarChunks.AWAIT_BUDGET_TICKS + 200;
    /** The hall's offsets from the clicked floor block: the pump and the processor stand against the north wall. */
    private static final BlockPos PUMP = new BlockPos(-2, 1, -4);

    private static final BlockPos PROCESSOR = new BlockPos(2, 1, -4);
    private static final String BLOCKED = "message.deepcharter.outpost.blocked";
    private static final String HAS_ONE = "message.deepcharter.outpost.has_one";
    private static final String NOT_FLOOR = "message.deepcharter.outpost.not_floor";
    private static final String CLOSED = "message.deepcharter.outpost.closed";
    private static final String NOT_YOURS = "message.deepcharter.outpost.not_yours";
    private static final String OCCUPIED = "message.deepcharter.outpost.occupied";
    private static final String OVERLAPS = "message.deepcharter.outpost.overlaps";
    private static final String NOT_HERE = "message.deepcharter.outpost.not_here";
    private static final String NOT_ON_TOP = "message.deepcharter.outpost.not_on_top";

    /** The clicked floor block of a stage in layer 1, a player of a charter standing on it, and the charter. */
    private record Stage(ServerLevel level, BlockPos floor, MockPlayer mock, CharterId charter) {
        ServerPlayer player() {
            return mock.player();
        }
    }

    // assertionException(String, Object...) leaves the placeholders unfilled in the report.
    @FormatMethod
    private static RuntimeException failure(
            GameTestHelper helper, @FormatString String format, @Nullable Object... args) {
        return helper.assertionException(Component.literal(String.format(format, args)));
    }

    private static ServerLevel layerOne(GameTestHelper helper) {
        return TestLayers.level(helper, 1);
    }

    /** The floor of layer 1: the top block of the breach crust, in a column of solid stone with a 2 by 2 shaft up from it. */
    private static BlockPos stageFloor(GameTestHelper helper, ServerLevel level, int x) {
        BlockPos floor = new BlockPos(x, level.getMinY() + LayerTuning.DEFAULT.crustThickness() - 1, Z);
        RoomCarver.carve(level, x - 8, x + 8, floor.getY() + 1, floor.getY() + 16, Z - 8, Z + 8, Blocks.STONE);
        RoomCarver.carve(
                level,
                new BlockPos(x, floor.getY() + 1, Z),
                new BlockPos(x + 1, floor.getY() + 16, Z + 1),
                Blocks.AIR.defaultBlockState());
        if (!level.getBlockState(floor).is(LayerBlocks.BREACH_CRUST)) {
            throw failure(
                    helper,
                    "the floor block %s of layer 1 should be breach crust, was %s",
                    floor,
                    level.getBlockState(floor));
        }
        return floor;
    }

    private static Stage stage(GameTestHelper helper, int x, boolean onCharter) {
        ServerLevel level = layerOne(helper);
        BlockPos floor = stageFloor(helper, level, x);
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer mock = MockPlayers.join(helper, "outposter-" + x);
        mock.player().setGameMode(GameType.SURVIVAL);
        CharterId charter = null;
        if (onCharter) {
            if (Charters.found(
                            server,
                            mock.player().getUUID(),
                            "Outpost Test " + UUID.randomUUID().toString().substring(0, 8))
                    .isPresent()) {
                throw failure(helper, "founding the charter should succeed");
            }
            charter = Charters.charterOfOrThrow(server, mock.player().getUUID())
                    .orElseThrow()
                    .id();
        }
        mock.teleportTo(level, Vec3.atBottomCenterOf(floor.above()), 0f, 0f);
        return new Stage(level, floor, mock, charter == null ? new CharterId(new UUID(0, 0)) : charter);
    }

    /** Runs {@code body} on the first tick the stage is entity-ticking, with the pump and the processor repaired and no outposts built. */
    private static void whenReady(GameTestHelper helper, Stage stage, Runnable body) {
        MinecraftServer server = helper.getLevel().getServer();
        RepairState repaired = new RepairState();
        TerminalTypes.FUEL_PUMP.parts().forEach(part -> repaired.insert(TerminalTypes.FUEL_PUMP, part));
        TerminalTypes.ORE_PROCESSOR.parts().forEach(part -> repaired.insert(TerminalTypes.ORE_PROCESSOR, part));
        FarChunks.awaitEntityTicking(
                helper,
                stage.level(),
                stage.floor(),
                () -> WorldData.swap(server)
                        .with(RepairState.TYPE, repaired)
                        .with(OutpostData.TYPE, new OutpostData())
                        .run(() -> {
                            body.run();
                            helper.succeed();
                        }));
    }

    private static InteractionResult useKit(Stage stage, BlockPos clicked, Direction face) {
        ServerPlayer player = stage.player();
        ItemStack kit = new ItemStack(OutpostRegistry.KIT);
        player.setItemInHand(InteractionHand.MAIN_HAND, kit);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(clicked), face, clicked, false);
        return kit.useOn(new UseOnContext(player, InteractionHand.MAIN_HAND, hit));
    }

    private static void expectBlock(GameTestHelper helper, Stage stage, BlockPos offset, Block expected, String what) {
        BlockPos at = stage.floor().offset(offset);
        if (!stage.level().getBlockState(at).is(expected)) {
            throw failure(
                    helper,
                    "%s: %s should be %s, was %s",
                    what,
                    at,
                    expected,
                    stage.level().getBlockState(at).getBlock());
        }
    }

    /** The kit is refused for {@code key}: the builder says so, a use of the kit does nothing, and the kit stays in the hand. */
    private static void expectRefusal(
            GameTestHelper helper, Stage stage, BlockPos clicked, Direction face, String key, String what) {
        Optional<Component> refusal = OutpostBuilder.build(stage.player(), stage.level(), clicked, face);
        String said = refusal.map(message -> message.getContents() instanceof TranslatableContents translatable
                        ? translatable.getKey()
                        : message.getString())
                .orElse("nothing: it built");
        if (!said.equals(key)) {
            throw failure(helper, "%s: the builder should refuse with %s, it said %s", what, key, said);
        }
        InteractionResult result = useKit(stage, clicked, face);
        if (result.consumesAction()) {
            throw failure(helper, "%s: the use of the kit should fail, it gave %s", what, result);
        }
        if (stage.player().getItemInHand(InteractionHand.MAIN_HAND).isEmpty()) {
            throw failure(helper, "%s: a refused kit must stay in the hand", what);
        }
    }

    private static long balance(GameTestHelper helper, Stage stage) {
        return Charters.findOrThrow(helper.getLevel().getServer(), stage.charter())
                .orElseThrow()
                .account();
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void theKitUnfoldsAnOutpostWithAPumpAndAProcessorAndLeavesTheCrustToDrillThrough(GameTestHelper helper) {
        Stage stage = stage(helper, 8000, true);
        whenReady(helper, stage, () -> {
            InteractionResult result = useKit(stage, stage.floor(), Direction.UP);
            if (!result.consumesAction()) {
                throw failure(
                        helper,
                        "the kit should build, the use gave %s and the bar says %s",
                        result,
                        stage.mock().actionBarMessages());
            }
            if (!stage.player().getItemInHand(InteractionHand.MAIN_HAND).isEmpty()) {
                throw failure(helper, "building should use the kit up");
            }
            expectBlock(helper, stage, PUMP, TerminalTypes.FUEL_PUMP.block(), "the fuel pump");
            expectBlock(helper, stage, PROCESSOR, TerminalTypes.ORE_PROCESSOR.block(), "the sell terminal");
            expectBlock(
                    helper,
                    stage,
                    new BlockPos(0, 0, 0),
                    LayerBlocks.BREACH_CRUST,
                    "the breach crust in the middle of the floor");
            expectBlock(
                    helper, stage, new BlockPos(2, 0, 0), ColonyKit.HAZARD_BAND, "the hazard ring round the breach");
            expectBlock(helper, stage, new BlockPos(3, 0, 3), ColonyKit.CONCRETE_FOOTING, "the footing of the floor");
            expectBlock(helper, stage, new BlockPos(5, 3, 0), ColonyKit.RIVETED_PLATE, "the east wall");
            expectBlock(helper, stage, new BlockPos(0, 3, -5), ColonyKit.RIVETED_PLATE, "the north wall");
            expectBlock(helper, stage, new BlockPos(3, 6, 3), ColonyKit.RIVETED_PLATE, "the ceiling");
            expectBlock(helper, stage, new BlockPos(0, 6, 0), Blocks.AIR, "the shaft, still open through the ceiling");
            expectBlock(helper, stage, new BlockPos(0, 3, 0), Blocks.AIR, "the hall");
            expectBlock(helper, stage, new BlockPos(3, 3, 3), Blocks.AIR, "the hall's corner");
            expectBlock(helper, stage, new BlockPos(-2, 3, -4), ColonyKit.WALL_LAMP, "the lamp over the pump");
            expectBlock(helper, stage, new BlockPos(-4, 1, -4), ColonyKit.STEEL_BEAM, "the corner post");
            OutpostData data = OutpostData.get(helper.getLevel().getServer());
            if (data.of(stage.charter(), 1)
                    .filter(outpost -> outpost.centre().equals(stage.floor()))
                    .isEmpty()) {
                throw failure(
                        helper,
                        "the charter's outpost in layer 1 should be recorded at %s, the data holds %s",
                        stage.floor(),
                        data.of(stage.charter(), 1));
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aPodInTheHallFillsAtTheOutpostPriceAndItsOreSellsAtTheOutpostRate(GameTestHelper helper) {
        Stage stage = stage(helper, 8100, true);
        whenReady(helper, stage, () -> {
            useKit(stage, stage.floor(), Direction.UP);
            MinecraftServer server = helper.getLevel().getServer();
            PodEntity pod = TestEntities.create(PodRegistry.POD, stage.level());
            pod.setPos(Vec3.atBottomCenterOf(stage.floor().offset(1, 1, 2)));
            stage.level().addFreshEntity(pod);
            try {
                PodComponents.register(pod, stage.charter());
                pod.setFuel(0f);
                Charters.deposit(server, stage.charter(), 1000);
                stage.mock()
                        .teleportTo(
                                stage.level(),
                                Vec3.atBottomCenterOf(stage.floor().offset(-1, 1, -2)),
                                0f,
                                0f);
                long tank = Math.round(PodStats.of(pod).tankLitres());
                var filled =
                        Terminals.act(stage.player(), stage.floor().offset(PUMP), FuelPump.FILL, new CompoundTag());
                if (filled.isPresent()) {
                    throw failure(helper, "the outpost pump should fill the pod, it refused with %s", filled.get());
                }
                float full = PodTuning.DEFAULT.shell().fullFuel();
                if (pod.fuel() < full - 0.01f) {
                    throw failure(helper, "the pod should leave with a full tank, it has %s of %s", pod.fuel(), full);
                }
                long paid = 1000 - balance(helper, stage);
                long expected = tank * 2;
                if (paid != expected) {
                    throw failure(
                            helper,
                            "a full %d L tank should cost $%d at $2 a litre, it cost $%d",
                            tank,
                            expected,
                            paid);
                }
                long before = balance(helper, stage);
                for (int i = 0; i < 3; i++) {
                    stage.player().getInventory().add(OreRegistry.stack(OreType.IRONIUM));
                }
                var sold = Terminals.act(
                        stage.player(),
                        stage.floor().offset(PROCESSOR),
                        OreProcessor.SELL_INVENTORY,
                        new CompoundTag());
                if (sold.isPresent()) {
                    throw failure(helper, "the outpost terminal should buy the ore, it refused with %s", sold.get());
                }
                long earned = balance(helper, stage) - before;
                if (earned != 3 * 24) {
                    throw failure(
                            helper, "3 Ironium at $30 should sell for $72 at 80 percent, they sold for $%d", earned);
                }
            } finally {
                pod.discard();
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void theKitIsRefusedWhereAnOutpostCannotStand(GameTestHelper helper) {
        Stage stage = stage(helper, 8200, true);
        whenReady(helper, stage, () -> {
            expectRefusal(helper, stage, stage.floor(), Direction.NORTH, NOT_ON_TOP, "a click on a side face");
            BlockPos high = stage.floor().above(OutpostTuning.DEFAULT.floorBand() + 1);
            expectRefusal(helper, stage, high, Direction.UP, NOT_FLOOR, "a click above the floor band");
            BlockPos blocker = stage.floor().offset(3, 2, 3);
            stage.level().setBlock(blocker, TerminalTypes.FUEL_PUMP.block().defaultBlockState(), Block.UPDATE_CLIENTS);
            expectRefusal(helper, stage, stage.floor(), Direction.UP, BLOCKED, "a terminal in the hall");
            if (!stage.level().getBlockState(blocker).is(TerminalTypes.FUEL_PUMP.block())) {
                throw failure(helper, "a refused kit must leave the blocker standing");
            }
            if (OutpostData.get(helper.getLevel().getServer())
                    .of(stage.charter(), 1)
                    .isPresent()) {
                throw failure(helper, "a refused kit records no outpost");
            }
        });
    }

    /** A second charter's player, on a charter of their own, standing in the hall of {@code stage}. */
    private static Stage stranger(GameTestHelper helper, Stage stage) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer mock = MockPlayers.join(helper, "stranger-" + stage.floor().getX());
        mock.player().setGameMode(GameType.SURVIVAL);
        if (Charters.found(
                        server,
                        mock.player().getUUID(),
                        "Stranger Test " + UUID.randomUUID().toString().substring(0, 8))
                .isPresent()) {
            throw failure(helper, "founding the stranger's charter should succeed");
        }
        CharterId charter = Charters.charterOfOrThrow(server, mock.player().getUUID())
                .orElseThrow()
                .id();
        mock.teleportTo(stage.level(), Vec3.atBottomCenterOf(stage.floor().offset(-1, 1, -2)), 0f, 0f);
        return new Stage(stage.level(), stage.floor(), mock, charter);
    }

    /** The key of the translatable {@code refusal}, or its text. */
    private static String keyOf(Optional<Component> refusal) {
        return refusal.map(message -> message.getContents() instanceof TranslatableContents translatable
                        ? translatable.getKey()
                        : message.getString())
                .orElse("nothing: it was served");
    }

    private static TerminalAction.Context contextAt(Stage stage, BlockPos pos) {
        MinecraftServer server = stage.level().getServer();
        return new TerminalAction.Context(
                server,
                stage.player(),
                Charters.readableCharterOf(server, stage.player().getUUID()),
                TerminalTypes.FUEL_PUMP,
                pos,
                new CompoundTag());
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aSecondChartersPodIsRefusedFuelAndSalesAtAnOutpostAndTheOwnerIsServed(GameTestHelper helper) {
        Stage owner = stage(helper, 8700, true);
        whenReady(helper, owner, () -> {
            useKit(owner, owner.floor(), Direction.UP);
            MinecraftServer server = helper.getLevel().getServer();
            Stage other = stranger(helper, owner);
            PodEntity pod = TestEntities.create(PodRegistry.POD, owner.level());
            pod.setPos(Vec3.atBottomCenterOf(owner.floor().offset(1, 1, 2)));
            owner.level().addFreshEntity(pod);
            try {
                PodComponents.register(pod, other.charter());
                pod.setFuel(0f);
                Charters.deposit(server, other.charter(), 1000);
                for (int i = 0; i < 3; i++) {
                    other.player().getInventory().add(OreRegistry.stack(OreType.IRONIUM));
                }
                BlockPos pump = owner.floor().offset(PUMP);
                BlockPos processor = owner.floor().offset(PROCESSOR);
                if (Terminals.act(other.player(), pump, FuelPump.FILL, new CompoundTag())
                        .isEmpty()) {
                    throw failure(helper, "a second charter's pod should be refused fuel at the outpost");
                }
                if (Terminals.act(other.player(), processor, OreProcessor.SELL_INVENTORY, new CompoundTag())
                        .isEmpty()) {
                    throw failure(helper, "a second charter's ore should be refused a sale at the outpost");
                }
                if (pod.fuel() != 0f) {
                    throw failure(helper, "a refused fill must leave the tank empty, it has %s", pod.fuel());
                }
                if (balance(helper, other) != 1000) {
                    throw failure(
                            helper, "a refusal must leave the balance at $1000, it is $%d", balance(helper, other));
                }
                if (other.player()
                                .getInventory()
                                .countItem(OreRegistry.stack(OreType.IRONIUM).getItem())
                        != 3) {
                    throw failure(helper, "a refused sale must keep the ore");
                }
                String said = keyOf(OutpostAccess.refusal(contextAt(other, pump)));
                if (!said.equals(CLOSED)) {
                    throw failure(helper, "the refusal at a closed outpost should be %s, it was %s", CLOSED, said);
                }
                // The owner's own pod is served at the same pump.
                PodEntity ownPod = TestEntities.create(PodRegistry.POD, owner.level());
                ownPod.setPos(Vec3.atBottomCenterOf(owner.floor().offset(2, 1, 2)));
                owner.level().addFreshEntity(ownPod);
                try {
                    PodComponents.register(ownPod, owner.charter());
                    ownPod.setFuel(0f);
                    pod.discard();
                    Charters.deposit(server, owner.charter(), 1000);
                    owner.mock()
                            .teleportTo(
                                    owner.level(),
                                    Vec3.atBottomCenterOf(owner.floor().offset(-1, 1, -2)),
                                    0f,
                                    0f);
                    var served = Terminals.act(owner.player(), pump, FuelPump.FILL, new CompoundTag());
                    if (served.isPresent() || ownPod.fuel() <= 0f) {
                        throw failure(helper, "the owner should be served at their own pump, got %s", served);
                    }
                } finally {
                    ownPod.discard();
                }
            } finally {
                pod.discard();
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aGuestIsServedAtTheOutpostPriceOnceTheOwnerOpensItAndRefusedAgainWhenItCloses(GameTestHelper helper) {
        Stage owner = stage(helper, 9100, true);
        whenReady(helper, owner, () -> {
            useKit(owner, owner.floor(), Direction.UP);
            MinecraftServer server = helper.getLevel().getServer();
            Stage guest = stranger(helper, owner);
            PodEntity pod = TestEntities.create(PodRegistry.POD, owner.level());
            pod.setPos(Vec3.atBottomCenterOf(owner.floor().offset(1, 1, 2)));
            owner.level().addFreshEntity(pod);
            try {
                PodComponents.register(pod, guest.charter());
                pod.setFuel(0f);
                Charters.deposit(server, guest.charter(), 1000);
                Charters.deposit(server, owner.charter(), 1000);
                BlockPos pump = owner.floor().offset(PUMP);
                if (Charters.findOrThrow(server, owner.charter()).orElseThrow().outpostsShared()) {
                    throw failure(helper, "a charter's outposts are closed to others by default");
                }
                if (Terminals.act(guest.player(), pump, FuelPump.FILL, new CompoundTag())
                        .isEmpty()) {
                    throw failure(helper, "a guest should be refused at an outpost that is not shared");
                }
                // The guest's own Director opens the guest's outposts, never the owner's.
                if (Charters.setOutpostsShared(server, guest.player().getUUID(), true)
                        .isPresent()) {
                    throw failure(helper, "the guest's Director should open the guest's own outposts");
                }
                if (Charters.findOrThrow(server, owner.charter()).orElseThrow().outpostsShared()) {
                    throw failure(helper, "a guest's flip must not open the owner's outposts");
                }
                if (Terminals.act(guest.player(), pump, FuelPump.FILL, new CompoundTag())
                        .isEmpty()) {
                    throw failure(helper, "opening the guest's own outposts does not open the owner's");
                }
                if (Charters.setOutpostsShared(server, owner.player().getUUID(), true)
                        .isPresent()) {
                    throw failure(helper, "the owner's Director should open the outposts");
                }
                long tank = Math.round(PodStats.of(pod).tankLitres());
                var served = Terminals.act(guest.player(), pump, FuelPump.FILL, new CompoundTag());
                if (served.isPresent() || pod.fuel() <= 0f) {
                    throw failure(helper, "a guest should be served at a shared outpost, got %s", served);
                }
                long paid = 1000 - balance(helper, guest);
                if (paid != tank * 2) {
                    throw failure(helper, "a guest pays the outpost's $2 a litre ($%d), it paid $%d", tank * 2, paid);
                }
                if (balance(helper, owner) != 1000) {
                    throw failure(helper, "the owner takes no cut: the balance is $%d", balance(helper, owner));
                }
                for (int i = 0; i < 3; i++) {
                    guest.player().getInventory().add(OreRegistry.stack(OreType.IRONIUM));
                }
                long before = balance(helper, guest);
                var sold = Terminals.act(
                        guest.player(),
                        owner.floor().offset(PROCESSOR),
                        OreProcessor.SELL_INVENTORY,
                        new CompoundTag());
                if (sold.isPresent() || balance(helper, guest) - before != 3 * 24) {
                    throw failure(helper, "a guest sells at the outpost rate, got %s", sold);
                }
                Charters.setOutpostsShared(server, owner.player().getUUID(), false);
                pod.setFuel(0f);
                if (Terminals.act(guest.player(), pump, FuelPump.FILL, new CompoundTag())
                        .isEmpty()) {
                    throw failure(helper, "a guest is refused again once the owner closes the outposts");
                }
            } finally {
                pod.discard();
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aSharedHallStillRefusesAGuestWithNoCharterAndTheUnreadableCharterDataNeverThrows(
            GameTestHelper helper) {
        Stage owner = stage(helper, 9200, true);
        whenReady(helper, owner, () -> {
            useKit(owner, owner.floor(), Direction.UP);
            MinecraftServer server = helper.getLevel().getServer();
            Charters.setOutpostsShared(server, owner.player().getUUID(), true);
            Stage guest = stranger(helper, owner);
            BlockPos pump = owner.floor().offset(PUMP);
            TerminalAction.Context guestContext = contextAt(guest, pump);
            TerminalAction.Context loner = new TerminalAction.Context(
                    server, guest.player(), Optional.empty(), TerminalTypes.FUEL_PUMP, pump, new CompoundTag());
            if (!keyOf(OutpostAccess.refusal(loner)).equals(CLOSED)) {
                throw failure(helper, "a player on no charter is refused at a shared outpost too");
            }
            if (OutpostAccess.refusal(guestContext).isPresent()) {
                throw failure(helper, "a guest should be served at a shared outpost");
            }
            UnreadableChecks.assertSavedDataNoThrow(
                    helper, "outpost sharing", server, CharterData.TYPE, Map.of("OutpostAccess.refusal", () -> {
                        if (!keyOf(OutpostAccess.refusal(guestContext)).equals(CLOSED)) {
                            throw failure(helper, "unreadable charters leave a guest refused");
                        }
                    }));
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aDormantOwnersOutpostIsClosedToGuestsAndReopensAsItWasLeftWhenTheCharterIsRevived(
            GameTestHelper helper) {
        Stage owner = stage(helper, 9300, true);
        whenReady(helper, owner, () -> {
            useKit(owner, owner.floor(), Direction.UP);
            MinecraftServer server = helper.getLevel().getServer();
            Charters.setOutpostsShared(server, owner.player().getUUID(), true);
            Stage guest = stranger(helper, owner);
            TerminalAction.Context guestContext = contextAt(guest, owner.floor().offset(PUMP));
            if (OutpostAccess.refusal(guestContext).isPresent()) {
                throw failure(helper, "a guest should be served while the owner is shared and has a Director");
            }
            if (Charters.leave(server, owner.player().getUUID()).isPresent()) {
                throw failure(helper, "the owner's only member should be able to leave");
            }
            if (!Charters.findOrThrow(server, owner.charter()).orElseThrow().dormant()) {
                throw failure(helper, "the owner's charter should be dormant");
            }
            if (!keyOf(OutpostAccess.refusal(guestContext)).equals(CLOSED)) {
                throw failure(helper, "a dormant owner's outposts are closed to guests, whatever the flag says");
            }
            if (Charters.revive(server, owner.player().getUUID(), owner.charter())
                    .isPresent()) {
                throw failure(helper, "the former member should revive the charter");
            }
            if (OutpostAccess.refusal(guestContext).isPresent()) {
                throw failure(helper, "a revived charter is open again, as it was left");
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aPumpAtDepthWithNoOutpostServesNoOne(GameTestHelper helper) {
        Stage stage = stage(helper, 8800, true);
        whenReady(helper, stage, () -> {
            BlockPos pump = stage.floor().offset(3, 1, 3);
            stage.level().setBlock(pump, TerminalTypes.FUEL_PUMP.block().defaultBlockState(), Block.UPDATE_CLIENTS);
            Charters.deposit(helper.getLevel().getServer(), stage.charter(), 1000);
            String said = keyOf(OutpostAccess.refusal(contextAt(stage, pump)));
            if (!said.equals(NOT_YOURS)) {
                throw failure(helper, "an unowned pump at depth should refuse with %s, it said %s", NOT_YOURS, said);
            }
            if (Terminals.act(stage.player(), pump, FuelPump.FILL, new CompoundTag())
                    .isEmpty()) {
                throw failure(helper, "an unowned pump at depth should refuse the fill");
            }
            if (balance(helper, stage) != 1000) {
                throw failure(helper, "a refusal must leave the balance alone");
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void theKitIsRefusedAndKeptWhereAnotherEntityStandsInTheHall(GameTestHelper helper) {
        Stage stage = stage(helper, 8900, true);
        whenReady(helper, stage, () -> {
            PodEntity pod = TestEntities.create(PodRegistry.POD, stage.level());
            pod.setPos(Vec3.atBottomCenterOf(stage.floor().offset(3, 2, 3)));
            stage.level().addFreshEntity(pod);
            try {
                expectRefusal(helper, stage, stage.floor(), Direction.UP, OCCUPIED, "a pod in the hall");
            } finally {
                pod.discard();
            }
            if (!stage.level().getBlockState(stage.floor().offset(3, 2, 3)).is(Blocks.STONE)) {
                throw failure(helper, "a refused kit must cut nothing");
            }
            if (OutpostData.get(helper.getLevel().getServer())
                    .of(stage.charter(), 1)
                    .isPresent()) {
                throw failure(helper, "a refused kit records no outpost");
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void theKitIsRefusedAndKeptWhereAnotherChartersHallStands(GameTestHelper helper) {
        Stage first = stage(helper, 9000, true);
        Stage second = stage(helper, 9010, true);
        whenReady(helper, first, () -> {
            if (!useKit(first, first.floor(), Direction.UP).consumesAction()) {
                throw failure(helper, "the first kit should build");
            }
            expectRefusal(helper, second, second.floor(), Direction.UP, OVERLAPS, "a hall 10 blocks from another");
            if (OutpostData.get(helper.getLevel().getServer())
                    .of(second.charter(), 1)
                    .isPresent()) {
                throw failure(helper, "a refused kit records no outpost");
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aCharterHasOneOutpostInALayer(GameTestHelper helper) {
        Stage stage = stage(helper, 8300, true);
        BlockPos second = stageFloor(helper, stage.level(), 8340);
        whenReady(helper, stage, () -> {
            if (!useKit(stage, stage.floor(), Direction.UP).consumesAction()) {
                throw failure(helper, "the first kit should build");
            }
            expectRefusal(helper, stage, second, Direction.UP, HAS_ONE, "a second outpost of the charter in layer 1");
            if (OutpostData.get(helper.getLevel().getServer())
                    .of(stage.charter(), 1)
                    .filter(outpost -> outpost.centre().equals(stage.floor()))
                    .isEmpty()) {
                throw failure(helper, "the second kit must record nothing");
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void aPlayerOnNoCharterCannotBuildOne(GameTestHelper helper) {
        Stage stage = stage(helper, 8400, false);
        whenReady(helper, stage, () -> {
            expectRefusal(
                    helper,
                    stage,
                    stage.floor(),
                    Direction.UP,
                    CharterRefusal.NOT_ON_A_CHARTER.translationKey(),
                    "a player on no charter");
            if (OutpostData.get(helper.getLevel().getServer())
                    .of(stage.charter(), 1)
                    .isPresent()) {
                throw failure(helper, "a refused kit records no outpost");
            }
        });
    }

    @GameTest(maxTicks = MAX_TICKS)
    public void theKitDoesNothingOnTheSurface(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer mock = MockPlayers.join(helper, "surface-outposter");
        mock.player().setGameMode(GameType.SURVIVAL);
        if (Charters.found(
                        server,
                        mock.player().getUUID(),
                        "Surface Test " + UUID.randomUUID().toString().substring(0, 8))
                .isPresent()) {
            throw failure(helper, "founding the charter should succeed");
        }
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        BlockPos floor = helper.absolutePos(new BlockPos(1, 1, 1));
        Stage stage = new Stage(
                helper.getLevel(),
                floor,
                mock,
                Charters.charterOfOrThrow(server, mock.player().getUUID())
                        .orElseThrow()
                        .id());
        WorldData.with(server, OutpostData.TYPE, new OutpostData(), () -> {
            expectRefusal(helper, stage, floor, Direction.UP, NOT_HERE, "a kit used on the surface");
            if (OutpostData.get(server).of(stage.charter(), 1).isPresent()) {
                throw failure(helper, "a refused kit records no outpost");
            }
        });
        helper.succeed();
    }

    @GameTest
    public void outpostDataOfAnotherVersionIsKeptAndNeverThrowsOnAGameplayPath(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        if (OutpostData.VERSION != 1) {
            throw failure(
                    helper,
                    "the saved outposts are version 1, not %d, until a change carries a migration test",
                    OutpostData.VERSION);
        }
        UnreadableChecks.assertSavedDataNoThrow(
                helper,
                "outpost",
                server,
                OutpostData.TYPE,
                Map.of("isReadable", () -> OutpostData.get(server).isReadable()));
        helper.succeed();
    }

    @GameTest
    public void theOutpostsPricesAreWorseThanTheColonys(GameTestHelper helper) {
        OutpostTuning tuning = OutpostTuning.DEFAULT;
        if (tuning.sellPercent() != 80 || tuning.fuelPriceFactor() != 2 || tuning.floorBand() != 8) {
            throw failure(
                    helper, "the outpost's terms are 80 percent, twice the fuel and 8 blocks of floor, not %s", tuning);
        }
        helper.succeed();
    }

    /** The kit's recipe, pinned: four Bronzium and two Ironium round a pump motor, redstone and an ore feeder. */
    @GameTest
    public void theKitIsCraftedFromFourBronziumTwoIroniumAPumpMotorAnOreFeederAndRedstone(GameTestHelper helper) {
        ItemStack bronzium = OreRegistry.stack(OreType.BRONZIUM);
        ItemStack ironium = OreRegistry.stack(OreType.IRONIUM);
        ItemStack motor = new ItemStack(TerminalParts.PUMP_MOTOR);
        ItemStack feeder = new ItemStack(TerminalParts.ORE_FEEDER);
        ItemStack redstone = new ItemStack(Items.REDSTONE);
        List<ItemStack> grid =
                List.of(bronzium, ironium, bronzium, motor, redstone, feeder, bronzium, ironium, bronzium);
        ItemStack made = craft(helper, grid);
        if (!made.is(OutpostRegistry.KIT) || made.getCount() != 1) {
            throw failure(helper, "the grid should craft one outpost kit, it crafted %s", made);
        }
        List<ItemStack> swapped =
                List.of(ironium, bronzium, ironium, motor, redstone, feeder, bronzium, ironium, bronzium);
        if (!craft(helper, swapped).isEmpty()) {
            throw failure(helper, "a grid of other ore should craft nothing");
        }
        helper.succeed();
    }

    private static ItemStack craft(GameTestHelper helper, List<ItemStack> grid) {
        CraftingInput input = CraftingInput.of(3, 3, grid);
        ServerLevel level = helper.getLevel();
        return level.recipeAccess()
                .getRecipeFor(RecipeType.CRAFTING, input, level)
                .map(holder -> holder.value().assemble(input))
                .orElse(ItemStack.EMPTY);
    }

    /**
     * The acceptance of #320: a pilot who fills the tank at the outpost and drills the crust of the breach in the hall's floor drops into layer 2 with a full tank,
     * which is where a layer 2 run starts. The crust is the middle of the hall's floor, left as it was.
     */
    @GameTest(maxTicks = MAX_TICKS + 400)
    public void aPodFilledInTheHallDropsThroughItsBreachIntoLayerTwoWithAFullTank(GameTestHelper helper) {
        Stage stage = stage(helper, 8500, true);
        boolean[] ready = {false};
        float full = PodTuning.DEFAULT.shell().fullFuel();
        MinecraftServer server = helper.getLevel().getServer();
        RepairState repaired = new RepairState();
        TerminalTypes.FUEL_PUMP.parts().forEach(part -> repaired.insert(TerminalTypes.FUEL_PUMP, part));
        FarChunks.awaitEntityTicking(helper, stage.level(), stage.floor(), () -> {
            WorldData.swap(server)
                    .with(RepairState.TYPE, repaired)
                    .with(OutpostData.TYPE, new OutpostData())
                    .run(() -> {
                        if (!useKit(stage, stage.floor(), Direction.UP).consumesAction()) {
                            throw failure(helper, "the kit should build");
                        }
                        Charters.deposit(server, stage.charter(), 100);
                        PodEntity pod = TestEntities.create(PodRegistry.POD, stage.level());
                        pod.setPos(
                                stage.floor().getX() + 1.0,
                                stage.floor().getY() + 1,
                                stage.floor().getZ() + 1.0);
                        stage.level().addFreshEntity(pod);
                        PodComponents.register(pod, stage.charter());
                        pod.setFuel(0f);
                        stage.mock()
                                .teleportTo(
                                        stage.level(),
                                        Vec3.atBottomCenterOf(stage.floor().offset(-1, 1, -2)),
                                        0f,
                                        0f);
                        if (Terminals.act(stage.player(), stage.floor().offset(PUMP), FuelPump.FILL, new CompoundTag())
                                .isPresent()) {
                            throw failure(helper, "the outpost pump should fill the pod");
                        }
                        if (!stage.player().startRiding(pod)) {
                            throw failure(helper, "the pilot could not mount the pod");
                        }
                        // The drill's way through the crust of the breach, under the pod: 2 by 2 columns, the 3 blocks
                        // of the crust.
                        for (int dx = 0; dx <= 1; dx++) {
                            for (int dz = 0; dz <= 1; dz++) {
                                for (int dy = 0; dy < LayerTuning.DEFAULT.crustThickness(); dy++) {
                                    if (!BreachService.breakCrust(
                                            stage.level(), stage.floor().offset(dx, -dy, dz))) {
                                        throw failure(
                                                helper,
                                                "the hall's floor should hold crust at %s",
                                                stage.floor().offset(dx, -dy, dz));
                                    }
                                }
                            }
                        }
                        ready[0] = true;
                    });
        });
        TestTicks.each(helper, () -> {
            if (!ready[0] || !TestLayers.isIn(stage.player().level(), 2)) {
                return;
            }
            if (!(stage.player().getVehicle() instanceof PodEntity arrived)) {
                throw failure(helper, "the pilot should arrive in layer 2 still in the pod");
            }
            // The engine idles while the pod drops, a few ticks of it: within a hundredth of the tank.
            if (arrived.fuel() < full * 0.99f) {
                throw failure(
                        helper, "the pod should start layer 2 with a full tank, it has %s of %s", arrived.fuel(), full);
            }
            helper.succeed();
        });
    }
}
