package io.github.pkeppeler.deepcharter.outpost;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.colony.ColonyKit;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.layer.LightSync;
import io.github.pkeppeler.deepcharter.layer.RoomSeal;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;

/**
 * Builds the outpost of a charter at the floor of layer 1, the start of layer 2: a lit steel hall with the colony's fuel pump and ore processor
 * (the pump and the sell terminal of SPEC section 11) against its north wall. It is cut into the rock round the block that the kit is used on,
 * which is the floor of the hall.
 *
 * <p>The hall is {@value #RADIUS_BLOCKS} blocks out each way from that block and {@value #HEIGHT} blocks high inside, in the Company's kit
 * ({@link ColonyKit}): plated walls and ceiling, a concrete floor and corner posts. The middle of the floor is left as it was (the breach
 * crust when the kit is used on the crust top, rock when it is used above it, up to the floor band), so a pod can drill down to layer 2 from the hall, with a hazard band round it; and the ceiling above is left as it was too, so a shaft that
 * ends over the hall stays open and a pod can drop in. A pod that climbs out of layer 2 arrives in the hall (the crossing keeps the column), where
 * the pump fills its tank and the terminal buys its ore, at {@link OutpostRates}.
 *
 * <p>The shell is sealed against lava and gas before the cut ({@link RoomSeal}); a block entity or any entity but the builder in the hall's volume, or another outpost within two
 * halls' reach, refuses the build, and every check is made before the first block, so a refusal keeps the kit and changes nothing. The outpost is recorded in {@link OutpostData}.
 */
public final class OutpostBuilder {
    static final int RADIUS_BLOCKS = 4;
    static final int HEIGHT = 5;
    /** The shell's distance from the middle: the walls stand this far out. */
    static final int EDGE = RADIUS_BLOCKS + 1;
    /** The breach under the floor stays open this many blocks out from the middle (a 3 by 3 pit). */
    private static final int PIT = 1;
    /** The ceiling stays as it was this many blocks out from the middle (5 by 5), so any shaft up to 3 wide that the click lies in stays open. */
    private static final int HATCH = 2;
    /** The layer where the kit works: the floor of the first layer is the start of the second. */
    private static final int LAYER = 1;
    /** Blocks set tell the clients and wake nothing, as a layer carve does. */
    private static final int FLAGS = Block.UPDATE_CLIENTS;

    private OutpostBuilder() {}

    /** Builds the outpost on the top of {@code clicked}, or returns why not. Checks everything before it changes anything. */
    public static Optional<Component> build(ServerPlayer player, ServerLevel level, BlockPos clicked, Direction face) {
        OptionalInt layer = OutpostRates.layerOf(level);
        if (layer.isEmpty() || layer.getAsInt() != LAYER) {
            return refusal("not_here");
        }
        if (face != Direction.UP) {
            return refusal("not_on_top");
        }
        int crustTop = level.getMinY() + LayerTuning.DEFAULT.crustThickness() - 1;
        if (clicked.getY() < crustTop || clicked.getY() > crustTop + OutpostTuning.DEFAULT.floorBand()) {
            return Optional.of(
                    Component.translatable("message.deepcharter.outpost.not_floor", OutpostTuning.DEFAULT.floorBand()));
        }
        MinecraftServer server = level.getServer();
        if (!Charters.isReadable(server)) {
            return Optional.of(CharterRefusal.NOT_ON_A_CHARTER.message());
        }
        Optional<Charter> charter = Charters.readableCharterOf(server, player.getUUID());
        if (charter.isEmpty()) {
            return Optional.of(CharterRefusal.NOT_ON_A_CHARTER.message());
        }
        OutpostData data = OutpostData.get(server);
        if (!data.isReadable()) {
            return refusal("unreadable");
        }
        if (data.of(charter.get().id(), LAYER).isPresent()) {
            return refusal("has_one");
        }
        if (data.overlapping(LAYER, clicked).isPresent()) {
            return refusal("overlaps");
        }
        BlockPos shellMin = clicked.offset(-EDGE, 0, -EDGE);
        BlockPos shellMax = clicked.offset(EDGE, HEIGHT + 1, EDGE);
        if (level.isOutsideBuildHeight(shellMax)) {
            return refusal("no_room_above");
        }
        AABB shell = AABB.encapsulatingFullBlocks(shellMin, shellMax);
        // entity-query: the hall is cut with whatever stands in it, so any entity but the builder refuses the kit
        if (!level.getEntities(player, shell).isEmpty()) {
            return refusal("occupied");
        }
        for (BlockPos pos : BlockPos.betweenClosed(shellMin, shellMax)) {
            if (level.getBlockEntity(pos) != null) {
                return refusal("blocked");
            }
        }
        cut(level, clicked);
        data.add(new OutpostData.Outpost(charter.get().id(), LAYER, clicked.immutable()));
        return Optional.empty();
    }

    private static Optional<Component> refusal(String key) {
        return Optional.of(Component.translatable("message.deepcharter.outpost." + key));
    }

    private static void cut(ServerLevel level, BlockPos floor) {
        BlockPos interiorMin = floor.offset(-RADIUS_BLOCKS, 1, -RADIUS_BLOCKS);
        BlockPos interiorMax = floor.offset(RADIUS_BLOCKS, HEIGHT, RADIUS_BLOCKS);
        // The shell first: the cut tells its neighbours, and lava among them would flow in.
        RoomSeal.seal(level, interiorMin, interiorMax);
        BlockState air = Blocks.AIR.defaultBlockState();
        for (BlockPos pos : BlockPos.betweenClosed(interiorMin, interiorMax)) {
            level.setBlock(pos, air, FLAGS);
        }
        for (int dx = -EDGE; dx <= EDGE; dx++) {
            for (int dz = -EDGE; dz <= EDGE; dz++) {
                int out = Math.max(Math.abs(dx), Math.abs(dz));
                if (out > PIT) {
                    Block floorBlock = out == HATCH ? ColonyKit.HAZARD_BAND : ColonyKit.CONCRETE_FOOTING;
                    level.setBlock(floor.offset(dx, 0, dz), floorBlock.defaultBlockState(), FLAGS);
                }
                if (out > HATCH) {
                    level.setBlock(
                            floor.offset(dx, HEIGHT + 1, dz), ColonyKit.RIVETED_PLATE.defaultBlockState(), FLAGS);
                }
                if (out == EDGE) {
                    for (int dy = 1; dy <= HEIGHT; dy++) {
                        level.setBlock(floor.offset(dx, dy, dz), ColonyKit.RIVETED_PLATE.defaultBlockState(), FLAGS);
                    }
                }
            }
        }
        furnish(level, floor);
        LightSync.holdSendUntilLit(level, interiorMin.offset(-1, -1, -1), interiorMax.offset(1, 1, 1));
    }

    /** The posts, the lamps and the two terminals, which stand against the north wall facing into the hall. */
    private static void furnish(ServerLevel level, BlockPos floor) {
        for (int dx : new int[] {-RADIUS_BLOCKS, RADIUS_BLOCKS}) {
            for (int dz : new int[] {-RADIUS_BLOCKS, RADIUS_BLOCKS}) {
                for (int dy = 1; dy <= HEIGHT; dy++) {
                    level.setBlock(floor.offset(dx, dy, dz), ColonyKit.STEEL_BEAM.defaultBlockState(), FLAGS);
                }
            }
        }
        BlockPos north = floor.offset(0, 0, -RADIUS_BLOCKS);
        place(level, TerminalTypes.FUEL_PUMP.block(), north.offset(-2, 1, 0), Direction.SOUTH);
        place(level, TerminalTypes.ORE_PROCESSOR.block(), north.offset(2, 1, 0), Direction.SOUTH);
        place(level, ColonyKit.WALL_LAMP, north.offset(-2, 3, 0), Direction.SOUTH);
        place(level, ColonyKit.WALL_LAMP, north.offset(2, 3, 0), Direction.SOUTH);
        place(level, ColonyKit.WALL_LAMP, north.offset(0, 4, 0), Direction.SOUTH);
        place(level, ColonyKit.WALL_LAMP, floor.offset(0, 4, RADIUS_BLOCKS), Direction.NORTH);
        place(level, ColonyKit.WALL_LAMP, floor.offset(-RADIUS_BLOCKS, 4, 0), Direction.EAST);
        place(level, ColonyKit.WALL_LAMP, floor.offset(RADIUS_BLOCKS, 4, 0), Direction.WEST);
    }

    private static void place(ServerLevel level, Block block, BlockPos pos, Direction facing) {
        level.setBlock(pos, block.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing), FLAGS);
    }
}
