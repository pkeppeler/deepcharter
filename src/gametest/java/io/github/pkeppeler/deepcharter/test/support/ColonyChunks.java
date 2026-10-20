package io.github.pkeppeler.deepcharter.test.support;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonyKit;
import io.github.pkeppeler.deepcharter.colony.ColonyLayout;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.colony.ColonyTuning;
import io.github.pkeppeler.deepcharter.colony.FounderStatue;
import io.github.pkeppeler.deepcharter.colony.HostBody;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The chunks of the colony's pad, and a wait for them to tick. An entity in a chunk that does not tick is not found by a query (the
 * Host's displays are entities), and a rebuild takes away the displays of the first try only in chunks that tick.
 */
public final class ColonyChunks {
    private ColonyChunks() {}

    /** One position, at the pad's ground, in each chunk the pad touches. */
    public static List<BlockPos> of(ColonySite.Placed colony) {
        int half = ColonyTuning.DEFAULT.padSize() / 2;
        BlockPos centre = colony.center();
        Set<Long> seen = new HashSet<>();
        List<BlockPos> chunks = new ArrayList<>();
        for (int x = centre.getX() - half; x < centre.getX() + half + 16; x += 16) {
            for (int z = centre.getZ() - half; z < centre.getZ() + half + 16; z += 16) {
                int chunkX = Math.min(x, centre.getX() + half - 1) >> 4;
                int chunkZ = Math.min(z, centre.getZ() + half - 1) >> 4;
                if (seen.add(((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL))) {
                    chunks.add(new BlockPos(chunkX << 4, centre.getY(), chunkZ << 4));
                }
            }
        }
        return chunks;
    }

    /** Keeps the pad's chunks loaded and ticking for as long as the world lives. */
    public static void force(ServerLevel level, ColonySite.Placed colony) {
        for (BlockPos pos : of(colony)) {
            FarChunks.pin(level, pos.getX() >> 4, pos.getZ() >> 4);
        }
    }

    /**
     * Runs {@code body} once, on the first tick when every chunk of {@code positions} ticks and {@code ready} holds (the entities of a
     * chunk load a little after it ticks), and succeeds the test when it returns. Call from the test method, which must give itself
     * {@link FarChunks#AWAIT_BUDGET_TICKS} ticks and does not call {@code succeed}.
     */
    public static void whenTicking(
            GameTestHelper helper, ServerLevel level, List<BlockPos> positions, BooleanSupplier ready, Runnable body) {
        int[] ticking = {0};
        FarChunks.awaitEntityTicking(helper, level, positions, index -> ticking[0]++);
        boolean[] ran = {false};
        RuntimeException[] failed = {null};
        helper.succeedWhen(() -> {
            if (failed[0] != null) {
                throw failed[0];
            }
            if (ticking[0] < positions.size()) {
                throw helper.assertionException(
                        Component.literal("waiting for the chunks to tick: " + ticking[0] + " of " + positions.size()));
            }
            if (!ready.getAsBoolean()) {
                throw helper.assertionException(Component.literal("waiting for the entities of the chunks to load"));
            }
            if (ran[0]) {
                return;
            }
            ran[0] = true;
            try {
                body.run();
            } catch (RuntimeException e) {
                failed[0] = e;
                throw e;
            }
        });
    }

    /** The block displays the layout's pieces place, apart from the Host's hands, which a work order places. */
    public static int displaysOf(MinecraftServer server) {
        return ColonyLayout.read(server).pieces().stream()
                .mapToInt(ColonyLayout.Piece::displays)
                .sum();
    }

    /** The block displays standing on the pad now, apart from the Host's hands. */
    public static int displaysOnThePad(ServerLevel level, ColonySite.Placed colony) {
        int half = ColonyTuning.DEFAULT.padSize() / 2;
        BlockPos centre = colony.center();
        AABB pad = new AABB(
                centre.getX() - half,
                centre.getY(),
                centre.getZ() - half,
                centre.getX() + half,
                centre.getY() + ColonyTuning.DEFAULT.clearHeight(),
                centre.getZ() + half);
        return level.getEntitiesOfClass(
                        Display.BlockDisplay.class,
                        pad,
                        display -> !display.getBlockState().equals(FounderStatue.handsState()))
                .size();
    }

    /** The displays of the Host's body, loading the chunk he stands in; empty before the colony is built. */
    public static List<Display.BlockDisplay> hostBody(MinecraftServer server) {
        return statueDisplays(server, HostBody.state());
    }

    /** Takes the Host's hands away: the displays that are found and the marker, which gives the plinth its plate back. */
    public static void removeHands(MinecraftServer server) {
        FounderStatue.markerPos(server).ifPresent(pos -> {
            ServerLevel overworld = server.overworld();
            overworld.getChunk(pos);
            if (overworld.getBlockState(pos).is(Blocks.STRUCTURE_VOID)) {
                overworld.setBlock(pos, ColonyKit.RIVETED_PLATE_RED.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        });
        FounderStatue.hands(server).forEach(Entity::discard);
    }

    private static List<Display.BlockDisplay> statueDisplays(MinecraftServer server, BlockState state) {
        Optional<BlockPos> anchor = Colony.anchor(server, ColonyAnchor.STATUE);
        if (anchor.isEmpty()) {
            return List.of();
        }
        ServerLevel overworld = server.overworld();
        overworld.getChunk(anchor.get());
        // entity-query: the statue's displays that are loaded, for a test that counts his body; whether he has hands is
        // saved data
        return overworld.getEntitiesOfClass(
                Display.BlockDisplay.class,
                new AABB(anchor.get()).inflate(12),
                display -> display.getBlockState().equals(state));
    }
}
