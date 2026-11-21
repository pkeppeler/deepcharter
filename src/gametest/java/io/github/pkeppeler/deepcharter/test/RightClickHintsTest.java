package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.colony.OreCarBlock;
import io.github.pkeppeler.deepcharter.layer.BlockReadouts;
import io.github.pkeppeler.deepcharter.layer.BlockReadouts.Readout;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Server GameTests for the right-click hints of the block readout (#892). The gate walks every registered {@code deepcharter} block
 * whose class handles a right-click and fails when its default state reads without a hint, so the next such block cannot be missed.
 */
public class RightClickHintsTest {
    /** Blocks that handle a right-click and need no hint, each with the reason. */
    private static final Map<String, String> EXEMPT = Map.of();

    private static final List<String> RIGHT_CLICK_METHODS = List.of("useWithoutItem", "useItemOn");

    private static boolean handlesRightClick(Class<?> type) {
        for (Class<?> c = type; c != Block.class && c != null; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (RIGHT_CLICK_METHODS.contains(method.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Optional<String> hintKey(GameTestHelper helper, BlockState state) {
        return BlockReadouts.SHARED
                .readout(state, helper.getLevel(), BlockPos.ZERO, helper.makeMockPlayer(GameType.SURVIVAL))
                .flatMap(Readout::hint)
                .map(hint -> ((TranslatableContents) hint.getContents()).getKey());
    }

    @GameTest
    public void everyRightClickBlockHasAHint(GameTestHelper helper) {
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            Class<?> type = block.getClass();
            String id = BuiltInRegistries.BLOCK.getKey(block).toString();
            if (!BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals(DeepCharter.MOD_ID)
                    || !handlesRightClick(type)
                    || EXEMPT.containsKey(id)) {
                continue;
            }
            checked++;
            if (hintKey(helper, block.defaultBlockState()).isEmpty()) {
                missing.add(id);
            }
        }
        if (checked == 0) {
            throw helper.assertionException("no right-click block found: the reflection walk is broken");
        }
        if (!missing.isEmpty()) {
            throw helper.assertionException("right-click blocks with no readout hint: %s", missing);
        }
        helper.succeed();
    }

    @GameTest
    public void aLootedOreCarHasNothingLeftToDo(GameTestHelper helper) {
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block instanceof OreCarBlock) {
                Optional<String> looted =
                        hintKey(helper, block.defaultBlockState().setValue(OreCarBlock.LOOTED, true));
                if (looted.isPresent()) {
                    throw helper.assertionException("a looted ore car should have no hint, got %s", looted);
                }
                helper.succeed();
                return;
            }
        }
        throw helper.assertionException("no ore car registered");
    }
}
