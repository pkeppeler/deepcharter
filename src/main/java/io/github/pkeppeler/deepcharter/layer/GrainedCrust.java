package io.github.pkeppeler.deepcharter.layer;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.pkeppeler.deepcharter.DeepCharter;
import io.github.pkeppeler.deepcharter.attachment.Versioned;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

/**
 * The grained crust's pulse and the rules it enforces (#746, ADR 0060): the drill skates sideways across the grain, a block placed
 * against it crumbles, a fluid is absorbed, and whatever stops in it is squeezed. Every name and message here is a placeholder until the
 * lore session (#12) names them.
 */
public final class GrainedCrust {
    private static final int VERSION = 1;

    /** Whether the player was told why a block placed against the grain crumbles: once for each player (it is kept across a respawn). */
    public record Explained(boolean told) {
        public static final Explained NO = new Explained(false);
        public static final MapCodec<Explained> BODY = RecordCodecBuilder.mapCodec(
                instance -> instance.group(Codec.BOOL.fieldOf("told").forGetter(Explained::told))
                        .apply(instance, Explained::new));
    }

    public static final AttachmentType<Versioned<Explained>> EXPLAINED = AttachmentRegistry.create(
            Identifier.fromNamespaceAndPath(DeepCharter.MOD_ID, "grain_explained"),
            builder -> builder.persistent(Versioned.codec(VERSION, Explained.BODY))
                    .initializer(() -> Versioned.of(Explained.NO))
                    .copyOnDeath());

    private GrainedCrust() {}

    public static void init() {}

    /** True on the one tick of each {@link LayerTuning#grainPulseTicks()} when the grain flexes. Every level pulses together, on the world clock. */
    public static boolean isPulse(Level level) {
        return level.getGameTime() % LayerTuning.DEFAULT.grainPulseTicks() == 0;
    }

    /** Ticks until the next pulse, 1 to a whole period: on a pulse tick, the next pulse is a whole period away. */
    public static int ticksToPulse(Level level) {
        int period = LayerTuning.DEFAULT.grainPulseTicks();
        return period - (int) (level.getGameTime() % period);
    }
}
