package io.github.pkeppeler.deepcharter.test;

import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import io.github.pkeppeler.deepcharter.layer.CampaignBands;
import io.github.pkeppeler.deepcharter.layer.HandBreak;
import io.github.pkeppeler.deepcharter.layer.LayerBlocks;
import io.github.pkeppeler.deepcharter.ore.HazardBlocks;
import io.github.pkeppeler.deepcharter.test.support.FarChunks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import io.github.pkeppeler.deepcharter.test.support.TestTicks;
import java.util.List;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
import org.jspecify.annotations.Nullable;

/**
 * Server GameTests for brute-forcing pod-only blocks with a hand tool (#858, ADR 0069): the one predicate, the constant
 * 60 s break time, the tool each layer needs, the durability a block costs (Unbreaking cannot lower it), the pick that
 * shatters, no drops, and creative players left alone. The three kinds of pod-only block are the breach crust, Company
 * rock and {@code deep_rock} below layer 1. Every test has its own column.
 *
 * <p>A test that breaks a block in one call uses {@code gameMode.destroyBlock}, which pays the whole cost at once;
 * {@link #aFullMiningRunTakesTheBreakTicksAndPaysTheShare} mines for real, tick by tick.
 */
public class HandBreakTest {
    private static final float BREAK_PROGRESS = 1.0F / 1200;
    /** The share of a tool's maximum durability a block costs: layer 1, layer 2, layer 3 and deeper. */
    private static final double[] SHARES = {0.75, 0.9, 1.0};

    private static final int Z = 7700;
    private static final int X_PROGRESS = 7700;
    private static final int X_KINDS = 7800;
    private static final int X_UNBREAKING = 8000;
    private static final int X_NO_BITE = 8200;
    private static final int X_SHATTER = 8300;
    private static final int X_CREATIVE = 8400;
    private static final int X_LAYER_ONE_ROCK = 8600;
    private static final int X_TOLD = 8700;
    private static final int X_FULL = 8800;
    private static final int X_MINING_SHATTERS = 8900;
    private static final int X_CAMPAIGN = 21800;
    private static final int FULL_RUN_TICKS = 1200;

    /** A pod-only block of one kind in one layer, and the tool that bites it. */
    private record Kind(String name, int layer, BlockState state, Item bitingTool) {}

    private static List<Kind> kinds() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        return List.of(
                new Kind("crust in layer 1", 1, LayerBlocks.BREACH_CRUST.defaultBlockState(), Items.DIAMOND_PICKAXE),
                new Kind("crust in layer 2", 2, LayerBlocks.BREACH_CRUST.defaultBlockState(), Items.NETHERITE_PICKAXE),
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

    /** A tool that cannot bite a block, and the rig it is tried in. */
    private record Refusal(Rig rig, Item tool, BlockState state) {}

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

    /** What a block of {@code layer} should cost a tool of {@code maxDamage}, from the shares the design fixes. */
    private static int expectedCost(int layer, int maxDamage) {
        return (int) Math.round(SHARES[Math.min(layer, SHARES.length) - 1] * maxDamage);
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

    /** A bare number for a tool that bites and none for one that does not, whatever it is enchanted with or the player feels. */
    @GameTest
    public void aBitingToolMakesTheSameSlowProgressWhateverItsEnchantmentsAndEffects(GameTestHelper helper) {
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
        helper.succeed();
    }

    /** One predicate for all three kinds: each is governed, each is slow for the tool that bites and zero for the rest. */
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
            hold(rig.player(), new ItemStack(kind.bitingTool));
            expectProgress(helper, rig, pos, BREAK_PROGRESS, kind.name + " with " + kind.bitingTool);
            hold(rig.player(), ItemStack.EMPTY);
            expectProgress(helper, rig, pos, 0.0F, kind.name + " with a bare hand");
            hold(rig.player(), new ItemStack(Items.IRON_PICKAXE));
            expectProgress(helper, rig, pos, 0.0F, kind.name + " with an iron pick");
        }
        helper.succeed();
    }

    /** Layer 1 asks for a diamond pick or better, layer 2 for netherite only. */
    @GameTest
    public void aToolThatCannotBiteMakesNoProgressAndBreaksNothing(GameTestHelper helper) {
        Rig one = rig(helper, 1, X_NO_BITE, "hb-nobite-1");
        Rig two = rig(helper, 2, X_NO_BITE + 1, "hb-nobite-2");
        List<Refusal> cases = List.of(
                new Refusal(one, Items.IRON_PICKAXE, LayerBlocks.BREACH_CRUST.defaultBlockState()),
                new Refusal(one, Items.DIAMOND_AXE, HazardBlocks.COMPANY_ROCK.defaultBlockState()),
                new Refusal(two, Items.DIAMOND_PICKAXE, LayerBlocks.BREACH_CRUST.defaultBlockState()),
                new Refusal(two, Items.DIAMOND_PICKAXE, HazardBlocks.COMPANY_ROCK.defaultBlockState()),
                new Refusal(two, Items.DIAMOND_PICKAXE, Blocks.STONE.defaultBlockState()));
        for (Refusal refusal : cases) {
            Rig rig = refusal.rig;
            Item tool = refusal.tool;
            BlockState state = refusal.state;
            BlockPos pos = rig.cell(2);
            rig.level.setBlock(pos, state, 3);
            ItemStack held = new ItemStack(tool);
            hold(rig.player(), held);
            String what = tool + " against " + state.getBlock().getName().getString();
            expectProgress(helper, rig, pos, 0.0F, what);
            if (rig.player().gameMode.destroyBlock(pos)
                    || !rig.level.getBlockState(pos).equals(state)) {
                throw failure(helper, "%s broke the block", what);
            }
            if (held.getDamageValue() != 0) {
                throw failure(helper, "%s was damaged by a refused break", what);
            }
            InteractionResult swing = AttackBlockCallback.EVENT
                    .invoker()
                    .interact(rig.player(), rig.level, InteractionHand.MAIN_HAND, pos, Direction.UP);
            if (swing != InteractionResult.FAIL) {
                throw failure(helper, "%s: a swing gave %s, expected FAIL", what, swing);
            }
        }
        helper.succeed();
    }

    /** The block goes, drops nothing and costs its layer's share of the pick, however many Unbreaking levels it carries. */
    @GameTest
    public void eachKindCostsItsShareOfTheToolWhateverUnbreakingSays(GameTestHelper helper) {
        List<Kind> kinds = kinds();
        for (int i = 0; i < kinds.size(); i++) {
            Kind kind = kinds.get(i);
            Rig rig = rig(helper, kind.layer, X_UNBREAKING + 20 * i, "hb-cost-" + i);
            BlockPos pos = rig.cell(0);
            rig.level.setBlock(pos, kind.state, 3);
            ItemStack pick = enchanted(rig.level, new ItemStack(kind.bitingTool), Enchantments.UNBREAKING, 3);
            enchanted(rig.level, pick, Enchantments.MENDING, 1);
            hold(rig.player(), pick);
            int expected = expectedCost(kind.layer, pick.getMaxDamage());
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
                        pick.getMaxDamage());
            }
            if (dropsAround(rig.level, pos) != 0) {
                throw failure(helper, "%s dropped something", kind.name);
            }
        }
        helper.succeed();
    }

    /** Control for the no-drop checks: the same stone in layer 1 is an ordinary block, which does drop. */
    @GameTest
    public void deepRockInLayerOneStaysANormalBlockThatDrops(GameTestHelper helper) {
        Rig rig = rig(helper, 1, X_LAYER_ONE_ROCK, "hb-rock-1");
        BlockPos pos = rig.cell(0);
        BlockState stone = Blocks.STONE.defaultBlockState();
        rig.level.setBlock(pos, stone, 3);
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
    }

    /** A pick without the durability for a whole block is used up and the block stays. */
    @GameTest
    public void aPickWithTooLittleDurabilityShattersAndTheBlockStays(GameTestHelper helper) {
        Rig rig = rig(helper, 2, X_SHATTER, "hb-shatter");
        BlockPos pos = rig.cell(0);
        rig.level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        ItemStack worn = stack(Items.NETHERITE_PICKAXE, maxDamage(Items.NETHERITE_PICKAXE) - 100);
        hold(rig.player(), worn);
        if (rig.player().gameMode.destroyBlock(pos)) {
            throw failure(helper, "a pick with 100 points left broke a block that costs most of a pick");
        }
        if (!rig.level.getBlockState(pos).is(Blocks.STONE)) {
            throw failure(helper, "the block is gone though the pick shattered");
        }
        if (!rig.player().getMainHandItem().isEmpty()) {
            throw failure(helper, "the pick did not shatter: %s", rig.player().getMainHandItem());
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
        hold(rig.player(), stack(Items.NETHERITE_PICKAXE, max - expectedCost(2, max)));
        if (!rig.player().gameMode.destroyBlock(pos)
                || !rig.level.getBlockState(pos).isAir()) {
            throw failure(helper, "a pick with exactly the cost left did not break the block");
        }
        if (!rig.player().getMainHandItem().isEmpty()) {
            throw failure(helper, "the pick was not used up: %s", rig.player().getMainHandItem());
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

    /** The first swing prints one line, a tool that cannot bite gets the other line, and neither repeats. */
    @GameTest
    public void theFirstSwingSaysAPodDrillIsTheWayAndOnlyOnce(GameTestHelper helper) {
        Rig biting = rig(helper, 1, X_TOLD, "hb-told-bites");
        Rig clumsy = rig(helper, 1, X_TOLD + 1, "hb-told-clumsy");
        for (Rig rig : List.of(biting, clumsy)) {
            rig.level.setBlock(rig.cell(0), HazardBlocks.COMPANY_ROCK.defaultBlockState(), 3);
        }
        hold(biting.player(), new ItemStack(Items.DIAMOND_PICKAXE));
        hold(clumsy.player(), new ItemStack(Items.WOODEN_PICKAXE));
        for (int swing = 0; swing < 3; swing++) {
            for (Rig rig : List.of(biting, clumsy)) {
                AttackBlockCallback.EVENT
                        .invoker()
                        .interact(rig.player(), rig.level, InteractionHand.MAIN_HAND, rig.cell(0), Direction.UP);
            }
        }
        String firstTry = "deepcharter.layer.pod_only.first_try";
        String cannotBite = "deepcharter.layer.pod_only.cannot_bite";
        if (toldCount(biting.mock, firstTry) != 1 || toldCount(biting.mock, cannotBite) != 0) {
            throw failure(
                    helper,
                    "a tool that bites was told %s, expected the first-try line once",
                    biting.mock.chatMessages());
        }
        if (toldCount(clumsy.mock, cannotBite) != 1 || toldCount(clumsy.mock, firstTry) != 0) {
            throw failure(
                    helper,
                    "a tool that cannot bite was told %s, expected the cannot-bite line once",
                    clumsy.mock.chatMessages());
        }
        helper.succeed();
    }

    /**
     * A real mining run: START, then every tick the server's own, then STOP once the break time is up. The block holds until
     * then, goes without a drop, and the pick has paid the layer's share, with Unbreaking III, Efficiency V and Haste II on
     * it and the player. Layer 2's stone drops cobblestone when it is mined as an ordinary block.
     */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + FULL_RUN_TICKS + 200)
    public void aFullMiningRunTakesTheBreakTicksAndPaysTheShare(GameTestHelper helper) {
        Rig rig = rig(helper, 2, X_FULL, "hb-full");
        BlockPos pos = rig.cell(0);
        rig.level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        ItemStack pick = new ItemStack(Items.NETHERITE_PICKAXE);
        enchanted(rig.level, pick, Enchantments.UNBREAKING, 3);
        enchanted(rig.level, pick, Enchantments.EFFICIENCY, 5);
        hold(rig.player(), pick);
        rig.player().addEffect(new MobEffectInstance(MobEffects.HASTE, MobEffectInstance.INFINITE_DURATION, 1));
        int expected = expectedCost(2, pick.getMaxDamage());
        int[] startedAt = {-1};
        boolean[] stopped = {false};
        FarChunks.awaitEntityTicking(helper, rig.level, pos, () -> startedAt[0] = (int) helper.getTick());
        TestTicks.each(helper, () -> {
            if (startedAt[0] < 0) {
                return;
            }
            int mined = (int) helper.getTick() - startedAt[0];
            if (mined == 0) {
                rig.player()
                        .gameMode
                        .handleBlockBreakAction(
                                pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, Direction.UP, 319, 0);
                return;
            }
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

    /** Mining with a pick that cannot pay shatters it on the way, and a STOP at the end breaks nothing. */
    @GameTest(maxTicks = FarChunks.AWAIT_BUDGET_TICKS + 400)
    public void aPickThatRunsOutWhileMiningShattersAndTheBlockStays(GameTestHelper helper) {
        Rig rig = rig(helper, 2, X_MINING_SHATTERS, "hb-mining-shatters");
        BlockPos pos = rig.cell(0);
        rig.level.setBlock(pos, Blocks.STONE.defaultBlockState(), 3);
        hold(rig.player(), stack(Items.NETHERITE_PICKAXE, maxDamage(Items.NETHERITE_PICKAXE) - 100));
        int[] startedAt = {-1};
        FarChunks.awaitEntityTicking(helper, rig.level, pos, () -> startedAt[0] = (int) helper.getTick());
        TestTicks.each(helper, () -> {
            if (startedAt[0] < 0) {
                return;
            }
            int mined = (int) helper.getTick() - startedAt[0];
            if (mined == 0) {
                rig.player()
                        .gameMode
                        .handleBlockBreakAction(
                                pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, Direction.UP, 319, 0);
                return;
            }
            if (!rig.level.getBlockState(pos).is(Blocks.STONE)) {
                throw failure(helper, "the block went while the pick ran out");
            }
            if (rig.player().getMainHandItem().isEmpty()) {
                rig.player()
                        .gameMode
                        .handleBlockBreakAction(
                                pos, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, Direction.UP, 319, 1);
                if (!rig.level.getBlockState(pos).is(Blocks.STONE)) {
                    throw failure(helper, "a STOP with no pick left broke the block");
                }
                helper.succeed();
            } else if (mined > 300) {
                throw failure(helper, "the pick had not shattered after %d ticks of mining", mined);
            }
        });
    }

    /** Layer 3 and deeper cost a whole pick: the campaign world's unbuilt depth is the first of them. */
    @GameTest(dimension = "deepcharter:campaign_sample")
    public void deeperThanLayerTwoACrustCostsAWholeNetheritePick(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int y = CampaignBands.layer(CampaignBands.lastLayer()).minY() - 100;
        BlockPos pos = new BlockPos(X_CAMPAIGN, y, X_CAMPAIGN);
        MockPlayer mock = MockPlayers.join(
                helper.getLevel().getServer(), "hb-campaign", level, Vec3.atBottomCenterOf(pos.above(3)));
        ServerPlayer player = mock.player();
        player.setNoGravity(true);
        level.setBlock(pos, LayerBlocks.BREACH_CRUST.defaultBlockState(), 3);
        if (HandBreak.layerOf(level, pos) != 3) {
            throw failure(helper, "unbuilt depth counts as layer %d, expected 3", HandBreak.layerOf(level, pos));
        }
        player.setGameMode(GameType.SURVIVAL);
        hold(player, new ItemStack(Items.DIAMOND_PICKAXE));
        if (player.gameMode.destroyBlock(pos) || !level.getBlockState(pos).is(LayerBlocks.BREACH_CRUST)) {
            throw failure(helper, "a diamond pick broke the crust of layer 3");
        }
        hold(player, new ItemStack(Items.NETHERITE_PICKAXE));
        if (!player.gameMode.destroyBlock(pos) || !level.getBlockState(pos).isAir()) {
            throw failure(helper, "a fresh netherite pick could not break the crust of layer 3");
        }
        if (!player.getMainHandItem().isEmpty()) {
            throw failure(helper, "the block did not cost the whole pick: %s", player.getMainHandItem());
        }
        helper.succeed();
    }

    // assertionException(String, Object...) leaves the placeholders unfilled in the report.
    @FormatMethod
    private static RuntimeException failure(
            GameTestHelper helper, @FormatString String format, @Nullable Object... args) {
        return helper.assertionException(Component.literal(String.format(format, args)));
    }
}
