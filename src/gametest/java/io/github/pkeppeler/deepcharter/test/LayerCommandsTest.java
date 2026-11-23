package io.github.pkeppeler.deepcharter.test;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.pkeppeler.deepcharter.layer.LayerChain;
import io.github.pkeppeler.deepcharter.layer.LayerTuning;
import io.github.pkeppeler.deepcharter.surface.SurfaceBlocks;
import io.github.pkeppeler.deepcharter.test.support.MockPlayer;
import io.github.pkeppeler.deepcharter.test.support.MockPlayers;
import io.github.pkeppeler.deepcharter.test.support.RoomCarver;
import io.github.pkeppeler.deepcharter.test.support.TestLayers;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Server GameTests for {@code /deepcharter layer goto}: op-only, and it moves a player within the campaign level. */
public class LayerCommandsTest {
    @GameTest
    public void gotoIsOpOnly(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer mock =
                MockPlayers.join(server, "layer-goto-denied", helper.getLevel(), helper.absoluteVec(Vec3.ZERO));
        try {
            var source = mock.player()
                    .createCommandSourceStack()
                    .withPermission(LevelBasedPermissionSet.forLevel(PermissionLevel.ALL));
            var before = mock.player().level().dimension();
            try {
                server.getCommands().getDispatcher().execute("deepcharter layer goto 1", source);
                throw helper.assertionException("a non-op ran /deepcharter layer goto");
            } catch (CommandSyntaxException denied) {
                if (!denied.getType().equals(CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand())
                        && !denied.getType()
                                .equals(CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument())) {
                    throw helper.assertionException(
                            "expected an unknown-command failure, got %s", String.valueOf(denied.getMessage()));
                }
            }
            if (!mock.player().level().dimension().equals(before)) {
                throw helper.assertionException("a denied non-op still changed dimension");
            }
        } finally {
            mock.leave();
        }
        helper.succeed();
    }

    @GameTest
    public void gotoMovesAnOpToTheLayer(GameTestHelper helper) throws CommandSyntaxException {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer mock = MockPlayers.join(server, "layer-goto-op", helper.getLevel(), helper.absoluteVec(Vec3.ZERO));
        try {
            // A goto stays in the campaign level and moves the player to the band.
            ServerLevel level = TestLayers.level(helper, 1);
            mock.teleportTo(level, TestLayers.vec(1, 0.5, 100, 0.5), 0, 0);
            var source = mock.player().createCommandSourceStack().withPermission(LevelBasedPermissionSet.GAMEMASTER);
            server.getCommands().getDispatcher().execute("deepcharter layer goto 2", source);
            LayerChain.Band levelBand = TestLayers.band(level, 2);
            if (!TestLayers.isIn(mock.player(), 2) || mock.player().level() != level) {
                throw helper.assertionException(
                        "player is at y=%s in %s after goto 2",
                        mock.player().getY(), mock.player().level().dimension());
            }
            Vec3 pos = mock.player().position();
            if (pos.y < levelBand.minY() + LayerTuning.DEFAULT.crustThickness() || pos.y > levelBand.maxY()) {
                throw helper.assertionException("player placed at y=%s, outside the layer", pos.y);
            }
            try {
                server.getCommands().getDispatcher().execute("deepcharter layer goto 3", source);
                throw helper.assertionException("goto 3 should fail: only 2 layers exist");
            } catch (CommandSyntaxException outOfRange) {
                if (!String.valueOf(outOfRange.getMessage()).contains("No layer 3")) {
                    throw helper.assertionException(
                            "expected the no-such-layer failure, got %s", String.valueOf(outOfRange.getMessage()));
                }
            }
        } finally {
            mock.leave();
        }
        helper.succeed();
    }

    /** A goto from a level that is not the campaign world has no layer to go to: it fails, and the player stays where they are. */
    @GameTest
    public void gotoFailsOutsideTheCampaignWorld(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer mock = MockPlayers.join(server, "layer-goto-flat", helper.getLevel(), helper.absoluteVec(Vec3.ZERO));
        try {
            var source = mock.player().createCommandSourceStack().withPermission(LevelBasedPermissionSet.GAMEMASTER);
            Vec3 before = mock.player().position();
            try {
                server.getCommands().getDispatcher().execute("deepcharter layer goto 1", source);
                throw helper.assertionException("goto 1 should fail in the test pack's flat overworld");
            } catch (CommandSyntaxException refused) {
                if (!String.valueOf(refused.getMessage()).contains("campaign world")) {
                    throw helper.assertionException(
                            "expected the not-campaign failure, got %s", String.valueOf(refused.getMessage()));
                }
            }
            if (!mock.player().position().equals(before)) {
                throw helper.assertionException("a refused goto still moved the player");
            }
        } finally {
            mock.leave();
        }
        helper.succeed();
    }

    /** The motion-blocking heightmap counts the mod's blocks as ground (#350): goto stands the player on one, not inside it. */
    @GameTest
    public void gotoStandsOnAModBlock(GameTestHelper helper) throws CommandSyntaxException {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer mock =
                MockPlayers.join(server, "layer-goto-block", helper.getLevel(), helper.absoluteVec(Vec3.ZERO));
        try {
            ServerLevel layer = TestLayers.level(helper, 2);
            mock.teleportTo(layer, TestLayers.vec(1, 300_000.5, 100, 300_000.5), 0, 0);
            layer.getChunk(300_000 >> 4, 300_000 >> 4);
            BlockPos block = new BlockPos(300_000, TestLayers.band(layer, 2).maxY() - 2, 300_000);
            // The command stands on the highest place with room over it: the band's rock has none here, so two cells
            // are opened over the block.
            RoomCarver.carve(layer, block.above(), block.above(2), Blocks.AIR.defaultBlockState());
            layer.setBlockAndUpdate(block, SurfaceBlocks.REGOLITH_PACKED.defaultBlockState());
            var source = mock.player().createCommandSourceStack().withPermission(LevelBasedPermissionSet.GAMEMASTER);
            server.getCommands().getDispatcher().execute("deepcharter layer goto 2", source);
            if (mock.player().blockPosition().getY() != block.getY() + 1) {
                throw helper.assertionException(
                        "player placed at %s, expected on top of the block at %s",
                        mock.player().blockPosition().toShortString(), block.toShortString());
            }
        } finally {
            mock.leave();
        }
        helper.succeed();
    }

    /** A far column of the campaign level: goto places the player in the band there, under its top and over its crust. */
    @GameTest
    public void gotoPlacesAPlayerInTheBandAtAFarColumn(GameTestHelper helper) throws CommandSyntaxException {
        MinecraftServer server = helper.getLevel().getServer();
        MockPlayer mock = MockPlayers.join(server, "layer-goto-far", helper.getLevel(), helper.absoluteVec(Vec3.ZERO));
        try {
            ServerLevel layer = TestLayers.level(helper, 1);
            LayerChain.Band layerBand = TestLayers.band(layer, 1);
            mock.teleportTo(
                    layer,
                    new Vec3(
                            200_000.5,
                            TestLayers.band(layer, TestLayers.SURFACE).minY() + 50,
                            200_000.5),
                    0,
                    0);
            var source = mock.player().createCommandSourceStack().withPermission(LevelBasedPermissionSet.GAMEMASTER);
            server.getCommands().getDispatcher().execute("deepcharter layer goto 1", source);
            double y = mock.player().position().y;
            if (mock.player().level() != layer
                    || y < layerBand.minY() + LayerTuning.DEFAULT.crustThickness()
                    || y > layerBand.maxY()) {
                throw helper.assertionException("player placed at y=%s, outside the layer", y);
            }
        } finally {
            mock.leave();
        }
        helper.succeed();
    }
}
