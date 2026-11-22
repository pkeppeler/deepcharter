package io.github.pkeppeler.deepcharter.test.evidence;

import io.github.pkeppeler.deepcharter.colony.Colony;
import io.github.pkeppeler.deepcharter.colony.ColonyAnchor;
import io.github.pkeppeler.deepcharter.colony.ColonySite;
import io.github.pkeppeler.deepcharter.layer.StructureKind;
import io.github.pkeppeler.deepcharter.layer.StructureSite;
import io.github.pkeppeler.deepcharter.sound.phonograph.PhonographBlock;
import io.github.pkeppeler.deepcharter.sound.phonograph.PhonographRegistry;
import io.github.pkeppeler.deepcharter.test.support.TestColony;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

/**
 * Evidence scenario "phonograph-models" for #911: the real models of the phonograph and the Company PA horn. Stills of the colony's
 * phonograph on the counter of the Lamp and Pick, close and in the bar; a craftable phonograph empty and loaded on a slab in the sky; the
 * horn under the roof of the punch clock hall, close and in place; and the wreck phonograph in the corner of PROSPECTOR-0002's bay.
 * Every place comes from an anchor or a structure site, and each camera is set from the block's own facing.
 */
public class PhonographModelsScenario extends EvidenceScenario {
    private static final int SETTLE_TICKS = 80;
    /** From the Lamp and Pick's anchor, the counter lies within this many blocks. */
    private static final int COUNTER_REACH = 8;
    /** The slab in the sky is this long (x) and deep (z), the craftable phonographs on it two blocks apart. */
    private static final int SLAB_LENGTH = 9;

    private static final int SLAB_DEPTH = 5;

    @Override
    protected String name() {
        return "phonograph-models";
    }

    @Override
    protected void run(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            awaitWorld(context);
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = server.getPlayerList().getPlayers().getFirst();
                player.setPermanentlyInvulnerable(true);
                // The Handbook in the hand would cover the view.
                player.getInventory().clearContent();
                player.getAbilities().mayfly = true;
                player.getAbilities().flying = true;
                player.onUpdateAbilities();
                player.addEffect(new MobEffectInstance(
                        MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false));
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
            });
            colonyPhonograph(context, singleplayer);
            craftablePhonographs(context, singleplayer);
            punchClockHorn(context, singleplayer);
            wreckPhonograph(context, singleplayer);
        }
    }

    /** The colony's, on the counter: close, playing (a cylinder on the mandrel), and from the door in the bar. */
    private void colonyPhonograph(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        BlockPos counter = singleplayer.getServer().computeOnServer(server -> {
            ColonySite.Placed colony = colony(server);
            ServerLevel overworld = server.overworld();
            BlockPos at = TestColony.anchor(colony, ColonyAnchor.LAMP_AND_PICK);
            for (BlockPos pos : BlockPos.betweenClosed(
                    at.offset(-COUNTER_REACH, -1, -COUNTER_REACH), at.offset(COUNTER_REACH, 5, COUNTER_REACH))) {
                overworld.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL);
                if (overworld.getBlockState(pos).is(PhonographRegistry.COLONY_PHONOGRAPH)) {
                    return pos.immutable();
                }
            }
            throw new AssertionError("no colony phonograph within " + COUNTER_REACH + " blocks of the Lamp and Pick");
        });
        Direction front = facing(singleplayer, counter);
        Vec3 target = Vec3.atCenterOf(counter).add(0, 0.1, 0);
        Vec3 sideways = Vec3.atLowerCornerOf(front.getClockWise().getUnitVec3i());
        look(singleplayer, Level.OVERWORLD, front, target, 1.6, 0.5, sideways.scale(0.5));
        capture(context, "colony-phonograph-close", SETTLE_TICKS);

        singleplayer.getServer().runOnServer(server -> {
            ServerLevel overworld = server.overworld();
            overworld.setBlock(counter, overworld.getBlockState(counter).setValue(PhonographBlock.LOADED, true), 3);
        });
        look(singleplayer, Level.OVERWORLD, front, target, 1.6, 0.5, sideways.scale(0.5));
        capture(context, "colony-phonograph-playing", SETTLE_TICKS / 2);

        look(singleplayer, Level.OVERWORLD, front, target, 5.0, 1.3, sideways.scale(2.0));
        capture(context, "colony-phonograph-in-the-bar", SETTLE_TICKS / 2);
    }

    /** Two craftable phonographs on a slab in the overworld's sky, above the colony's centre: empty, and with a cylinder in. */
    private void craftablePhonographs(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        BlockPos[] pair = singleplayer.getServer().computeOnServer(server -> {
            ColonySite.Placed colony = colony(server);
            ServerLevel overworld = server.overworld();
            BlockPos centre = colony.center().atY(SKY_SLAB_Y);
            BlockPos corner = centre.offset(-SLAB_LENGTH / 2, 0, -SLAB_DEPTH / 2);
            for (int x = 0; x < SLAB_LENGTH; x++) {
                for (int z = 0; z < SLAB_DEPTH; z++) {
                    BlockPos pos = corner.offset(x, 0, z);
                    overworld.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL);
                    overworld.setBlock(pos, Blocks.POLISHED_ANDESITE.defaultBlockState(), 3);
                    overworld.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), 3);
                }
            }
            BlockPos empty = centre.offset(-1, 1, 0);
            BlockPos loaded = centre.offset(1, 1, 0);
            var base = PhonographRegistry.PHONOGRAPH
                    .defaultBlockState()
                    .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH);
            overworld.setBlock(empty, base, 3);
            overworld.setBlock(loaded, base.setValue(PhonographBlock.LOADED, true), 3);
            return new BlockPos[] {empty, loaded};
        });
        for (int i = 0; i < pair.length; i++) {
            Vec3 target = Vec3.atCenterOf(pair[i]).add(0, 0.1, 0);
            look(singleplayer, Level.OVERWORLD, Direction.NORTH, target, 1.7, 0.6, new Vec3(0.5, 0, 0));
            capture(context, i == 0 ? "phonograph-empty-close" : "phonograph-loaded-close", SETTLE_TICKS);
        }
    }

    /** The Company horn hangs over the middle of the punch clock hall: close from below, and from the hall's end. */
    private void punchClockHorn(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        StructureSite site = LayerStructuresScenario.prepare(singleplayer, StructureKind.PUNCH_CLOCK);
        ResourceKey<Level> layer = TestLayers.dimension(StructureKind.PUNCH_CLOCK.layer());
        Vec3 horn = at(site, 0, 4.5, 0);
        LayerStructuresScenario.fly(singleplayer, layer, at(site, -2.4, 3.3, -2.4), horn);
        capture(context, "company-horn-close", SETTLE_TICKS);
        LayerStructuresScenario.fly(singleplayer, layer, at(site, -6.5, 1.2, -3), horn);
        capture(context, "company-horn-in-the-punch-clock-hall", SETTLE_TICKS / 2);
    }

    /** The wreck's phonograph stands in the corner of PROSPECTOR-0002's bay, 5 along and 5 across the site's own axes. */
    private void wreckPhonograph(ClientGameTestContext context, TestSingleplayerContext singleplayer) {
        StructureSite site = LayerStructuresScenario.prepare(singleplayer, StructureKind.WRECK);
        ResourceKey<Level> layer = TestLayers.dimension(StructureKind.WRECK.layer());
        BlockPos stand = BlockPos.containing(at(site, 5, 0.5, 5));
        Direction front = singleplayer.getServer().computeOnServer(server -> {
            ServerLevel level = TestLayers.level(server, StructureKind.WRECK.layer());
            if (!level.getBlockState(stand).is(PhonographRegistry.WRECK_PHONOGRAPH)) {
                throw new AssertionError("the wreck phonograph is not at " + stand.toShortString());
            }
            return level.getBlockState(stand).getValue(HorizontalDirectionalBlock.FACING);
        });
        Vec3 target = Vec3.atCenterOf(stand).add(0, 0.1, 0);
        look(singleplayer, layer, front, target, 1.6, 0.5, Vec3.ZERO);
        capture(context, "wreck-phonograph-close", SETTLE_TICKS);
        look(
                singleplayer,
                layer,
                front,
                target,
                4.5,
                1.4,
                Vec3.atLowerCornerOf(front.getClockWise().getUnitVec3i()).scale(1.5));
        capture(context, "wreck-phonograph-in-the-bay", SETTLE_TICKS / 2);
    }

    private void capture(ClientGameTestContext context, String still, int settleTicks) {
        context.waitTicks(settleTicks);
        screenshot(context, still);
        frame(context);
    }

    private static ColonySite.Placed colony(MinecraftServer server) {
        return Colony.placed(server)
                .orElseThrow(() -> new AssertionError("the colony was not built when the world started"));
    }

    private static Direction facing(TestSingleplayerContext singleplayer, BlockPos pos) {
        return singleplayer
                .getServer()
                .computeOnServer(
                        server -> server.overworld().getBlockState(pos).getValue(HorizontalDirectionalBlock.FACING));
    }

    /** Puts the camera {@code distance} out from {@code target} along {@code front} (the way the block faces), {@code up} above it and {@code aside} off to one side, looking at the target. */
    private static void look(
            TestSingleplayerContext singleplayer,
            ResourceKey<Level> dimension,
            Direction front,
            Vec3 target,
            double distance,
            double up,
            Vec3 aside) {
        Vec3 out = Vec3.atLowerCornerOf(front.getUnitVec3i()).scale(distance);
        LayerStructuresScenario.fly(
                singleplayer, dimension, target.add(out).add(aside).add(0, up, 0), target);
    }
}
