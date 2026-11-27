package io.github.pkeppeler.deepcharter.pod;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A target the navigator marked on the pilot's HUD: the echo it was, where it was when marked, and when. A mark outlives the echo it came from: it
 * holds for {@link SonarTuning#markLifetimeTicks()}, so the pilot can fly by it after the next ping has replaced the picture.
 */
public record SonarMark(SonarKind kind, BlockPos pos, long markedAt) {
    public static final Codec<SonarMark> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    SonarKind.CODEC.fieldOf("kind").forGetter(SonarMark::kind),
                    BlockPos.CODEC.fieldOf("pos").forGetter(SonarMark::pos),
                    Codec.LONG.fieldOf("markedAt").forGetter(SonarMark::markedAt))
            .apply(instance, SonarMark::new));
    public static final StreamCodec<ByteBuf, SonarMark> STREAM = StreamCodec.composite(
            SonarKind.STREAM,
            SonarMark::kind,
            BlockPos.STREAM_CODEC,
            SonarMark::pos,
            ByteBufCodecs.VAR_LONG,
            SonarMark::markedAt,
            SonarMark::new);
}
