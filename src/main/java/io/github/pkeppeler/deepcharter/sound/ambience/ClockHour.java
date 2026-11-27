package io.github.pkeppeler.deepcharter.sound.ambience;

import io.github.pkeppeler.deepcharter.DeepCharter;
import java.util.OptionalInt;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.timeline.Timeline;

/** The world's hour on a twelve-hour dial, read from the mod's sky timeline on either side: what the punch clock gets right or wrong. */
public final class ClockHour {
    private static final ResourceKey<Timeline> SKY =
            ResourceKey.create(Registries.TIMELINE, Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "sky"));

    private ClockHour() {}

    /** The hour, 1 to 12, or empty in a world with no sky timeline (a data pack removed it). */
    public static OptionalInt of(Level level) {
        return level.registryAccess()
                .lookupOrThrow(Registries.TIMELINE)
                .get(SKY)
                .map(Holder.Reference::value)
                .flatMap(sky -> sky.periodTicks()
                        .map(period -> ClockCaller.worldHour(sky.getCurrentTicks(level.clockManager()), period)))
                .map(OptionalInt::of)
                .orElse(OptionalInt.empty());
    }
}
