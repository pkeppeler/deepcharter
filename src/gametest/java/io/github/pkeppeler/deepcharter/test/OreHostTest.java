package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.ore.OreBlock;
import io.github.pkeppeler.deepcharter.ore.OreBlock.Host;
import io.github.pkeppeler.deepcharter.ore.OreRegistry;
import io.github.pkeppeler.deepcharter.ore.OreType;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.OreBlocks;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * #549, ADR 0055: an ore sits in the rock of its layer. The zone fill sets the host of the ore it places to the rock it
 * replaces, a placed ore item takes the rock of the layer it is placed in, mining it drops what it dropped, and a block
 * saved before the property existed loads with the default host.
 */
public class OreHostTest {
    private static final int FIRST_CHUNK = 440;
    private static final int SIDE = 3;
    /** The ore blocks the zone fill places across {@link #SIDE} x {@link #SIDE} chunks must be at least this many in each layer, or the check proves nothing. */
    private static final int AT_LEAST = 50;

    private static Host expectedHost(int layer) {
        return layer == 1 ? Host.PACKED_SHALE : Host.GREEN_SHALE;
    }

    private static ServerLevel layer(GameTestHelper helper, int layer) {
        ServerLevel level = helper.getLevel().getServer().getLevel(LayerChain.dimension(layer));
        if (level == null) {
            throw helper.assertionException("dimension %s did not load", LayerChain.dimension(layer));
        }
        return level;
    }

    @GameTest(maxTicks = 6000)
    public void generatedOreInEachLayerSitsInThatLayersRock(GameTestHelper helper) {
        for (int layer = 1; layer <= 2; layer++) {
            ServerLevel level = layer(helper, layer);
            Map<Host, Integer> found = new EnumMap<>(Host.class);
            for (int cx = FIRST_CHUNK; cx < FIRST_CHUNK + SIDE; cx++) {
                for (int cz = FIRST_CHUNK; cz < FIRST_CHUNK + SIDE; cz++) {
                    level.getChunk(cx, cz, ChunkStatus.FULL);
                    for (int x = cx << 4; x < (cx << 4) + 16; x++) {
                        for (int z = cz << 4; z < (cz << 4) + 16; z++) {
                            for (int y = level.getMinY(); y < level.getMinY() + level.getHeight(); y++) {
                                BlockState state = level.getBlockState(new BlockPos(x, y, z));
                                if (state.getBlock() instanceof OreBlock) {
                                    found.merge(state.getValue(OreBlock.HOST), 1, Integer::sum);
                                }
                            }
                        }
                    }
                }
            }
            int total = found.values().stream().mapToInt(Integer::intValue).sum();
            int right = found.getOrDefault(expectedHost(layer), 0);
            if (total < AT_LEAST || right != total) {
                throw helper.assertionException(
                        "layer_%s should hold at least %s ores, all in %s, it holds %s",
                        layer, AT_LEAST, expectedHost(layer), found);
            }
        }
        helper.succeed();
    }

    @GameTest
    public void anOreItemPlacedInALayerTakesThatLayersRock(GameTestHelper helper) {
        MockPlayer mock = MockPlayers.join(helper, "ore-host-place");
        for (int layer = 1; layer <= 2; layer++) {
            ServerLevel level = layer(helper, layer);
            BlockPos floor = new BlockPos(7400, 80, 3000 + layer * 8);
            mock.teleportTo(level, Vec3.atBottomCenterOf(floor.above(4)), 0, 0);
            mock.player().setGameMode(GameType.SURVIVAL);
            int last = floor.getX() + OreType.values().length;
            RoomCarver.carve(
                    level,
                    floor.getX(),
                    last,
                    floor.getY() - 2,
                    floor.getY(),
                    floor.getZ(),
                    floor.getZ(),
                    Blocks.STONE);
            RoomCarver.carve(
                    level,
                    floor.getX(),
                    last,
                    floor.getY() + 1,
                    floor.getY() + 3,
                    floor.getZ(),
                    floor.getZ(),
                    Blocks.AIR);
            for (OreType type : OreType.values()) {
                BlockPos at = floor.above().east(type.ordinal());
                ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getValue(type.blockId()));
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(at.below()), Direction.UP, at.below(), false);
                InteractionResult result = ((BlockItem) stack.getItem())
                        .place(new BlockPlaceContext(level, mock.player(), InteractionHand.MAIN_HAND, stack, hit));
                BlockState placed = level.getBlockState(at);
                if (!result.consumesAction()
                        || !placed.is(OreBlocks.of(type))
                        || placed.getValue(OreBlock.HOST) != expectedHost(layer)) {
                    throw helper.assertionException(
                            "%s placed in layer_%s should sit in %s, it is %s (%s)",
                            type.blockId(), layer, expectedHost(layer), placed, result);
                }
            }
        }
        helper.succeed();
    }

    @GameTest
    public void minedOreDropsItsItemWhateverItsRock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (OreType type : OreType.values()) {
            Block block = OreBlocks.of(type);
            for (Host host : Host.values()) {
                BlockState state = block.defaultBlockState().setValue(OreBlock.HOST, host);
                ResourceKey<LootTable> key = block.getLootTable().orElseThrow();
                LootTable table = level.getServer().reloadableRegistries().getLootTable(key);
                List<ItemStack> drops = table.getRandomItems(
                        new LootParams.Builder(level)
                                .withParameter(LootContextParams.ORIGIN, Vec3.ZERO)
                                .withParameter(LootContextParams.BLOCK_STATE, state)
                                .withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
                                .create(LootContextParamSets.BLOCK),
                        1L);
                if (drops.size() != 1 || !drops.getFirst().is(OreRegistry.item(type))) {
                    throw helper.assertionException("%s in %s should drop 1 %s, it drops %s", block, host, type, drops);
                }
            }
        }
        helper.succeed();
    }

    /** A world saved before the property existed names the block and no properties; it must still load, in the default rock. */
    @GameTest
    public void anOreSavedWithoutAHostLoadsAndOneWithAHostKeepsIt(GameTestHelper helper) {
        for (OreType type : OreType.values()) {
            // The state as an older save wrote it: the same compound with no host in it.
            Tag saved = BlockState.CODEC
                    .encodeStart(
                            NbtOps.INSTANCE,
                            OreBlocks.of(type).defaultBlockState().setValue(OreBlock.HOST, Host.GREEN_SHALE))
                    .getOrThrow();
            CompoundTag legacy = ((CompoundTag) saved).copy();
            legacy.keySet().removeIf(key -> !key.equals("Name") && !key.equals("id"));
            BlockState old = BlockState.CODEC.parse(NbtOps.INSTANCE, legacy).getOrThrow();
            if (!old.is(OreBlocks.of(type)) || old.getValue(OreBlock.HOST) != Host.PACKED_SHALE) {
                throw helper.assertionException(
                        "%s saved with no host should load in packed shale, it is %s", type, old);
            }
            BlockState green = OreBlocks.of(type).defaultBlockState().setValue(OreBlock.HOST, Host.GREEN_SHALE);
            BlockState back = BlockState.CODEC
                    .parse(
                            NbtOps.INSTANCE,
                            BlockState.CODEC.encodeStart(NbtOps.INSTANCE, green).getOrThrow())
                    .getOrThrow();
            if (back != green) {
                throw helper.assertionException("%s in green shale should survive a save, it came back %s", type, back);
            }
        }
        helper.succeed();
    }
}
