package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.terminal.RepairState;
import io.github.pkeppeler.deepcharter.terminal.TerminalActivity;
import io.github.pkeppeler.deepcharter.terminal.TerminalBlock;
import io.github.pkeppeler.deepcharter.terminal.TerminalBlockEntity;
import io.github.pkeppeler.deepcharter.terminal.TerminalEvents;
import io.github.pkeppeler.deepcharter.terminal.TerminalType;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.WorldData;
import io.github.pkeppeler.deepcharter.texture.TextureProperties;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Server GameTests for #857: a terminal is two blocks high, like a door. A player places both halves or neither, breaking either
 * half in creative takes both and drops nothing, the block entity lives in the lower half, using either half opens the terminal,
 * and the lit screen follows the type's repair on both halves.
 */
public class TerminalHalvesTest {
    private static final List<String> OPENED = new ArrayList<>();

    static {
        TerminalEvents.OPENED.register((server, type, player, pos) ->
                OPENED.add(type.id() + "/" + player.getGameProfile().name()));
    }

    private static MockPlayer creative(GameTestHelper helper, String name) {
        MockPlayer mock = MockPlayers.join(helper, name);
        mock.player().setGameMode(GameType.CREATIVE);
        return mock;
    }

    private static void standBy(GameTestHelper helper, MockPlayer mock, BlockPos ground) {
        Vec3 centre = Vec3.atCenterOf(helper.absolutePos(ground.above()));
        mock.teleportTo(
                helper.getLevel(),
                new Vec3(centre.x + 2, centre.y - mock.player().getEyeHeight(), centre.z - 2),
                0,
                0);
    }

    private static BlockState half(GameTestHelper helper, BlockPos pos) {
        return helper.getLevel().getBlockState(helper.absolutePos(pos));
    }

    private static void placeBoth(GameTestHelper helper, TerminalType type, BlockPos relative) {
        TerminalBlock.place(
                helper.getLevel(),
                helper.absolutePos(relative),
                type.block().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH),
                3);
    }

    @GameTest
    public void aPlayerPlacesBothHalvesFacingTheSameWay(GameTestHelper helper) {
        MockPlayer mock = creative(helper, "halves-placer");
        try {
            BlockPos ground = new BlockPos(1, -1, 1);
            standBy(helper, mock, ground);
            ItemStack item = new ItemStack(TerminalTypes.FUEL_PUMP.block().asItem());
            mock.player().setItemInHand(InteractionHand.MAIN_HAND, item);
            helper.placeAt(mock.player(), item, ground, Direction.UP);
            BlockState lower = half(helper, ground.above());
            BlockState upper = half(helper, ground.above(2));
            if (!lower.is(TerminalTypes.FUEL_PUMP.block())
                    || lower.getValue(TerminalBlock.HALF) != DoubleBlockHalf.LOWER) {
                throw helper.assertionException("the lower half should be placed first, found %s", lower);
            }
            if (!upper.is(TerminalTypes.FUEL_PUMP.block())
                    || upper.getValue(TerminalBlock.HALF) != DoubleBlockHalf.UPPER) {
                throw helper.assertionException("the upper half should be placed over it, found %s", upper);
            }
            if (lower.getValue(BlockStateProperties.HORIZONTAL_FACING)
                            != upper.getValue(BlockStateProperties.HORIZONTAL_FACING)
                    || !lower.getValue(TextureProperties.ACTIVE).equals(upper.getValue(TextureProperties.ACTIVE))) {
                throw helper.assertionException("both halves should face and glow alike: %s and %s", lower, upper);
            }
        } finally {
            mock.leave();
        }
        helper.succeed();
    }

    @GameTest
    public void aTerminalIsNotPlacedWithoutRoomAboveIt(GameTestHelper helper) {
        MockPlayer mock = creative(helper, "halves-no-room");
        try {
            BlockPos ground = new BlockPos(1, -1, 1);
            standBy(helper, mock, ground);
            helper.setBlock(ground.above(2), Blocks.STONE.defaultBlockState());
            ItemStack item = new ItemStack(TerminalTypes.FUEL_PUMP.block().asItem());
            mock.player().setItemInHand(InteractionHand.MAIN_HAND, item);
            helper.placeAt(mock.player(), item, ground, Direction.UP);
            if (!half(helper, ground.above()).isAir()) {
                throw helper.assertionException(
                        "nothing should be placed with stone over it, found %s", half(helper, ground.above()));
            }
            if (!half(helper, ground.above(2)).is(Blocks.STONE)) {
                throw helper.assertionException("the stone above should be left alone");
            }
        } finally {
            mock.leave();
        }
        helper.succeed();
    }

    @GameTest
    public void breakingEitherHalfInCreativeTakesBothAndDropsNothing(GameTestHelper helper) {
        MockPlayer mock = creative(helper, "halves-breaker");
        try {
            for (int which = 1; which <= 2; which++) {
                BlockPos lower = new BlockPos(1 + 2 * (which - 1), 1, 1);
                placeBoth(helper, TerminalTypes.ORE_PROCESSOR, lower);
                mock.player().gameMode.destroyBlock(helper.absolutePos(lower.above(which - 1)));
                if (!half(helper, lower).isAir() || !half(helper, lower.above()).isAir()) {
                    throw helper.assertionException(
                            "breaking the %s half should take both, found %s over %s",
                            which == 1 ? "lower" : "upper", half(helper, lower.above()), half(helper, lower));
                }
                if (helper.getLevel().getBlockEntity(helper.absolutePos(lower)) instanceof TerminalBlockEntity) {
                    throw helper.assertionException("the block entity should go with the lower half");
                }
            }
            AABB around = new AABB(
                    Vec3.atCenterOf(helper.absolutePos(new BlockPos(0, 0, 0))),
                    Vec3.atCenterOf(helper.absolutePos(new BlockPos(6, 4, 3))));
            if (!helper.getLevel().getEntitiesOfClass(ItemEntity.class, around).isEmpty()) {
                throw helper.assertionException("breaking a terminal should drop nothing");
            }
        } finally {
            mock.leave();
        }
        helper.succeed();
    }

    @GameTest
    public void onlyTheLowerHalfHoldsTheBlockEntity(GameTestHelper helper) {
        BlockPos lower = new BlockPos(1, 1, 1);
        placeBoth(helper, TerminalTypes.UPGRADE_TERMINAL, lower);
        if (!(helper.getLevel().getBlockEntity(helper.absolutePos(lower)) instanceof TerminalBlockEntity entity)
                || entity.type() != TerminalTypes.UPGRADE_TERMINAL) {
            throw helper.assertionException("the lower half should hold the terminal's block entity");
        }
        if (helper.getLevel().getBlockEntity(helper.absolutePos(lower.above())) != null) {
            throw helper.assertionException("the upper half should hold none");
        }
        helper.succeed();
    }

    @GameTest
    public void usingEitherHalfOpensTheTerminal(GameTestHelper helper) {
        MockPlayer mock = MockPlayers.join(helper, "halves-user");
        try {
            BlockPos lower = new BlockPos(1, 1, 1);
            placeBoth(helper, TerminalTypes.FUEL_PUMP, lower);
            BlockPos at = helper.absolutePos(lower);
            mock.teleportTo(helper.getLevel(), Vec3.atBottomCenterOf(at).add(0, 0, 2), 0, 0);
            io.github.pkeppeler.deepcharter.charter.Charters.found(
                    helper.getLevel().getServer(), mock.player().getUUID(), "Halves Co " + at.getX() + at.getZ());
            for (int up = 0; up <= 1; up++) {
                OPENED.clear();
                BlockPos used = at.above(up);
                helper.getLevel()
                        .getBlockState(used)
                        .useWithoutItem(
                                helper.getLevel(),
                                mock.player(),
                                new BlockHitResult(Vec3.atCenterOf(used), Direction.SOUTH, used, false));
                if (!OPENED.equals(List.of(TerminalTypes.FUEL_PUMP.id() + "/halves-user"))) {
                    throw helper.assertionException(
                            "using the %s half should open the terminal, opened: %s",
                            up == 0 ? "lower" : "upper", OPENED);
                }
            }
        } finally {
            mock.leave();
        }
        helper.succeed();
    }

    @GameTest
    public void bothHalvesLightWhenTheirTypeIsRepaired(GameTestHelper helper) {
        BlockPos lower = new BlockPos(1, 1, 1);
        placeBoth(helper, TerminalTypes.FUEL_PUMP, lower);
        WorldData.with(helper.getLevel().getServer(), RepairState.TYPE, new RepairState(), () -> {
            BlockPos at = helper.absolutePos(lower);
            TerminalActivity.sync(helper.getLevel(), at);
            for (int up = 0; up <= 1; up++) {
                if (helper.getLevel().getBlockState(at.above(up)).getValue(TextureProperties.ACTIVE)) {
                    throw helper.assertionException(
                            "an unrepaired pump should be dark in its %s half", up == 0 ? "lower" : "upper");
                }
            }
            for (var part : TerminalTypes.FUEL_PUMP.parts()) {
                RepairState.get(helper.getLevel().getServer()).insert(TerminalTypes.FUEL_PUMP, part);
            }
            TerminalActivity.sync(helper.getLevel(), at.above());
            for (int up = 0; up <= 1; up++) {
                if (!helper.getLevel().getBlockState(at.above(up)).getValue(TextureProperties.ACTIVE)) {
                    throw helper.assertionException(
                            "a repaired pump should be lit in its %s half", up == 0 ? "lower" : "upper");
                }
            }
        });
        helper.succeed();
    }

    /** A terminal saved as one block (before it was two high), or set by a command, gets its monitor when its chunk loads. */
    @GameTest
    public void aLoneLowerHalfGetsItsUpperHalfWhenItsChunkLoads(GameTestHelper helper) {
        BlockPos lower = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockState alone = TerminalTypes.REPAIR_STATION
                .block()
                .defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
        helper.getLevel().setBlock(lower, alone, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        if (!helper.getLevel().getBlockState(lower.above()).isAir()) {
            throw helper.assertionException("the setup should leave air over the lone lower half");
        }
        TerminalActivity.sync(helper.getLevel(), helper.getLevel().getChunkAt(lower));
        BlockState upper = helper.getLevel().getBlockState(lower.above());
        if (!upper.is(TerminalTypes.REPAIR_STATION.block())
                || upper.getValue(TerminalBlock.HALF) != DoubleBlockHalf.UPPER
                || upper.getValue(BlockStateProperties.HORIZONTAL_FACING) != Direction.EAST) {
            throw helper.assertionException("the chunk load should put the upper half over it, found %s", upper);
        }
        // A neighbour update from above no longer takes the terminal with it.
        helper.getLevel().setBlock(lower.above(), upper, Block.UPDATE_ALL);
        if (!helper.getLevel().getBlockState(lower).is(TerminalTypes.REPAIR_STATION.block())
                || !(helper.getLevel().getBlockEntity(lower) instanceof TerminalBlockEntity)) {
            throw helper.assertionException("the lower half and its block entity should survive");
        }
        helper.succeed();
    }
}
