package io.github.pkeppeler.deepcharter.pod;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;

/**
 * One contact of a ping: a kind and where it is, to a block. A block echo sits at the middle of the {@link SonarTuning#cellBlocks()}-wide
 * cell it came from, so the navigator knows the neighbourhood and not the block.
 */
public record SonarEcho(SonarKind kind, BlockPos pos) {
    public static final Codec<SonarEcho> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    SonarKind.CODEC.fieldOf("kind").forGetter(SonarEcho::kind),
                    BlockPos.CODEC.fieldOf("pos").forGetter(SonarEcho::pos))
            .apply(instance, SonarEcho::new));
    public static final StreamCodec<ByteBuf, SonarEcho> STREAM = StreamCodec.composite(
            SonarKind.STREAM, SonarEcho::kind, BlockPos.STREAM_CODEC, SonarEcho::pos, SonarEcho::new);
}
