package io.github.pkeppeler.deepcharter.test;

import static io.github.pkeppeler.deepcharter.test.support.Failures.failure;

import io.github.pkeppeler.deepcharter.layer.BlockReadouts;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.HandBreak;
import io.github.pkeppeler.deepcharter.layer.HandBreakCost;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.test.support.CampaignEnvironments;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for brute-forcing pod-only blocks with a hand tool (#858, ADR 0069): the one predicate, the constant
 * 60 s break time for every tool in every layer, the durability a block costs (half a diamond or better tool, the
 * whole of a lesser one, which is destroyed as the block goes; Unbreaking and Mending change neither), no drops, and
 * creative players left alone. The three kinds of pod-only block are the breach crust, Company
 * rock and {@code deep_rock} below layer 1. Every test has its own column.
 *
 * <p>A test that breaks a block in one call uses {@code gameMode.destroyBlock}, which pays the whole cost at once;
 * {@link #aFullMiningRunTakesTheBreakTicksAndPaysHalfTheTool} mines for real, tick by tick.
 */
public class HandBreakTest {
    private static final float BREAK_PROGRESS = 1.0F / 1200;
    /** Every tool below diamond, which any pod-only block destroys. */
    private static final List<Item> LESSER_PICKS = List.of(
            Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.GOLDEN_PICKAXE, Items.COPPER_PICKAXE);

    private static final int Z = 7700;
    private static final int X_PROGRESS = 7700;
    private static final int X_KINDS = 7800;
    private static final int X_UNBREAKING = 8000;
    private static final int X_NO_BITE = 8200;
    private static final int X_ANY_TOOL = 10300;
    private static final int X_HALF = 10400;
    private static final int X_DESTROYED = 10100;
    private static final int X_DURABLE_RUNS_OUT = 10500;
    private static final int X_SHATTER = 8300;
    private static final int X_CREATIVE = 8400;
    private static final int X_LAYER_ONE_ROCK = 8600;
    private static final int X_TOLD = 8700;
    private static final int X_FULL = 8800;
    private static final int X_MINING_SHATTERS = 8900;
    private static final int X_GAS = 9000;
    private static final int X_VETO = 9100;
    private static final int X_ADVENTURE = 9200;
    private static final int X_EARLY = 9300;
    private static final int X_ABORT = 9400;
    private static final int X_SWAP = 9500;
    private static final int X_RESET = 9600;
    private static final int X_READOUT = 9700;
    private static final int X_SENT = 9800;
    private static final int X_PACKET = 9900;
    private static final int X_CAMPAIGN = 21800;
    private static final String SAMPLE = "deepcharter:campaign_sample";
    private static final int FULL_RUN_TICKS = 1200;
    /** A STOP this many ticks in is past the 0.7 of the break time that vanilla's check lets through. */
    private static final int EARLY_STOP_TICKS = 850;
    /** The ticks short of the break time that a STOP may come and still break the block. */
    private static final int SLACK = LayerTuning.DEFAULT.handBreak().stopSlackTicks();

    private static final int X_UNBREAKABLE = 10600;
    private static final int X_CHANGE = 9650;

    /** Positions a test has asked the veto listener below to refuse. A Fabric event cannot be unregistered. */
    private static final Set<BlockPos> VETOED = ConcurrentHashMap.newKeySet();

    static {
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> !VETOED.contains(pos));
    }

    /** A pod-only block of one kind in one layer, and a durable tool to break it with. */
    private record Kind(String name, int layer, BlockState state, Item tool) {}

    private static List<Kind> kinds() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        return List.of(
                new Kind("crust in layer 1", 1, LayerBlocks.BREACH_CRUST.defaultBlockState(), Items.DIAMOND_PICKAXE),
                new Kind("crust in layer 2", 2, LayerBlocks.BREACH_CRUST.defaultBlockState(), Items.NETHERITE_PICKAXE),
                new Kind("crust in layer 3", 3, LayerBlocks.BREACH_CRUST.defaultBlockState(), Items.NETHERITE_PICKAXE),
                new Kind(
                        "Company rock in layer 1",
                        1,
                        HazardBlocks.COMPANY_ROCK.defaultBlockState(),
                        Items.DIAMOND_PICKAXE),
                new Kind(
                        "Company rock in layer 2",
                        2,
                        HazardBlocks.COMPANY_ROCK.defaultBlockState(),
                        Items.NETHERITE_PICKAXE),
                new Kind("deep rock in layer 2", 2, stone, Items.NETHERITE_PICKAXE),
                new Kind("ore in layer 2", 2, Blocks.IRON_ORE.defaultBlockState(), Items.NETHERITE_PICKAXE));
    }

    /** The player, the level and a stone-floored room above which the blocks under test go, in a room of its own. */
    private record Rig(ServerLevel level, MockPlayer mock, BlockPos floor) {
        ServerPlayer player() {
            return mock.player();
        }

        /** The cell {@code dx} blocks east of the first, one above the floor. */
        BlockPos cell(int dx) {
            return floor.offset(dx, 1, 0);
        }
    }

    private static Rig rig(GameTestHelper helper, int layer, int x, String name) {
        ServerLevel level = TestLayers.level(helper, layer);
        BlockPos floor = TestLayers.pos(layer, x, 80, Z);
        RoomCarver.carve(
                level, x - 3, x + 12, floor.getY() - 3, floor.getY(), floor.getZ() - 4, floor.getZ() + 4, Blocks.STONE);
        RoomCarver.carve(level, x - 3, x + 12, floor.getY() + 1, floor.getY() + 6, Z - 4, Z + 4, Blocks.AIR);
        MockPlayer mock = MockPlayers.join(helper, name);
        mock.teleportTo(level, Vec3.atBottomCenterOf(floor.above().offset(0, 0, 2)), 180f, 0f);
        mock.player().setGameMode(GameType.SURVIVAL);
        return new Rig(level, mock, floor);
    }

    private static int maxDamage(Item item) {
        return new ItemStack(item).getMaxDamage();
    }

    private static ItemStack stack(Item item, int damage) {
        ItemStack stack = new ItemStack(item);
        stack.setDamageValue(damage);
        return stack;
    }

    private static ItemStack enchanted(ServerLevel level, ItemStack stack, ResourceKey<Enchantment> key, int rank) {
        stack.enchant(
                level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key), rank);
        return stack;
    }

    private static void hold(ServerPlayer player, ItemStack stack) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
    }

    /** What a block should cost a durable tool of {@code maxDamage}: half of it, rounded up, whatever the layer. */
    private static int expectedCost(int maxDamage) {
        return (maxDamage + 1) / 2;
    }

    private static int dropsAround(ServerLevel level, BlockPos pos) {
        return level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(3))
                .size();
    }

    /** How many chat lines with {@code key} the player got; other lines, such as a join notice, do not count. */
    private static long toldCount(MockPlayer mock, String key) {
        return mock.chatMessages().stream()
                .filter(message -> message.getContents() instanceof TranslatableContents translatable
                        && translatable.getKey().equals(key))
                .count();
    }

    private static boolean actionBarTold(MockPlayer mock, String key) {
        return mock.actionBarMessages().stream()
                .anyMatch(message -> message.getContents() instanceof TranslatableContents translatable
                        && translatable.getKey().equals(key));
    }

    private static void expectProgress(GameTestHelper helper, Rig rig, BlockPos pos, float expected, String what) {
        float progress = rig.level.getBlockState(pos).getDestroyProgress(rig.player(), rig.level, pos);
        if (progress != expected) {
            throw failure(helper, "%s: destroy progress %s, expected %s", what, progress, expected);
        }
    }

    /**
     * A bare number for any tool and none for an item that is no tool, whatever it is enchanted with or the player
     * feels.
     */
    @GameTest
    public void aToolMakesTheSameSlowProgressWhateverItsEnchantmentsAndEffects(GameTestHelper helper) {
        Rig rig = rig(helper, 1, X_PROGRESS, "hb-progress");
        BlockPos pos = rig.cell(0);
        rig.level.setBlock(pos, LayerBlocks.BREACH_CRUST.defaultBlockState(), 3);
        hold(rig.player(), new ItemStack(Items.DIAMOND_PICKAXE));
        expectProgress(helper, rig, pos, BREAK_PROGRESS, "a plain diamond pick");

        ItemStack tuned = new ItemStack(Items.DIAMOND_PICKAXE);
        enchanted(rig.level, tuned, Enchantments.EFFICIENCY, 5);
        hold(rig.player(), tuned);
        rig.player().addEffect(new MobEffectInstance(MobEffects.HASTE, MobEffectInstance.INFINITE_DURATION, 1));
        expectProgress(helper, rig, pos, BREAK_PROGRESS, "Efficiency V and Haste II");

        rig.player().removeAllEffects();
        rig.player()
                .addEffect(new MobEffectInstance(MobEffects.MINING_FATIGUE, MobEffectInstance.INFINITE_DURATION, 2));
        rig.player().setOnGround(false);
        expectProgress(helper, rig, pos, BREAK_PROGRESS, "Mining Fatigue III in the air");
        rig.player().removeAllEffects();
        hold(rig.player(), new ItemStack(Items.WOODEN_PICKAXE));
        expectProgress(helper, rig, pos, BREAK_PROGRESS, "a wooden pick");
        helper.succeed();
    }

    /** One predicate for all three kinds: each is governed, each is slow for any tool and zero for a bare hand. */
    @GameTest
    public void eachKindOfPodOnlyBlockFollowsTheSameRule(GameTestHelper helper) {
        List<Kind> kinds = kinds();
        for (int i = 0; i < kinds.size(); i++) {
            Kind kind = kinds.get(i);
            Rig rig = rig(helper, kind.layer, X_KINDS + 20 * i, "hb-kinds-" + i);
            BlockPos pos = rig.cell(0);
            rig.level.setBlock(pos, kind.state, 3);
            if (!HandBreak.isPodOnly(rig.level, pos, kind.state)) {
                throw failure(helper, "%s is not pod-only", kind.name);
            }
            hold(rig.player(), new ItemStack(kind.tool));
            expectProgress(helper, rig, pos, BREAK_PROGRESS, kind.name + " with " + kind.tool);
            hold(rig.player(), new ItemStack(Items.IRON_PICKAXE));
            expectProgress(helper, rig, pos, BREAK_PROGRESS, kind.name + " with an iron pick");
            hold(rig.player(), ItemStack.EMPTY);
            expectProgress(helper, rig, pos, 0.0F, kind.name + " with a bare hand");
        }
        helper.succeed();
    }

    /**
     * An item that is no tool (a bare hand, a stick, a fishing rod, an elytra, shears) makes no progress, breaks
     * nothing and is refused the swing.
     */
    @GameTest
    public void anItemThatIsNoToolMakesNoProgressAndBreaksNothing(GameTestHelper helper) {
        Rig one = rig(helper, 1, X_NO_BITE, "hb-notool-1");
        Rig two = rig(helper, 2, X_NO_BITE + 1, "hb-notool-2");
        List<ItemStack> items = List.of(
                ItemStack.EMPTY,
                new ItemStack(Items.STICK),
                new ItemStack(Items.FISHING_ROD),
                new ItemStack(Items.ELYTRA),
                new ItemStack(Items.SHEARS));
        List<BlockState> states = List.of(
                LayerBlocks.BREACH_CRUST.defaultBlockState(),
                HazardBlocks.COMPANY_ROCK.defaultBlockState(),
                Blocks.STONE.defaultBlockState());
        for (Rig rig : List.of(one, two)) {
            for (ItemStack held : items) {
                for (BlockState state : states) {
                    if (rig == one && state.is(Blocks.STONE)) {
                        // Stone in layer 1 is an ordinary block, not pod-only: the rule is not its business.
                        continue;
                    }
                    BlockPos pos = rig.cell(2);
                    rig.level.setBlock(pos, state, 3);
                    hold(rig.player(), held);
                    String what =
                            held + " against " + state.getBlock().getName().getString();
                    expectProgress(helper, rig, pos, 0.0F, what);
                    if (rig.player().gameMode.destroyBlock(pos)
                            || !rig.level.getBlockState(pos).equals(state)) {
                        throw failure(helper, "%s broke the block", what);
                    }
                    InteractionResult swing = AttackBlockCallback.EVENT
                            .invoker()
                            .interact(rig.player(), rig.level, InteractionHand.MAIN_HAND, pos, Direction.UP);
                    if (swing != InteractionResult.FAIL) {
                        throw failure(helper, "%s: a swing gave %s, expected FAIL", what, swing);
                    }
                }
            }
        }
        helper.succeed();
    }

    /**
     * Nothing is refused for being too weak: every pickaxe, wood to netherite, breaks every kind of block in layers 1
     * to 3.
     */
    @GameTest
    public void noToolIsRefusedForBeingTooWeakInAnyLayer(GameTestHelper helper) {
        List<Item> picks = new ArrayList<>(LESSER_PICKS);
        picks.add(Items.DIAMOND_PICKAXE);
        picks.add(Items.NETHERITE_PICKAXE);
        for (int layer = 1; layer <= 3; layer++) {
            Rig rig = rig(helper, layer, X_ANY_TOOL + layer, "hb-anytool-" + layer);
            BlockPos pos = rig.cell(2);
            List<BlockState> states = new ArrayList<>(List.of(
                    LayerBlocks.BREACH_CRUST.defaultBlockState(), HazardBlocks.COMPANY_ROCK.defaultBlockState()));
            if (layer > 1) {
                states.add(Blocks.STONE.defaultBlockState());
            }
            for (BlockState state : states) {
                for (Item pick : picks) {
                    rig.level.setBlock(pos, state, 3);
                    hold(rig.player(), new ItemStack(pick));
                    String what =
                            pick + " against " + state.getBlock().getName().getString() + " in layer " + layer;
                    expectProgress(helper, rig, pos, BREAK_PROGRESS, what);
                    InteractionResult swing = AttackBlockCallback.EVENT
                            .invoker()
                            .interact(rig.player(), rig.level, InteractionHand.MAIN_HAND, pos, Direction.UP);
                    if (swing != InteractionResult.PASS) {
                        throw failure(helper, "%s: a swing gave %s, expected PASS", what, swing);
                    }
                    if (!rig.player().gameMode.destroyBlock(pos)
                            || !rig.level.getBlockState(pos).isAir()) {
                        throw failure(helper, "%s did not break the block", what);
                    }
                }
            }
        }
        helper.succeed();
    }

    /** A diamond or netherite tool pays half its durability for a block, the same in layers 1 and 3. */
    @GameTest
    public void diamondAndNetheriteCostHalfTheToolInLayersOneAndThree(GameTestHelper helper) {
        for (Item tool : List.of(Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE, Items.DIAMOND_AXE)) {
            int[] cost = new int[4];
            for (int layer : new int[] {1, 3}) {
                Rig rig = rig(helper, layer, X_HALF + layer, "hb-half-" + layer + "-" + tool);
                BlockPos pos = rig.cell(0);
                rig.level.setBlock(pos, LayerBlocks.BREACH_CRUST.defaultBlockState(), 3);
                ItemStack pick = new ItemStack(tool);
                hold(rig.player(), pick);
                if (!rig.player().gameMode.destroyBlock(pos)
                        || !rig.level.getBlockState(pos).isAir()) {
                    throw failure(helper, "%s did not break the crust in layer %d", tool, layer);
                }
                cost[layer] = pick.getDamageValue();
                if (cost[layer] != expectedCost(pick.getMaxDamage())) {
                    throw failure(
                            helper,
                            "%s cost %d in layer %d, expected half of %d",
                            tool,
                            cost[layer],
                            layer,
                            pick.getMaxDamage());
                }
            }
            if (cost[1] != cost[3]) {
                throw failure(helper, "%s cost %d in layer 1 and %d in layer 3", tool, cost[1], cost[3]);
            }
        }
        helper.succeed();
    }

    /**
     * The block goes, drops nothing and costs half the pick, however many Unbreaking levels it carries and with
     * Mending on it. It breaks only once every kind's chunk is entity-ticking: a drop in a chunk the server has not
     * yet promoted is not found by an entity query, so an earlier read would pass the no-drop check without proving
     * it (#872).
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 200)
    public void eachKindCostsHalfTheToolWhateverUnbreakingSays(GameTestHelper helper) {
        List<Kind> kinds = kinds();
        List<Rig> rigs = new ArrayList<>();
        List<BlockPos> cells = new ArrayList<>();
        for (int i = 0; i < kinds.size(); i++) {
            Kind kind = kinds.get(i);
            Rig rig = rig(helper, kind.layer, X_UNBREAKING + 20 * i, "hb-cost-" + i);
            rig.level.setBlock(rig.cell(0), kind.state, 3);
            rigs.add(rig);
            cells.add(rig.cell(0));
        }
        boolean[] ready = {false};
        FarChunks.awaitAllEntityTicking(helper, rigs.get(0).level, cells, () -> ready[0] = true);
        TestTicks.each(helper, () -> {
            if (!ready[0]) {
                return;
            }
            for (int i = 0; i < kinds.size(); i++) {
                checkCost(helper, kinds.get(i), rigs.get(i));
            }
            helper.succeed();
        });
    }

    private static void checkCost(GameTestHelper helper, Kind kind, Rig rig) {
        BlockPos pos = rig.cell(0);
        ItemStack pick = enchanted(rig.level, new ItemStack(kind.tool), Enchantments.UNBREAKING, 3);
        enchanted(rig.level, pick, Enchantments.MENDING, 1);
        hold(rig.player(), pick);
        int max = pick.getMaxDamage();
        int expected = expectedCost(max);
        if (!rig.player().gameMode.destroyBlock(pos)
                || !rig.level.getBlockState(pos).isAir()) {
            throw failure(helper, "%s did not break", kind.name);
        }
        if (pick.getDamageValue() != expected) {
            throw failure(
                    helper,
                    "%s cost %d durability, expected %d of %d",
                    kind.name,
                    pick.getDamageValue(),
                    expected,
                    max);
        }
        if (dropsAround(rig.level, pos) != 0) {
            throw failure(helper, "%s dropped something", kind.name);
        }
    }

    /**
     * Control for the no-drop checks: the same stone in layer 1 is an ordinary block, which does drop. It reads the
     * drop only once the chunk is entity-ticking, as the other tests that count drops do: an item added to a chunk the
     * server has not yet promoted is not found by an entity query, and a read at tick 0 once counted none (#872, seen
     * on #1235).
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 200)
    public void deepRockInLayerOneStaysANormalBlockThatDrops(GameTestHelper helper) {
        Rig rig = rig(helper, 1, X_LAYER_ONE_ROCK, "hb-rock-1");
        BlockPos pos = rig.cell(0);
        BlockState stone = Blocks.STONE.defaultBlockState();
        rig.level.setBlock(pos, stone, 3);
        boolean[] ready = {false};
        FarChunks.awaitEntityTicking(helper, rig.level, pos, () -> ready[0] = true);
        TestTicks.each(helper, () -> {
            if (!ready[0]) {
                return;
            }
            if (HandBreak.isPodOnly(rig.level, pos, stone)) {
                throw failure(helper, "deep rock in layer 1 is pod-only");
            }
            ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
            hold(rig.player(), pick);
            float progress = stone.getDestroyProgress(rig.player(), rig.level, pos);
            if (progress <= 0 || progress == BREAK_PROGRESS) {
                throw failure(helper, "stone in layer 1 has progress %s, expected the vanilla one", progress);
            }
            InteractionResult swing = AttackBlockCallback.EVENT
                    .invoker()
                    .interact(rig.player(), rig.level, InteractionHand.MAIN_HAND, pos, Direction.UP);
            if (swing != InteractionResult.PASS) {
                throw failure(helper, "a swing at stone in layer 1 gave %s, expected PASS", swing);
            }
            if (!rig.player().gameMode.destroyBlock(pos)
                    || !rig.level.getBlockState(pos).isAir()) {
                throw failure(helper, "an iron pick could not break stone in layer 1");
            }
            if (pick.getDamageValue() > 1) {
                throw failure(helper, "stone in layer 1 cost %d durability", pick.getDamageValue());
            }
            if (dropsAround(rig.level, pos) == 0) {
                throw failure(helper, "stone in layer 1 dropped nothing, so the no-drop checks prove nothing");
            }
            helper.succeed();
        });
    }

    /** A durable tool without the durability for half its price is used up by the block, and the block still goes. */
    @GameTest
    public void aDurablePickWithTooLittleDurabilityIsUsedUpAndTheBlockStillGoes(GameTestHelper helper) {
        Rig rig = rig(helper, 2, X_SHATTER, "hb-shatter");
        BlockPos pos = rig.cell(0);
        rig.level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        hold(rig.player(), stack(Items.NETHERITE_PICKAXE, maxDamage(Items.NETHERITE_PICKAXE) - 100));
        if (!rig.player().gameMode.destroyBlock(pos)
                || !rig.level.getBlockState(pos).isAir()) {
            throw failure(helper, "a pick with 100 points left did not break the block");
        }
        if (!rig.player().getMainHandItem().isEmpty()) {
            throw failure(helper, "the pick was not used up: %s", rig.player().getMainHandItem());
        }
        helper.succeed();
    }

    /** A pick that has exactly the cost is used up by the block, which goes. */
    @GameTest
    public void aPickWithExactlyTheCostBreaksTheBlockAndIsUsedUp(GameTestHelper helper) {
        Rig rig = rig(helper, 2, X_SHATTER + 1, "hb-exact");
        BlockPos pos = rig.cell(0);
        rig.level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        int max = maxDamage(Items.NETHERITE_PICKAXE);
        hold(rig.player(), stack(Items.NETHERITE_PICKAXE, max - expectedCost(max)));
        if (!rig.player().gameMode.destroyBlock(pos)
                || !rig.level.getBlockState(pos).isAir()) {
            throw failure(helper, "a pick with exactly the cost left did not break the block");
        }
        if (!rig.player().getMainHandItem().isEmpty()) {
            throw failure(helper, "the pick was not used up: %s", rig.player().getMainHandItem());
        }
        helper.succeed();
    }

    /** A tool below diamond is destroyed by the block, whatever its wear, and the block goes. */
    @GameTest
    public void aToolBelowDiamondIsDestroyedByTheBlockAndTheBlockGoes(GameTestHelper helper) {
        for (int i = 0; i < LESSER_PICKS.size(); i++) {
            Item tool = LESSER_PICKS.get(i);
            Rig rig = rig(helper, 2, X_DESTROYED + 20 * i, "hb-destroyed-" + i);
            BlockPos pos = rig.cell(0);
            rig.level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
            hold(rig.player(), new ItemStack(tool));
            if (!rig.player().gameMode.destroyBlock(pos)
                    || !rig.level.getBlockState(pos).isAir()) {
                throw failure(helper, "%s did not break the block", tool);
            }
            if (!rig.player().getMainHandItem().isEmpty()) {
                throw failure(
                        helper, "%s was not destroyed: %s", tool, rig.player().getMainHandItem());
            }
        }
        helper.succeed();
    }

    /** Creative players break any block as they always did, with no tool, no cost and no refusal. */
    @GameTest
    public void creativePlayersAreUnaffected(GameTestHelper helper) {
        List<Kind> kinds = kinds();
        for (int i = 0; i < kinds.size(); i++) {
            Kind kind = kinds.get(i);
            Rig rig = rig(helper, kind.layer, X_CREATIVE + 20 * i, "hb-creative-" + i);
            BlockPos pos = rig.cell(0);
            rig.level.setBlock(pos, kind.state, 3);
            rig.player().setGameMode(GameType.CREATIVE);
            hold(rig.player(), ItemStack.EMPTY);
            if (HandBreak.governs(rig.player(), rig.level, pos, kind.state)) {
                throw failure(helper, "the rule governs a creative player at %s", kind.name);
            }
            InteractionResult swing = AttackBlockCallback.EVENT
                    .invoker()
                    .interact(rig.player(), rig.level, InteractionHand.MAIN_HAND, pos, Direction.UP);
            if (swing != InteractionResult.PASS) {
                throw failure(helper, "a creative swing at %s gave %s", kind.name, swing);
            }
            if (!rig.player().gameMode.destroyBlock(pos)
                    || !rig.level.getBlockState(pos).isAir()) {
                throw failure(helper, "a creative player could not break %s", kind.name);
            }
            if (toldCount(rig.mock, "deepcharter.layer.pod_only.first_try") != 0) {
                throw failure(helper, "a creative player was told a pod drill is the way at %s", kind.name);
            }
        }
        helper.succeed();
    }

    /** The first swing prints one line, a bare hand gets the other line, and neither repeats. */
    @GameTest
    public void theFirstSwingSaysAPodDrillIsTheWayAndOnlyOnce(GameTestHelper helper) {
        Rig biting = rig(helper, 1, X_TOLD, "hb-told-bites");
        Rig clumsy = rig(helper, 1, X_TOLD + 1, "hb-told-clumsy");
        for (Rig rig : List.of(biting, clumsy)) {
            rig.level.setBlock(rig.cell(0), HazardBlocks.COMPANY_ROCK.defaultBlockState(), 3);
        }
        hold(biting.player(), new ItemStack(Items.DIAMOND_PICKAXE));
        hold(clumsy.player(), ItemStack.EMPTY);
        for (int swing = 0; swing < 3; swing++) {
            for (Rig rig : List.of(biting, clumsy)) {
                AttackBlockCallback.EVENT
                        .invoker()
                        .interact(rig.player(), rig.level, InteractionHand.MAIN_HAND, rig.cell(0), Direction.UP);
            }
        }
        // The mock reads its connection at the end of a tick, so the lines are there a tick later.
        helper.runAfterDelay(3, () -> {
            String firstTry = "deepcharter.layer.pod_only.first_try";
            String cannotBite = "deepcharter.layer.pod_only.cannot_bite";
            if (toldCount(biting.mock, firstTry) != 1 || toldCount(biting.mock, cannotBite) != 0) {
                throw failure(
                        helper, "a tool was told %s, expected the first-try line once", biting.mock.chatMessages());
            }
            if (toldCount(clumsy.mock, cannotBite) != 1 || toldCount(clumsy.mock, firstTry) != 0) {
                throw failure(
                        helper,
                        "a bare hand was told %s, expected the cannot-bite line once",
                        clumsy.mock.chatMessages());
            }
            helper.succeed();
        });
    }

    /**
     * A real mining run: START, then every tick the server's own, then STOP once the break time is up. The block holds
     * until then, goes without a drop, and the pick has paid half its durability, with Unbreaking III, Efficiency V and
     * Haste II on it and the player. Layer 2's stone drops cobblestone when it is mined as an ordinary block.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + FULL_RUN_TICKS + 200)
    public void aFullMiningRunTakesTheBreakTicksAndPaysHalfTheTool(GameTestHelper helper) {
        Rig rig = rig(helper, 2, X_FULL, "hb-full");
        BlockPos pos = rig.cell(0);
        rig.level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        ItemStack pick = new ItemStack(Items.NETHERITE_PICKAXE);
        enchanted(rig.level, pick, Enchantments.UNBREAKING, 3);
        enchanted(rig.level, pick, Enchantments.EFFICIENCY, 5);
        hold(rig.player(), pick);
        rig.player().addEffect(new MobEffectInstance(MobEffects.HASTE, MobEffectInstance.INFINITE_DURATION, 1));
        int expected = expectedCost(pick.getMaxDamage());
        boolean[] ready = {false};
        int[] startedAt = {-1};
        boolean[] stopped = {false};
        FarChunks.awaitEntityTicking(helper, rig.level, pos, () -> ready[0] = true);
        TestTicks.each(helper, () -> {
            if (!ready[0]) {
                return;
            }
            // Start on this body's own first tick: the order of the two bodies in a tick is not fixed.
            if (startedAt[0] < 0) {
                startedAt[0] = (int) helper.getTick();
                rig.player()
                        .gameMode
                        .handleBlockBreakAction(
                                pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, Direction.UP, 319, 0);
                return;
            }
            int mined = (int) helper.getTick() - startedAt[0];
            if (!stopped[0]) {
                if (!rig.level.getBlockState(pos).is(Blocks.STONE)) {
                    throw failure(helper, "the block went after %d ticks of mining", mined);
                }
                if (mined == FULL_RUN_TICKS) {
                    rig.player()
                            .gameMode
                            .handleBlockBreakAction(
                                    pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, Direction.UP, 319, 1);
                    stopped[0] = true;
                }
                return;
            }
            if (!rig.level.getBlockState(pos).isAir()) {
                throw failure(helper, "the block is still there after the break time");
            }
            if (pick.getDamageValue() != expected) {
                throw failure(helper, "the run cost %d durability, expected %d", pick.getDamageValue(), expected);
            }
            if (dropsAround(rig.level, pos) != 0) {
                throw failure(helper, "the brute-forced block dropped something");
            }
            if (!actionBarTold(rig.mock, "deepcharter.layer.pod_only.burning")) {
                throw failure(helper, "the action bar never said the pick is burning out");
            }
            helper.succeed();
        });
    }

    /**
     * A tool below diamond (every kind, with Unbreaking III and Mending on it) mines for the whole break time without
     * breaking: the block holds and the pick is there, worn to its last point, until the break, where the block goes
     * and the pick is destroyed, on the same call. Nothing drops.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + FULL_RUN_TICKS + 200)
    public void aToolBelowDiamondLastsTheBreakTimeAndIsDestroyedAsTheBlockGoes(GameTestHelper helper) {
        List<Rig> rigs = new ArrayList<>();
        List<ItemStack> picks = new ArrayList<>();
        List<BlockPos> cells = new ArrayList<>();
        for (int i = 0; i < LESSER_PICKS.size(); i++) {
            Rig rig = rig(helper, 2, X_MINING_SHATTERS + 20 * i, "hb-lesser-" + i);
            ItemStack pick = enchanted(rig.level, new ItemStack(LESSER_PICKS.get(i)), Enchantments.UNBREAKING, 3);
            enchanted(rig.level, pick, Enchantments.MENDING, 1);
            hold(rig.player(), pick);
            rig.level.setBlock(rig.cell(0), Blocks.STONE.defaultBlockState(), 3);
            rigs.add(rig);
            picks.add(pick);
            cells.add(rig.cell(0));
        }
        boolean[] ready = {false};
        int[] startedAt = {-1};
        FarChunks.awaitAllEntityTicking(helper, rigs.get(0).level, cells, () -> ready[0] = true);
        TestTicks.each(helper, () -> {
            if (!ready[0]) {
                return;
            }
            // Start on this body's own first tick: the order of the two bodies in a tick is not fixed.
            if (startedAt[0] < 0) {
                startedAt[0] = (int) helper.getTick();
                for (Rig rig : rigs) {
                    action(rig, rig.cell(0), ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, 0);
                }
                return;
            }
            int mined = (int) helper.getTick() - startedAt[0];
            for (int i = 0; i < rigs.size(); i++) {
                Rig rig = rigs.get(i);
                BlockPos pos = rig.cell(0);
                String what = LESSER_PICKS.get(i) + " after " + mined + " ticks of mining";
                if (mined < FULL_RUN_TICKS) {
                    if (!rig.level.getBlockState(pos).is(Blocks.STONE)
                            || rig.player().getMainHandItem() != picks.get(i)) {
                        throw failure(helper, "%s: the block or the pick went before the break time", what);
                    }
                } else if (mined == FULL_RUN_TICKS) {
                    ItemStack pick = picks.get(i);
                    if (pick.getDamageValue() != pick.getMaxDamage() - 1) {
                        throw failure(
                                helper,
                                "%s: damage %d of %d, expected all but the last point spent",
                                what,
                                pick.getDamageValue(),
                                pick.getMaxDamage());
                    }
                    if (!actionBarTold(rig.mock, "deepcharter.layer.pod_only.destroying")) {
                        throw failure(helper, "%s: the action bar never said the pick will be destroyed", what);
                    }
                    action(rig, pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, 1);
                    if (!rig.level.getBlockState(pos).isAir()) {
                        throw failure(helper, "%s: the block is still there after the break time", what);
                    }
                    if (!rig.player().getMainHandItem().isEmpty()) {
                        throw failure(helper, "%s: the pick was not destroyed as the block went", what);
                    }
                    if (dropsAround(rig.level, pos) != 0) {
                        throw failure(helper, "%s: the block dropped something", what);
                    }
                }
            }
            if (mined == FULL_RUN_TICKS) {
                helper.succeed();
            }
        });
    }

    /**
     * A durable pick that cannot pay half its price is worn to its last point on the way and holds there, so the block
     * stays until the break time is up; the break uses it up and the block goes.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + FULL_RUN_TICKS + 200)
    public void aDurablePickThatCannotPayLastsTheBreakTimeAndIsUsedUpByTheBreak(GameTestHelper helper) {
        Rig rig = rig(helper, 2, X_DURABLE_RUNS_OUT, "hb-runs-out");
        BlockPos pos = rig.cell(0);
        rig.level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        ItemStack pick = stack(Items.NETHERITE_PICKAXE, maxDamage(Items.NETHERITE_PICKAXE) - 100);
        hold(rig.player(), pick);
        boolean[] ready = {false};
        int[] startedAt = {-1};
        FarChunks.awaitEntityTicking(helper, rig.level, pos, () -> ready[0] = true);
        TestTicks.each(helper, () -> {
            if (!ready[0]) {
                return;
            }
            // Start on this body's own first tick: the order of the two bodies in a tick is not fixed.
            if (startedAt[0] < 0) {
                startedAt[0] = (int) helper.getTick();
                action(rig, pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, 0);
                return;
            }
            int mined = (int) helper.getTick() - startedAt[0];
            if (mined < FULL_RUN_TICKS) {
                if (!rig.level.getBlockState(pos).is(Blocks.STONE)
                        || rig.player().getMainHandItem() != pick) {
                    throw failure(helper, "the block or the pick went after %d ticks of mining", mined);
                }
            } else if (mined == FULL_RUN_TICKS) {
                action(rig, pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, 1);
                if (!rig.level.getBlockState(pos).isAir()
                        || !rig.player().getMainHandItem().isEmpty()) {
                    throw failure(helper, "the break did not take the block and use the pick up");
                }
                helper.succeed();
            }
        });
    }

    /**
     * The campaign world's unbuilt depth counts as the layer below the last built, and costs half a pick like any
     * other.
     */
    @GameTest(environment = CampaignEnvironments.B, dimension = SAMPLE)
    public void deeperThanLayerTwoACrustCostsHalfAPick(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int y = CampaignBands.layer(CampaignBands.lastLayer()).minY() - 100;
        BlockPos pos = new BlockPos(X_CAMPAIGN, y, X_CAMPAIGN);
        MockPlayer mock = MockPlayers.join(
                helper.getLevel().getServer(), "hb-campaign", level, Vec3.atBottomCenterOf(pos.above(3)));
        ServerPlayer player = mock.player();
        player.setNoGravity(true);
        level.setBlock(pos, LayerBlocks.BREACH_CRUST.defaultBlockState(), 3);
        int unbuilt = CampaignBands.lastLayer() + 1;
        if (HandBreak.layerOf(level, pos) != unbuilt) {
            throw failure(
                    helper, "unbuilt depth counts as layer %d, expected %d", HandBreak.layerOf(level, pos), unbuilt);
        }
        player.setGameMode(GameType.SURVIVAL);
        for (Item tool : List.of(Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE)) {
            level.setBlock(pos, LayerBlocks.BREACH_CRUST.defaultBlockState(), 3);
            ItemStack pick = new ItemStack(tool);
            hold(player, pick);
            if (!player.gameMode.destroyBlock(pos) || !level.getBlockState(pos).isAir()) {
                throw failure(helper, "a fresh %s could not break the crust of unbuilt depth", tool);
            }
            if (pick.getDamageValue() != expectedCost(pick.getMaxDamage())) {
                throw failure(
                        helper,
                        "the %s cost %d of %d, expected half",
                        tool,
                        pick.getDamageValue(),
                        pick.getMaxDamage());
            }
        }
        helper.succeed();
    }

    private static void action(Rig rig, BlockPos pos, ServerboundPlayerActionPacket.Action action, int sequence) {
        rig.player().gameMode.handleBlockBreakAction(pos, action, Direction.UP, 319, sequence);
    }

    /**
     * Sends START on the first tick the chunk is entity-ticking, then calls {@code perTick} with the ticks mined so
     * far on every tick after. The two test bodies run in no fixed order in a tick, so START is this body's own first
     * tick.
     */
    private static void drive(GameTestHelper helper, Rig rig, BlockPos pos, IntConsumer perTick) {
        boolean[] ready = {false};
        int[] startedAt = {-1};
        FarChunks.awaitEntityTicking(helper, rig.level, pos, () -> ready[0] = true);
        TestTicks.each(helper, () -> {
            if (!ready[0]) {
                return;
            }
            if (startedAt[0] < 0) {
                startedAt[0] = (int) helper.getTick();
                action(rig, pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, 0);
                return;
            }
            perTick.accept((int) helper.getTick() - startedAt[0]);
        });
    }

    /** A pocket mined by hand vents like any hand break: the hazard still bites, and brute force is no way round it. */
    @GameTest
    public void bruteForcingAGasPocketVentsIt(GameTestHelper helper) {
        Rig rig = rig(helper, 2, X_GAS, "hb-gas");
        BlockPos pocket = rig.cell(0);
        BlockPos neighbour = pocket.east();
        rig.level.setBlock(pocket, HazardBlocks.GAS_POCKET.defaultBlockState(), 3);
        rig.level.setBlock(neighbour, Blocks.STONE.defaultBlockState(), 3);
        hold(rig.player(), new ItemStack(Items.NETHERITE_PICKAXE));
        if (!rig.player().gameMode.destroyBlock(pocket)
                || !rig.level.getBlockState(pocket).isAir()) {
            throw failure(helper, "the gas pocket did not break");
        }
        if (!rig.level.getBlockState(neighbour).isAir()) {
            throw failure(helper, "the pocket did not vent: the rock beside it stands");
        }
        helper.succeed();
    }

    /** A listener's veto refuses the break, charges nothing and leaves the block, which the client is sent back. */
    @GameTest
    public void aVetoedBreakChargesNothingAndLeavesTheBlock(GameTestHelper helper) {
        Rig rig = rig(helper, 2, X_VETO, "hb-veto");
        BlockPos pos = rig.cell(0);
        rig.level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        ItemStack pick = new ItemStack(Items.NETHERITE_PICKAXE);
        hold(rig.player(), pick);
        VETOED.add(pos);
        if (rig.player().gameMode.destroyBlock(pos)
                || !rig.level.getBlockState(pos).is(Blocks.STONE)) {
            throw failure(helper, "a vetoed break went through");
        }
        if (pick.getDamageValue() != 0) {
            throw failure(helper, "a vetoed break cost %d durability", pick.getDamageValue());
        }
        helper.succeed();
    }

    /** Vanilla's adventure-mode refusal still holds for a pick that bites: no break, no cost. */
    @GameTest
    public void anAdventurePlayerWithABitingPickBreaksNothing(GameTestHelper helper) {
        Rig rig = rig(helper, 2, X_ADVENTURE, "hb-adventure");
        BlockPos pos = rig.cell(0);
        rig.level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        ItemStack pick = new ItemStack(Items.NETHERITE_PICKAXE);
        hold(rig.player(), pick);
        rig.player().setGameMode(GameType.ADVENTURE);
        if (rig.player().gameMode.destroyBlock(pos)
                || !rig.level.getBlockState(pos).is(Blocks.STONE)) {
            throw failure(helper, "an adventure player broke deep rock with a netherite pick");
        }
        if (pick.getDamageValue() != 0) {
            throw failure(helper, "a refused adventure break cost %d durability", pick.getDamageValue());
        }
        helper.succeed();
    }

    /**
     * The 60 s is a floor: a STOP past vanilla's 0.7 of the break time, but short of the break time less the slack, is
     * refused. The block stays, the client is sent it back and the break charges nothing.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + EARLY_STOP_TICKS + 200)
    public void anEarlyStopIsRefusedAndChargesNothing(GameTestHelper helper) {
        Rig rig = rig(helper, 2, X_EARLY, "hb-early");
        BlockPos pos = rig.cell(0);
        rig.level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        ItemStack pick = new ItemStack(Items.NETHERITE_PICKAXE);
        hold(rig.player(), pick);
        drive(helper, rig, pos, mined -> {
            if (mined < EARLY_STOP_TICKS) {
                if (!rig.level.getBlockState(pos).is(Blocks.STONE)) {
                    throw failure(helper, "the block went after %d ticks of mining", mined);
                }
                return;
            }
            int worn = pick.getDamageValue();
            action(rig, pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, 1);
            if (!rig.level.getBlockState(pos).is(Blocks.STONE)) {
                throw failure(helper, "a STOP after %d ticks broke the block, short of 60 s", mined);
            }
            if (pick.getDamageValue() != worn || rig.player().getMainHandItem() != pick) {
                throw failure(
                        helper, "a refused STOP charged the pick: damage %d, was %d", pick.getDamageValue(), worn);
            }
            helper.succeed();
        });
    }

    /** A STOP within the slack of the break time breaks the block and pays the rest, so skew never costs a block. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + FULL_RUN_TICKS + 200)
    public void aStopWithinTheSlackOfTheBreakTimeStillBreaksTheBlock(GameTestHelper helper) {
        Rig rig = rig(helper, 2, X_EARLY + 20, "hb-slack");
        BlockPos pos = rig.cell(0);
        rig.level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        ItemStack pick = new ItemStack(Items.NETHERITE_PICKAXE);
        hold(rig.player(), pick);
        int expected = expectedCost(pick.getMaxDamage());
        drive(helper, rig, pos, mined -> {
            if (mined < FULL_RUN_TICKS - SLACK + 1) {
                return;
            }
            action(rig, pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, 1);
            if (!rig.level.getBlockState(pos).isAir()) {
                throw failure(helper, "a STOP %d ticks short of 60 s did not break the block", FULL_RUN_TICKS - mined);
            }
            if (pick.getDamageValue() != expected) {
                throw failure(helper, "the break cost %d durability, expected %d", pick.getDamageValue(), expected);
            }
            helper.succeed();
        });
    }

    /** One tick further short than the slack is refused: the floor is the break time less the slack and no less. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + FULL_RUN_TICKS + 200)
    public void aStopJustPastTheSlackIsRefused(GameTestHelper helper) {
        Rig rig = rig(helper, 2, X_EARLY + 40, "hb-past-slack");
        BlockPos pos = rig.cell(0);
        rig.level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        hold(rig.player(), new ItemStack(Items.NETHERITE_PICKAXE));
        drive(helper, rig, pos, mined -> {
            if (mined < FULL_RUN_TICKS - SLACK - 3) {
                return;
            }
            action(rig, pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, 1);
            if (!rig.level.getBlockState(pos).is(Blocks.STONE)) {
                throw failure(helper, "a STOP %d ticks short of 60 s broke the block", FULL_RUN_TICKS - mined);
            }
            helper.succeed();
        });
    }

    /**
     * Stopping and starting again throws away the count: what the first swing spent is wasted, and the block costs its
     * whole price again.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + FULL_RUN_TICKS + 400)
    public void abortingAndStartingAgainWastesWhatWasSpent(GameTestHelper helper) {
        Rig rig = rig(helper, 2, X_ABORT, "hb-abort");
        BlockPos pos = rig.cell(0);
        rig.level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        ItemStack pick = new ItemStack(Items.NETHERITE_PICKAXE);
        hold(rig.player(), pick);
        int expected = expectedCost(pick.getMaxDamage());
        int restartAt = 100;
        int[] wasted = {-1};
        drive(helper, rig, pos, mined -> {
            if (mined == restartAt) {
                wasted[0] = pick.getDamageValue();
                if (wasted[0] <= 0) {
                    throw failure(helper, "100 ticks of mining spent nothing");
                }
                action(rig, pos, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, 1);
                action(rig, pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, 2);
            } else if (mined == restartAt + FULL_RUN_TICKS) {
                action(rig, pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, 3);
                if (!rig.level.getBlockState(pos).isAir()) {
                    throw failure(helper, "the block is still there after a whole run since the restart");
                }
                if (pick.getDamageValue() != wasted[0] + expected) {
                    throw failure(
                            helper,
                            "the pick took %d damage, expected the %d wasted plus the block's %d",
                            pick.getDamageValue(),
                            wasted[0],
                            expected);
                }
                helper.succeed();
            }
        });
    }

    /** The packet a client sends, not a call into the game mode: the same wear, through the listener's own checks. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 200)
    public void aStartPacketFromTheClientWearsThePickToo(GameTestHelper helper) {
        Rig rig = rig(helper, 2, X_PACKET, "hb-packet");
        BlockPos pos = rig.cell(0);
        rig.level.setBlock(pos, LayerBlocks.BREACH_CRUST.defaultBlockState(), 3);
        ItemStack pick = new ItemStack(Items.NETHERITE_PICKAXE);
        hold(rig.player(), pick);
        boolean[] ready = {false};
        boolean[] sent = {false};
        int[] sentAt = {0};
        FarChunks.awaitEntityTicking(helper, rig.level, pos, () -> ready[0] = true);
        TestTicks.each(helper, () -> {
            if (!ready[0]) {
                return;
            }
            if (!sent[0]) {
                sent[0] = true;
                sentAt[0] = (int) helper.getTick();
                rig.player()
                        .connection
                        .handlePlayerAction(new ServerboundPlayerActionPacket(
                                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, Direction.SOUTH, 0));
            } else if (helper.getTick() - sentAt[0] == 60) {
                if (pick.getDamageValue() <= 0) {
                    throw failure(helper, "60 ticks after a START packet the pick is unworn");
                }
                if (!actionBarTold(rig.mock, "deepcharter.layer.pod_only.burning")) {
                    throw failure(helper, "60 ticks after a START packet the action bar never said the pick burns");
                }
                helper.succeed();
            }
        });
    }

    /** The client goes on mining while the server wears the pick, so only wear alone is no change of tool. */
    @GameTest
    public void wearAloneIsNotANewTool(GameTestHelper helper) {
        ServerLevel level = TestLayers.level(helper, 1);
        ItemStack began = new ItemStack(Items.DIAMOND_PICKAXE);
        ItemStack worn = stack(Items.DIAMOND_PICKAXE, 300);
        ItemStack enchanted = enchanted(level, new ItemStack(Items.DIAMOND_PICKAXE), Enchantments.EFFICIENCY, 1);
        if (!HandBreak.sameButWorn(began, worn)) {
            throw failure(helper, "a worn pick counts as a new tool");
        }
        if (HandBreak.sameButWorn(began, enchanted)) {
            throw failure(helper, "an enchanted pick counts as the same tool");
        }
        if (HandBreak.sameButWorn(began, new ItemStack(Items.NETHERITE_PICKAXE))) {
            throw failure(helper, "another pick counts as the same tool");
        }
        if (HandBreak.sameButWorn(began, ItemStack.EMPTY)) {
            throw failure(helper, "an empty hand counts as the same tool");
        }
        helper.succeed();
    }

    /**
     * What the player sees: the server sends the worn pick to the client while the player mines, not only when the
     * block goes.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 200)
    public void theWornPickIsSentToThePlayerWhileMining(GameTestHelper helper) {
        Rig rig = rig(helper, 2, X_SENT, "hb-sent");
        BlockPos pos = rig.cell(0);
        rig.level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        hold(rig.player(), new ItemStack(Items.NETHERITE_PICKAXE));
        drive(helper, rig, pos, mined -> {
            if (mined < 100) {
                return;
            }
            int worn = rig.mock.slotUpdates().stream()
                    .filter(stack -> stack.is(Items.NETHERITE_PICKAXE))
                    .mapToInt(ItemStack::getDamageValue)
                    .max()
                    .orElse(0);
            if (worn <= 0) {
                throw failure(
                        helper,
                        "after %d ticks of mining the player was sent no worn pick (held damage %d, %d slot updates)",
                        mined,
                        rig.player().getMainHandItem().getDamageValue(),
                        rig.mock.slotUpdates().size());
            }
            helper.succeed();
        });
    }

    /**
     * Swapping the pick for an item that is no tool stops the spending and the progress; the pick put away keeps what
     * it spent.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 300)
    public void swappingToAnItemThatIsNoToolStopsTheSpending(GameTestHelper helper) {
        Rig rig = rig(helper, 2, X_SWAP, "hb-swap");
        BlockPos pos = rig.cell(0);
        rig.level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        ItemStack pick = new ItemStack(Items.NETHERITE_PICKAXE);
        ItemStack stick = new ItemStack(Items.STICK);
        hold(rig.player(), pick);
        int[] spent = {-1};
        drive(helper, rig, pos, mined -> {
            if (mined == 100) {
                spent[0] = pick.getDamageValue();
                hold(rig.player(), stick);
            } else if (mined == 140) {
                if (pick.getDamageValue() != spent[0] || stick.getDamageValue() != 0) {
                    throw failure(
                            helper,
                            "the damage moved after the swap: pick %d (was %d), stick %d",
                            pick.getDamageValue(),
                            spent[0],
                            stick.getDamageValue());
                }
                action(rig, pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, 1);
                if (!rig.level.getBlockState(pos).is(Blocks.STONE)) {
                    throw failure(helper, "a stick finished a block the netherite pick began");
                }
                helper.succeed();
            }
        });
    }

    /**
     * Changing the held item restarts the count, so a client cannot mine with one tool and finish with another. A
     * wooden pick mines for most of the break time, a netherite pick is swapped in, and the STOP at the break time is
     * refused.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + FULL_RUN_TICKS + 200)
    public void changingTheHeldItemRestartsTheCount(GameTestHelper helper) {
        Rig rig = rig(helper, 2, X_CHANGE, "hb-change");
        BlockPos pos = rig.cell(0);
        rig.level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        hold(rig.player(), new ItemStack(Items.WOODEN_PICKAXE));
        ItemStack other = new ItemStack(Items.NETHERITE_PICKAXE);
        drive(helper, rig, pos, mined -> {
            if (mined == FULL_RUN_TICKS - 200) {
                hold(rig.player(), other);
            } else if (mined == FULL_RUN_TICKS) {
                action(rig, pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, 1);
                if (!rig.level.getBlockState(pos).is(Blocks.STONE)) {
                    throw failure(helper, "a tool swapped in at the end finished the block");
                }
                helper.succeed();
            }
        });
    }

    /**
     * A tool that cannot be damaged (Unbreakable) is still a tool: it makes progress, breaks the block after the break
     * time and pays nothing, the one exception to the cost. Its pick, a lesser one and a durable one, is not destroyed.
     */
    @GameTest
    public void anUnbreakableToolBreaksTheBlockAndPaysNothing(GameTestHelper helper) {
        List<Item> picks = List.of(Items.IRON_PICKAXE, Items.NETHERITE_PICKAXE);
        for (int i = 0; i < picks.size(); i++) {
            Rig rig = rig(helper, 2, X_UNBREAKABLE + 20 * i, "hb-unbreakable-" + i);
            BlockPos pos = rig.cell(0);
            rig.level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
            ItemStack pick = new ItemStack(picks.get(i));
            pick.set(DataComponents.UNBREAKABLE, Unit.INSTANCE);
            hold(rig.player(), pick);
            expectProgress(helper, rig, pos, BREAK_PROGRESS, picks.get(i) + " unbreakable");
            if (HandBreakCost.grind(rig.player(), rig.level, pos, Direction.UP, 10) != 11
                    || !rig.player().gameMode.destroyBlock(pos)
                    || !rig.level.getBlockState(pos).isAir()) {
                throw failure(helper, "an unbreakable %s did not break the block", picks.get(i));
            }
            if (rig.player().getMainHandItem() != pick || pick.getDamageValue() != 0) {
                throw failure(helper, "an unbreakable %s was worn or destroyed: %s", picks.get(i), pick);
            }
        }
        helper.succeed();
    }

    /**
     * The mining count starts again on a block that is not pod-only, and an item that is no tool leaves it where it
     * was.
     */
    @GameTest
    public void theCountResetsOnAnOrdinaryBlockAndWaitsForATool(GameTestHelper helper) {
        Rig rig = rig(helper, 2, X_RESET, "hb-reset");
        BlockPos podOnly = rig.cell(0);
        BlockPos ordinary = rig.cell(1);
        rig.level.setBlock(podOnly, Blocks.STONE.defaultBlockState(), 3);
        rig.level.setBlock(ordinary, Blocks.DIRT.defaultBlockState(), 3);
        hold(rig.player(), new ItemStack(Items.NETHERITE_PICKAXE));
        int onOrdinary = HandBreakCost.grind(rig.player(), rig.level, ordinary, Direction.UP, 77);
        if (onOrdinary != 0) {
            throw failure(helper, "the count after an ordinary block is %d, expected 0", onOrdinary);
        }
        hold(rig.player(), new ItemStack(Items.STICK));
        int withStick = HandBreakCost.grind(rig.player(), rig.level, podOnly, Direction.UP, 77);
        if (withStick != 77) {
            throw failure(helper, "the count with an item that is no tool is %d, expected 77", withStick);
        }
        hold(rig.player(), new ItemStack(Items.NETHERITE_PICKAXE));
        int withNetherite = HandBreakCost.grind(rig.player(), rig.level, podOnly, Direction.UP, 77);
        if (withNetherite != 78) {
            throw failure(helper, "the count with a tool is %d, expected 78", withNetherite);
        }
        helper.succeed();
    }

    private static String hintOf(BlockState state, ServerLevel level, int layer, ServerPlayer viewer) {
        // The layer is read from the position's height, so the block must sit inside it.
        return BlockReadouts.SHARED
                .readout(state, level, TestLayers.pos(layer, X_READOUT, 40, 0), viewer)
                .flatMap(readout -> readout.hint())
                .map(hint -> hint.getContents() instanceof TranslatableContents translatable
                        ? translatable.getKey()
                        : hint.getString())
                .orElse("(none)");
    }

    /**
     * (A) The crosshair readout says what a brute-forced block would cost the viewer's tool: a durable tool burns out,
     * a lesser one is destroyed, and a bare hand is only told the block is pod-only.
     */
    @GameTest
    public void theReadoutSaysWhatABruteForcedBlockWouldCostTheHeldTool(GameTestHelper helper) {
        ServerLevel one = TestLayers.level(helper, 1);
        BlockState crust = LayerBlocks.BREACH_CRUST.defaultBlockState();
        BlockState company = HazardBlocks.COMPANY_ROCK.defaultBlockState();
        ServerPlayer viewer = rig(helper, 1, X_READOUT, "hb-readout").player();
        String plain = "hint.deepcharter.pod_drill_only";
        String burns = "hint.deepcharter.pod_drill_only_burns";
        String destroys = "hint.deepcharter.pod_drill_only_destroys";
        hold(viewer, new ItemStack(Items.DIAMOND_PICKAXE));
        expectHint(helper, burns, hintOf(crust, one, 1, viewer), "crust with a diamond pick");
        expectHint(helper, burns, hintOf(company, one, 1, viewer), "Company rock with a diamond pick");
        for (Item pick : LESSER_PICKS) {
            hold(viewer, new ItemStack(pick));
            expectHint(helper, destroys, hintOf(crust, one, 1, viewer), "crust with " + pick);
            expectHint(helper, destroys, hintOf(company, one, 1, viewer), "Company rock with " + pick);
        }
        hold(viewer, ItemStack.EMPTY);
        expectHint(helper, plain, hintOf(crust, one, 1, viewer), "crust with a bare hand");
        expectHint(
                helper,
                "hint.deepcharter.company_rock",
                hintOf(company, one, 1, viewer),
                "Company rock with a bare hand: what does break it");
        ServerLevel two = TestLayers.level(helper, 2);
        BlockState stone = Blocks.STONE.defaultBlockState();
        hold(viewer, new ItemStack(Items.DIAMOND_PICKAXE));
        expectHint(helper, burns, hintOf(stone, two, 2, viewer), "deep rock in layer 2 with diamond");
        hold(viewer, new ItemStack(Items.NETHERITE_PICKAXE));
        expectHint(helper, burns, hintOf(stone, two, 2, viewer), "deep rock in layer 2 with netherite");
        hold(viewer, new ItemStack(Items.IRON_PICKAXE));
        expectHint(helper, destroys, hintOf(stone, two, 2, viewer), "deep rock in layer 2 with iron");
        viewer.setGameMode(GameType.CREATIVE);
        expectHint(helper, "(none)", hintOf(crust, one, 1, viewer), "crust read by a creative player");
        helper.succeed();
    }

    private static void expectHint(GameTestHelper helper, String expected, String actual, String what) {
        if (!expected.equals(actual)) {
            throw failure(helper, "%s: hint %s, expected %s", what, actual, expected);
        }
    }
}
