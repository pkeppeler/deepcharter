package io.github.pkeppeler.deepcharter.terminal;

import io.netty.buffer.ByteBuf;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * What a client needs to draw one online terminal, as the server sees it now. An offline terminal sends none: it opens
 * its {@link RepairMenu}.
 *
 * @param type    the terminal type id
 * @param feature what the type adds ({@link TerminalFeatures}): present for a type that has one
 * @param notices the lines to show under the header ({@link TerminalNotices}): sent with the view, never saved
 */
public record TerminalView(BlockPos pos, Identifier type, Optional<TerminalFeature> feature, List<Component> notices) {
    private static final StreamCodec<ByteBuf, List<Component>> NOTICES_CODEC =
            ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC.apply(ByteBufCodecs.list());

    public static final StreamCodec<ByteBuf, TerminalView> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public TerminalView decode(ByteBuf buffer) {
            BlockPos pos = BlockPos.STREAM_CODEC.decode(buffer);
            Identifier type = Identifier.STREAM_CODEC.decode(buffer);
            return new TerminalView(
                    pos, type, TerminalFeatures.codec(type).decode(buffer), NOTICES_CODEC.decode(buffer));
        }

        @Override
        public void encode(ByteBuf buffer, TerminalView view) {
            BlockPos.STREAM_CODEC.encode(buffer, view.pos);
            Identifier.STREAM_CODEC.encode(buffer, view.type);
            TerminalFeatures.codec(view.type).encode(buffer, view.feature);
            NOTICES_CODEC.encode(buffer, view.notices);
        }
    };

    public TerminalView {
        notices = List.copyOf(notices);
    }

    /** The feature if the view carries one of class {@code kind}. */
    public <T extends TerminalFeature> Optional<T> feature(Class<T> kind) {
        return feature.filter(kind::isInstance).map(kind::cast);
    }
}
