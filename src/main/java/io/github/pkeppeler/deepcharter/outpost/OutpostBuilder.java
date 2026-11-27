package io.github.pkeppeler.deepcharter.outpost;

import io.github.pkeppeler.deepcharter.charter.Charter;
import io.github.pkeppeler.deepcharter.charter.CharterRefusal;
import io.github.pkeppeler.deepcharter.charter.Charters;
import io.github.pkeppeler.deepcharter.colony.ColonyKit;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerChain.Band;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.layer.LightSync;
import io.github.pkeppeler.deepcharter.layer.RoomSeal;
import io.github.pkeppeler.deepcharter.terminal.TerminalBlock;
import io.github.pkeppeler.deepcharter.terminal.TerminalTypes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Builds the outpost of a charter at the floor of layer 1 (the band at the clicked block's Y, in a campaign world), the start of layer 2: a lit steel hall with the colony's fuel pump and ore processor
 * (the pump and the sell terminal of SPEC section 11) against its north wall. It is cut into the rock round the block that the kit is used on,
 * which is the floor of the hall.
 *
 * <p>The hall is {@value #RADIUS_BLOCKS} blocks out each way from that block and {@value #HEIGHT} blocks high inside, in the Company's kit
 * ({@link ColonyKit}): plated walls and ceiling, a concrete floor and corner posts. The middle of the floor is left as it was (the breach
 * crust when the kit is used on the crust top, rock when it is used above it, up to the floor band), so a pod can drill down to layer 2 from the hall, with a hazard band round it; and the ceiling above is left as it was too, so a shaft that
 * ends over the hall stays open and a pod can drop in. A pod that climbs out of layer 2 arrives in the hall (the crossing keeps the column), where
 * the pump fills its tank and the terminal buys its ore, at {@link OutpostRates}.
 *
 * <p>The shell is sealed against lava and gas before the cut ({@link RoomSeal}); a block entity or anything but a loose item or orb (and the builder) in the hall's volume (a mob, another player, a pod, or
 * the items of a player on another charter: the refusal names the first), or another outpost within two halls' reach, refuses the build, and
 * every check is made before the first block, so a refusal keeps the kit and changes nothing. Loose items and XP orbs in the volume do not refuse it
 * (they are swept to the builder). The outpost is recorded in {@link OutpostData}.
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
    /** How many cells further out than the wall the overflow looks for a place to lie. */
    private static final int SEARCH = 4;
    /** Blocks set tell the clients and wake nothing, as a layer carve does. */
    private static final int FLAGS = Block.UPDATE_CLIENTS;

    private OutpostBuilder() {}

    /** Builds the outpost on the top of {@code clicked}, or returns why not. Checks everything before it changes anything. */
    public static Optional<Component> build(ServerPlayer player, ServerLevel level, BlockPos clicked, Direction face) {
        Optional<Band> band = LayerChain.layerAt(level, clicked.getY()).filter(found -> found.layer() == LAYER);
        if (band.isEmpty()) {
            return refusal("not_here");
        }
        if (face != Direction.UP) {
            return refusal("not_on_top");
        }
        int crustTop = band.get().minY() + LayerTuning.DEFAULT.crustThickness() - 1;
        if (clicked.getY() < crustTop || clicked.getY() > crustTop + OutpostTuning.DEFAULT.floorBand()) {
            return Optional.of(
                    Component.translatable("message.deepcharter.outpost.not_floor", OutpostTuning.DEFAULT.floorBand()));
        }
        MinecraftServer server = level.getServer();
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
        AABB shell = shellOf(clicked);
        // entity-query: the hall is cut with whatever stands in it, so anything but a sweepable item or orb refuses
        // the kit
        List<Entity> inShell = level.getEntities(player, shell);
        for (Entity entity : inShell) {
            Optional<Component> blocked = blockedBy(server, charter.get(), entity);
            if (blocked.isPresent()) {
                return blocked;
            }
        }
        for (BlockPos pos : BlockPos.betweenClosed(shellMin, shellMax)) {
            if (level.getBlockEntity(pos) != null) {
                return refusal("blocked");
            }
        }
        List<ItemStack> overflow = sweep(player, level, inShell);
        cut(level, clicked);
        placeOverflow(player, level, data, clicked, overflow);
        data.add(new OutpostData.Outpost(charter.get().id(), LAYER, level.dimension(), clicked.immutable()));
        return Optional.empty();
    }

    /** Why {@code entity} refuses the kit of {@code builders}, or empty if it is swept. */
    private static Optional<Component> blockedBy(MinecraftServer server, Charter builders, Entity entity) {
        if (entity instanceof ExperienceOrb) {
            return Optional.empty();
        }
        if (entity instanceof ItemEntity item) {
            // Sweeping another crew's death pile into the builder's pockets would be looting it.
            if (item.getOwner() instanceof ServerPlayer owner
                    && Charters.readableCharterOf(server, owner.getUUID())
                            .map(Charter::id)
                            .filter(builders.id()::equals)
                            .isEmpty()) {
                return Optional.of(
                        Component.translatable("message.deepcharter.outpost.occupied_items", owner.getName()));
            }
            return Optional.empty();
        }
        return Optional.of(Component.translatable("message.deepcharter.outpost.occupied", entity.getName()));
    }

    /**
     * Moves the hall's loose items and orbs to the builder before the cut, so the cut never entombs them, and returns what the inventory
     * did not take. An orb is touched once for each orb it merged, because only the touch pays and each pays its own experience. The
     * touches are bounded by that count, so an orb that holds none (26.3 loads a {@code Count} of 0 or less as 1, so this is a guard) is
     * discarded unpaid, never touched forever.
     */
    private static List<ItemStack> sweep(ServerPlayer player, ServerLevel level, List<Entity> inShell) {
        List<ItemStack> overflow = new ArrayList<>();
        for (Entity entity : inShell) {
            if (entity instanceof ItemEntity item) {
                ItemStack stack = item.getItem().copy();
                item.discard();
                player.getInventory().add(stack);
                if (!stack.isEmpty()) {
                    overflow.add(stack);
                }
            } else if (entity instanceof ExperienceOrb orb) {
                for (int touches = mergedCount(level, orb); touches > 0 && !orb.isRemoved(); touches--) {
                    player.takeXpDelay = 0;
                    orb.playerTouch(player);
                }
                orb.discard();
            }
        }
        return overflow;
    }

    /** How many orbs {@code orb} holds: its {@code Count}, which has no getter. */
    private static int mergedCount(ServerLevel level, ExperienceOrb orb) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess());
        orb.saveWithoutId(output);
        return output.buildResult().getIntOr("Count", 0);
    }

    /** Drops the overflow after the cut, each stack where {@link #overflowSpot} says. */
    private static void placeOverflow(
            ServerPlayer player, ServerLevel level, OutpostData data, BlockPos floor, List<ItemStack> overflow) {
        if (overflow.isEmpty()) {
            return;
        }
        Vec3 spot = overflowSpot(player, level, data, floor);
        for (ItemStack stack : overflow) {
            ItemEntity entity = new ItemEntity(level, spot.x, spot.y, spot.z, stack);
            entity.setDeltaMovement(Vec3.ZERO);
            level.addFreshEntity(entity);
        }
    }

    /**
     * Where what does not fit lies, after the cut, and never by setting a block: at the builder if the builder stands outside the shell. Else the
     * first of: the cell just outside the wall on the builder's side if it is air; an air cell with a sturdy top under it, up to
     * {@value #SEARCH} cells further out on that line and in no other outpost's hall; a cell inside the new hall that the cut leaves as air
     * (not entombed, and the sealed hall is not lootable by others).
     */
    private static Vec3 overflowSpot(ServerPlayer player, ServerLevel level, OutpostData data, BlockPos floor) {
        if (!shellOf(floor).intersects(player.getBoundingBox())) {
            return player.position();
        }
        int dx = player.getBlockX() - floor.getX();
        int dz = player.getBlockZ() - floor.getZ();
        int out = EDGE + 1;
        int x = Mth.clamp(dx, -EDGE, EDGE);
        int z = Mth.clamp(dz, -EDGE, EDGE);
        Direction away;
        if (Math.abs(dx) >= Math.abs(dz)) {
            x = dx >= 0 ? out : -out;
            away = dx >= 0 ? Direction.EAST : Direction.WEST;
        } else {
            z = dz >= 0 ? out : -out;
            away = dz >= 0 ? Direction.SOUTH : Direction.NORTH;
        }
        int y = Mth.clamp(player.getBlockY(), floor.getY() + 1, floor.getY() + HEIGHT);
        BlockPos spot = floor.offset(x, y - floor.getY(), z);
        if (level.getBlockState(spot).isAir()) {
            return Vec3.atBottomCenterOf(spot);
        }
        for (int step = 1; step <= SEARCH; step++) {
            BlockPos cell = spot.relative(away, step);
            BlockPos below = cell.below();
            if (level.getBlockState(cell).isAir()
                    && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
                    && data.containing(LAYER, cell).isEmpty()) {
                return Vec3.atBottomCenterOf(cell);
            }
        }
        return Vec3.atBottomCenterOf(floor.offset(HATCH, 1, HATCH));
    }

    private static AABB shellOf(BlockPos floor) {
        return AABB.encapsulatingFullBlocks(floor.offset(-EDGE, 0, -EDGE), floor.offset(EDGE, HEIGHT + 1, EDGE));
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
        placeTerminal(level, TerminalTypes.FUEL_PUMP.block(), north.offset(-2, 1, 0));
        placeTerminal(level, TerminalTypes.ORE_PROCESSOR.block(), north.offset(2, 1, 0));
        place(level, ColonyKit.WALL_LAMP, north.offset(-2, 3, 0), Direction.SOUTH);
        place(level, ColonyKit.WALL_LAMP, north.offset(2, 3, 0), Direction.SOUTH);
        place(level, ColonyKit.WALL_LAMP, north.offset(0, 4, 0), Direction.SOUTH);
        place(level, ColonyKit.WALL_LAMP, floor.offset(0, 4, RADIUS_BLOCKS), Direction.NORTH);
        place(level, ColonyKit.WALL_LAMP, floor.offset(-RADIUS_BLOCKS, 4, 0), Direction.EAST);
        place(level, ColonyKit.WALL_LAMP, floor.offset(RADIUS_BLOCKS, 4, 0), Direction.WEST);
    }

    private static void placeTerminal(ServerLevel level, Block block, BlockPos pos) {
        TerminalBlock.place(
                level,
                pos,
                block.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH),
                FLAGS);
    }

    private static void place(ServerLevel level, Block block, BlockPos pos, Direction facing) {
        level.setBlock(pos, block.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, facing), FLAGS);
    }
}
