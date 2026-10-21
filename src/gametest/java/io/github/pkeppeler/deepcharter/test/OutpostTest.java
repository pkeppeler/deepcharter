package io.github.pkeppeler.deepcharter.test;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import io.github.pkeppeler.deepcharter.charter.CharterId;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.colony.ColonyKit;
import io.github.pkeppeler.deepcharter.fuel.FuelPump;
import io.github.pkeppeler.deepcharter.layer.BreachService;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.market.OreProcessor;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
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
import io.github.pkeppeler.deepcharter.terminal.TerminalParts;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.terminal.Terminals;
import io.github.pkeppeler.deepcharter.test.support.ClientLight;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestEntities;
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
        return ClientLight.serverLevel(helper.getLevel().getServer(), LayerChain.dimension(1));
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
                        data.all());
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
            if (!OutpostData.get(helper.getLevel().getServer()).all().isEmpty()) {
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
            if (OutpostData.get(helper.getLevel().getServer()).all().size() != 1) {
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
            if (!OutpostData.get(helper.getLevel().getServer()).all().isEmpty()) {
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
            if (!OutpostData.get(server).all().isEmpty()) {
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
            if (!ready[0] || !stage.player().level().dimension().equals(LayerChain.dimension(2))) {
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
