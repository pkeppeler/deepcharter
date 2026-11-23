package io.github.pkeppeler.deepcharter.test.mixin;

import io.github.pkeppeler.deepcharter.test.support.BatchKeyView;
import org.spongepowered.asm.mixin.Mixin;

/** Lets {@code GameTestBatchFactoryMixin} read the environment of a batch key by type: the record's own accessor does the work. */
@Mixin(targets = "net.minecraft.gametest.framework.GameTestBatchFactory$BatchKey", remap = false)
public abstract class BatchKeyMixin implements BatchKeyView {}
