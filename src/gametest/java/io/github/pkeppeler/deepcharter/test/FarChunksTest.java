package io.github.pkeppeler.deepcharter.test;

import net.fabricmc.fabric.api.gametest.v1.GameTest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;

import io.github.pkeppeler.deepcharter.test.support.FarChunks;

/** Server GameTests for {@link FarChunks}. */
public class FarChunksTest {
	private static final String NESTED_MESSAGE = "FarChunks.awaitEntityTicking must be called from the test method, not from a tick callback";

	@GameTest
	public void awaitingFromATickCallbackFailsFast(GameTestHelper helper) {
		helper.runAfterDelay(2, () -> {
			try {
				FarChunks.awaitEntityTicking(helper, helper.getLevel(), BlockPos.ZERO, () -> { });
			} catch (IllegalStateException expected) {
				if (!NESTED_MESSAGE.equals(expected.getMessage())) {
					throw helper.assertionException("wrong message: " + expected.getMessage());
				}
				helper.succeed();
				return;
			}
			throw helper.assertionException("a nested call should throw IllegalStateException");
		});
	}
}
