package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.surface.SurfaceBlocks;
import io.github.pkeppeler.deepcharter.test.support.SalvagePrefabConcepts;
import io.github.pkeppeler.deepcharter.test.support.SalvagePrefabConcepts.Option;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Server GameTests for the wreck pieces of the salvage prefab concept round (#1214,
 * docs/design/salvage-prefab-concepts.md): the three structure files that {@code tools/colony/wrecks.py} writes load as
 * the layout file says, with every block known to the game, and each has salvage crates worked into it. The evidence
 * scenario {@code salvage-prefab-concepts} shoots them.
 */
public class SalvagePrefabConceptsTest {
    /** A wreck shows where its salvage would come from: at least this many crates stand in it. */
    private static final int CRATES = 4;

    @GameTest
    public void everyPieceLoadsWithTheBlocksAndDisplaysItsLayoutSays(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        List<Option> options = SalvagePrefabConcepts.read(server);
        if (options.size() != 3) {
            throw helper.assertionException("%s options in the layout, the round has three", options.size());
        }
        for (Option option : options) {
            CompoundTag root = SalvagePrefabConcepts.file(server, option);
            SalvagePrefabConcepts.palette(root, option.name());
            int blocks = root.getListOrEmpty("blocks").size();
            int displays = root.getListOrEmpty("entities").size();
            if (blocks != option.blocks() || displays != option.displays()) {
                throw helper.assertionException(
                        "piece %s holds %s blocks and %s displays, its layout says %s and %s: run tools/colony/wrecks.py",
                        option.name(), blocks, displays, option.blocks(), option.displays());
            }
            if (server.getStructureTemplateManager().get(option.structure()).isEmpty()) {
                throw helper.assertionException("piece %s does not load as a structure", option.name());
            }
        }
        helper.succeed();
    }

    @GameTest
    public void everyPieceHasSalvageCratesWorkedIntoIt(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        for (Option option : SalvagePrefabConcepts.read(server)) {
            CompoundTag root = SalvagePrefabConcepts.file(server, option);
            List<BlockState> palette = SalvagePrefabConcepts.palette(root, option.name());
            int crates = 0;
            for (Tag tag : root.getListOrEmpty("blocks")) {
                if (palette.get(((CompoundTag) tag).getIntOr("state", 0)).is(SurfaceBlocks.SALVAGE_CRATE)) {
                    crates++;
                }
            }
            if (crates < CRATES) {
                throw helper.assertionException(
                        "piece %s has %s salvage crates, a wreck shows at least %s", option.name(), crates, CRATES);
            }
        }
        helper.succeed();
    }
}
