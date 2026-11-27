package io.github.pkeppeler.deepcharter.test;

import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.colony.OreCarBlock;
import io.github.pkeppeler.deepcharter.layer.BlockReadouts;
import io.github.pkeppeler.deepcharter.layer.BlockReadouts.Readout;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Server GameTests for the right-click hints of the block readout (#892). The gate walks every registered {@code deepcharter} block
 * whose class overrides {@code useWithoutItem} or {@code useItemOn} and fails when none of its states reads with a hint, so the next
 * such block cannot be missed. It does not cover a right-click wired through a block entity, a {@code UseBlockCallback} or an item's
 * {@code useOn}: a gate passing is not proof that every right-click has a hint.
 */
public class RightClickHintsTest {
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
            Identifier key = BuiltInRegistries.BLOCK.getKey(block);
            if (!key.getNamespace().equals(DeepCharter.MOD_ID) || !handlesRightClick(block.getClass())) {
                continue;
            }
            checked++;
            if (block.getStateDefinition().getPossibleStates().stream()
                    .noneMatch(state -> hintKey(helper, state).isPresent())) {
                missing.add(key.toString());
            }
        }
        if (checked == 0) {
            throw helper.assertionException("no right-click block found: the reflection walk is broken");
        }
        if (!missing.isEmpty()) {
            throw helper.assertionException(
                    "right-click blocks with no readout hint: %s; add a hint provider from the feature's init",
                    missing);
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
