package io.github.pkeppeler.deepcharter.test.support;

import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;

/** The environment of the runner's package-private {@code GameTestBatchFactory.BatchKey}, which {@code BatchKeyMixin} makes implement this. */
public interface BatchKeyView {
    Holder<TestEnvironmentDefinition<?>> environment();
}
